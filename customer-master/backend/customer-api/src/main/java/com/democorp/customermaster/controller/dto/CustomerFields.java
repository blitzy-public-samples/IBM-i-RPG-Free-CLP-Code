package com.democorp.customermaster.controller.dto;

import java.util.Objects;

import com.democorp.customermaster.domain.Address;
import com.democorp.customermaster.domain.Customer;

import io.swagger.v3.oas.annotations.media.Schema;
import org.hibernate.validator.constraints.CodePointLength;

/**
 * The nine customer data fields a user enters: the body of {@code POST /api/customers} and
 * the shape {@code CustomerUpdateRequest}, {@code ReviewRequest}, {@code ReviewResponse} and
 * {@code CustomerResponse} extend or carry, serialized as
 * {@code {name, addr, city, state, zip, corpPhone, acctMgr, acctPhone, active}}.
 *
 * <p>Replaces the input fields of the MTNCUSTD {@code DETAILS} record
 * [5250_Subfile/MTNCUSTD.DSPF:61-125], which MTNCUSTR edited field by field and moved into
 * its {@code CUSTMAST_ds} record [5250_Subfile/MTNCUSTR.SQLRPGLE:387-544]. Each limit is the
 * display-file length, which equals the V3 column size [5250_Subfile/Custmast2.sql:9-19]:
 *
 * <table>
 *   <caption>JSON property, display field and column</caption>
 *   <tr><th>Property</th><th>MTNCUSTD field</th><th>V3 column</th><th>Max length</th></tr>
 *   <tr><td>{@code name}</td><td>{@code SD_NAME 40}</td><td>{@code name varchar(40)}</td>
 *       <td>40</td></tr>
 *   <tr><td>{@code addr}</td><td>{@code SD_ADDR 40}</td><td>{@code addr varchar(40)}</td>
 *       <td>40</td></tr>
 *   <tr><td>{@code city}</td><td>{@code SD_CITY 20}</td><td>{@code city varchar(20)}</td>
 *       <td>20</td></tr>
 *   <tr><td>{@code state}</td><td>{@code SD_STATE 2}</td><td>{@code state char(2)}</td>
 *       <td>2</td></tr>
 *   <tr><td>{@code zip}</td><td>{@code SD_ZIP 10}</td><td>{@code zip varchar(10)}</td>
 *       <td>10</td></tr>
 *   <tr><td>{@code corpPhone}</td><td>{@code SD_CORPPH 20}</td>
 *       <td>{@code corpphone varchar(20)}</td><td>20</td></tr>
 *   <tr><td>{@code acctMgr}</td><td>{@code SD_ACCTMGR 40}</td>
 *       <td>{@code acctmgr varchar(40)}</td><td>40</td></tr>
 *   <tr><td>{@code acctPhone}</td><td>{@code SD_ACCTPH 20}</td>
 *       <td>{@code acctphone varchar(20)}</td><td>20</td></tr>
 *   <tr><td>{@code active}</td><td>{@code SD_ACTIVE 1}</td><td>{@code active char(1)}</td>
 *       <td>1</td></tr>
 * </table>
 *
 * <p><b>The contract is the component list.</b> The component order is the JSON member order.
 * The committed OpenAPI snapshot and the frontend's {@code src/api/schema.d.ts} list the
 * properties alphabetically instead, because {@code application.yml} sets
 * {@code springdoc.writer-with-order-by-keys: true}; adding or removing a component changes
 * both, which must then be regenerated. The component names are also the
 * {@code errors[].field} values of problem+json responses, which the UI matches to its inputs,
 * so they must not be renamed. The record carries no serialization annotation and is flat, and
 * its two mapping methods, {@link #toDraft()} and {@link #from(Customer)}, follow no getter
 * convention, so neither Jackson nor springdoc sees a property beyond the nine. A {@code null}
 * value serializes as {@code null}.
 *
 * <p><b>Published schema.</b> Each component's documentation-only {@code @Schema} declares it a
 * string or {@code null}, and none is {@code required}: a client may omit a field or send JSON
 * {@code null}, and both reach the service as {@code null}. The record's own documentation-only
 * {@code @Schema} closes the schema ({@code additionalProperties: false}), so the contract admits
 * no member beyond the nine, as the binder does when it answers any other member with 400
 * APP0400. As the {@code customer} member of {@code ReviewResponse},
 * {@code controller/DtoSchemaCustomizer} publishes this schema with all nine properties
 * required, because a successful review returns every field and nothing else.
 *
 * <p><b>What a client cannot send.</b> {@code custId}, {@code chgTime}, {@code chgUser},
 * {@code rowVersion} and {@code version} are deliberately absent: the id is allocated on add
 * and taken from the path on update, the change stamp comes from the clock and the
 * authenticated principal, and only {@code CustomerUpdateRequest} adds {@code version}.
 * With {@code spring.jackson.deserialization.fail-on-unknown-properties} enabled, any such
 * member in a request body is rejected with 400 APP0400. Nor may a body give a member twice:
 * {@code spring.jackson.parser.strict-duplicate-detection} rejects a name repeated within one
 * JSON object with 400 APP0400 "malformed request body", before or after the last property is
 * seen, instead of keeping either value.
 *
 * <p><b>Validation stops at what the column can store.</b> Each component carries only
 * {@link CodePointLength} and {@link StorableText}, which the controller enforces with
 * {@code @Valid}: a longer value fails as {@code MethodArgumentNotValidException} and becomes 400
 * APP0400 with an {@code errors[]} entry on the property, the API counterpart of the display field
 * that cannot hold more characters. {@code @CodePointLength} counts characters (code points), as
 * the {@code varchar} and {@code char} columns do, so a value is rejected exactly when it is longer
 * than its column: a {@code name} of 40 supplementary characters such as U+1F600 is accepted and
 * stored, one of 41 is not. {@code @Size} would count UTF-16 units and reject such a value from 21
 * characters on. A value containing U+0000 (NUL), which a PostgreSQL text column cannot hold, or
 * an unpaired surrogate, which the driver would store as {@code ?}, is rejected the same way by
 * {@link StorableText} before the service runs, so it never reaches the database as 500 DEM9999,
 * is never confirmed or answered as a value other than the one stored, and uses up no customer
 * id; no format rule is added, and {@code null} passes both constraints. No component is
 * {@code @NotNull} or {@code @NotBlank}: a blank or missing value is a business-rule failure
 * that {@code service/CustomerValidator} reports as 422 DEM0501, DEM0502 or DEM0503, in the
 * source order and stopping at the first, as {@code EditUpdData} does. An absent
 * {@code active} must also reach the service as {@code null}, so that an add or ADD review
 * defaults it to {@code Y}.
 *
 * <p><b>Values pass through unchanged.</b> Neither mapping method trims, uppercases,
 * defaults or checks a value: normalization belongs to {@code domain/TextNormalizer},
 * applied by {@code CustomerMaintenanceService}, and the rules to {@code CustomerValidator}.
 *
 * <p>Example, an add body without {@code active}, and the way back from a customer:
 * <pre>{@code
 * // {"name":"acme inc","addr":"1 main st","city":"auburn","state":"me","zip":"04210",
 * //  "corpPhone":"(207) 555-0100","acctMgr":"jane doe","acctPhone":"(207) 555-0101"}
 * Customer draft = fields.toDraft();
 * draft.name();                           // "acme inc": uppercased later by the service
 * draft.active();                         // null: the service makes it "Y" on add
 * CustomerFields.from(draft).equals(fields);   // true: the round trip loses nothing
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
 */
@Schema(additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record CustomerFields(
        // Each limit is the column width counted in characters (code points), the unit varchar and
        // char count. @Size cannot express it: it counts UTF-16 units, so it would reject a value of
        // supplementary characters that fits its column. controller/CodePointLengthSchemaCustomizer
        // publishes @CodePointLength as the maxLength and minLength @Size would have produced.
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
     * Builds a new customer draft from these fields, with no id, change stamp or version.
     *
     * <p>Every value, {@code null} included, is passed to {@link Customer#draft} unchanged, and
     * the four address fields become one {@link Address}. The maintenance service then
     * normalizes and validates the draft and assigns its id, stamp and version.
     *
     * @return a draft whose {@code custId}, {@code chgTime}, {@code chgUser} and
     *         {@code rowVersion} are {@code null}
     */
    public Customer toDraft() {
        return Customer.draft(
                name,
                new Address(addr, city, state, zip),
                corpPhone,
                acctMgr,
                acctPhone,
                active);
    }

    /**
     * Copies the nine data fields of a customer, for example the normalized and standardized
     * values a review returns, or the stored values of a loaded customer.
     *
     * <p>Values pass through unchanged. {@link Customer#address()} is never {@code null}; should
     * it be, the four address fields are {@code null}, the same values an empty embedded
     * address reads back as.
     *
     * @param c the customer; must not be {@code null}
     * @return its nine data fields
     * @throws NullPointerException if {@code c} is {@code null}
     */
    public static CustomerFields from(Customer c) {
        Objects.requireNonNull(c, "customer");
        Address address = c.address();
        if (address == null) {
            address = new Address(null, null, null, null);
        }
        return new CustomerFields(
                c.name(),
                address.addr(),
                address.city(),
                address.state(),
                address.zip(),
                c.corpPhone(),
                c.acctMgr(),
                c.acctPhone(),
                c.active());
    }
}
