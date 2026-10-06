package com.democorp.customermaster.service.exception;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * A customer failed one of the maintenance field rules, or its address could not be standardized.
 *
 * <p>This exception replaces the error half of MTNCUSTR's {@code EditUpdData}
 * ({@code 5250_Subfile/MTNCUSTR.SQLRPGLE:387-544}) and of the USPS variant's {@code Edit_Address}
 * ({@code USPS_Address/MTNCUSTR.SQLRPGLE:471-499}). In the source each failing {@code Edit_SD_*} routine sent one
 * CUSTMSGF message to the message subfile ({@code SndSflMsg}) and set the reverse-image (RI) and position-cursor (PC)
 * indicators of its screen field ({@code 5250_Subfile/MTNCUSTD.DSPF:65-123}); evaluation stopped at the first failure.
 * Here the same facts travel as data: the CUSTMSGF {@link #code() code}, its substitution {@link #args() args}, and the
 * {@link #errors() fields} to highlight, the first of which receives focus.
 *
 * <h2>Rule numbering</h2>
 * <p>{@link #rule()} records which stage failed, so callers can tell how far evaluation progressed:
 * <table>
 *   <caption>Rules, in evaluation order</caption>
 *   <tr><th>Rule</th><th>Field (JSON name)</th><th>Code and args</th><th>Source routine</th></tr>
 *   <tr><td>1</td><td>{@code active}</td><td>DEM0501 {@code ["Active Status"]}</td><td>Edit_SD_ACTIVE</td></tr>
 *   <tr><td>2</td><td>{@code name}</td><td>DEM0502 {@code ["Name"]}</td><td>Edit_SD_NAME</td></tr>
 *   <tr><td>3</td><td>{@code addr}</td><td>DEM0502 {@code ["Address"]}</td><td>Edit_SD_ADDR</td></tr>
 *   <tr><td>4</td><td>{@code city}</td><td>DEM0502 {@code ["City"]}</td><td>Edit_SD_CITY</td></tr>
 *   <tr><td>5 ({@link #STATE_RULE})</td><td>{@code state}</td><td>DEM0503, no args</td><td>Edit_SD_STATE</td></tr>
 *   <tr><td>6</td><td>{@code zip}</td><td>DEM0502 {@code ["ZIP"]}</td><td>Edit_SD_ZIP</td></tr>
 *   <tr><td>7</td><td>{@code acctPhone}</td><td>DEM0502 {@code ["Account Manager Phone"]}</td><td>Edit_SD_ACCTPH</td></tr>
 *   <tr><td>8</td><td>{@code acctMgr}</td><td>DEM0502 {@code ["Account Manager Name"]}</td><td>Edit_SD_ACCTMGR</td></tr>
 *   <tr><td>9</td><td>{@code corpPhone}</td><td>DEM0502 {@code ["Corporate Phone"]}</td><td>Edit_SD_CORPPH</td></tr>
 *   <tr><td>10 ({@link #ADDRESS_RULE})</td><td>{@code state}, or {@code addr}, {@code city}, {@code state},
 *       {@code zip}</td><td>DEM0503 (standardized State not in STATES, no args), or DEM9898
 *       {@code [USPS error description]}</td><td>Edit_Address</td></tr>
 * </table>
 *
 * <p>Rules 1 to 9 are thrown by {@code CustomerValidator}, rule 10 by {@code AddressStandardizationService}, which runs
 * only after rules 1 to 9 have passed. {@code CustomerMaintenanceService.review} treats {@code rule() > STATE_RULE} as
 * "the State rule passed", because Edit_SD_STATE had already moved the valid state into the working record
 * ({@code 5250_Subfile/MTNCUSTR.SQLRPGLE:488-494}); it then wraps this exception in {@code ReviewFailedException}
 * carrying {@code stateAccepted}, which the UI's working State reads.
 *
 * <h2>HTTP mapping</h2>
 * <p>Only {@code ApiExceptionHandler} maps this exception, through {@code ProblemFactory}: status 422, {@code code}
 * and {@code args} at the top level, {@code detail} = the catalog text of {@code code} with {@code args} substituted,
 * and one {@code errors[]} entry {@code {field, code, message}} per {@link FieldError}, in order, where
 * {@code message} is the catalog text of that entry's code with this exception's args. DEM9898 therefore yields four
 * entries sharing the one message {@code "USPS: <description>"}, with {@code addr} first, as the source put the
 * cursor on ADDR and reverse image on ADDR, CITY, STATE and ZIP.
 *
 * <h2>Message safety</h2>
 * <p>{@link #getMessage()} is the code only. Field values, USPS descriptions and any other user-derived text live
 * solely in {@link #args()}, so logging this exception never writes customer data.
 *
 * <p>Instances are immutable and safe to share between threads.
 */
public final class CustomerValidationException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** The State rule (Edit_SD_STATE). A failure at a higher rule means the input state was accepted. */
    public static final int STATE_RULE = 5;

    /** The address stage (Edit_Address): standardization failures and the standardized-State check. */
    public static final int ADDRESS_RULE = 10;

    /** The first field rule (Edit_SD_ACTIVE). */
    private static final int FIRST_RULE = 1;

    /** CUSTMSGF code of a failed address standardization ({@code USPS: {0}}). */
    private static final String ADDRESS_NOT_STANDARDIZED = "DEM9898";

    /** The only codes a field rule or the address stage can raise (AAP message inventory). */
    private static final Set<String> CODES = Set.of("DEM0501", "DEM0502", "DEM0503", ADDRESS_NOT_STANDARDIZED);

    /** DEM9898 highlights these fields, in this order; the first receives focus. */
    private static final List<String> ADDRESS_FIELDS = List.of("addr", "city", "state", "zip");

    /** The failed rule, 1 to 10. */
    private final int rule;

    /** The CUSTMSGF code, also the message catalog key. */
    private final String code;

    /*
     * Transient because List is not declared Serializable. The exception is never serialized by the application;
     * the accessors fall back to empty lists should a deserialized copy ever be read.
     */
    private final transient List<String> args;

    private final transient List<FieldError> errors;

    /**
     * One field to highlight, carrying the catalog code its message is built from.
     *
     * @param field the JSON property name of the field: {@code active}, {@code name}, {@code addr}, {@code city},
     *              {@code state}, {@code zip}, {@code acctPhone}, {@code acctMgr} or {@code corpPhone}
     * @param code  the CUSTMSGF code whose text, with the exception's args, becomes this field's message
     */
    public record FieldError(String field, String code) {

        /**
         * Rejects a missing or blank field name or code.
         *
         * @throws NullPointerException     if {@code field} or {@code code} is {@code null}
         * @throws IllegalArgumentException if {@code field} or {@code code} is blank
         */
        public FieldError {
            Objects.requireNonNull(field, "field");
            Objects.requireNonNull(code, "code");
            if (field.isBlank()) {
                throw new IllegalArgumentException("field must not be blank");
            }
            if (code.isBlank()) {
                throw new IllegalArgumentException("code must not be blank");
            }
        }
    }

    /**
     * Creates the exception for one failed rule.
     *
     * <p>Example, the blank-name rule: {@code new CustomerValidationException(2, "DEM0502", List.of("Name"),
     * List.of(new FieldError("name", "DEM0502")))}.
     *
     * @param rule   the failed rule, 1 to 9 for the field rules in source order, {@link #ADDRESS_RULE} for the
     *               address stage
     * @param code   the CUSTMSGF code: DEM0501, DEM0502, DEM0503 or DEM9898
     * @param args   the substitution values for the code's {@code {n}} placeholders; copied, must not contain
     *               {@code null}
     * @param errors the fields to highlight, the first receiving focus; copied, must not be empty or contain
     *               {@code null}
     * @throws NullPointerException     if {@code code}, {@code args} or {@code errors} is {@code null}, or a list
     *                                  contains {@code null}
     * @throws IllegalArgumentException if {@code rule} is outside 1 to 10, {@code code} is not one of the four codes,
     *                                  {@code errors} is empty, or a DEM9898 is not raised at {@link #ADDRESS_RULE}
     *                                  on exactly {@code addr}, {@code city}, {@code state}, {@code zip} in that order
     */
    public CustomerValidationException(int rule, String code, List<String> args, List<FieldError> errors) {
        super(requireKnownCode(code));
        if (rule < FIRST_RULE || rule > ADDRESS_RULE) {
            throw new IllegalArgumentException(
                    "rule must be between " + FIRST_RULE + " and " + ADDRESS_RULE + ", was " + rule);
        }
        List<String> argsCopy = List.copyOf(Objects.requireNonNull(args, "args"));
        List<FieldError> errorsCopy = List.copyOf(Objects.requireNonNull(errors, "errors"));
        if (errorsCopy.isEmpty()) {
            throw new IllegalArgumentException("errors must name at least one field");
        }
        if (ADDRESS_NOT_STANDARDIZED.equals(code)) {
            requireAddressStage(rule, errorsCopy);
        }
        this.rule = rule;
        this.code = code;
        this.args = argsCopy;
        this.errors = errorsCopy;
    }

    /**
     * Validates the code before it becomes the exception message, so the message is never {@code null}.
     */
    private static String requireKnownCode(String code) {
        Objects.requireNonNull(code, "code");
        if (!CODES.contains(code)) {
            throw new IllegalArgumentException("code must be one of DEM0501, DEM0502, DEM0503, DEM9898, was " + code);
        }
        return code;
    }

    /**
     * DEM9898 is raised only by the address stage and highlights the four address fields with {@code addr} first,
     * mirroring PC_SD_ADDR plus RI_SD_ADDR, RI_SD_CITY, RI_SD_STATE and RI_SD_ZIP in Edit_Address.
     */
    private static void requireAddressStage(int rule, List<FieldError> errors) {
        if (rule != ADDRESS_RULE) {
            throw new IllegalArgumentException(
                    ADDRESS_NOT_STANDARDIZED + " must be raised at rule " + ADDRESS_RULE + ", was " + rule);
        }
        List<String> fields = errors.stream().map(FieldError::field).toList();
        if (!fields.equals(ADDRESS_FIELDS)) {
            throw new IllegalArgumentException(
                    ADDRESS_NOT_STANDARDIZED + " must name the fields " + ADDRESS_FIELDS + " in order, was " + fields);
        }
    }

    /**
     * Returns the failed rule.
     *
     * @return 1 to 9 for the field rules in source order, {@link #ADDRESS_RULE} for the address stage; a value above
     *         {@link #STATE_RULE} means the input state passed the State rule
     */
    public int rule() {
        return rule;
    }

    /**
     * Returns the CUSTMSGF code, which is also the message catalog key.
     *
     * @return DEM0501, DEM0502, DEM0503 or DEM9898
     */
    public String code() {
        return code;
    }

    /**
     * Returns the substitution values for the code's placeholders: the field label for DEM0501 and DEM0502, the USPS
     * error description for DEM9898, none for DEM0503.
     *
     * @return an unmodifiable list, never {@code null}
     */
    public List<String> args() {
        return args == null ? List.of() : args;
    }

    /**
     * Returns the fields to highlight, in order; the first receives focus.
     *
     * @return an unmodifiable list, never {@code null}; never empty on an instance created by the constructor
     */
    public List<FieldError> errors() {
        return errors == null ? List.of() : errors;
    }
}
