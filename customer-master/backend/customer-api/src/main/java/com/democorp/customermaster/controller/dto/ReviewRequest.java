package com.democorp.customermaster.controller.dto;

import com.democorp.customermaster.service.CustomerMaintenanceService;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import org.hibernate.validator.constraints.CodePointLength;

/**
 * The body of {@code POST /api/customers/review}: why the review is requested plus the nine data
 * fields, which follow {@link CustomerFields}.
 *
 * <p><b>Purpose.</b> {@link PurposeDeserializer} binds {@code purpose} only from a JSON string
 * exactly {@code "ADD"} or {@code "EDIT"}. Another case, surrounding whitespace, a number or quoted
 * index ({@code 0}, {@code "1"}) and any other JSON type are rejected with 400 APP0400 and an
 * {@code errors[]} entry on {@code purpose}; a JSON {@code null} or a missing purpose fails
 * {@link NotNull} the same way.
 *
 * <p><b>Review versus save.</b> A review stores nothing: {@code ADD} precedes
 * {@code POST /api/customers} and {@code EDIT} precedes {@code PUT /api/customers/{custId}}, as
 * MTNCUSTR's confirmation preceded its write [5250_Subfile/MTNCUSTR.SQLRPGLE:219-247,272-291]. An
 * absent {@code active} reaches the service as {@code null}, so an {@code ADD} review defaults it
 * to {@code Y}.
 *
 * @param purpose   why the review is requested: {@code ADD} before {@code POST /api/customers},
 *                  {@code EDIT} before {@code PUT /api/customers/{custId}}; required
 * @param name      the customer name, at most 40 characters
 * @param addr      the street line, at most 40 characters
 * @param city      the city, at most 20 characters
 * @param state     the 2-character state code
 * @param zip       the ZIP code, at most 10 characters; no format is enforced, as in the source
 * @param corpPhone the corporate phone, at most 20 characters; no format is enforced
 * @param acctMgr   the account manager's name, at most 40 characters
 * @param acctPhone the account manager's phone, at most 20 characters; no format is enforced
 * @param active    the active code, one character; {@code Y} or {@code N} once validated
 */
@Schema(additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record ReviewRequest(
        @NotNull @JsonDeserialize(using = PurposeDeserializer.class) CustomerMaintenanceService.Purpose purpose,
        // Column widths in code points, not @Size's UTF-16 units: see the note in CustomerFields.
        @Schema(types = {"string", "null"}) @CodePointLength(max = 40) @StorableText String name,
        @Schema(types = {"string", "null"}) @CodePointLength(max = 40) @StorableText String addr,
        @Schema(types = {"string", "null"}) @CodePointLength(max = 20) @StorableText String city,
        @Schema(types = {"string", "null"}) @CodePointLength(max = 2) @StorableText String state,
        @Schema(types = {"string", "null"}) @CodePointLength(max = 10) @StorableText String zip,
        @Schema(types = {"string", "null"}) @CodePointLength(max = 20) @StorableText String corpPhone,
        @Schema(types = {"string", "null"}) @CodePointLength(max = 40) @StorableText String acctMgr,
        @Schema(types = {"string", "null"}) @CodePointLength(max = 20) @StorableText String acctPhone,
        @Schema(types = {"string", "null"}) @CodePointLength(max = 1) @StorableText String active) {

    /**
     * Returns the nine data fields of this request, without the purpose.
     *
     * <p>Every value, {@code null} included, is passed through unchanged: trimming, uppercasing,
     * the {@code active} default and the rules belong to the maintenance service and
     * {@code CustomerValidator}.
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
