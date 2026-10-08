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
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
 * One request is sent per call, except that the JDK client re-sends it once, on another
 * connection, when the connection is reset or closed before any byte of the response
 * arrives; {@link #validate validate} states the request count of every outcome.
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
 * URI is a secret. No log line and no exception message written here is built from the
 * URI, the query string or the request document, none carries a configured credential in
 * any form the mask below covers, and no exception message quotes the response. Logs
 * carry the host, the HTTP status and the kind of fault. When the response holds a USPS
 * {@code Error} element, the line also carries that element's {@code Number} and
 * {@code Description} as the service sent them: at INFO for an address-level error, and
 * at WARN for a response fault whose body is a Web Tools root {@code <Error>}, such as an
 * authorization failure, or holds an {@code Error} in its one {@code Address}, such as
 * one the response rules reject. These are the only response texts ever logged. A
 * {@code Description} is free text that can echo what the request carried, the request
 * URL and a credential included, so it is logged only through the redaction, the mask and
 * the escaping below. Spring's {@code ResourceAccessException} quotes the full URI in its
 * message, so it is never chained or logged: the fault names its root cause's class
 * instead. Each logged reason, {@code Number} and {@code Description} first has every
 * echo of the request replaced by a marker, in this order: a request document, from
 * {@code <AddressValidateRequest} to its closing tag or the end of the text, the same in
 * its {@code &lt;} entity form, or URL-encoded from {@code %3CAddressValidateRequest} to
 * the next whitespace, by {@value #REQUEST_DOCUMENT_MARKER}; a Verify query, from
 * {@code API=Verify&XML=} or {@code API=Verify&amp;XML=} to the next whitespace, by
 * {@value #REQUEST_QUERY_MARKER}; and the configured base URL by
 * {@value #REQUEST_URL_MARKER}. An echoed request URL is thus logged as
 * {@code [request URL]?[request query]}, and the rest of the text as sent. Every fault
 * reason, logged or thrown, and every logged {@code Number} and {@code Description} then
 * passes through a mask that replaces each configured credential with {@code ****} in its
 * raw form, its request-wire attribute form, its {@code &apos;} entity form and the URL
 * encoding of each. Each logged reason, {@code Number} and {@code Description}, once
 * redacted and masked, has its control, format and line-separator characters escaped, so
 * text from the service cannot forge or split a log line; the host, parsed from the
 * configured base URL, cannot hold such characters and is logged as parsed.
 * Results and exception messages keep their text unescaped. The client is built from
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

    /** Logged in place of a {@code Number} or {@code Description} element the response lacks. */
    private static final String ABSENT = "-";

    /**
     * Logged in place of an echoed request document, in any of the forms
     * {@link #REQUEST_DOCUMENT} matches.
     */
    static final String REQUEST_DOCUMENT_MARKER = "[request document]";

    /**
     * Logged in place of an echoed Verify query, in any of the forms {@link #REQUEST_QUERY}
     * matches.
     */
    static final String REQUEST_QUERY_MARKER = "[request query]";

    /** Logged in place of each occurrence of the configured base URL. */
    static final String REQUEST_URL_MARKER = "[request URL]";

    /**
     * An echoed request document, which carries the credentials and the customer address:
     * decoded, from {@code <AddressValidateRequest} to the next
     * {@code </AddressValidateRequest>} inclusive, or to the end of the text when unclosed;
     * the same in its {@code &lt;} entity form, closed by
     * {@code &lt;/AddressValidateRequest&gt;}; or URL-encoded, from
     * {@code %3CAddressValidateRequest} (hex digits in either case) to the next whitespace,
     * a span no encoded document breaks. Each form ends only at a closing tag of its own
     * form, which the values inside it, escaped once more, can never spell. Linear in the
     * text length: each lazy span tests one fixed-length closing tag at each character and
     * never backtracks into itself, and the encoded span is one run of non-whitespace.
     */
    private static final Pattern REQUEST_DOCUMENT = Pattern.compile(
            "<AddressValidateRequest.*?(?:</AddressValidateRequest>|\\z)"
                    + "|&lt;AddressValidateRequest.*?(?:&lt;/AddressValidateRequest&gt;|\\z)"
                    + "|%3[Cc]AddressValidateRequest\\S*",
            Pattern.DOTALL);

    /**
     * An echoed Verify query, its separator written as {@code &} or {@code &amp;}, from
     * {@code API=Verify} to the next whitespace, so it spans the query in any
     * percent-encoding. A {@link #REQUEST_DOCUMENT_MARKER} the document step left right
     * after {@code XML=} belongs to the query and is taken whole, although it holds a blank.
     * Linear: fixed literals followed by one run of non-whitespace.
     */
    private static final Pattern REQUEST_QUERY = Pattern.compile(
            "API=Verify(?:&amp;|&)XML=(?:" + Pattern.quote(REQUEST_DOCUMENT_MARKER) + ")?\\S*");

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
     * when it elapses. The JDK client re-sends a {@code GET} once when its connection is
     * reset or closed before any byte of the response arrives, and no builder setting
     * disables that; {@link #validate validate} states the resulting request count.
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
     * Sends the Verify request and returns the service's answer.
     *
     * <p><b>Requests sent.</b> Each call sends one Verify request, with one exception the
     * JDK {@link HttpClient} adds on its own for a {@code GET}: when the connection is reset
     * or closed before any byte of the response arrives, the JDK client re-sends the
     * request once, on another connection. The service can then receive the request,
     * credentials included, twice, and never more; the call still ends in one result or
     * one {@link AddressServiceUnavailableException}, with one log line. No setting of the
     * JDK client turns this re-send off: its {@code jdk.httpclient.disableRetryConnect}
     * system property covers refused connects only, for the whole JVM. Every other outcome
     * sends the request at most once: any response, whatever its status; a response cut
     * short after its first bytes; a read timeout; and a refused or timed-out connect, which
     * sends none.
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
            logCodecFault(status, e.getMessage(), raw.body());
            throw e;
        }

        if (result.standardized()) {
            log.debug("USPS address standardized: host={} status={}", host, status);
        } else if (log.isInfoEnabled()) {
            // The Number is logged as the service sent it, not as the parsed int, so a
            // credential echoed with a sign or leading zeros is still masked whole. The body
            // parse accepted holds exactly one Error, which serviceError reads.
            Optional<UspsXmlCodec.ServiceError> serviceError = codec.serviceError(raw.body());
            String number = serviceError.isPresent()
                    ? serviceError.get().number()
                    : Integer.toString(result.errorNumber());
            log.info("USPS address not standardized: host={} status={} errorNumber={} errorDescription={}",
                    host, status, logElement(number), logText(result.errorDescription()));
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
            log.warn("USPS address validation failed: host={} reason={}", host, logText(reason));
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

    /** Logs a fault at WARN with the host, the status and the reason, masked and escaped. */
    private void logFault(int status, String reason) {
        log.warn("USPS address validation failed: host={} status={} reason={}", host, status, logText(reason));
    }

    /**
     * Logs a fault {@link UspsXmlCodec#parse(byte[])} reported. When the body holds a USPS
     * {@code Error} element, a Web Tools root {@code <Error>} or the {@code Error} of the
     * one {@code Address}, the WARN line also carries its {@code Number} and
     * {@code Description} as the service sent them, redacted, masked and escaped, with
     * {@value #ABSENT} for an element the {@code Error} lacks. Otherwise the line is the
     * one {@link #logFault logFault} writes.
     *
     * @param status the HTTP status, a 2xx
     * @param reason the codec's fault message, which quotes no response content
     * @param body   the response body the codec rejected, at most {@value #MAX_BODY_BYTES} bytes
     */
    private void logCodecFault(int status, String reason, byte[] body) {
        Optional<UspsXmlCodec.ServiceError> serviceError = codec.serviceError(body);
        if (serviceError.isEmpty()) {
            logFault(status, reason);
            return;
        }
        UspsXmlCodec.ServiceError error = serviceError.get();
        log.warn("USPS address validation failed: host={} status={} reason={} errorNumber={} errorDescription={}",
                host, status, logText(reason), logElement(error.number()), logElement(error.description()));
    }

    /**
     * Prepares the text of a USPS {@code Error} child for the log: {@value #ABSENT} when
     * the element is absent, otherwise its {@link #logText log text}, so an empty element
     * logs as empty.
     */
    private String logElement(String text) {
        return text == null ? ABSENT : logText(text);
    }

    /**
     * Prepares a text for a log argument: every echo of the request
     * {@link #redactRequest redacted} first, so that a document or query is replaced whole,
     * credentials and customer address included, then {@link #mask masked}, so that a
     * credential outside them is replaced whole before any of its characters is escaped,
     * then made {@link #logSafe log-safe}.
     *
     * @param text text about to be logged; {@code null} reads as {@code ""}
     * @return the redacted, masked, escaped text
     */
    private String logText(String text) {
        return logSafe(mask(redactRequest(text)));
    }

    /**
     * Replaces every echo of the outbound request in {@code text}, in this order: each
     * {@linkplain #REQUEST_DOCUMENT request document} with {@value #REQUEST_DOCUMENT_MARKER},
     * each {@linkplain #REQUEST_QUERY Verify query} with {@value #REQUEST_QUERY_MARKER}, and
     * each occurrence of the configured base URL with {@value #REQUEST_URL_MARKER}. An
     * echoed request URL, encoded or decoded, becomes {@code [request URL]?[request query]};
     * every other character is kept. Linear in the text length, which is at most the
     * {@value #MAX_BODY_BYTES}-byte body.
     *
     * <p>Applied to logged text only: results and exception messages keep the text as the
     * service sent it. Package-private so that the tests can check every form it covers.
     *
     * @param text text about to be logged; {@code null} reads as {@code ""}
     * @return the text with every echo of the request replaced by its marker
     */
    String redactRequest(String text) {
        if (text == null) {
            return "";
        }
        String redacted = REQUEST_DOCUMENT.matcher(text)
                .replaceAll(Matcher.quoteReplacement(REQUEST_DOCUMENT_MARKER));
        redacted = REQUEST_QUERY.matcher(redacted)
                .replaceAll(Matcher.quoteReplacement(REQUEST_QUERY_MARKER));
        return redacted.replace(baseUrl, REQUEST_URL_MARKER);
    }

    /**
     * Escapes every character that could end, split or disguise a log line: each code
     * point whose {@link Character#getType(int) general category} is
     * {@link Character#CONTROL CONTROL} (CR, LF, TAB, ESC, DEL, NEL and the other C0 and
     * C1 controls), {@link Character#FORMAT FORMAT} (such as the bidirectional override
     * U+202E), {@link Character#LINE_SEPARATOR LINE_SEPARATOR} (U+2028) or
     * {@link Character#PARAGRAPH_SEPARATOR PARAGRAPH_SEPARATOR} (U+2029) becomes a visible
     * backslash, {@code u} and its code point in at least four uppercase hexadecimal
     * digits, so a line feed is logged as <code>&#92;u000A</code>. Every other character is
     * kept, accented letters included.
     *
     * <p>Applied to logged text only: results and exception messages keep the text as the
     * service sent it.
     *
     * @param text text about to be logged; {@code null} reads as {@code ""}
     * @return the text with those characters escaped
     */
    static String logSafe(String text) {
        if (text == null) {
            return "";
        }
        StringBuilder safe = new StringBuilder(text.length());
        text.codePoints().forEach(codePoint -> {
            switch (Character.getType(codePoint)) {
                case Character.CONTROL, Character.FORMAT,
                        Character.LINE_SEPARATOR, Character.PARAGRAPH_SEPARATOR ->
                        safe.append(String.format("\\u%04X", codePoint));
                default -> safe.appendCodePoint(codePoint);
            }
        });
        return safe.toString();
    }

    /**
     * Replaces every configured credential form in {@code text} with
     * {@value AddressValidationProperties#MASK}, longest form first so that one credential
     * contained in the other is still masked whole.
     *
     * <p>Package-private so that the tests can check every credential form it covers.
     *
     * @param text text about to be logged or thrown; {@code null} reads as {@code ""}
     * @return the masked text
     */
    String mask(String text) {
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
     * The forms in which a credential can appear in text, each also URL-encoded (UTF-8) as
     * a query string or an echoing service would carry it:
     * <ul>
     *   <li>raw, as configured;</li>
     *   <li>the request-wire form, the attribute text {@link UspsXmlCodec#attributeValue}
     *       returns, which is what the request document carries and which leaves an
     *       apostrophe literal ({@code p'&q} travels as {@code p'&amp;q});</li>
     *   <li>the full-entity form of {@link #xmlEscape xmlEscape}, which also encodes the
     *       apostrophe ({@code p&apos;&amp;q}), as other XML serializers write it.</li>
     * </ul>
     * Blank credentials yield no form. Duplicates are dropped, and the forms are sorted
     * longest first.
     */
    private static List<String> secretForms(String... credentials) {
        Set<String> forms = new LinkedHashSet<>();
        for (String credential : credentials) {
            if (credential == null || credential.isBlank()) {
                continue;
            }
            List<String> written = List.of(
                    credential, UspsXmlCodec.attributeValue(credential), xmlEscape(credential));
            for (String form : written) {
                forms.add(form);
                forms.add(URLEncoder.encode(form, StandardCharsets.UTF_8));
            }
        }
        List<String> sorted = new ArrayList<>(forms);
        sorted.sort(Comparator.comparingInt(String::length).reversed());
        return List.copyOf(sorted);
    }

    /**
     * Escapes every character an XML attribute value can carry as a predefined entity,
     * the apostrophe included. This is the full-entity form other XML serializers produce;
     * the request document's own form comes from {@link UspsXmlCodec#attributeValue}.
     */
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
