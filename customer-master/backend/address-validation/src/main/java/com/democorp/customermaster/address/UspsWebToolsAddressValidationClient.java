package com.democorp.customermaster.address;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * {@link AddressValidationClient} that calls the USPS Web Tools {@code Verify} API with the
 * {@code AddressValidateRequest} XML document, the contract the original address service
 * documents.
 *
 * <h2>Source replaced</h2>
 * {@code USAdrVal} in USPS_Address/USADRVAL.SQLRPGLE runs
 * {@code QSYS2.HTTP_GET('https://secure.shippingapis.com/ShippingAPI.dll' concat
 * '?API=Verify&XML=' concat url_encode(...)) into :retXML} [74-93] with a 2,048-byte
 * response buffer [58], reading the user id and password from the USPS_ID and USPS_PWD
 * data areas [43-46,66-69]. Any SQLSTATE other than {@code 00000} after the call goes to
 * {@code SQLProblem('USPS API Call')} [94-96], which dumps the program and ends it with a
 * CPF9898 escape message carrying the SQL diagnostics (Service_Pgms/SRV_SQL.SQLRPGLE:20-57).
 * Here the HTTP call is a Spring {@link RestClient} GET over the JDK
 * {@link HttpClient}, the credentials come from {@link AddressValidationProperties.Usps},
 * the XML is written and read by {@link UspsXmlCodec}, and every fault is an
 * {@link AddressServiceUnavailableException}, which customer-api maps to 502 APP0502.
 *
 * <h2>Request</h2>
 * {@code GET <base-url>?API=Verify&XML=<url-encoded AddressValidateRequest>}; when the
 * configured base URL already carries a query, the Verify parameters are appended with
 * {@code &}. The {@link URI} is passed to the client as built, so nothing re-encodes it.
 * Redirects are never followed, HTTP/1.1 is used, the connect timeout is
 * {@link AddressValidationProperties.Usps#connectTimeout()} and the read timeout
 * {@link AddressValidationProperties.Usps#readTimeout()} bounds the wait for the response.
 *
 * <h2>Outcomes</h2>
 * <ul>
 *   <li><b>Standardized</b> or <b>address-level error</b>: a 2xx response of at most
 *       {@value #MAX_BODY_BYTES} bytes that passes the response rules of
 *       {@link UspsXmlCodec#parse(byte[])} is returned as parsed. An address-level error,
 *       such as {@code Address Not Found.}, is a normal result that customer-api turns
 *       into 422 DEM9898.</li>
 *   <li><b>Service fault</b>, thrown as {@link AddressServiceUnavailableException} and
 *       never returned as a result:
 *       <ul>
 *         <li>a status outside 2xx, 3xx included ({@code "USPS returned HTTP 500"});</li>
 *         <li>a body over {@value #MAX_BODY_BYTES} bytes, measured on the bytes read and
 *             not on {@code Content-Length}, which replaces the source's 2,048-byte
 *             buffer;</li>
 *         <li>a connect or read timeout, a refused connection or any other I/O error
 *             ({@code "USPS address service I/O failure: HttpTimeoutException"});</li>
 *         <li>every fault {@link UspsXmlCodec#parse(byte[])} reports, such as a malformed
 *             body or a Web Tools root {@code <Error>}.</li>
 *       </ul></li>
 * </ul>
 *
 * <h2>Secret and data hygiene</h2>
 * The Web Tools contract puts the user id and password in the query string, so the request
 * URI is a secret. No log line and no exception message written here contains the URI, the
 * query string, the request document, the {@code USERID} attribute name, a credential, a
 * customer address value or the response body. Logs carry the host, the HTTP status and
 * the kind of fault only; an address-level error also logs the USPS error number and
 * description. Spring's {@code ResourceAccessException} quotes the full URI in its message,
 * so it is never chained or logged: the fault names its root cause's class instead. Every
 * text logged or thrown passes through a mask that replaces each configured credential, in
 * its raw, XML-escaped and URL-encoded forms, with {@code ****}. The client is built from
 * the static {@link RestClient#builder()} rather than an application {@code RestClient.Builder}
 * bean, so no observation registry records the URI as a metric or trace tag.
 *
 * <h2>Before enabling</h2>
 * USPS retired the Web Tools APIs, including this endpoint, on 2026-01-25. The client keeps
 * the base URL configurable but cannot be assumed to reach a live service. Complete the
 * pre-enablement checklist in {@code customer-master/docs/developer-guide.md} (endpoint,
 * registration and licensing, contract differences, credential exposure in the query
 * string, the success test) before setting {@code ADDRESS_VALIDATION_CLIENT=usps}.
 *
 * <h2>Lifecycle and threading</h2>
 * The class carries no stereotype annotation: its package lies under customer-api's
 * component-scan root, and the bean is created only by
 * {@link AddressValidationAutoConfiguration}. All fields are final and immutable or
 * thread-safe ({@link HttpClient}, {@link RestClient}, {@link UspsXmlCodec}), so one
 * instance serves concurrent requests. {@link #validate validate} opens no transaction and
 * touches no shared mutable state. {@link #close()} shuts the HTTP client down; Spring
 * calls it when the application context closes.
 *
 * <p>Example:
 * <pre>{@code
 * var properties = new AddressValidationProperties(true, AddressValidationProperties.Client.USPS,
 *         new AddressValidationProperties.Usps(
 *                 AddressValidationProperties.DEFAULT_BASE_URL, userId, password,
 *                 Duration.ofSeconds(5), Duration.ofSeconds(10)));
 * try (var client = new UspsWebToolsAddressValidationClient(properties)) {
 *     AddressValidationResult result = client.validate(
 *             new AddressValidationRequest("", "8 ELMWOOD DR", "OLD HAVEN", "CT", "06399", ""));
 * }
 * }</pre>
 */
public class UspsWebToolsAddressValidationClient implements AddressValidationClient, AutoCloseable {

    /**
     * Largest response body accepted, in bytes (64 KiB). A larger body is a service fault.
     * It replaces the 2,048-byte {@code retXML} buffer of the source
     * [USPS_Address/USADRVAL.SQLRPGLE:58], which a valid Verify response never approaches.
     */
    public static final int MAX_BODY_BYTES = 65_536;

    private static final Logger log = LoggerFactory.getLogger(UspsWebToolsAddressValidationClient.class);

    private static final byte[] NO_BODY = new byte[0];

    private final String baseUrl;
    private final String userId;
    private final String password;

    /** Host of the base URL, the only part of the endpoint that is ever logged. */
    private final String host;

    /** Credential forms the mask replaces, longest first; empty when none is configured. */
    private final List<String> secretForms;

    private final UspsXmlCodec codec;
    private final HttpClient httpClient;
    private final RestClient restClient;

    /**
     * Creates the client from the bound settings.
     *
     * <p>The JDK client is built with the configured connect timeout, redirects disabled
     * (a 3xx is a fault, never followed) and HTTP/1.1 (no {@code h2c} upgrade attempt on
     * plain {@code http} endpoints such as a local gateway or test fake). The read timeout
     * is set on Spring's {@link JdkClientHttpRequestFactory}, which cancels the exchange
     * when it elapses.
     *
     * @param properties the module settings; only {@link AddressValidationProperties#usps()}
     *                   is used, already normalized and validated by its constructor
     * @throws NullPointerException if {@code properties} is {@code null}
     */
    public UspsWebToolsAddressValidationClient(AddressValidationProperties properties) {
        Objects.requireNonNull(properties, "properties");
        AddressValidationProperties.Usps usps = properties.usps();
        this.baseUrl = usps.baseUrl();
        this.userId = usps.userId();
        this.password = usps.password();
        // The base URL was parsed and checked for a host when the settings were bound.
        this.host = URI.create(baseUrl).getHost();
        this.secretForms = secretForms(userId, password);
        this.codec = new UspsXmlCodec();

        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(usps.connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .version(HttpClient.Version.HTTP_1_1)
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(usps.readTimeout());
        // The static builder, never an application RestClient.Builder bean: such a bean
        // carries the observation registry, whose metrics and traces would record the
        // request URI, credentials included.
        this.restClient = RestClient.builder()
                .requestFactory(requestFactory)
                .build();
    }

    /**
     * Sends one Verify request and returns the service's answer.
     *
     * @param request the address to check; never {@code null}
     * @return the standardized address, or the address-level error the service reported
     * @throws AddressServiceUnavailableException for a status outside 2xx, a body over
     *         {@value #MAX_BODY_BYTES} bytes, a timeout or other I/O failure, or a response
     *         that fails the response rules of {@link UspsXmlCodec#parse(byte[])}
     * @throws NullPointerException if {@code request} is {@code null}
     */
    @Override
    public AddressValidationResult validate(AddressValidationRequest request) {
        Objects.requireNonNull(request, "request");
        Raw raw = exchange(requestUri(request));

        int status = raw.status().value();
        if (!raw.status().is2xxSuccessful()) {
            throw fault(status, "USPS returned HTTP " + status);
        }
        if (raw.body().length > MAX_BODY_BYTES) {
            throw fault(status, "USPS response exceeds " + MAX_BODY_BYTES + " bytes");
        }

        AddressValidationResult result;
        try {
            result = codec.parse(raw.body());
        } catch (AddressServiceUnavailableException e) {
            logFault(status, e.getMessage());
            throw e;
        }

        if (result.standardized()) {
            log.debug("USPS address standardized: host={} status={}", host, status);
        } else {
            log.info("USPS address not standardized: host={} status={} errorNumber={} errorDescription={}",
                    host, status, result.errorNumber(), mask(result.errorDescription()));
        }
        return result;
    }

    /**
     * Shuts the JDK HTTP client down, waiting for any exchange still in flight, each of
     * which ends within the configured timeouts. A later {@link #validate validate} call
     * fails with {@link AddressServiceUnavailableException}. Spring infers this method as
     * the bean's destroy method.
     */
    @Override
    public void close() {
        httpClient.close();
    }

    /**
     * Builds {@code <base-url>?API=Verify&XML=<encoded document>}, or {@code &...} when the
     * base URL already holds a query. The result carries the credentials and must never be
     * logged.
     */
    private URI requestUri(AddressValidationRequest request) {
        String query = codec.requestQuery(request, userId, password);
        String separator = baseUrl.indexOf('?') >= 0 ? "&" : "?";
        try {
            return URI.create(baseUrl + separator + query);
        } catch (IllegalArgumentException e) {
            // Unreachable with a base URL that passed binding, as the encoded query holds only
            // URI-safe characters. Neither chained nor quoted: the parser's message repeats the
            // whole input, credentials included.
            throw new IllegalStateException(
                    "the USPS request could not be built from " + AddressValidationProperties.PREFIX
                            + ".usps.base-url");
        }
    }

    /**
     * Performs the GET and captures the status and, for a 2xx, at most
     * {@value #MAX_BODY_BYTES} + 1 bytes of the body, so an oversize body is detected
     * without reading it whole. A non-2xx body is never read. No message converter and no
     * status handler is involved, and the response is closed when the exchange returns.
     */
    private Raw exchange(URI uri) {
        try {
            return restClient.get()
                    .uri(uri)
                    .exchange((clientRequest, clientResponse) -> {
                        HttpStatusCode status = clientResponse.getStatusCode();
                        byte[] body = status.is2xxSuccessful() ? readBounded(clientResponse.getBody()) : NO_BODY;
                        return new Raw(status, body);
                    });
        } catch (RestClientException | UncheckedIOException e) {
            // Timeouts (HttpTimeoutException, HttpConnectTimeoutException), refused connections
            // and read failures arrive wrapped in ResourceAccessException, whose message holds
            // the full request URI with USERID and PASSWORD. The fault therefore names the root
            // cause's class only and is constructed without a cause.
            Throwable root = NestedExceptionUtils.getMostSpecificCause(e);
            String reason = "USPS address service I/O failure: " + root.getClass().getSimpleName();
            log.warn("USPS address validation failed: host={} reason={}", host, mask(reason));
            throw new AddressServiceUnavailableException(mask(reason));
        }
    }

    /** Reads at most one byte beyond the limit; the caller rejects anything longer. */
    private static byte[] readBounded(InputStream body) throws IOException {
        return body == null ? NO_BODY : body.readNBytes(MAX_BODY_BYTES + 1);
    }

    /** Logs a fault seen after a status was received and returns the exception to throw. */
    private AddressServiceUnavailableException fault(int status, String reason) {
        logFault(status, reason);
        return new AddressServiceUnavailableException(mask(reason));
    }

    /** Logs a fault at WARN with the host, the status and the masked reason. */
    private void logFault(int status, String reason) {
        log.warn("USPS address validation failed: host={} status={} reason={}", host, status, mask(reason));
    }

    /**
     * Replaces every configured credential form in {@code text} with
     * {@value AddressValidationProperties#MASK}, longest form first so that one credential
     * contained in the other is still masked whole.
     *
     * @param text text about to be logged or thrown; {@code null} reads as {@code ""}
     * @return the masked text
     */
    private String mask(String text) {
        if (text == null) {
            return "";
        }
        String masked = text;
        for (String form : secretForms) {
            masked = masked.replace(form, AddressValidationProperties.MASK);
        }
        return masked;
    }

    /**
     * The forms in which a credential can appear in text: as configured, XML-escaped as the
     * request document writes an attribute, and URL-encoded as the query carries either of
     * those. Blank credentials yield no form. Sorted longest first.
     */
    private static List<String> secretForms(String... credentials) {
        Set<String> forms = new LinkedHashSet<>();
        for (String credential : credentials) {
            if (credential == null || credential.isBlank()) {
                continue;
            }
            String escaped = xmlEscape(credential);
            forms.add(credential);
            forms.add(escaped);
            forms.add(URLEncoder.encode(credential, StandardCharsets.UTF_8));
            forms.add(URLEncoder.encode(escaped, StandardCharsets.UTF_8));
        }
        List<String> sorted = new ArrayList<>(forms);
        sorted.sort(Comparator.comparingInt(String::length).reversed());
        return List.copyOf(sorted);
    }

    /** Escapes the characters an XML attribute value can carry escaped. */
    private static String xmlEscape(String value) {
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }

    /**
     * Status and bounded body of one exchange, captured inside the exchange callback and
     * examined after the response is closed.
     *
     * @param status the HTTP status
     * @param body   the body bytes read, empty for a non-2xx status
     */
    private record Raw(HttpStatusCode status, byte[] body) {
    }
}
