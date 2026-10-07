package com.democorp.customermaster.address;

/**
 * Validates and standardizes one USA address against an address service.
 *
 * <p><b>Source replaced.</b> This interface replaces the {@code USAdrVal} prototype
 * {@code dcl-pr USAdrVal likeds(USAdrValDS) extproc('USADRVAL'); inAddr likeds(USAdrValDS);}
 * [Copy_Mbrs/USADRVAL_P.RPGLE:2-4]. Its one implementation, the USADRVAL service program
 * [USPS_Address/USADRVAL.SQLRPGLE:49-139], reached callers through the binding directory
 * ADRVAL_BND [USPS_Address/CRTBNDDIR.CLLE:4-7]. Here the {@code USAdrValDS} parameter and
 * return value become {@link AddressValidationRequest} and {@link AddressValidationResult},
 * and bind-time resolution becomes injection of exactly one bean of this type. The
 * interface knows the USPS address contract and nothing about customers; customer-api's
 * {@code AddressStandardizationService} owns the mapping from customer fields (the
 * Edit_Address logic) and is the only caller.
 *
 * <h2>Outcomes of {@link #validate validate}</h2>
 * Every call ends in exactly one of three ways:
 * <ul>
 *   <li><b>Standardized.</b> The service returned an address. The result's
 *       {@link AddressValidationResult#standardized() standardized()} is {@code true},
 *       which is the source's own test, "If a city was returned, assume it worked"
 *       [USPS_Address/USADRVAL.SQLRPGLE:118-121]. Its address components hold the
 *       standardized values, {@code zip4} is {@code ""} when the service returned no
 *       ZIP+4, {@code errorNumber} is {@code 0}, and {@code errorSource} and
 *       {@code errorDescription} are {@code ""}.</li>
 *   <li><b>Address-level error.</b> The service examined the address and rejected it.
 *       {@code standardized()} is {@code false}, and {@code errorNumber},
 *       {@code errorSource} and {@code errorDescription} carry the service's report, read
 *       from its {@code Error} element [USPS_Address/USADRVAL.SQLRPGLE:123-133], for
 *       example {@code -2147219401} / {@code clsAMS} / {@code Address Not Found.}. An
 *       implementation with no report to give, such as the stub echoing an input whose
 *       city is blank, leaves them {@code 0}, {@code ""} and {@code ""}. This outcome is a
 *       normal return, never an exception; customer-api turns it into 422 DEM9898
 *       {@code "USPS: " + errorDescription()}.</li>
 *   <li><b>Service fault.</b> The service gave no usable answer. The implementation throws
 *       {@link AddressServiceUnavailableException}, which customer-api maps to 502
 *       APP0502. This replaces the source's {@code SQLProblem} calls after the HTTP GET and
 *       after each XMLTABLE, which ended the program on any SQLSTATE other than
 *       {@code 00000} [USPS_Address/USADRVAL.SQLRPGLE:94-96,114-116,134-136]. A fault is
 *       never returned as a result and never disguised as an address-level error.</li>
 * </ul>
 *
 * <h2>Implementations and selection</h2>
 * <table>
 *   <caption>Implementations of this interface and the setting that selects each</caption>
 *   <tr><th>Implementation</th><th>Selected by</th><th>Behaviour</th></tr>
 *   <tr><td>{@link StubAddressValidationClient}</td>
 *       <td>{@code customer-master.address.client=stub} ({@code ADDRESS_VALIDATION_CLIENT});
 *           the default, also when the property is absent</td>
 *       <td>Deterministic and offline: fixture lookup from
 *           {@code stub/usps-stub-fixtures.json}, the {@code BADADDR} error, otherwise an
 *           uppercase echo with a blank {@code zip4}. Used by local runs, Compose and every
 *           test suite.</td></tr>
 *   <tr><td>{@link UspsWebToolsAddressValidationClient}</td>
 *       <td>{@code customer-master.address.client=usps}, with
 *           {@code customer-master.address.usps.user-id} set</td>
 *       <td>The USPS Web Tools {@code Verify} XML API at
 *           {@code customer-master.address.usps.base-url}, the contract the source
 *           documents [USPS_Address/USADRVAL.SQLRPGLE:74-137].</td></tr>
 * </table>
 * {@link AddressValidationAutoConfiguration} registers exactly one of them, according to
 * {@link AddressValidationProperties#client()}. Both of its bean methods back off when the
 * context already holds a bean of this interface type, so an application bean or a test
 * double, such as a {@code @MockitoBean AddressValidationClient}, replaces them entirely.
 * {@code customer-master.address.enabled=false} does not remove the bean; customer-api then
 * skips the call.
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
 *       customer address value. The message contract of
 *       {@link AddressServiceUnavailableException} states the same rule for faults.</li>
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
 *
 * <p>Example, one call and its three outcomes:
 * <pre>{@code
 * try {
 *     AddressValidationResult result = client.validate(
 *             new AddressValidationRequest("", "123 MAIN ST", "ANYTOWN", "CA", "90210", ""));
 *     if (result.standardized()) {
 *         String zip = result.zip4().isBlank()
 *                 ? result.zip5()                              // "90210"
 *                 : result.zip5() + "-" + result.zip4();       // "90210-1234"
 *     } else {
 *         String detail = "USPS: " + result.errorDescription(); // 422 DEM9898
 *     }
 * } catch (AddressServiceUnavailableException e) {
 *     // the service gave no usable answer: 502 APP0502
 * }
 * }</pre>
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
     */
    AddressValidationResult validate(AddressValidationRequest request);
}
