package com.democorp.customermaster.address;

/**
 * Signals that the address service could not give a usable answer, so no
 * address was validated or standardized.
 *
 * <p><b>Source behaviour replaced.</b> On IBM i, USADRVAL tests the SQLSTATE
 * after each of its three statements and, on anything other than
 * {@code 00000}, calls SQLProblem with a debug label: {@code 'USPS API Call'}
 * after the HTTP GET, {@code 'XMLPARSE of Addr'} after the address XMLTABLE
 * and {@code 'XMLPARSE of Error'} after the error XMLTABLE. SQLProblem reads
 * the diagnostics, dumps the program and sends a CPF9898 escape message that
 * carries the SQLSTATE and the SQL message text, which ends the program. The
 * target replaces that termination and dump with this one typed runtime
 * exception.
 *
 * <p><b>When it is thrown.</b> For every address-service fault, by
 * {@code UspsWebToolsAddressValidationClient.validate} and
 * {@code UspsXmlCodec.parse}:
 * <ul>
 *   <li>a transport failure: connection refused, I/O error or timeout;</li>
 *   <li>a response status outside 2xx;</li>
 *   <li>a response body larger than 64 KiB;</li>
 *   <li>a body that is not well-formed XML or declares a DOCTYPE;</li>
 *   <li>a well-formed response that breaks the response rules: a root other
 *       than {@code AddressValidateResponse} (a Web Tools root
 *       {@code <Error>} included), no {@code Address} row or more than one,
 *       an element longer than its width, a blank {@code City} without
 *       exactly one {@code Error}, or an {@code Error} whose {@code Number},
 *       {@code Source} or {@code Description} is missing or invalid.</li>
 * </ul>
 * {@code AddressValidationClient.validate} declares it in its
 * {@code @throws} clause.
 *
 * <p><b>What it is not.</b> An address the service examined and rejected,
 * such as "Address Not Found.", is a normal result: an
 * {@code AddressValidationResult} whose {@code standardized()} is false and
 * whose error number, source and description are filled. This exception is
 * never used for that case, and a fault is never turned into such a result.
 *
 * <p><b>HTTP mapping.</b> customer-api's {@code ApiExceptionHandler} maps this
 * exception to {@code 502 Bad Gateway}, {@code application/problem+json}
 * with catalog key {@code APP0502} ("Address service is unavailable. Try
 * again later.") and no {@code errors}. When it arises during a review after
 * the State rule passed, {@code CustomerMaintenanceService.review} first
 * wraps it in {@code ReviewFailedException} so the problem also carries
 * {@code stateAccepted}. It never becomes DEM9898 or a 500.
 *
 * <p><b>Message contract for code that constructs it.</b> The message names
 * the kind of fault only, for example:
 * <ul>
 *   <li>{@code "USPS returned HTTP 500"}</li>
 *   <li>{@code "response root is not AddressValidateResponse"}</li>
 *   <li>{@code "USPS address service I/O failure: HttpTimeoutException"}</li>
 * </ul>
 * It must never contain the request URL or its query string, the request
 * XML, the {@code USERID} attribute name, the user id, the password, any
 * customer address value or any part of the response body. The message
 * reaches server logs, so this rule keeps credentials and customer data out
 * of them; the API response never shows the message at all.
 *
 * <p><b>Causes.</b> The two-argument constructor exists for causes whose own
 * message is safe under the rule above, such as a JDK
 * {@code java.net.http.HttpTimeoutException} or a {@code java.io.IOException}
 * raised before any URL was formatted into it. Two kinds of cause must be
 * handled with care:
 * <ul>
 *   <li>Do not chain Spring {@code RestClientException} or
 *       {@code ResourceAccessException}. Their messages contain the full
 *       request URI, and the Web Tools query string carries the user id and
 *       password. Name the root cause's class in the message instead.</li>
 *   <li>Prefer not to chain XML parser exceptions. A
 *       {@code SAXParseException} can quote part of the document it failed
 *       on, which is response body content.</li>
 * </ul>
 * Logging this exception with its stack trace prints every chained message,
 * so an unsafe cause would undo the message contract.
 *
 * <pre>{@code
 * try {
 *     body = restClient.get().uri(uri).retrieve().body(byte[].class);
 * } catch (ResourceAccessException e) {
 *     Throwable root = NestedExceptionUtils.getMostSpecificCause(e);
 *     throw new AddressServiceUnavailableException(
 *             "USPS address service I/O failure: " + root.getClass().getSimpleName());
 * }
 * }</pre>
 *
 * <p>This class carries no fields beyond those of {@link RuntimeException}
 * and has no Spring stereotype annotation: its package lies under
 * customer-api's component-scan root, and the module's beans are registered
 * only by its auto-configuration.
 */
public final class AddressServiceUnavailableException extends RuntimeException {

    /** Serialized form version; the class adds no state to {@link RuntimeException}. */
    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception for a fault detected directly, such as a non-2xx
     * status, an oversize body or a response that breaks the response rules.
     *
     * @param message the kind of fault, free of URLs, credentials, request
     *                XML, address values and response content
     */
    public AddressServiceUnavailableException(String message) {
        super(message);
    }

    /**
     * Creates the exception around an underlying failure whose own message is
     * safe to log, such as a JDK {@code HttpTimeoutException}.
     *
     * @param message the kind of fault, free of URLs, credentials, request
     *                XML, address values and response content
     * @param cause   the underlying failure; never a Spring
     *                {@code RestClientException}, whose message carries the
     *                request URI and with it the credentials
     */
    public AddressServiceUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
