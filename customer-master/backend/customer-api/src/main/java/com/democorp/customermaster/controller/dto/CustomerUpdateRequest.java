package com.democorp.customermaster.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import org.hibernate.validator.constraints.CodePointLength;

/**
 * The body of {@code PUT /api/customers/{custId}}: the nine data fields, which follow
 * {@link CustomerFields}, plus the {@code version} the client read.
 *
 * <p><b>Version.</b> {@code version} is a boxed {@link Long} marked {@link NotNull}, so a body
 * without it fails validation with 400 APP0400 instead of becoming 0. It binds only from an exact
 * integral JSON number such as {@code 0}; a fraction or exponent ({@code 0.9}, {@code 1.0},
 * {@code 1e0}), a string ({@code "0"}) or a boolean is rejected with 400 APP0400. The database
 * compares it: the maintenance service issues {@code UPDATE … WHERE custid = ? AND row_version = ?}
 * and, when no row changes, answers 404 DEM0599 if the row is gone and 409 DEM1002 otherwise. It
 * replaces the {@code CHGTIME = :Orig_CHGTIME} condition of {@code UpdateRecd}
 * [5250_Subfile/MTNCUSTR.SQLRPGLE:576-591].
 *
 * <p><b>What a client cannot send.</b> {@code custId} comes from the path, and {@code chgTime},
 * {@code chgUser} and {@code rowVersion} are set by the service; each is rejected as an unknown
 * member with 400 APP0400.
 *
 * @param name      the customer name, at most 40 characters
 * @param addr      the street line, at most 40 characters
 * @param city      the city, at most 20 characters
 * @param state     the 2-character state code
 * @param zip       the ZIP code, at most 10 characters; no format is enforced, as in the source
 * @param corpPhone the corporate phone, at most 20 characters; no format is enforced
 * @param acctMgr   the account manager's name, at most 40 characters
 * @param acctPhone the account manager's phone, at most 20 characters; no format is enforced
 * @param active    the active code, one character; {@code Y} or {@code N} once validated
 * @param version   the row version the client read; required
 */
@Schema(additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record CustomerUpdateRequest(
        // Column widths in code points, not @Size's UTF-16 units: see the note in CustomerFields.
        @Schema(types = {"string", "null"}) @CodePointLength(max = 40) @StorableText String name,
        @Schema(types = {"string", "null"}) @CodePointLength(max = 40) @StorableText String addr,
        @Schema(types = {"string", "null"}) @CodePointLength(max = 20) @StorableText String city,
        @Schema(types = {"string", "null"}) @CodePointLength(max = 2) @StorableText String state,
        @Schema(types = {"string", "null"}) @CodePointLength(max = 10) @StorableText String zip,
        @Schema(types = {"string", "null"}) @CodePointLength(max = 20) @StorableText String corpPhone,
        @Schema(types = {"string", "null"}) @CodePointLength(max = 40) @StorableText String acctMgr,
        @Schema(types = {"string", "null"}) @CodePointLength(max = 20) @StorableText String acctPhone,
        @Schema(types = {"string", "null"}) @CodePointLength(max = 1) @StorableText String active,
        @NotNull Long version) {

    /**
     * Returns the nine data fields of this request, without the version.
     *
     * <p>Every value, {@code null} included, is passed through unchanged: trimming, uppercasing
     * and the rules belong to the maintenance service and {@code CustomerValidator}.
     *
     * @return the data fields, in {@code CustomerFields} order
     */
    public CustomerFields fields() {
        return new CustomerFields(
                name,
                addr,
                city,
                state,
                zip,
                corpPhone,
                acctMgr,
                acctPhone,
                active);
    }
}
