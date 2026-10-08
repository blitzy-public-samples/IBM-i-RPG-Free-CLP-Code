package com.democorp.customermaster.config;

import java.sql.SQLException;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

/**
 * A value-free copy of an exception and every exception linked to it, written to a log in place of the
 * original.
 *
 * <p><b>Why.</b> Exception messages carry data the log must not hold. Spring Data JDBC's
 * {@code DbActionExecutionException} quotes the action it failed to run, and with it the aggregate being
 * written; a PostgreSQL error quotes the SQL text and its {@code DETAIL}, such as
 * {@code Key (custid)=(EEEF) already exists} or {@code Failing row contains (...)}; and any message may
 * hold CR, LF or NUL characters that split or corrupt a log record. Every log line of an unexpected
 * failure ({@code ApiExceptionHandler}, {@code ProblemErrorController}, {@code ErrorDispatchFilter}), the
 * two ERROR lines of {@code CustomerGeneratorRunner} and the DEBUG lines of
 * {@code GeneratorStartupFailureReporter} therefore pass {@code RedactedThrowable.of(failure)} to SLF4J,
 * never the failure itself, while an SQLSTATE argument of a line is still read from the original
 * failure.
 *
 * <p><b>Policy.</b>
 * <ul>
 *   <li><b>Graph.</b> Every exception reachable from the failure is copied: its cause chain, its
 *       suppressed exceptions and, for a {@link SQLException}, its {@link SQLException#getNextException()}
 *       chain, each recursively.</li>
 *   <li><b>Message.</b> A copy's message is built only from the original's class name and, for a
 *       {@link SQLException}, its SQLSTATE when that is five characters of {@code [0-9A-Z]}, and its vendor
 *       code, for example {@code org.postgresql.util.PSQLException [SQLSTATE 23505, vendor code 0; message
 *       withheld]}. {@code getMessage()}, {@code getLocalizedMessage()} and {@code toString()} of an original
 *       are never called, so no text of theirs can reach the log. A class name keeps letters, digits and
 *       {@code . _ $ / -}; any other character becomes {@code ?}.</li>
 *   <li><b>Location.</b> A copy carries the original's stack frames unchanged, so the log still shows
 *       where each exception was raised.</li>
 *   <li><b>Links.</b> The cause of the original becomes the cause of the copy. Its suppressed exceptions,
 *       then the next exception of an {@link SQLException}, become suppressed exceptions of the copy, each
 *       linked once.</li>
 *   <li><b>Cycles and sharing.</b> An exception reached twice is copied once, by identity, and linked again,
 *       so a cyclic graph terminates; the JDK and Logback print the repeat as
 *       {@code [CIRCULAR REFERENCE: ...]} from the copy's own value-free text.</li>
 *   <li><b>Bound.</b> At most {@value #MAX_NODES} exceptions are copied. The first one beyond the bound
 *       is replaced by a single marker copy saying that further exceptions were omitted; the rest are
 *       dropped.</li>
 *   <li><b>Failing accessors.</b> An accessor of an original that throws ({@code getCause()},
 *       {@code getSuppressed()}, {@code getStackTrace()}, {@code getNextException()},
 *       {@code getSQLState()}, {@code getErrorCode()}) counts as returning nothing, so logging a failure
 *       never fails itself.</li>
 *   <li><b>Idempotence.</b> A {@code RedactedThrowable} found in a graph is linked as it is, never copied
 *       again, so its text keeps naming the class it was made from.</li>
 * </ul>
 * In a log the copies render as, for example,
 * <pre>
 * c.d.c.config.RedactedThrowable: org.springframework.dao.DuplicateKeyException [message withheld]
 *     at org.springframework.jdbc.support.SQLErrorCodeSQLExceptionTranslator.doTranslate(...)
 *     ...
 * Caused by: c.d.c.config.RedactedThrowable: org.postgresql.util.PSQLException [SQLSTATE 23505,
 *     vendor code 0; message withheld]
 *     at org.postgresql.core.v3.QueryExecutorImpl.receiveErrorResponse(...)
 * </pre>
 * where {@code c.d.c} stands for {@code com.democorp.customermaster}.
 *
 * <p><b>Use.</b> Instances exist only to be logged and are never thrown; they capture no stack trace of
 * their own. Each call to {@link #of(Throwable)} builds a new graph that shares nothing mutable with the
 * original, and the class holds no other state, so it is safe on every thread.
 */
public final class RedactedThrowable extends Throwable {

    /** Most exceptions one call to {@link #of(Throwable)} copies. */
    static final int MAX_NODES = 64;

    /** The closing words of every copy's message: the original message is not reproduced. */
    static final String WITHHELD = "message withheld";

    /** Message of the marker that stands in for the exceptions beyond {@link #MAX_NODES}. */
    static final String OMITTED = "[further exceptions omitted: more than " + MAX_NODES + " linked]";

    /** Longest class name reproduced, in characters. */
    private static final int MAX_CLASS_NAME_LENGTH = 256;

    /** Length of an SQLSTATE. */
    private static final int SQL_STATE_LENGTH = 5;

    /** Characters other than letters and digits a reproduced class name keeps. */
    private static final String CLASS_NAME_PUNCTUATION = "._$/-";

    /** Stands in for the suppressed exceptions of an original whose accessor fails. */
    private static final Throwable[] NO_THROWABLES = new Throwable[0];

    /** The frames of the omission marker, and of an original whose stack trace cannot be read. */
    private static final StackTraceElement[] NO_FRAMES = new StackTraceElement[0];

    /** Serialization version; instances are never serialized by the application, which only logs them. */
    private static final long serialVersionUID = 1L;

    /**
     * Creates a copy with the given value-free message and no stack frames.
     *
     * @param message the message, built by {@link #describe(Throwable)} or {@link #OMITTED}
     */
    private RedactedThrowable(String message) {
        super(message);
    }

    /**
     * Returns the value-free copy of a failure and of every exception linked to it, as described on this
     * class.
     *
     * @param failure the failure to copy; may be {@code null}
     * @return the copy, or {@code null} when {@code failure} is {@code null}, so a call site may pass the
     *     result to SLF4J as its throwable argument either way
     */
    public static RedactedThrowable of(Throwable failure) {
        return failure == null ? null : new Copier().copy(failure);
    }

    /**
     * Returns an SQLSTATE when it is exactly five characters of {@code [0-9A-Z]}, ignoring surrounding
     * blanks.
     *
     * @param state the SQLSTATE an {@link SQLException} reports; may be {@code null}
     * @return the trimmed SQLSTATE, or {@code null} when it is absent or has another form
     */
    private static String safeSqlState(String state) {
        if (state == null) {
            return null;
        }
        String trimmed = state.trim();
        if (trimmed.length() != SQL_STATE_LENGTH) {
            return null;
        }
        for (int i = 0; i < SQL_STATE_LENGTH; i++) {
            char c = trimmed.charAt(i);
            if (!(c >= '0' && c <= '9') && !(c >= 'A' && c <= 'Z')) {
                return null;
            }
        }
        return trimmed;
    }

    /**
     * Skips capturing the current stack: a copy carries the original's frames instead.
     *
     * @return this instance
     */
    @Override
    public synchronized Throwable fillInStackTrace() {
        return this;
    }

    /**
     * Builds the value-free message of a copy from the original's class name and, for an
     * {@link SQLException}, its SQLSTATE and vendor code.
     *
     * @param original the exception being copied
     * @return the message, for example {@code java.lang.IllegalStateException [message withheld]}
     */
    private static String describe(Throwable original) {
        StringBuilder text = new StringBuilder(safeClassName(original.getClass())).append(" [");
        if (original instanceof SQLException sqlException) {
            String state = safeSqlState(read(sqlException::getSQLState, null));
            if (state != null) {
                text.append("SQLSTATE ").append(state).append(", ");
            }
            text.append("vendor code ").append(read(sqlException::getErrorCode, 0)).append("; ");
        }
        return text.append(WITHHELD).append(']').toString();
    }

    /**
     * Returns a class's binary name with every character other than a letter, a digit or
     * {@value #CLASS_NAME_PUNCTUATION} replaced by {@code ?}, cut to {@value #MAX_CLASS_NAME_LENGTH}
     * characters.
     *
     * @param type the class
     * @return the printable name
     */
    private static String safeClassName(Class<?> type) {
        String name = type.getName();
        int length = Math.min(name.length(), MAX_CLASS_NAME_LENGTH);
        StringBuilder safe = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            char c = name.charAt(i);
            safe.append(Character.isLetterOrDigit(c) || CLASS_NAME_PUNCTUATION.indexOf(c) >= 0 ? c : '?');
        }
        return safe.toString();
    }

    /**
     * Calls an accessor of an original exception, treating a failure or a {@code null} result as the
     * fallback.
     *
     * @param accessor the accessor
     * @param fallback the value used when the accessor throws or returns {@code null}
     * @param <T> the accessor's result type
     * @return the accessor's result, or {@code fallback}
     */
    private static <T> T read(Supplier<T> accessor, T fallback) {
        try {
            T value = accessor.get();
            return value == null ? fallback : value;
        } catch (RuntimeException failedAccessor) {
            return fallback;
        }
    }

    /** One copy operation: the identity map of copies made so far and the node bound. */
    private static final class Copier {

        /** Original to copy, by identity, so every exception is copied once. */
        private final Map<Throwable, RedactedThrowable> copies;

        /** Whether the marker for exceptions beyond the bound has been issued. */
        private boolean omitted;

        /** Starts an operation that has copied nothing yet. */
        private Copier() {
            this.copies = new IdentityHashMap<>();
        }

        /**
         * Returns the copy of an original, creating it with its links on first sight.
         *
         * @param original the exception to copy
         * @return its copy; the original itself when it is already a {@code RedactedThrowable}; the marker
         *     for the first exception beyond the bound; {@code null} for every later one
         */
        RedactedThrowable copy(Throwable original) {
            if (original instanceof RedactedThrowable alreadyRedacted) {
                return alreadyRedacted;
            }
            RedactedThrowable existing = copies.get(original);
            if (existing != null) {
                return existing;
            }
            if (copies.size() >= MAX_NODES) {
                return omissionMarker();
            }
            RedactedThrowable copy = new RedactedThrowable(describe(original));
            copies.put(original, copy);
            copy.setStackTrace(Arrays.stream(read(original::getStackTrace, NO_FRAMES))
                    .filter(Objects::nonNull)
                    .toArray(StackTraceElement[]::new));

            Set<Throwable> linked = Collections.newSetFromMap(new IdentityHashMap<>());
            Throwable cause = read(original::getCause, null);
            if (cause != null) {
                linked.add(cause);
                RedactedThrowable causeCopy = copy(cause);
                if (causeCopy != null && causeCopy != copy) {
                    copy.initCause(causeCopy);
                }
            }
            for (Throwable suppressed : read(original::getSuppressed, NO_THROWABLES)) {
                link(copy, suppressed, linked);
            }
            if (original instanceof SQLException sqlException) {
                link(copy, read(sqlException::getNextException, null), linked);
            }
            return copy;
        }

        /**
         * Adds the copy of a linked original to a copy's suppressed exceptions, unless it is absent, already
         * linked to that copy, or dropped by the bound.
         *
         * @param copy the copy being built
         * @param original a suppressed or next exception of the copy's original; may be {@code null}
         * @param linked the originals already linked to {@code copy}
         */
        private void link(RedactedThrowable copy, Throwable original, Set<Throwable> linked) {
            if (original == null || !linked.add(original)) {
                return;
            }
            RedactedThrowable linkedCopy = copy(original);
            if (linkedCopy != null && linkedCopy != copy) {
                copy.addSuppressed(linkedCopy);
            }
        }

        /**
         * Returns the marker for the first exception beyond {@link #MAX_NODES}, then {@code null}.
         *
         * @return the marker, once
         */
        private RedactedThrowable omissionMarker() {
            if (omitted) {
                return null;
            }
            omitted = true;
            RedactedThrowable marker = new RedactedThrowable(OMITTED);
            marker.setStackTrace(NO_FRAMES);
            return marker;
        }
    }
}
