package com.democorp.customermaster.controller.dto;

import com.democorp.customermaster.service.CustomerMaintenanceService;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * The body of {@code POST /api/customers/review}: why the review is requested plus the nine
 * {@link CustomerFields}, serialized as
 * {@code {purpose, name, addr, city, state, zip, corpPhone, acctMgr, acctPhone, active}}.
 *
 * <p>Replaces the Enter key on the MTNCUSTD detail window in edit or add mode. MTNCUSTR then ran
 * {@code EditUpdData} (or {@code EditAddData}, which calls it), stopping at the first field in
 * error, and, when every field passed, protected the window and asked for confirmation:
 * {@code SndSflMsg('DEM0000')} while editing [5250_Subfile/MTNCUSTR.SQLRPGLE:219-247] and
 * {@code SndSflMsg('DEM0009')} while adding [5250_Subfile/MTNCUSTR.SQLRPGLE:272-291]. The USPS
 * variant ends the same pass with {@code Edit_Address} [USPS_Address/MTNCUSTR.SQLRPGLE:405-499].
 * The controller hands this body to {@code CustomerMaintenanceService.review(purpose, draft)},
 * which runs the nine field rules, then the address standardization, and answers with the
 * normalized values and the DEM0000 or DEM0009 notice; nothing is stored.
 *
 * <table>
 *   <caption>Property, source counterpart and limit</caption>
 *   <tr><th>Property</th><th>Source</th><th>Rule here</th></tr>
 *   <tr><td>{@code purpose}</td><td>The MTNCUSTR function code: {@code E} (editing) or
 *       {@code A} (adding) [5250_Subfile/MTNCUSTR.SQLRPGLE:176-297]</td>
 *       <td>Required; exactly {@code "ADD"} or {@code "EDIT"}</td></tr>
 *   <tr><td>{@code name} .. {@code active}</td><td>The MTNCUSTD input fields
 *       [5250_Subfile/MTNCUSTD.DSPF:61-120]</td>
 *       <td>The {@code CustomerFields} limits: 40, 40, 20, 2, 10, 20, 40, 20, 1; no U+0000</td></tr>
 * </table>
 *
 * <p><b>The contract is the component list.</b> {@code purpose} comes first, then the nine data
 * fields in {@code CustomerFields} order and with its limits, which are the MTNCUSTD field
 * lengths. The component order is the JSON member order. The committed OpenAPI snapshot and the
 * frontend's {@code src/api/schema.d.ts} list the properties alphabetically instead, because
 * {@code application.yml} sets {@code springdoc.writer-with-order-by-keys: true}; adding,
 * removing or renaming a component changes both, which must then be regenerated. The record is
 * flat by declaration rather than through {@code @JsonUnwrapped}, carries one serialization
 * annotation, the {@link JsonDeserialize} that binds {@code purpose} through
 * {@link PurposeDeserializer} and leaves its schema the enum's {@code [ADD, EDIT]}, and its one
 * mapping method, {@link #fields()}, follows no getter convention, so neither Jackson nor
 * springdoc sees a property beyond these ten. As in {@code CustomerFields}, each data field's
 * documentation-only {@code @Schema} publishes it as an optional string or {@code null};
 * {@code purpose} stays a required {@code ADD}/{@code EDIT} string.
 *
 * <p><b>What a client cannot send.</b> {@code custId}, {@code chgTime}, {@code chgUser},
 * {@code rowVersion} and {@code version} are deliberately absent: a review stores nothing, the id
 * is allocated on add and taken from the path on update, and the change stamp comes from the clock
 * and the authenticated principal. With
 * {@code spring.jackson.deserialization.fail-on-unknown-properties} enabled, any such member is
 * rejected with 400 APP0400.
 *
 * <p><b>Validation.</b>
 * <ul>
 *   <li>{@code purpose} is the service's {@link CustomerMaintenanceService.Purpose}, which
 *       {@link PurposeDeserializer} binds only from a JSON string exactly equal to {@code "ADD"} or
 *       {@code "EDIT"}: same case, nothing before or after it, no blank, tab or line break trimmed.
 *       Any other string, such as {@code "DELETE"}, {@code "add"}, {@code " ADD"} or
 *       {@code "EDIT\n"}, a number or quoted index ({@code 0}, {@code "1"}), a fraction, a boolean,
 *       an object or an array fails deserialization on {@code purpose}, as does any content after
 *       the body's JSON object ({@code HttpMessageNotReadableException}; {@code spring.jackson} in
 *       {@code application.yml}); a JSON {@code null} or a missing one deserializes to {@code null}
 *       and fails {@link NotNull}; all become 400 APP0400 with an {@code errors[]} entry on
 *       {@code purpose}, except trailing content, which names no field.</li>
 *   <li>Each data field carries only {@link Size} and {@link StorableText}, as in
 *       {@code CustomerFields}: a longer value, or one containing U+0000 (NUL), which a PostgreSQL
 *       text column cannot hold and a later save could never store, is rejected with 400 APP0400
 *       and an {@code errors[]} entry on the property, while a blank or missing value reaches
 *       {@code service/CustomerValidator}, which reports it as 422 DEM0501, DEM0502 or DEM0503 in
 *       source order. An absent {@code active} must reach the service as {@code null}, so that an
 *       {@code ADD} review defaults it to {@code Y} [5250_Subfile/MTNCUSTR.SQLRPGLE:251-255].</li>
 * </ul>
 *
 * <p>Example, an add review without {@code active}:
 * <pre>{@code
 * // POST /api/customers/review
 * // {"purpose":"ADD","name":"acme inc","addr":"1 main st","city":"auburn","state":"me",
 * //  "zip":"04210","corpPhone":"(207) 555-0100","acctMgr":"jane doe",
 * //  "acctPhone":"(207) 555-0101"}
 * ReviewResult checked = service.review(request.purpose(), request.fields().toDraft());
 * checked.notice().code();             // "DEM0009": Press Enter to add. Press F12 to cancel
 * checked.customer().active();         // "Y": the add default, applied by the service
 * }</pre>
 *
 * <p>Instances are immutable and therefore thread-safe.
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
public record ReviewRequest(
        @NotNull @JsonDeserialize(using = PurposeDeserializer.class) CustomerMaintenanceService.Purpose purpose,
        @Schema(types = {"string", "null"}) @Size(max = 40) @StorableText String name,
        @Schema(types = {"string", "null"}) @Size(max = 40) @StorableText String addr,
        @Schema(types = {"string", "null"}) @Size(max = 20) @StorableText String city,
        @Schema(types = {"string", "null"}) @Size(max = 2) @StorableText String state,
        @Schema(types = {"string", "null"}) @Size(max = 10) @StorableText String zip,
        @Schema(types = {"string", "null"}) @Size(max = 20) @StorableText String corpPhone,
        @Schema(types = {"string", "null"}) @Size(max = 40) @StorableText String acctMgr,
        @Schema(types = {"string", "null"}) @Size(max = 20) @StorableText String acctPhone,
        @Schema(types = {"string", "null"}) @Size(max = 1) @StorableText String active) {

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
