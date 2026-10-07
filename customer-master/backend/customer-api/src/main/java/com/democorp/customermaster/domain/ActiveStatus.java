package com.democorp.customermaster.domain;

import java.util.Optional;

/**
 * The customer's active flag: {@code Y} (active) or {@code N} (inactive), and nothing else.
 *
 * <p><b>Source.</b> MTNCUSTR's {@code Edit_SD_ACTIVE} accepts the screen value only when it is exactly
 * {@code 'Y'} or {@code 'N'}, moves it into the record, and otherwise rejects it with DEM0501
 * ({@code 'Active Status'}) and the field highlighted [5250_Subfile/MTNCUSTR.SQLRPGLE:444-453]. The
 * column is {@code Active CHAR(1) DEFAULT 'Y'} [5250_Subfile/Custmast2.sql:19]; the target hardens it to
 * {@code active char(1) NOT NULL DEFAULT 'Y'} with {@code CONSTRAINT custmast_active_ck CHECK (active IN
 * ('Y','N'))} (V3__create_custmast.sql), so the two constants of this enum are exactly the values the
 * database accepts.
 *
 * <p><b>Exact match only.</b> {@link #fromCode(String)} neither trims nor case-folds. Callers normalize
 * first with {@link TextNormalizer#field(String)}, which uppercases and strips trailing blanks, as the
 * 5250 field did (it declares no {@code CHECK(LC)}, and its trailing blanks carried no meaning). A keyed
 * {@code y} therefore arrives here as {@code Y} and passes, while a leading blank survives normalization
 * and fails, exactly as {@code ' Y'} failed the source comparison. Doing either step here as well would
 * hide a missing normalization in a caller.
 *
 * <p><b>Responsibilities kept elsewhere.</b> This type only names and validates the code:
 * <ul>
 *   <li>The DEM0501 key, its {@code Active Status} argument and the field error belong to
 *       {@code service/CustomerValidator} (rule 1 of the nine field rules) and the message catalog.</li>
 *   <li>Applying {@link #DEFAULT} to an {@code active} absent from an add or an ADD review happens in the
 *       service and DTO layer, before the rules run; a value that is present, even blank, is validated as
 *       given.</li>
 *   <li>{@code Customer.active} and the search row keep the stored code as a {@code String}; use
 *       {@link #code()} to write one and {@link #fromCode(String)} to read one.</li>
 * </ul>
 *
 * <pre>{@code
 * ActiveStatus.fromCode(TextNormalizer.field("y"));   // Optional[Y]
 * ActiveStatus.fromCode("N");                         // Optional[N]
 * ActiveStatus.fromCode(" Y");                        // Optional.empty (leading blank)
 * ActiveStatus.fromCode("X");                         // Optional.empty -> DEM0501 in the validator
 * ActiveStatus.DEFAULT.code();                        // "Y"
 * }</pre>
 *
 * <p>The type depends on the JDK alone and, like every enum, is immutable and thread-safe.
 */
public enum ActiveStatus {

    /** Active customer: shown by default in the search list; the add default. */
    Y,

    /** Inactive customer: listed only when inactive rows are included (F9), and shown in red. */
    N;

    /**
     * The status a new customer receives when the add request carries no {@code active} value, as MTNCUSTR
     * sets {@code ACTIVE = 'Y'} when the add screen opens and again when F5 clears it
     * [5250_Subfile/MTNCUSTR.SQLRPGLE:253-254,269-270], and as the column default {@code 'Y'} does.
     */
    public static final ActiveStatus DEFAULT = Y;

    /** Shared results of {@link #fromCode(String)}; an {@link Optional} is immutable, so one serves all. */
    private static final Optional<ActiveStatus> FOUND_Y = Optional.of(Y);

    private static final Optional<ActiveStatus> FOUND_N = Optional.of(N);

    /**
     * The stored one-character code of this status.
     *
     * @return {@code "Y"} or {@code "N"}, the value written to {@code custmast.active}
     */
    public String code() {
        return name();
    }

    /**
     * Resolves a stored or already-normalized code to its status.
     *
     * <p>Only the exact strings {@code "Y"} and {@code "N"} are recognized. Every other value, including
     * {@code null}, {@code ""}, {@code " "}, lowercase {@code "y"}, {@code "X"}, {@code "YES"} and
     * {@code " Y"}, yields an empty result, which the validator reports as DEM0501.
     *
     * @param code the code to resolve; may be {@code null}
     * @return the matching status, or {@link Optional#empty()} when {@code code} is not exactly
     *         {@code "Y"} or {@code "N"}; never {@code null}
     */
    public static Optional<ActiveStatus> fromCode(String code) {
        if (code == null) {
            return Optional.empty();
        }
        return switch (code) {
            case "Y" -> FOUND_Y;
            case "N" -> FOUND_N;
            default -> Optional.empty();
        };
    }
}
