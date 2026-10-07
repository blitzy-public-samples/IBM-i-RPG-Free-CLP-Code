package com.democorp.customermaster.controller.dto;

import com.democorp.customermaster.service.CustomerMaintenanceService;

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
 *       <td>Required; {@code "ADD"} or {@code "EDIT"}</td></tr>
 *   <tr><td>{@code name} .. {@code active}</td><td>The MTNCUSTD input fields
 *       [5250_Subfile/MTNCUSTD.DSPF:61-120]</td>
 *       <td>The {@code CustomerFields} limits: 40, 40, 20, 2, 10, 20, 40, 20, 1</td></tr>
 * </table>
 *
 * <p><b>The contract is the component list.</b> {@code purpose} comes first, then the nine data
 * fields in {@code CustomerFields} order and with its limits, which are the MTNCUSTD field
 * lengths. The component order is the JSON member order and the OpenAPI property order, so it
 * must not change without regenerating the committed OpenAPI snapshot and the frontend's
 * {@code src/api/schema.d.ts}. The record is flat by declaration rather than through
 * {@code @JsonUnwrapped}, carries no serialization or OpenAPI annotation, and its one mapping
 * method, {@link #fields()}, follows no getter convention, so neither Jackson nor springdoc sees a
 * property beyond these ten.
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
 *   <li>{@code purpose} is the service's {@link CustomerMaintenanceService.Purpose}, which Jackson
 *       binds by constant name. An unknown value such as {@code "DELETE"} fails deserialization
 *       ({@code HttpMessageNotReadableException}), and a missing one deserializes to {@code null}
 *       and fails {@link NotNull}; both become 400 APP0400.</li>
 *   <li>Each data field carries only {@link Size}, as in {@code CustomerFields}: a longer value is
 *       rejected with 400 APP0400, while a blank or missing value reaches
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
        @NotNull CustomerMaintenanceService.Purpose purpose,
        @Size(max = 40) String name,
        @Size(max = 40) String addr,
        @Size(max = 20) String city,
        @Size(max = 2) String state,
        @Size(max = 10) String zip,
        @Size(max = 20) String corpPhone,
        @Size(max = 40) String acctMgr,
        @Size(max = 20) String acctPhone,
        @Size(max = 1) String active) {

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
