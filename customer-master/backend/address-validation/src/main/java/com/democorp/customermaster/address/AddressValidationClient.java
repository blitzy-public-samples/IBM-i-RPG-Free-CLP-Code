package com.democorp.customermaster.address;

/**
 * Validates and standardizes one USA address against an address service.
 *
 * <p>Replaces the {@code USAdrVal} prototype [Copy_Mbrs/USADRVAL_P.RPGLE:2-4], whose one
 * implementation, the USADRVAL service program [USPS_Address/USADRVAL.SQLRPGLE:49-139],
 * reached callers through the binding directory ADRVAL_BND [USPS_Address/CRTBNDDIR.CLLE:4-7].
 * Its {@code USAdrValDS} parameter and return value become {@link AddressValidationRequest}
 * and {@link AddressValidationResult}. The interface knows the USPS address contract and
 * nothing about customers; customer-api's {@code AddressStandardizationService} owns the
 * mapping from customer fields (the Edit_Address logic) and is the only caller.
 * {@link AddressValidationAutoConfiguration} registers exactly one implementation:
 * {@link StubAddressValidationClient} by default, or
 * {@link UspsWebToolsAddressValidationClient}, which calls the Web Tools contract the source
 * documents [USPS_Address/USADRVAL.SQLRPGLE:74-137].
 *
 * <h2>Outcomes of {@link #validate validate}</h2>
 * Every call ends in exactly one of three ways:
 * <ul>
 *   <li><b>Standardized.</b> {@link AddressValidationResult#standardized() standardized()}
 *       is {@code true}, the source's test "If a city was returned, assume it worked"
 *       [USPS_Address/USADRVAL.SQLRPGLE:118-121], and the error components are {@code 0},
 *       {@code ""} and {@code ""}.</li>
 *   <li><b>Address-level error.</b> The service examined the address and rejected it:
 *       {@code standardized()} is {@code false}, and the error components carry the
 *       service's {@code Error} element [USPS_Address/USADRVAL.SQLRPGLE:123-133]. An
 *       implementation with no report to give, such as the stub echoing an input whose
 *       city is blank, leaves them {@code 0}, {@code ""} and {@code ""}. This outcome is a
 *       normal return, never an exception; customer-api turns it into 422 DEM9898.</li>
 *   <li><b>Service fault.</b> The service gave no usable answer, and the implementation
 *       throws {@link AddressServiceUnavailableException} (502 APP0502), replacing the
 *       source's {@code SQLProblem} calls
 *       [USPS_Address/USADRVAL.SQLRPGLE:94-96,114-116,134-136]. A fault is never returned
 *       as a result and never disguised as an address-level error.</li>
 * </ul>
 *
 * <h2>Rules for every implementation</h2>
 * <ul>
 *   <li><b>Thread safety.</b> The instance is a singleton bean shared by every concurrent
 *       request in customer-api. It must be safe for concurrent use without external
 *       synchronization and must keep no mutable state between calls.</li>
 *   <li><b>Blocking call, no transaction.</b> {@code validate} may block on network I/O up
 *       to its configured connect and read timeouts. customer-api calls it only while
 *       reviewing a customer, with no open transaction and no held database connection,
 *       and implementations must not depend on either.</li>
 *   <li><b>Secret and data hygiene.</b> No log line and no exception message may contain
 *       the request URL or its query string, the request document, a credential or a
 *       customer address value. {@link AddressServiceUnavailableException} states the
 *       message contract for faults.</li>
 * </ul>
 *
 * <h2>Seam for a future adapter</h2>
 * USPS retired the Web Tools APIs, which the source and
 * {@link UspsWebToolsAddressValidationClient} call, on 2026-01-25. Their replacement, the
 * USPS Addresses 3.0 API, uses OAuth 2.0 client credentials and JSON and requires a signed
 * licence. An adapter for it would be one more implementation of this interface, mapping
 * {@link AddressValidationRequest#address2()} (the Web Tools street line) to
 * {@code streetAddress} and {@link AddressValidationRequest#address1()} to
 * {@code secondaryAddress}; no caller would change. Building that adapter is outside the
 * scope of this module. The checks to complete before enabling any real client are listed
 * in {@code customer-master/docs/developer-guide.md}.
 */
@FunctionalInterface
public interface AddressValidationClient {

    /**
     * Validates and standardizes one address.
     *
     * <p>Replaces {@code po = USAdrVal(pi)}: one request, one answer, no state kept between
     * calls. The request already fits the {@code USAdrValDS} widths, because
     * {@link AddressValidationRequest} rejects a longer value when it is constructed.
     *
     * @param request the address to check; never {@code null}. A {@code null} request is a
     *                programming error, and implementations may throw
     *                {@link NullPointerException} for it
     * @return the service's answer, never {@code null}: a standardized address
     *         ({@link AddressValidationResult#standardized() standardized()} {@code true}),
     *         or an address-level error ({@code standardized()} {@code false}, with
     *         {@code errorNumber}, {@code errorSource} and {@code errorDescription} holding
     *         the service's report)
     * @throws AddressServiceUnavailableException for every service fault, with no result
     *         returned: a transport failure (connection refused, I/O error), a connect or
     *         read timeout, a response status outside 2xx, a response body over 64 KiB, a
     *         body that is not well-formed XML or declares a DOCTYPE, or a well-formed
     *         response that breaks the response rules (a root other than
     *         {@code AddressValidateResponse}, not exactly one {@code Address}, a value
     *         wider than its field, a blank {@code City} without exactly one valid
     *         {@code Error})
     * @throws IllegalArgumentException when the implementation's request format cannot carry
     *         a character of a request value, which is a caller error and no service fault:
     *         {@link UspsWebToolsAddressValidationClient} refuses a character outside the
     *         XML 1.0 {@code Char} production this way, before sending anything
     */
    AddressValidationResult validate(AddressValidationRequest request);
}
