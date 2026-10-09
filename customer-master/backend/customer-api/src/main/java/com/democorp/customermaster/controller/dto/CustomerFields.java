package com.democorp.customermaster.controller.dto;

import java.util.Objects;

import com.democorp.customermaster.domain.Address;
import com.democorp.customermaster.domain.Customer;

import io.swagger.v3.oas.annotations.media.Schema;
import org.hibernate.validator.constraints.CodePointLength;

/**
 * The nine customer data fields a user enters: the body of {@code POST /api/customers}, and the
 * nine fields {@link CustomerUpdateRequest} and {@link ReviewRequest} declare and
 * {@link ReviewResponse} and {@link CustomerResponse} carry. Each limit is the MTNCUSTD field
 * length, which equals the column size [5250_Subfile/MTNCUSTD.DSPF:61-125].
 *
 * <p><b>Null and default.</b> A missing or JSON {@code null} value passes both constraints and
 * reaches the service as {@code null}, so an absent {@code active} becomes {@code Y} on an add or
 * an {@code ADD} review.
 *
 * <p><b>400 versus 422.</b> {@link CodePointLength} and {@link StorableText} reject a value that is
 * too long, or holds an unpaired surrogate, a control or format character (U+0000, TAB, a line
 * break and the bidirectional and zero-width characters included), a line or paragraph separator
 * or a noncharacter, with 400 APP0400 and an {@code errors[]} entry on the property, before the
 * service runs. A blank or missing value is a
 * business-rule failure that {@code service/CustomerValidator} reports as 422 DEM0501, DEM0502 or
 * DEM0503, in source order and stopping at the first, so no component is {@code @NotNull} or
 * {@code @NotBlank}.
 *
 * <p>{@code custId}, {@code chgTime}, {@code chgUser}, {@code rowVersion} and {@code version} are
 * not accepted: the id is allocated on add and taken from the path on update, the change stamp
 * comes from the clock and the authenticated principal, and only {@code CustomerUpdateRequest}
 * adds {@code version}.
 *
 * <p>The mapping methods pass every value through unchanged: normalization is
 * {@code domain/TextNormalizer}, applied by {@code CustomerMaintenanceService}.
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
