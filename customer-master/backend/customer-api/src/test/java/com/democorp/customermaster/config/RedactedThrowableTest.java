package com.democorp.customermaster.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import ch.qos.logback.classic.spi.ThrowableProxy;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.sql.BatchUpdateException;
import java.sql.SQLException;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Specifies {@link RedactedThrowable}, the value-free copy of an exception graph that every log line of an
 * unexpected failure writes in place of the failure.
 *
 * <p>Every test plants sentinels, CR, LF and NUL in the messages of the original graph, renders the copy
 * the two ways a log does (the JDK's {@code printStackTrace} and Logback's {@link ThrowableProxyUtil}) and
 * asserts that no sentinel and no control character survives, while the class names, the SQLSTATE, the
 * vendor code and the stack frames do. Each test first renders the original, to show the sentinels would
 * otherwise reach the log.
 *
 * <p>Pure JUnit 5 and AssertJ with Logback's renderer: no Spring context, no database, no Docker.
 */
@DisplayName("RedactedThrowable: exception graphs logged without their messages")
final class RedactedThrowableTest {

    /** Message text that must never appear in a rendered copy: CR/LF forging and NUL included. */
    private static final List<String> SENTINELS = List.of(
            "TOP-SENTINEL", "CAUSE-SENTINEL", "ROOT-SENTINEL", "SUPPRESSED-SENTINEL", "NEXT-SENTINEL",
            "NEXT2-SENTINEL", "FORGED", "\r", "\u0000");

    @Test
    @DisplayName("top, cause, suppressed and next-exception messages are withheld; types, SQLSTATE and frames stay")
    void withholdsEveryMessageAndKeepsDiagnostics() {
        IOException root = new IOException("ROOT-SENTINEL\r\nFORGED root line\u0000");
        SQLException cause = new SQLException("CAUSE-SENTINEL\r\nFORGED cause line", "23505", 42, root);
        SQLException next = new SQLException("NEXT-SENTINEL\nFORGED next line\u0000", "40001", 7);
        SQLException nextOfNext = new SQLException("NEXT2-SENTINEL\r\nFORGED", "08006");
        next.setNextException(nextOfNext);
        cause.setNextException(next);
        IllegalStateException suppressed = new IllegalStateException("SUPPRESSED-SENTINEL\u0000\r\nFORGED");
        RuntimeException top = new RuntimeException("TOP-SENTINEL\r\nFORGED top line\u0000", cause);
        top.addSuppressed(suppressed);
        assertThat(jdkRendering(top)).contains("TOP-SENTINEL", "CAUSE-SENTINEL", "\r\nFORGED");

        RedactedThrowable copy = RedactedThrowable.of(top);

        assertThat(copy.getMessage()).isEqualTo("java.lang.RuntimeException [message withheld]");
        assertThat(copy.getStackTrace()).containsExactly(top.getStackTrace());
        assertThat(copy.getSuppressed()).singleElement()
                .satisfies(s -> assertThat(s.getMessage())
                        .isEqualTo("java.lang.IllegalStateException [message withheld]"))
                .satisfies(s -> assertThat(s.getStackTrace()).containsExactly(suppressed.getStackTrace()));

        Throwable causeCopy = copy.getCause();
        assertThat(causeCopy).isInstanceOf(RedactedThrowable.class);
        assertThat(causeCopy.getMessage())
                .isEqualTo("java.sql.SQLException [SQLSTATE 23505, vendor code 42; message withheld]");
        assertThat(causeCopy.getStackTrace()).containsExactly(cause.getStackTrace());
        assertThat(causeCopy.getCause().getMessage()).isEqualTo("java.io.IOException [message withheld]");
        assertThat(causeCopy.getCause().getCause()).isNull();

        Throwable nextCopy = causeCopy.getSuppressed()[0];
        assertThat(causeCopy.getSuppressed()).hasSize(1);
        assertThat(nextCopy.getMessage())
                .isEqualTo("java.sql.SQLException [SQLSTATE 40001, vendor code 7; message withheld]");
        assertThat(nextCopy.getSuppressed()).singleElement()
                .satisfies(s -> assertThat(s.getMessage())
                        .isEqualTo("java.sql.SQLException [SQLSTATE 08006, vendor code 0; message withheld]"));

        for (String rendering : List.of(jdkRendering(copy), logbackRendering(copy))) {
            assertThat(rendering).doesNotContain(SENTINELS);
            assertThat(rendering.lines())
                    .noneMatch(line -> line.startsWith("FORGED"))
                    .anyMatch(line -> line.contains("at com.democorp.customermaster.config."
                            + "RedactedThrowableTest.withholdsEveryMessageAndKeepsDiagnostics("));
            assertThat(rendering).contains(
                    "RedactedThrowable: java.lang.RuntimeException [message withheld]",
                    "Caused by: com.democorp.customermaster.config.RedactedThrowable: java.sql.SQLException"
                            + " [SQLSTATE 23505, vendor code 42; message withheld]",
                    "java.io.IOException [message withheld]",
                    "Suppressed: com.democorp.customermaster.config.RedactedThrowable:"
                            + " java.lang.IllegalStateException [message withheld]",
                    "SQLSTATE 40001, vendor code 7",
                    "SQLSTATE 08006, vendor code 0");
        }
    }

    @Test
    @DisplayName("a next exception that is also the cause or a suppressed exception is linked once")
    void linksSharedNextExceptionOnce() {
        SQLException driver = new SQLException("NEXT-SENTINEL", "23505");
        BatchUpdateException batch = new BatchUpdateException("TOP-SENTINEL", "23505", 0, new int[0], driver);
        batch.setNextException(driver);

        RedactedThrowable copy = RedactedThrowable.of(batch);

        assertThat(copy.getMessage()).isEqualTo(
                "java.sql.BatchUpdateException [SQLSTATE 23505, vendor code 0; message withheld]");
        assertThat(copy.getSuppressed()).isEmpty();
        assertThat(copy.getCause().getMessage())
                .isEqualTo("java.sql.SQLException [SQLSTATE 23505, vendor code 0; message withheld]");
        assertThat(logbackRendering(copy)).doesNotContain(SENTINELS);
    }

    @Test
    @DisplayName("a cyclic cause and suppressed graph terminates, keeps its shape and renders without messages")
    void copiesCyclicGraph() {
        RuntimeException first = new RuntimeException("TOP-SENTINEL\r\nFORGED");
        IllegalStateException second = new IllegalStateException("CAUSE-SENTINEL\u0000");
        first.initCause(second);
        second.initCause(first);
        second.addSuppressed(first);
        first.addSuppressed(second);

        RedactedThrowable copy = RedactedThrowable.of(first);

        Throwable secondCopy = copy.getCause();
        assertThat(secondCopy.getMessage()).isEqualTo("java.lang.IllegalStateException [message withheld]");
        assertThat(secondCopy.getCause()).isSameAs(copy);
        assertThat(secondCopy.getSuppressed()).isEmpty();
        assertThat(copy.getSuppressed()).isEmpty();
        for (String rendering : List.of(jdkRendering(copy), logbackRendering(copy))) {
            assertThat(rendering)
                    .doesNotContain(SENTINELS)
                    .contains("CIRCULAR REFERENCE")
                    .contains("java.lang.IllegalStateException [message withheld]");
        }
    }

    @Test
    @DisplayName("a suppressed exception that is its own enclosing exception's cause is linked once")
    void suppressedCycleTerminates() {
        RuntimeException first = new RuntimeException("TOP-SENTINEL");
        IllegalStateException second = new IllegalStateException("SUPPRESSED-SENTINEL");
        first.addSuppressed(second);
        second.addSuppressed(first);

        RedactedThrowable copy = RedactedThrowable.of(first);

        Throwable secondCopy = copy.getSuppressed()[0];
        assertThat(secondCopy.getSuppressed()).containsExactly(copy);
        for (String rendering : List.of(jdkRendering(copy), logbackRendering(copy))) {
            assertThat(rendering).doesNotContain(SENTINELS).contains("CIRCULAR REFERENCE");
        }
    }

    @Test
    @DisplayName("a cause chain longer than the bound ends in one omission marker")
    void boundsLongCauseChain() {
        Throwable chain = new IllegalArgumentException("ROOT-SENTINEL");
        for (int i = 0; i < RedactedThrowable.MAX_NODES * 2; i++) {
            chain = new RuntimeException("CAUSE-SENTINEL " + i, chain);
        }

        RedactedThrowable copy = RedactedThrowable.of(chain);

        int copies = 0;
        Throwable last = copy;
        for (Throwable node = copy; node != null; node = node.getCause()) {
            copies++;
            last = node;
        }
        assertThat(copies).isEqualTo(RedactedThrowable.MAX_NODES + 1);
        assertThat(last.getMessage()).isEqualTo(RedactedThrowable.OMITTED);
        assertThat(last.getStackTrace()).isEmpty();
        assertThat(logbackRendering(copy)).doesNotContain(SENTINELS).contains(RedactedThrowable.OMITTED);
    }

    @Test
    @DisplayName("suppressed exceptions beyond the bound are replaced by one marker and then dropped")
    void boundsWideGraph() {
        RuntimeException top = new RuntimeException("TOP-SENTINEL");
        for (int i = 0; i < RedactedThrowable.MAX_NODES * 2; i++) {
            top.addSuppressed(new IllegalStateException("SUPPRESSED-SENTINEL " + i));
        }

        RedactedThrowable copy = RedactedThrowable.of(top);

        Throwable[] suppressed = copy.getSuppressed();
        assertThat(suppressed).hasSize(RedactedThrowable.MAX_NODES);
        assertThat(suppressed[suppressed.length - 1].getMessage()).isEqualTo(RedactedThrowable.OMITTED);
        assertThat(suppressed[0].getMessage()).isEqualTo("java.lang.IllegalStateException [message withheld]");
    }

    @Test
    @DisplayName("an SQLSTATE that is not five characters of [0-9A-Z] is left out; a padded one is trimmed")
    void keepsOnlyWellFormedSqlState() {
        assertThat(RedactedThrowable.of(new SQLException("x", "23505\r\nFORGED")).getMessage())
                .isEqualTo("java.sql.SQLException [vendor code 0; message withheld]");
        assertThat(RedactedThrowable.of(new SQLException("x", "2350a")).getMessage())
                .isEqualTo("java.sql.SQLException [vendor code 0; message withheld]");
        assertThat(RedactedThrowable.of(new SQLException("x", "235")).getMessage())
                .isEqualTo("java.sql.SQLException [vendor code 0; message withheld]");
        assertThat(RedactedThrowable.of(new SQLException("x", (String) null)).getMessage())
                .isEqualTo("java.sql.SQLException [vendor code 0; message withheld]");
        assertThat(RedactedThrowable.of(new SQLException("x", " 55P03 ", 9)).getMessage())
                .isEqualTo("java.sql.SQLException [SQLSTATE 55P03, vendor code 9; message withheld]");
    }

    @Test
    @DisplayName("accessors that throw count as returning nothing, so copying never fails")
    void toleratesFailingAccessors() {
        SQLException hostile = new SQLException("TOP-SENTINEL", "23505") {
            private static final long serialVersionUID = 1L;

            @Override
            public synchronized Throwable getCause() {
                throw new IllegalStateException("CAUSE-SENTINEL");
            }

            @Override
            public StackTraceElement[] getStackTrace() {
                throw new IllegalStateException("ROOT-SENTINEL");
            }

            @Override
            public String getSQLState() {
                throw new IllegalStateException("NEXT-SENTINEL");
            }

            @Override
            public SQLException getNextException() {
                throw new IllegalStateException("NEXT2-SENTINEL");
            }
        };

        assertThatCode(() -> RedactedThrowable.of(hostile)).doesNotThrowAnyException();
        RedactedThrowable copy = RedactedThrowable.of(hostile);

        assertThat(copy.getMessage()).isEqualTo(
                hostile.getClass().getName() + " [vendor code 0; message withheld]");
        assertThat(hostile.getClass().getName()).contains("RedactedThrowableTest$");
        assertThat(copy.getCause()).isNull();
        assertThat(copy.getStackTrace()).isEmpty();
        assertThat(copy.getSuppressed()).isEmpty();
    }

    @Test
    @DisplayName("an exception already redacted is linked as it is; null stays null")
    void keepsRedactedCopiesAndNull() {
        RedactedThrowable earlier = RedactedThrowable.of(new IllegalArgumentException("ROOT-SENTINEL"));
        RuntimeException top = new RuntimeException("TOP-SENTINEL", earlier);

        RedactedThrowable copy = RedactedThrowable.of(top);

        assertThat(copy.getCause()).isSameAs(earlier);
        assertThat(RedactedThrowable.of(earlier)).isSameAs(earlier);
        assertThat(RedactedThrowable.of(null)).isNull();
        assertThat(copy.getCause().getMessage()).isEqualTo("java.lang.IllegalArgumentException [message withheld]");
    }

    /**
     * Renders an exception as {@link Throwable#printStackTrace()} does.
     *
     * @param failure the exception
     * @return the rendering
     */
    private static String jdkRendering(Throwable failure) {
        StringWriter text = new StringWriter();
        try (PrintWriter writer = new PrintWriter(text)) {
            failure.printStackTrace(writer);
        }
        return text.toString();
    }

    /**
     * Renders an exception as Logback's console pattern does for the throwable of a logging event.
     *
     * @param failure the exception
     * @return the rendering
     */
    private static String logbackRendering(Throwable failure) {
        ThrowableProxy proxy = new ThrowableProxy(failure);
        proxy.calculatePackagingData();
        return ThrowableProxyUtil.asString(proxy);
    }
}
