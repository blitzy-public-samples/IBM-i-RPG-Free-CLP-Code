package com.democorp.customermaster.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;
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
 * member order. The committed OpenAPI snapshot and the frontend's {@code src/api/schema.d.ts}
 * list the properties alphabetically instead, because {@code application.yml} sets
 * {@code springdoc.writer-with-order-by-keys: true}; adding, removing or renaming a component
 * changes both, which must then be regenerated. The record is flat by declaration rather than
 * through {@code @JsonUnwrapped}, carries no serialization annotation, and its one mapping
 * method, {@link #fields()}, follows no getter convention, so neither Jackson nor springdoc sees
 * a property beyond these ten. As in {@code CustomerFields}, each data field's
 * documentation-only {@code @Schema} publishes it as an optional string or {@code null}, and the
 * record's own documentation-only {@code @Schema} closes the schema
 * ({@code additionalProperties: false}), so the contract admits no member beyond the ten, as the
 * binder does when it answers any other member with 400 APP0400.
 *
 * <p><b>What a client cannot send.</b> {@code custId} is taken from the path, and
 * {@code chgTime}, {@code chgUser} and {@code rowVersion} are set by the service from the clock,
 * the authenticated principal and the version check. None is declared here, and with
 * {@code spring.jackson.deserialization.fail-on-unknown-properties} enabled any such member is
 * rejected with 400 APP0400.
 *
 * <p><b>Validation.</b>
 * <ul>
 *   <li>Each data field carries only {@link Size} and {@link StorableText}, as in
 *       {@code CustomerFields}: a longer value, or one containing U+0000 (NUL), which a PostgreSQL
 *       text column cannot hold, is rejected with 400 APP0400 and an {@code errors[]} entry on the
 *       property, while a blank or missing value reaches
 *       {@code service/CustomerValidator}, which reports it as 422 DEM0501, DEM0502 or DEM0503 in
 *       source order.</li>
 *   <li>{@code version} is a boxed {@link Long} marked {@link NotNull}, so a body without it
 *       deserializes to {@code null} and fails validation with 400 APP0400 instead of silently
 *       becoming 0. It binds only from an exact integral JSON number such as {@code 0}: a fraction
 *       or exponent ({@code 0.9}, {@code 1.0}, {@code 1e0}), a string ({@code "0"}), a boolean
 *       and any content after the body's JSON object are rejected with 400 APP0400 before the
 *       service runs ({@code spring.jackson} in {@code application.yml}). Its value is not compared
 *       here: the database verifies it.</li>
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
@Schema(additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record CustomerUpdateRequest(
        @Schema(types = {"string", "null"}) @Size(max = 40) @StorableText String name,
        @Schema(types = {"string", "null"}) @Size(max = 40) @StorableText String addr,
        @Schema(types = {"string", "null"}) @Size(max = 20) @StorableText String city,
        @Schema(types = {"string", "null"}) @Size(max = 2) @StorableText String state,
        @Schema(types = {"string", "null"}) @Size(max = 10) @StorableText String zip,
        @Schema(types = {"string", "null"}) @Size(max = 20) @StorableText String corpPhone,
        @Schema(types = {"string", "null"}) @Size(max = 40) @StorableText String acctMgr,
        @Schema(types = {"string", "null"}) @Size(max = 20) @StorableText String acctPhone,
        @Schema(types = {"string", "null"}) @Size(max = 1) @StorableText String active,
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
