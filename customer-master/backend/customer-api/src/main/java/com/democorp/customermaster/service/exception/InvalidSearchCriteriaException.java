package com.democorp.customermaster.service.exception;

import java.util.List;
import java.util.Objects;

/**
 * A search or list request whose criteria cannot be evaluated, answered with HTTP 400.
 *
 * <p>Two catalog codes are carried, and no other:
 * <ul>
 *   <li><b>{@value #DEM0007}</b> "State selection field is invalid." on field {@code state}, with no
 *       arguments. This replaces the PMTCUSTR {@code ProcessSearchCriteria} rule
 *       (5250_Subfile/PMTCUSTR.SQLRPGLE, lines 635-646): a State filter that is not blank and whose
 *       trimmed length is not 2 sends DEM0007 to the message subfile, positions the cursor on the State
 *       field and opens no cursor. Here the search is likewise not run, and the field name
 *       {@code state} drives the highlight and focus in the client.</li>
 *   <li><b>{@value #APP0400}</b> "Request is not valid: {0}" for API guards the 5250 screen enforced
 *       through field lengths or never needed: {@code size} outside 1-100, {@code name} or {@code city}
 *       longer than 13 characters, a {@code name}, {@code city} or 2-character {@code state} filter
 *       containing U+0000 (which PostgreSQL text cannot hold), a malformed {@code cursor}, and, in the
 *       state list, a {@code sort} other than {@code name} or {@code code} or a {@code nameContains}
 *       longer than 10 characters or containing U+0000. Exactly one argument is required: a short,
 *       fixed reason written by the thrower, such as {@code "size must be between 1 and 100"},
 *       {@code "cursor is not valid"}, {@code "name must not contain U+0000"} or
 *       {@code "sort must be name or code"}.</li>
 * </ul>
 *
 * <p><b>Throwers.</b> {@code CustomerSearchService} (customer search filters, page size and cursor) and
 * {@code StateService} (state-list filter and sort).
 *
 * <p><b>Mapping.</b> This class knows nothing of HTTP. {@code ApiExceptionHandler} alone maps it, through
 * {@code ProblemFactory}, to a 400 {@code application/problem+json} response whose {@code code} is
 * {@link #code()}, whose {@code detail} is {@code MessageCatalog.text(code, args)}, and whose
 * {@code errors[0]} is {@code {field, code, message}} with {@link #field()} as the field.
 *
 * <p><b>What never travels.</b> Unlike the source's SQLProblem escape message, which carried the
 * SQLSTATE and SQL message text (Service_Pgms/SRV_SQL.SQLRPGLE, lines 20-57), neither the exception
 * message nor the arguments hold SQL text, SQLSTATE values, URLs, stack data or echoed user input. The
 * exception message is the catalog code alone, so a log line or a stray {@code toString()} discloses
 * nothing the client could not already see.
 *
 * <p>Example:
 * <pre>{@code
 * throw new InvalidSearchCriteriaException(InvalidSearchCriteriaException.DEM0007, "state", List.of());
 * throw new InvalidSearchCriteriaException(
 *         InvalidSearchCriteriaException.APP0400, "size", List.of("size must be between 1 and 100"));
 * }</pre>
 *
 * <p>The application payload is immutable: the final fields {@code code} and {@code field}, and the
 * final {@code args} list, an unmodifiable copy, are set once by the constructor. The exception instance
 * itself is not immutable, because it inherits the mutable stack trace, suppressed exceptions and cause
 * of {@link Throwable}. A new instance is created for each failure and stays with the request that
 * raised it; it is not cached, shared or reused across requests or threads.
 */
public final class InvalidSearchCriteriaException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** Catalog key "Request is not valid: {0}", for request guards with no source message. */
    public static final String APP0400 = "APP0400";

    /** Catalog key "State selection field is invalid.", the PMTCUSTR State filter rule. */
    public static final String DEM0007 = "DEM0007";

    /** The only field DEM0007 may name: PMTCUSTR positions the cursor on the State filter. */
    private static final String STATE_FIELD = "state";

    /** The catalog key, either {@link #APP0400} or {@link #DEM0007}. */
    private final String code;

    /** The request property in error: the JSON or query-parameter name that becomes {@code errors[0].field}. */
    private final String field;

    /**
     * The catalog substitution values. Transient because {@link List} is not declared
     * {@link java.io.Serializable}; the exception is never serialized in this application.
     */
    private final transient List<String> args;

    /**
     * Creates the exception for one rejected search criterion.
     *
     * @param code  the catalog key: {@link #DEM0007} or {@link #APP0400}
     * @param field the request property in error, such as {@code state}, {@code size}, {@code cursor},
     *              {@code name}, {@code city}, {@code includeInactive}, {@code nameContains} or
     *              {@code sort}; it must be non-blank, and for {@link #DEM0007} it must be {@code state}
     * @param args  the substitution values: empty for {@link #DEM0007}; exactly one non-blank, fixed
     *              reason for {@link #APP0400}
     * @throws NullPointerException     if {@code code} or {@code args} is {@code null}, or {@code args}
     *                                  holds a {@code null} element
     * @throws IllegalArgumentException if {@code code} is neither {@link #APP0400} nor {@link #DEM0007},
     *                                  if {@code field} is {@code null} or blank, or if {@code field} and
     *                                  {@code args} do not fit the code as described above
     */
    public InvalidSearchCriteriaException(String code, String field, List<String> args) {
        super(supportedCode(code));
        if (field == null || field.isBlank()) {
            throw new IllegalArgumentException("field must not be null or blank");
        }
        List<String> copiedArgs = List.copyOf(Objects.requireNonNull(args, "args"));
        if (DEM0007.equals(code)) {
            if (!STATE_FIELD.equals(field)) {
                throw new IllegalArgumentException(DEM0007 + " applies only to field " + STATE_FIELD);
            }
            if (!copiedArgs.isEmpty()) {
                throw new IllegalArgumentException(DEM0007 + " takes no arguments");
            }
        } else if (copiedArgs.size() != 1 || copiedArgs.get(0).isBlank()) {
            throw new IllegalArgumentException(APP0400 + " requires exactly one non-blank reason");
        }
        this.code = code;
        this.field = field;
        this.args = copiedArgs;
    }

    /**
     * Validates the code before it becomes the exception message, so the message is never {@code null}
     * and never anything but a supported catalog key.
     */
    private static String supportedCode(String code) {
        Objects.requireNonNull(code, "code");
        if (!APP0400.equals(code) && !DEM0007.equals(code)) {
            throw new IllegalArgumentException("code must be " + APP0400 + " or " + DEM0007);
        }
        return code;
    }

    /**
     * Returns the catalog key.
     *
     * @return {@link #APP0400} or {@link #DEM0007}
     */
    public String code() {
        return code;
    }

    /**
     * Returns the request property in error, which becomes {@code errors[0].field}.
     *
     * @return the non-blank field name; always {@code state} for {@link #DEM0007}
     */
    public String field() {
        return field;
    }

    /**
     * Returns the catalog substitution values.
     *
     * @return an unmodifiable list: empty for {@link #DEM0007}, one reason for {@link #APP0400}; never
     *         {@code null}, also on an instance restored by Java serialization, which drops the
     *         transient arguments
     */
    public List<String> args() {
        return args == null ? List.of() : args;
    }
}
