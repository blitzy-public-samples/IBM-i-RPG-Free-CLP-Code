package com.democorp.customermaster.address;

/**
 * One address submitted to an {@link AddressValidationClient} for validation and
 * standardization.
 *
 * <p>Replaces the input use of the {@code USAdrValDS} template
 * [Copy_Mbrs/USADRVALDS.RPGLE:3-9], which the caller passed as {@code pi} to
 * {@code USAdrVal} [USPS_Address/USADRVAL.SQLRPGLE:49-52] and which was
 * concatenated into the Web Tools {@code AddressValidateRequest} document
 * [USPS_Address/USADRVAL.SQLRPGLE:84-89]. The components keep the template's
 * names, order and {@code char(n)} widths:
 *
 * <table>
 *   <caption>Components, USPS meaning and maximum width</caption>
 *   <tr><th>Component</th><th>USPS Web Tools element</th><th>Meaning</th><th>Width</th></tr>
 *   <tr><td>{@code address1}</td><td>{@code Address1}</td>
 *       <td>Secondary line (suite, apartment, unit)</td><td>{@value #ADDRESS1_WIDTH}</td></tr>
 *   <tr><td>{@code address2}</td><td>{@code Address2}</td>
 *       <td>Street line (number and street)</td><td>{@value #ADDRESS2_WIDTH}</td></tr>
 *   <tr><td>{@code city}</td><td>{@code City}</td><td>City</td><td>{@value #CITY_WIDTH}</td></tr>
 *   <tr><td>{@code state}</td><td>{@code State}</td><td>Two-letter state code</td>
 *       <td>{@value #STATE_WIDTH}</td></tr>
 *   <tr><td>{@code zip5}</td><td>{@code Zip5}</td><td>Five-digit ZIP code</td>
 *       <td>{@value #ZIP5_WIDTH}</td></tr>
 *   <tr><td>{@code zip4}</td><td>{@code Zip4}</td><td>ZIP+4 extension</td>
 *       <td>{@value #ZIP4_WIDTH}</td></tr>
 * </table>
 *
 * <p>Web Tools names the lines the opposite way round from everyday usage:
 * {@code Address2} is the street and {@code Address1} the secondary line. That quirk
 * stays inside this module and the customer-api mapping that builds the request; it
 * never reaches the customer model.
 *
 * <p><b>Width contract.</b> A {@code char(n)} host variable can never hold more than
 * {@code n} characters, so the source could never send more. The compact constructor
 * enforces the same ceiling and rejects a longer value with
 * {@link IllegalArgumentException}. The customer street is 40 characters
 * [5250_Subfile/Custmast2.sql:12] while {@code Address2} holds 30, and Edit_Address
 * sends only the first 30 [USPS_Address/MTNCUSTR.SQLRPGLE:473]. A caller that skips
 * that cut therefore fails here, in its tests, instead of sending more than the source
 * could. Widths are counted in Unicode code points, so a supplementary character counts
 * once, as it does for a character-oriented field.
 *
 * <p><b>Values are stored as given.</b> {@code null} becomes {@code ""}; nothing else
 * is changed. The width check runs on the value as supplied, with no strip and no
 * case change. Stripping is the job of {@code UspsXmlCodec} when it writes the request
 * document, and uppercasing happens only in {@code StubAddressValidationClient}'s
 * fixture lookup and echo.
 *
 * <p><b>Privacy.</b> The exception message names the component and its width only,
 * never the rejected value, because the value is customer address data.
 *
 * <p>Example, the request Edit_Address builds for a street longer than 30 characters
 * (the caller has already cut it):
 * <pre>{@code
 * String street = "1234 NORTH EXTRAORDINARILY LONG AVE";        // 35 characters
 * var request = new AddressValidationRequest(
 *         "",                                                // address1: blank
 *         street.substring(0, AddressValidationRequest.ADDRESS2_WIDTH),
 *         "ANYTOWN", "CA", "90210", "");                     // zip4: blank
 *
 * new AddressValidationRequest(null, street, null, null, null, null);
 * // throws IllegalArgumentException("address2 exceeds 30 characters")
 * }</pre>
 *
 * @param address1 secondary address line (suite, apartment); at most
 *                 {@value #ADDRESS1_WIDTH} characters; {@code null} is stored as {@code ""}
 * @param address2 street line; at most {@value #ADDRESS2_WIDTH} characters;
 *                 {@code null} is stored as {@code ""}
 * @param city     city; at most {@value #CITY_WIDTH} characters; {@code null} is stored
 *                 as {@code ""}
 * @param state    state code; at most {@value #STATE_WIDTH} characters; {@code null} is
 *                 stored as {@code ""}
 * @param zip5     five-digit ZIP code; at most {@value #ZIP5_WIDTH} characters;
 *                 {@code null} is stored as {@code ""}
 * @param zip4     ZIP+4 extension; at most {@value #ZIP4_WIDTH} characters; {@code null}
 *                 is stored as {@code ""}
 */
public record AddressValidationRequest(
        String address1,
        String address2,
        String city,
        String state,
        String zip5,
        String zip4) {

    /** Maximum width of {@link #address1()}, from {@code Address1 char(30)}. */
    public static final int ADDRESS1_WIDTH = 30;

    /** Maximum width of {@link #address2()}, from {@code Address2 char(30)}. */
    public static final int ADDRESS2_WIDTH = 30;

    /** Maximum width of {@link #city()}, from {@code City char(30)}. */
    public static final int CITY_WIDTH = 30;

    /** Maximum width of {@link #state()}, from {@code State char(2)}. */
    public static final int STATE_WIDTH = 2;

    /** Maximum width of {@link #zip5()}, from {@code Zip5 char(5)}. */
    public static final int ZIP5_WIDTH = 5;

    /** Maximum width of {@link #zip4()}, from {@code Zip4 char(4)}. */
    public static final int ZIP4_WIDTH = 4;

    /**
     * Maps each {@code null} component to {@code ""} and rejects any component wider
     * than its {@code USAdrValDS} field.
     *
     * @throws IllegalArgumentException if a component exceeds its width; the message
     *                                  names the component and the width, never the value
     */
    public AddressValidationRequest {
        address1 = withinWidth("address1", address1, ADDRESS1_WIDTH);
        address2 = withinWidth("address2", address2, ADDRESS2_WIDTH);
        city = withinWidth("city", city, CITY_WIDTH);
        state = withinWidth("state", state, STATE_WIDTH);
        zip5 = withinWidth("zip5", zip5, ZIP5_WIDTH);
        zip4 = withinWidth("zip4", zip4, ZIP4_WIDTH);
    }

    /**
     * Returns {@code value}, or {@code ""} for {@code null}, after checking that it holds
     * at most {@code width} code points.
     *
     * <p>This is the single width and default check of the {@code USAdrValDS} template,
     * shared by this record and {@link AddressValidationResult}. It is package-local so
     * that both records keep one rule: {@code null} becomes {@code ""}, the width is
     * counted in code points on the value as given, and the exception message names the
     * component and width, never the value.
     *
     * @param component component name used in the exception message
     * @param value     the value as supplied by the caller, unmodified
     * @param width     the maximum number of code points allowed
     * @return the value as given, or {@code ""} when {@code value} is {@code null}
     * @throws IllegalArgumentException if the value is wider than {@code width}
     */
    static String withinWidth(String component, String value, int width) {
        if (value == null) {
            return "";
        }
        if (value.codePointCount(0, value.length()) > width) {
            throw new IllegalArgumentException(component + " exceeds " + width + " characters");
        }
        return value;
    }
}
