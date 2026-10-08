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
import java.util.stream.Collectors;
import java.util.stream.Stream;

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
 * <h2>Request and outcomes</h2>
 * {@code GET <base-url>?API=Verify&XML=<url-encoded AddressValidateRequest>}, joined with
 * {@code &} when the base URL already holds a query, sent as built over HTTP/1.1 with
 * redirects never followed and the configured connect and read timeouts;
 * {@link #validate validate} states how many requests each outcome sends. A 2xx body of at
 * most {@value #MAX_BODY_BYTES} bytes that passes {@link UspsXmlCodec#parse(byte[])} is
 * returned as parsed, an address-level error such as {@code Address Not Found.} included,
 * which customer-api turns into 422 DEM9898. A status outside 2xx, a larger body (counted
 * on the bytes read, not on {@code Content-Length}), a timeout or other I/O error, and
 * every fault the codec reports are thrown as {@link AddressServiceUnavailableException},
 * never returned as a result.
 *
 * <h2>Secret and data hygiene</h2>
 * The Web Tools contract puts the user id and password in the query string, so the request
 * URI is a secret: no log line or exception message is built from the URI, the query or
 * the request document, and no exception message quotes the response. Spring's
 * {@code ResourceAccessException} quotes the URI, so it is never chained or logged; the
 * fault names its root cause's class instead. A log line carries the host, the HTTP status
 * and the reason, and, when the response holds a USPS {@code Error}, its {@code Number} and
 * {@code Description} only as the {@link #diagnostic diagnostic} projection: a URL, a
 * credential, a request echo, a value this call submitted or other undocumented free text
 * never reaches the log, and a documented description or error code reads as sent.
 * Results keep the service's text, and exception messages keep their reason with every
 * credential masked by {@link UspsTextRedactor}. The client is built from the static
 * {@link RestClient#builder()} rather than an application {@code RestClient.Builder} bean,
 * so no observation registry records the URI as a metric or trace tag.
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
 * thread-safe ({@link HttpClient}, {@link RestClient}, {@link UspsXmlCodec},
 * {@link UspsTextRedactor}), so one instance serves concurrent requests; the values one
 * call redacts from its log line are passed down that call and never stored.
 * {@link #validate validate} opens no transaction and touches no shared mutable state.
 * {@link #close()} shuts the HTTP client down; Spring calls it when the application
 * context closes.
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

    /** Logged in place of each value the call submitted, in any of its {@link #addressForms forms}. */
    static final String ADDRESS_MARKER = "[address]";

    /** Logged in place of each URL {@link #URL} matches. */
    static final String URL_MARKER = "[URL]";

    /**
     * The USPS {@code Description} texts whose {@link #DOCUMENTED_SENTENCES sentences} the
     * log projection keeps, each as the service documents or sends it:
     * <ul>
     *   <li>{@code Address Not Found.}: the Web Tools address-level error with
     *       {@code Number} {@code -2147219401} and {@code Source} {@code clsAMS}, the triple
     *       {@link StubAddressValidationClient} reproduces;</li>
     *   <li>{@code Invalid Address.}, {@code Invalid City.}, {@code Invalid State Code.} and
     *       {@code Invalid Zip Code.}: the address-level errors of the USPS Web Tools
     *       Address Information API user guide, section 6.0 Error Response;</li>
     *   <li>{@code Authorization failure.  Perhaps username and/or password is incorrect.},
     *       the Web Tools root {@code Error} 80040B1A, and
     *       {@code XML Syntax Error: Please check the XML request to see if it can be parsed.},
     *       the root {@code Error} 80040B19, both as secure.shippingapis.com sent them on
     *       2026-10-08.</li>
     * </ul>
     */
    static final List<String> DOCUMENTED_DESCRIPTIONS = List.of(
            "Address Not Found.",
            "Invalid Address.",
            "Invalid City.",
            "Invalid State Code.",
            "Invalid Zip Code.",
            "Authorization failure.  Perhaps username and/or password is incorrect.",
            "XML Syntax Error: Please check the XML request to see if it can be parsed.");

    /**
     * The sentences of {@link #DOCUMENTED_DESCRIPTIONS}, each description split after every
     * {@code .} that whitespace follows, in order and without duplicates: the only free
     * text the log projection keeps.
     */
    static final List<String> DOCUMENTED_SENTENCES = sentences(DOCUMENTED_DESCRIPTIONS);

    /**
     * The markers the log projection always keeps, as an alternation of literals: those of
     * {@link UspsTextRedactor}, the credential mask and the projection's own.
     */
    private static final String MARKERS = Stream.of(
                    UspsTextRedactor.REQUEST_DOCUMENT_MARKER, UspsTextRedactor.REQUEST_QUERY_MARKER,
                    UspsTextRedactor.REQUEST_URL_MARKER, URL_MARKER, ADDRESS_MARKER,
                    AddressValidationProperties.MASK)
            .map(Pattern::quote)
            .collect(Collectors.joining("|"));

    /**
     * A {@link #DOCUMENTED_SENTENCES documented sentence}, as an alternation of the
     * sentences longest first, each sentence's whitespace-separated pieces quoted and
     * joined by {@code \s+}, matched case-sensitively and only where no letter or digit
     * precedes it. It holds no capturing group.
     */
    private static final String SENTENCES = "(?<![\\p{L}\\p{N}])(?:" + DOCUMENTED_SENTENCES.stream()
            .sorted(Comparator.comparingInt(String::length).reversed())
            .map(sentence -> Stream.of(sentence.split("\\s+"))
                    .map(Pattern::quote)
                    .collect(Collectors.joining("\\s+")))
            .collect(Collectors.joining("|")) + ")";

    /**
     * A URL that is not the configured base URL, to the next whitespace and in any case: a
     * scheme, which is a letter followed by at most 63 letters, digits, {@code +}, {@code .}
     * or {@code -}, then {@code ://}; a {@code mailto:} or {@code tel:} URI; or a word
     * starting with {@code www.}. Each alternative tests a bounded prefix at each position,
     * which keeps the scan linear on a {@value #MAX_BODY_BYTES}-byte body; a longer scheme
     * run is left to the token classification.
     */
    private static final Pattern URL = Pattern.compile(
            "(?i)\\b[a-z][a-z0-9+.-]{0,63}://\\S*|\\b(?:mailto|tel):\\S+|\\bwww\\.\\S*");

    /**
     * An error code: the integer lexical {@link UspsXmlCodec} accepts, or the eight
     * hexadecimal digits Web Tools writes for a root error such as {@code 80040B1A}.
     */
    private static final Pattern ERROR_CODE = Pattern.compile("[+-]?[0-9]+|[0-9A-F]{8}");

    /**
     * A kept marker, captured as group 1, a {@link #SENTENCES documented sentence}, captured
     * as group 2, or else a word: a run of letters, marks and digits.
     */
    private static final Pattern TOKEN = Pattern.compile(
            "(" + MARKERS + ")|(" + SENTENCES + ")|[\\p{L}\\p{M}\\p{N}]+");

    private final String baseUrl;
    private final String userId;
    private final String password;

    /** Host of the base URL, the only part of the endpoint that is ever logged. */
    private final String host;

    /** Redacts request echoes and credentials from every logged text and thrown reason. */
    private final UspsTextRedactor redactor;

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
        this.redactor = new UspsTextRedactor(usps);
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
     * <p><b>Requests sent.</b> One Verify request per call, except that the JDK
     * {@link HttpClient} re-sends a {@code GET} once, on another connection, when the
     * connection is reset or closed before any byte of the response arrives. The service
     * can then receive the request, credentials included, twice and never more, while the
     * call still ends in one result or one {@link AddressServiceUnavailableException} with
     * one log line. No JDK client setting turns the re-send off: its
     * {@code jdk.httpclient.disableRetryConnect} system property covers refused connects
     * only, for the whole JVM. Any response, whatever its status, a response cut short
     * after its first bytes and a read timeout each send the request once; a refused or
     * timed-out connect sends none.
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
            logCodecFault(status, e.getMessage(), raw.body(), request);
            throw e;
        }

        if (result.standardized()) {
            log.debug("USPS address standardized: host={} status={}", host, status);
        } else if (log.isInfoEnabled()) {
            // The Number is projected from the text the service sent, not from the parsed
            // int, so a credential echoed with a sign or leading zeros is still masked whole.
            // The body parse accepted holds exactly one Error, which serviceError reads.
            Optional<UspsXmlCodec.ServiceError> serviceError = codec.serviceError(raw.body());
            String number = serviceError.isPresent()
                    ? serviceError.get().number()
                    : Integer.toString(result.errorNumber());
            log.info("USPS address not standardized: host={} status={} errorNumber={} errorDescription={}",
                    host, status, logElement(number, request, true),
                    logElement(result.errorDescription(), request, false));
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
            throw new AddressServiceUnavailableException(redactor.mask(reason));
        }
    }

    /** Reads at most one byte beyond the limit; the caller rejects anything longer. */
    private static byte[] readBounded(InputStream body) throws IOException {
        return body == null ? NO_BODY : body.readNBytes(MAX_BODY_BYTES + 1);
    }

    /** Logs a fault seen after a status was received and returns the exception to throw. */
    private AddressServiceUnavailableException fault(int status, String reason) {
        logFault(status, reason);
        return new AddressServiceUnavailableException(redactor.mask(reason));
    }

    /** Logs a fault at WARN with the host, the status and the reason, masked and escaped. */
    private void logFault(int status, String reason) {
        log.warn("USPS address validation failed: host={} status={} reason={}", host, status, logText(reason));
    }

    /**
     * Logs a fault {@link UspsXmlCodec#parse(byte[])} reported. When the body holds a USPS
     * {@code Error} element, a Web Tools root {@code <Error>} or the {@code Error} of the
     * one {@code Address}, the WARN line also carries its {@code Number} and
     * {@code Description} as their {@link #diagnostic diagnostic} projection, with
     * {@value #ABSENT} for an element the {@code Error} lacks. Otherwise the line is the
     * one {@link #logFault logFault} writes.
     *
     * @param status  the HTTP status, a 2xx
     * @param reason  the codec's fault message, which quotes no response content
     * @param body    the response body the codec rejected, at most {@value #MAX_BODY_BYTES} bytes
     * @param request the request of this call, whose values the projection redacts
     */
    private void logCodecFault(int status, String reason, byte[] body, AddressValidationRequest request) {
        Optional<UspsXmlCodec.ServiceError> serviceError = codec.serviceError(body);
        if (serviceError.isEmpty()) {
            logFault(status, reason);
            return;
        }
        UspsXmlCodec.ServiceError error = serviceError.get();
        log.warn("USPS address validation failed: host={} status={} reason={} errorNumber={} errorDescription={}",
                host, status, logText(reason), logElement(error.number(), request, true),
                logElement(error.description(), request, false));
    }

    /**
     * Prepares the text of a USPS {@code Error} child for the log: {@value #ABSENT} when
     * the element is absent, otherwise its {@link #diagnostic diagnostic} projection made
     * {@link #logSafe log-safe}, so an empty element logs as empty.
     *
     * @param text    the element's text, or {@code null} when the element is absent
     * @param request the request of this call
     * @param number  {@code true} for a {@code Number}, {@code false} for a {@code Description}
     * @return the text to log
     */
    private String logElement(String text, AddressValidationRequest request, boolean number) {
        return text == null ? ABSENT : logSafe(diagnostic(text, request, number));
    }

    /**
     * Prepares a fault reason for a log argument: {@link UspsTextRedactor#redact redacted},
     * so that a request document or query is replaced whole, credentials and customer
     * address included, and a credential outside them is masked before any of its
     * characters is escaped, then made {@link #logSafe log-safe}.
     *
     * @param text text about to be logged; {@code null} reads as {@code ""}
     * @return the redacted, masked, escaped text
     */
    private String logText(String text) {
        return logSafe(redactor.redact(text));
    }

    /**
     * The log projection of a USPS {@code Number} or {@code Description}: what the service
     * sent, with everything that could carry a secret, a customer's data or other
     * undocumented content replaced by a marker. The steps run in this order:
     * <ol>
     *   <li>every echo of the request {@link UspsTextRedactor#redactRequest recognized} as
     *       such becomes {@code [request document]}, {@code [request query]} or
     *       {@code [request URL]};</li>
     *   <li>every configured credential is {@link UspsTextRedactor#mask masked} as
     *       {@value AddressValidationProperties#MASK};</li>
     *   <li>every {@link #addressForms form} of a value this call submitted becomes
     *       {@value #ADDRESS_MARKER} where no letter or digit adjoins it, case-sensitively,
     *       in one pass that leaves the markers above intact;</li>
     *   <li>every other {@link #URL URL}, a {@code mailto:} or {@code tel:} URI included,
     *       becomes {@value #URL_MARKER};</li>
     *   <li>a {@code Number} that is now, stripped, an {@link #ERROR_CODE error code} is
     *       logged as it stands;</li>
     *   <li>otherwise each {@link #TOKEN token} is classified: a marker, or a
     *       {@link #DOCUMENTED_SENTENCES documented sentence} found whole, in its documented
     *       case and where no letter or digit precedes it, is kept exactly as it appears.
     *       Every other word, one that also occurs inside a documented sentence included,
     *       joins a stretch: each maximal stretch of such words, from the first to the last
     *       and with the characters between them, becomes {@code [unlisted, N characters]}
     *       ({@code [unlisted, 1 character]} for one), N its length in code points.
     *       Characters outside those stretches, such as blanks, punctuation and controls,
     *       are kept.</li>
     * </ol>
     * A documented description therefore logs exactly as sent, sentence by sentence, and an
     * error code as sent; nothing else with a letter or digit in a {@code Number} or
     * {@code Description} reaches the log except as a marker.
     * The redacted values are computed from {@code request} on each call and never stored.
     * Applied to log lines only; {@link #logSafe logSafe} escapes the result.
     *
     * @param text    the element's text as the codec read it; never {@code null}
     * @param request the request of this call
     * @param number  {@code true} for a {@code Number}, which may log as an error code
     * @return the projection
     */
    private String diagnostic(String text, AddressValidationRequest request, boolean number) {
        String projected = redactSubmitted(redactor.redact(text), request);
        projected = URL.matcher(projected).replaceAll(Matcher.quoteReplacement(URL_MARKER));
        if (number && ERROR_CODE.matcher(projected.strip()).matches()) {
            return projected;
        }
        return keepDocumentedSentences(projected);
    }

    /**
     * Replaces each {@link #addressForms form} of a value {@code request} submitted with
     * {@value #ADDRESS_MARKER}, where neither the character before nor the one after it is
     * a letter or a digit. One pattern, built for this call, matches a marker first and
     * keeps it, so no value is found inside a marker or a replacement; at each position the
     * longest form that fits is taken.
     */
    private static String redactSubmitted(String text, AddressValidationRequest request) {
        List<String> forms = addressForms(request);
        if (forms.isEmpty()) {
            return text;
        }
        Pattern submitted = Pattern.compile("(" + MARKERS + ")|(?<![\\p{L}\\p{N}])(?:"
                + forms.stream().map(Pattern::quote).collect(Collectors.joining("|"))
                + ")(?![\\p{L}\\p{N}])");
        return submitted.matcher(text).replaceAll(match -> Matcher.quoteReplacement(
                match.group(1) != null ? match.group() : ADDRESS_MARKER));
    }

    /**
     * The forms in which a value the call submitted can come back: for each non-blank
     * {@code address1}, {@code address2}, {@code city}, {@code state}, {@code zip5} and
     * {@code zip4}, stripped as {@link UspsXmlCodec} writes it, the raw value, its element
     * text as the request writer escapes it ({@code &}, {@code <} and {@code >}), its
     * {@link UspsTextRedactor#xmlEscape full-entity} form, and the UTF-8 URL encoding of
     * each, with a blank written as {@code +} and as {@code %20}. Duplicates are dropped,
     * and the forms are sorted longest first.
     */
    private static List<String> addressForms(AddressValidationRequest request) {
        Set<String> forms = new LinkedHashSet<>();
        List<String> components = List.of(request.address1(), request.address2(), request.city(),
                request.state(), request.zip5(), request.zip4());
        for (String component : components) {
            String value = component.strip();
            if (value.isEmpty()) {
                continue;
            }
            List<String> written = List.of(value, elementText(value), UspsTextRedactor.xmlEscape(value));
            for (String form : written) {
                String encoded = URLEncoder.encode(form, StandardCharsets.UTF_8);
                forms.add(form);
                forms.add(encoded);
                forms.add(encoded.replace("+", "%20"));
            }
        }
        List<String> sorted = new ArrayList<>(forms);
        sorted.sort(Comparator.comparingInt(String::length).reversed());
        return sorted;
    }

    /** Escapes {@code &}, {@code <} and {@code >} as the request writer escapes element text. */
    private static String elementText(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /**
     * Keeps every marker and {@link #DOCUMENTED_SENTENCES documented sentence} of
     * {@code text} as it appears and replaces each maximal stretch of other words with
     * {@code [unlisted, N characters]}, as {@link #diagnostic diagnostic} states. Linear in
     * the text length.
     */
    private static String keepDocumentedSentences(String text) {
        Matcher token = TOKEN.matcher(text);
        StringBuilder kept = new StringBuilder(text.length());
        int copied = 0;
        int stretchStart = -1;
        int stretchEnd = -1;
        while (token.find()) {
            if (token.group(1) != null || token.group(2) != null) {
                if (stretchStart >= 0) {
                    copied = appendUnlisted(kept, text, copied, stretchStart, stretchEnd);
                    stretchStart = -1;
                }
            } else {
                if (stretchStart < 0) {
                    stretchStart = token.start();
                }
                stretchEnd = token.end();
            }
        }
        if (stretchStart >= 0) {
            copied = appendUnlisted(kept, text, copied, stretchStart, stretchEnd);
        }
        return kept.append(text, copied, text.length()).toString();
    }

    /**
     * Appends the kept text from {@code copied} to {@code start}, then the marker for the
     * stretch from {@code start} to {@code end}, and returns {@code end}.
     */
    private static int appendUnlisted(StringBuilder kept, String text, int copied, int start, int end) {
        int length = text.codePointCount(start, end);
        kept.append(text, copied, start)
                .append("[unlisted, ")
                .append(length)
                .append(length == 1 ? " character]" : " characters]");
        return end;
    }

    /**
     * The sentences of {@code descriptions}: each split after every {@code .} that
     * whitespace follows, the whitespace dropped, in order and without duplicates.
     */
    private static List<String> sentences(List<String> descriptions) {
        Pattern fullStop = Pattern.compile("(?<=\\.)\\s+");
        return descriptions.stream()
                .flatMap(fullStop::splitAsStream)
                .distinct()
                .toList();
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
     * Status and bounded body of one exchange, captured inside the exchange callback and
     * examined after the response is closed.
     *
     * @param status the HTTP status
     * @param body   the body bytes read, empty for a non-2xx status
     */
    private record Raw(HttpStatusCode status, byte[] body) {
    }
}
