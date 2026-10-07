package com.democorp.customermaster.address;

/**
 * The answer an {@link AddressValidationClient} gives for one
 * {@link AddressValidationRequest}: either a standardized address or an
 * address-level error reported by the service.
 *
 * <p>Replaces the returned {@code po likeds(USAdrValDS)} of {@code USAdrVal}
 * [USPS_Address/USADRVAL.SQLRPGLE:61,101-102,125] over the template
 * [Copy_Mbrs/USADRVALDS.RPGLE:3-13]. The components keep the template's names, order
 * and widths:
 *
 * <table>
 *   <caption>Components, USPS response element and maximum width</caption>
 *   <tr><th>Component</th><th>USPS Web Tools element</th><th>Template field</th>
 *       <th>Width</th></tr>
 *   <tr><td>{@code address1}</td><td>{@code Address/Address1} (secondary line)</td>
 *       <td>{@code Address1 char(30)}</td>
 *       <td>{@value AddressValidationRequest#ADDRESS1_WIDTH}</td></tr>
 *   <tr><td>{@code address2}</td><td>{@code Address/Address2} (street line)</td>
 *       <td>{@code Address2 char(30)}</td>
 *       <td>{@value AddressValidationRequest#ADDRESS2_WIDTH}</td></tr>
 *   <tr><td>{@code city}</td><td>{@code Address/City}</td><td>{@code City char(30)}</td>
 *       <td>{@value AddressValidationRequest#CITY_WIDTH}</td></tr>
 *   <tr><td>{@code state}</td><td>{@code Address/State}</td><td>{@code State char(2)}</td>
 *       <td>{@value AddressValidationRequest#STATE_WIDTH}</td></tr>
 *   <tr><td>{@code zip5}</td><td>{@code Address/Zip5}</td><td>{@code Zip5 char(5)}</td>
 *       <td>{@value AddressValidationRequest#ZIP5_WIDTH}</td></tr>
 *   <tr><td>{@code zip4}</td><td>{@code Address/Zip4}</td><td>{@code Zip4 char(4)}</td>
 *       <td>{@value AddressValidationRequest#ZIP4_WIDTH}</td></tr>
 *   <tr><td>{@code errorNumber}</td><td>{@code Address/Error/Number}</td>
 *       <td>{@code Number int(10)}</td><td>32-bit integer</td></tr>
 *   <tr><td>{@code errorSource}</td><td>{@code Address/Error/Source}</td>
 *       <td>{@code Source varchar(30)}</td><td>{@value #ERROR_SOURCE_WIDTH}</td></tr>
 *   <tr><td>{@code errorDescription}</td><td>{@code Address/Error/Description}</td>
 *       <td>{@code Description varchar(512)}</td>
 *       <td>{@value #ERROR_DESCRIPTION_WIDTH}</td></tr>
 * </table>
 *
 * <p><b>Success test.</b> {@link #standardized()} is true exactly when {@code city} is
 * non-blank. That is the source's own test: "If a city was returned, assume it worked"
 * [USPS_Address/USADRVAL.SQLRPGLE:118-121], which Edit_Address relies on
 * [USPS_Address/MTNCUSTR.SQLRPGLE:480]. The readme's rule, "If ADDRESS2 is non blank,
 * then you have a valid address" [USPS_Address/Readme.md:31], disagrees with the source
 * and is deliberately not used; the source behaviour prevails.
 *
 * <p><b>Address-level errors are results, service faults are not.</b> When the service
 * examined the address and rejected it, for example number {@code -2147219401}, source
 * {@code clsAMS}, description {@code Address Not Found.}, the client returns a normal
 * result built by {@link #error error(...)}: {@code city} is blank,
 * {@code standardized()} is false, and the three error components carry the service's
 * {@code Error} element. customer-api turns that into 422 DEM9898
 * {@code "USPS: " + errorDescription()}. A service fault (transport failure, non-2xx
 * status, oversized or malformed body, or a response that breaks the response rules) is
 * never represented by this record: it is thrown as
 * {@link AddressServiceUnavailableException}, which customer-api maps to 502 APP0502.
 *
 * <p><b>Width contract.</b> A {@code char(n)} or {@code varchar(n)} host variable can
 * never hold more than {@code n} characters, so the source could never return more. The
 * compact constructor enforces the same ceilings and rejects a longer value with
 * {@link IllegalArgumentException}. Widths are counted in Unicode code points, so a
 * supplementary character counts once, as it does for a character-oriented field. The
 * six address widths are the {@link AddressValidationRequest} constants, because the
 * template is shared by input and output [USPS_Address/USADRVAL.SQLRPGLE:17-21].
 *
 * <p><b>Values are stored as given.</b> {@code null} becomes {@code ""}; nothing else is
 * changed. There is no strip, padding or case change here: {@code UspsXmlCodec} has
 * already stripped every element value before it builds a result, and an empty element
 * (for example an empty {@code Source}) arrives as {@code ""}, the blank default of the
 * source's XMLTABLE columns [USPS_Address/USADRVAL.SQLRPGLE:107-112].
 *
 * <p><b>Privacy.</b> The exception message names the component and its width only,
 * never the rejected value, because the value is customer address data or service
 * response content.
 *
 * <p>Example, the two outcomes and how a consumer reads them:
 * <pre>{@code
 * var ok = AddressValidationResult.success(
 *         "", "123 MAIN ST", "ANYTOWN", "CA", "90210", "1234");
 * ok.standardized();        // true: a city was returned
 * ok.errorNumber();         // 0
 *
 * var notFound = AddressValidationResult.error(
 *         "", "", "", "", "", "", -2147219401, "clsAMS", "Address Not Found.");
 * notFound.standardized();  // false: no city
 * "USPS: " + notFound.errorDescription();   // "USPS: Address Not Found."
 * }</pre>
 *
 * @param address1         returned secondary address line; at most
 *                         {@value AddressValidationRequest#ADDRESS1_WIDTH} characters;
 *                         {@code null} is stored as {@code ""}
 * @param address2         returned (standardized) street line; at most
 *                         {@value AddressValidationRequest#ADDRESS2_WIDTH} characters;
 *                         {@code null} is stored as {@code ""}
 * @param city             returned city; at most
 *                         {@value AddressValidationRequest#CITY_WIDTH} characters;
 *                         blank means the address was not standardized; {@code null} is
 *                         stored as {@code ""}
 * @param state            returned state code; at most
 *                         {@value AddressValidationRequest#STATE_WIDTH} characters;
 *                         {@code null} is stored as {@code ""}
 * @param zip5             returned five-digit ZIP code; at most
 *                         {@value AddressValidationRequest#ZIP5_WIDTH} characters;
 *                         {@code null} is stored as {@code ""}
 * @param zip4             returned ZIP+4 extension; at most
 *                         {@value AddressValidationRequest#ZIP4_WIDTH} characters;
 *                         {@code ""} when the service returned none; {@code null} is
 *                         stored as {@code ""}
 * @param errorNumber      the service's error number; {@code 0} for a success
 * @param errorSource      the service's error source; at most
 *                         {@value #ERROR_SOURCE_WIDTH} characters; {@code ""} for a
 *                         success; {@code null} is stored as {@code ""}
 * @param errorDescription the service's error description; at most
 *                         {@value #ERROR_DESCRIPTION_WIDTH} characters; {@code ""} for a
 *                         success; {@code null} is stored as {@code ""}
 */
public record AddressValidationResult(
        String address1,
        String address2,
        String city,
        String state,
        String zip5,
        String zip4,
        int errorNumber,
        String errorSource,
        String errorDescription) {

    /** Maximum width of {@link #errorSource()}, from {@code Source varchar(30)}. */
    public static final int ERROR_SOURCE_WIDTH = 30;

    /** Maximum width of {@link #errorDescription()}, from {@code Description varchar(512)}. */
    public static final int ERROR_DESCRIPTION_WIDTH = 512;

    /**
     * Maps each {@code null} text component to {@code ""} and rejects any component wider
     * than its {@code USAdrValDS} field. Values are otherwise stored exactly as given.
     *
     * @throws IllegalArgumentException if a component exceeds its width; the message
     *                                  names the component and the width, never the value
     */
    public AddressValidationResult {
        address1 = AddressValidationRequest.withinWidth(
                "address1", address1, AddressValidationRequest.ADDRESS1_WIDTH);
        address2 = AddressValidationRequest.withinWidth(
                "address2", address2, AddressValidationRequest.ADDRESS2_WIDTH);
        city = AddressValidationRequest.withinWidth(
                "city", city, AddressValidationRequest.CITY_WIDTH);
        state = AddressValidationRequest.withinWidth(
                "state", state, AddressValidationRequest.STATE_WIDTH);
        zip5 = AddressValidationRequest.withinWidth(
                "zip5", zip5, AddressValidationRequest.ZIP5_WIDTH);
        zip4 = AddressValidationRequest.withinWidth(
                "zip4", zip4, AddressValidationRequest.ZIP4_WIDTH);
        errorSource = AddressValidationRequest.withinWidth(
                "errorSource", errorSource, ERROR_SOURCE_WIDTH);
        errorDescription = AddressValidationRequest.withinWidth(
                "errorDescription", errorDescription, ERROR_DESCRIPTION_WIDTH);
    }

    /**
     * Creates the result for an address the service standardized, with no error:
     * {@code errorNumber} {@code 0}, {@code errorSource} and {@code errorDescription}
     * {@code ""}.
     *
     * <p>{@link #standardized()} of the returned value is true only when {@code city} is
     * non-blank; callers such as {@code UspsXmlCodec.parse} use this factory exactly in
     * that case.
     *
     * @param address1 returned secondary line
     * @param address2 returned street line
     * @param city     returned city
     * @param state    returned state code
     * @param zip5     returned five-digit ZIP code
     * @param zip4     returned ZIP+4 extension, {@code ""} when none
     * @return the success result
     * @throws IllegalArgumentException if a component exceeds its width
     */
    public static AddressValidationResult success(
            String address1,
            String address2,
            String city,
            String state,
            String zip5,
            String zip4) {
        return new AddressValidationResult(
                address1, address2, city, state, zip5, zip4, 0, "", "");
    }

    /**
     * Creates the result for an address the service examined and rejected, carrying the
     * service's {@code Error} element alongside whatever address values the response
     * held.
     *
     * <p>The source reads the error only when no city was returned
     * [USPS_Address/USADRVAL.SQLRPGLE:118-133], so callers pass a blank {@code city} and
     * the result's {@link #standardized()} is false. The City alone decides success, as
     * in the source: a non-blank {@code city} would make the result standardized whatever
     * the error components hold.
     *
     * @param address1    secondary line as returned, usually {@code ""}
     * @param address2    street line as returned
     * @param city        city as returned; blank for an address-level error
     * @param state       state code as returned
     * @param zip5        five-digit ZIP code as returned
     * @param zip4        ZIP+4 extension as returned
     * @param number      the error {@code Number}, for example {@code -2147219401}
     * @param source      the error {@code Source}, for example {@code clsAMS}; may be
     *                    {@code ""}
     * @param description the error {@code Description}, for example
     *                    {@code Address Not Found.}; may be {@code ""}
     * @return the error result
     * @throws IllegalArgumentException if a component exceeds its width
     */
    public static AddressValidationResult error(
            String address1,
            String address2,
            String city,
            String state,
            String zip5,
            String zip4,
            int number,
            String source,
            String description) {
        return new AddressValidationResult(
                address1, address2, city, state, zip5, zip4, number, source, description);
    }

    /**
     * Tells whether the service standardized the address.
     *
     * <p>This is the source's City test, {@code if (po.City <> ' ')}
     * [USPS_Address/USADRVAL.SQLRPGLE:118-121]: true when {@code city} is non-blank, that
     * is, not empty after {@link String#strip()}. The readme's "ADDRESS2 non-blank" rule
     * [USPS_Address/Readme.md:31] is not used, because the source prevails over the readme.
     *
     * @return {@code true} when a city was returned
     */
    public boolean standardized() {
        return !city.isBlank();
    }
}
