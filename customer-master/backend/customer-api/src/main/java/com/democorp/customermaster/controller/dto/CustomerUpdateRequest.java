package com.democorp.customermaster.controller.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * The body of {@code PUT /api/customers/{custId}}: the nine {@link CustomerFields} plus the
 * {@code version} the client read, serialized as
 * {@code {name, addr, city, state, zip, corpPhone, acctMgr, acctPhone, active, version}}.
 *
 * <p>Replaces the screen data MTNCUSTR's {@code UpdateRecd} wrote back to CUSTMAST, whose
 * {@code where CUSTID = :CUSTID and CHGTIME = :Orig_CHGTIME} condition refused the update when
 * someone else had changed the row since it was read [5250_Subfile/MTNCUSTR.SQLRPGLE:576-591].
 * The target compares an integer row version instead of the timestamp: {@code version} is the
 * {@code version} of the {@code CustomerResponse} the client loaded, and the maintenance service
 * issues {@code UPDATE … WHERE custid = ? AND row_version = ?}. When no row changes, the service
 * answers 404 DEM0599 if the row is gone and 409 DEM1002 with {@code current} otherwise, the
 * counterpart of the source's DEM1002 message and re-read [5250_Subfile/MTNCUSTR.SQLRPGLE:593-599].
 *
 * <p><b>The contract is the component list.</b> The nine data fields come first, in
 * {@code CustomerFields} order and with its limits, which are the MTNCUSTD field lengths
 * [5250_Subfile/MTNCUSTD.DSPF:61-125]; {@code version} follows. The component order is the JSON
 * member order and the OpenAPI property order, so it must not change without regenerating the
 * committed OpenAPI snapshot and the frontend's {@code src/api/schema.d.ts}. The record is flat
 * by declaration rather than through {@code @JsonUnwrapped}, carries no serialization or OpenAPI
 * annotation, and its one mapping method, {@link #fields()}, follows no getter convention, so
 * neither Jackson nor springdoc sees a property beyond these ten.
 *
 * <p><b>What a client cannot send.</b> {@code custId} is taken from the path, and
 * {@code chgTime}, {@code chgUser} and {@code rowVersion} are set by the service from the clock,
 * the authenticated principal and the version check. None is declared here, and with
 * {@code spring.jackson.deserialization.fail-on-unknown-properties} enabled any such member is
 * rejected with 400 APP0400.
 *
 * <p><b>Validation.</b>
 * <ul>
 *   <li>Each data field carries only {@link Size}, as in {@code CustomerFields}: a longer value is
 *       rejected with 400 APP0400, while a blank or missing value reaches
 *       {@code service/CustomerValidator}, which reports it as 422 DEM0501, DEM0502 or DEM0503 in
 *       source order.</li>
 *   <li>{@code version} is a boxed {@link Long} marked {@link NotNull}, so a body without it
 *       deserializes to {@code null} and fails validation with 400 APP0400 instead of silently
 *       becoming 0. Its value is not compared here: the database verifies it.</li>
 * </ul>
 *
 * <p>Example:
 * <pre>{@code
 * // PUT /api/customers/EEEF
 * // {"name":"acme inc","addr":"1 main st","city":"auburn","state":"me","zip":"04210",
 * //  "corpPhone":"(207) 555-0100","acctMgr":"jane doe","acctPhone":"(207) 555-0101",
 * //  "active":"Y","version":0}
 * Customer draft = request.fields().toDraft();   // values as sent; the service normalizes
 * long expected = request.version();             // 0: the row_version the update must match
 * }</pre>
 *
 * <p>Instances are immutable and therefore thread-safe.
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
public record CustomerUpdateRequest(
        @Size(max = 40) String name,
        @Size(max = 40) String addr,
        @Size(max = 20) String city,
        @Size(max = 2) String state,
        @Size(max = 10) String zip,
        @Size(max = 20) String corpPhone,
        @Size(max = 40) String acctMgr,
        @Size(max = 20) String acctPhone,
        @Size(max = 1) String active,
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
