package com.democorp.customermaster.address;

/**
 * Signals that the address service gave no usable answer, so no address was validated or
 * standardized.
 *
 * <p>Replaces USADRVAL's SQLProblem calls after the HTTP GET and after each XMLTABLE, which
 * dumped the program and ended it on any SQLSTATE other than {@code 00000}
 * [USPS_Address/USADRVAL.SQLRPGLE:94-96,114-116,134-136].
 * {@link AddressValidationClient#validate validate} throws it for every service fault it
 * lists, and {@link UspsXmlCodec#parse parse} for each response rule a body breaks.
 * customer-api maps it to 502 APP0502, never to DEM9898 or a 500.
 *
 * <p><b>Not an address error.</b> An address the service examined and rejected, such as
 * "Address Not Found.", is a normal {@link AddressValidationResult} whose
 * {@link AddressValidationResult#standardized() standardized()} is false. This exception is
 * never used for that case, and a fault is never turned into such a result.
 *
 * <p><b>Message contract.</b> The message names the kind of fault only, for example
 * {@code "USPS returned HTTP 500"}. It reaches server logs, so it never contains the
 * request URL or its query string, the request XML, the {@code USERID} attribute name, the
 * user id, the password, a customer address value or any part of the response body. The
 * API response never shows it.
 *
 * <p><b>Causes.</b> Logging the exception with its stack trace prints every chained
 * message, so chain only a cause whose own message obeys the contract above, such as a JDK
 * {@code java.net.http.HttpTimeoutException}. Never chain a Spring
 * {@code RestClientException} or {@code ResourceAccessException}: their message holds the
 * request URI, whose Web Tools query string carries the user id and password; name the
 * root cause's class in the message instead. Prefer not to chain XML parser exceptions,
 * because a {@code SAXParseException} can quote the response body.
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
