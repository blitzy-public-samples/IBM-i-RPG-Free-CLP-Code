package com.democorp.customermaster.address;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/**
 * Specifies {@link UspsWebToolsAddressValidationClient}, the Java replacement for the
 * {@code QSYS2.HTTP_GET} call and the XMLTABLE reads inside {@code USAdrVal}
 * [USPS_Address/USADRVAL.SQLRPGLE:74-137].
 *
 * <p><b>Source behaviour.</b> After the HTTP call and after each XMLTABLE the source tests
 * {@code SQLSTATE <> '00000'} and calls {@code SQLProblem} [USADRVAL.SQLRPGLE:94-96,114-116,
 * 134-136], which dumps the program and ends it with a CPF9898 escape message
 * [Service_Pgms/SRV_SQL.SQLRPGLE:20-57]. The target turns every such fault into an
 * {@link AddressServiceUnavailableException}, which customer-api maps to 502 APP0502. A
 * fault is always thrown and never returned as a result, an address-level error result
 * included.
 *
 * <p><b>What is covered.</b>
 * <ul>
 *   <li>The request: a {@code GET} of {@code <base-url>?API=Verify&XML=<encoded document>}
 *       carrying the request values and both credentials, and the parsed success
 *       result.</li>
 *   <li>Transport faults: a read timeout, HTTP 500, a 302 redirect (never followed), a body
 *       cut short, a refused connection, and a body one byte over
 *       {@value UspsWebToolsAddressValidationClient#MAX_BODY_BYTES} bytes, with and without
 *       {@code Content-Length}. A body of exactly that size is accepted.</li>
 *   <li>Codec faults on a 200 response: a Web Tools root {@code <Error>} and a malformed
 *       document.</li>
 *   <li>An address-level error ({@code Address Not Found.}), which is a normal result and
 *       is logged with its error number and documented description.</li>
 *   <li>Log-line injection: a {@code Description} holding LF, CR LF, NEL, LINE SEPARATOR
 *       and PARAGRAPH SEPARATOR as character references is returned exactly as decoded,
 *       while the log shows one INFO line with the unlisted-description marker and its
 *       length, no forged line and none of those characters. {@code logSafe} escapes CR,
 *       LF, TAB, ESC, DEL, NEL, U+2028, U+2029, U+202E and a supplementary format
 *       character, and keeps ASCII and accented letters.</li>
 *   <li>Sensitive response text: a {@code Description} echoing a street, ZIP code, name and
 *       URL is returned unchanged and logged only as the marker with its length.</li>
 *   <li>Credential forms: with the password {@code p'&q}, the request carries the
 *       {@code PASSWORD} attribute exactly as {@link UspsXmlCodec#attributeValue} writes
 *       it, {@code p'&amp;q}; the mask removes that wire form, the raw form, the
 *       {@code &apos;} entity form and the URL encoding of each from the decoded document,
 *       the raw query and other text; and a {@code Description} echoing the wire form and
 *       its URL encoding leaves no form in the captured output.</li>
 *   <li>Secret hygiene: in every case, success included, neither the captured output nor
 *       any exception message in the cause chain carries the {@code USERID} attribute name
 *       or a credential in its raw, XML-escaped or URL-encoded form. The captured output
 *       holds every line Logback writes to the console in its default setup, which Spring
 *       Boot's {@code RootLogLevelConfigurator} puts at root level INFO; the test leaves
 *       that level as it is.</li>
 * </ul>
 *
 * <p><b>Fake server.</b> A JDK {@link HttpServer} on {@code 127.0.0.1} and an ephemeral
 * port, with a cached thread pool so a handler that waits on a latch blocks nothing else.
 * Each test installs its own handler for {@code /ShippingAPI.dll}; {@code /redirected}
 * counts the hits a followed redirect would make. The refused connection targets a
 * {@code 127.0.0.1} port held for the whole case by a bound socket that never listens.
 *
 * <p><b>Test values.</b> The credentials {@code TESTUSER123} and {@code pl&ce"holder} are
 * fictitious placeholders; the password carries {@code &} and {@code "} so that its
 * escaped and encoded forms differ from the raw one. The fictitious password
 * {@code p'&q} carries an apostrophe, which the request writer leaves literal while a
 * full-entity escaper writes {@code &apos;}. The address is the fictitious base
 * row of the {@code usps/*.xml} fixtures. The test never prints the captured query or the
 * decoded request document, and its failure messages name what is missing rather than
 * quoting either.
 *
 * <p>Plain JUnit 5 and AssertJ: no application context, no mocks, no network beyond the
 * loopback interface.
 */
@DisplayName("UspsWebToolsAddressValidationClient: HTTP transport, faults and secret hygiene")
@ExtendWith(OutputCaptureExtension.class)
class UspsWebToolsAddressValidationClientTest {

    /** Fictitious USPS Web Tools user id. */
    private static final String USER_ID = "TESTUSER123";

    /** Fictitious USPS Web Tools password; {@code &} and {@code "} need escaping. */
    private static final String PASSWORD = "pl&ce\"holder";

    /** {@link #PASSWORD} as the request document's attribute writer escapes it. */
    private static final String PASSWORD_XML_ESCAPED = "pl&amp;ce&quot;holder";

    /**
     * Fictitious password with an apostrophe, which the request document's attribute
     * writer leaves literal while a full-entity escaper writes {@code &apos;}.
     */
    private static final String APOSTROPHE_PASSWORD = "p'&q";

    /** {@link #APOSTROPHE_PASSWORD} as the request document's attribute writer escapes it. */
    private static final String APOSTROPHE_PASSWORD_WIRE = "p'&amp;q";

    /** {@link #APOSTROPHE_PASSWORD} with every predefined entity, the apostrophe included. */
    private static final String APOSTROPHE_PASSWORD_ENTITIES = "p&apos;&amp;q";

    /** Path of the fake Web Tools endpoint. */
    private static final String ENDPOINT_PATH = "/ShippingAPI.dll";

    /** Path a followed redirect would reach; it must never be hit. */
    private static final String REDIRECT_PATH = "/redirected";

    /** Fictitious base row, the one the {@code usps/*.xml} fixtures standardize. */
    private static final AddressValidationRequest REQUEST =
            new AddressValidationRequest("STE 2", "8 ELMWOOD DR", "OLD HAVEN", "CT", "06399", "");

    /** Result {@code success-zip4.xml} parses to. */
    private static final AddressValidationResult SUCCESS_ZIP4 =
            AddressValidationResult.success("STE 2", "8 ELMWOOD DR", "OLD HAVEN", "CT", "06399", "1234");

    /** Connect timeout of every case except the timeout case. */
    private static final Duration NORMAL_CONNECT_TIMEOUT = Duration.ofSeconds(5);

    /** Read timeout of every case except the timeout case. */
    private static final Duration NORMAL_READ_TIMEOUT = Duration.ofSeconds(5);

    /**
     * Read timeout of the timeout case, whose handler withholds the response until the test
     * has observed the fault.
     */
    private static final Duration SHORT_READ_TIMEOUT = Duration.ofMillis(300);

    /**
     * Connect timeout of the timeout case, longer than {@link #WATCHDOG}, so a fault raised
     * within the watchdog comes from the read timeout and never from the connect timeout.
     */
    private static final Duration TIMEOUT_CASE_CONNECT_TIMEOUT = Duration.ofMinutes(2);

    /**
     * Bound on every wait of the timeout case, so a broken client fails the test instead of
     * hanging it. It is a deadlock guard, not a performance limit.
     */
    private static final Duration WATCHDOG = Duration.ofSeconds(30);

    /** Body size limit of the client under test. */
    private static final int LIMIT = UspsWebToolsAddressValidationClient.MAX_BODY_BYTES;

    /**
     * Forms in which a credential could leak, keyed by a label that failure messages use
     * instead of the value.
     */
    private static final Map<String, String> FORBIDDEN_FORMS = forbiddenForms();

    private HttpServer server;
    private ExecutorService executor;
    private final AtomicReference<HttpHandler> handler = new AtomicReference<>();
    private final AtomicInteger endpointHits = new AtomicInteger();
    private final AtomicInteger redirectedHits = new AtomicInteger();
    private int port;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        executor = Executors.newCachedThreadPool();
        server.setExecutor(executor);
        server.createContext(ENDPOINT_PATH, exchange -> {
            endpointHits.incrementAndGet();
            HttpHandler current = handler.get();
            if (current == null) {
                // A test that installs no handler gets a fault status, never a success.
                drainAndRespond(exchange, 501, new byte[0]);
            } else {
                current.handle(exchange);
            }
        });
        server.createContext(REDIRECT_PATH, exchange -> {
            redirectedHits.incrementAndGet();
            drainAndRespond(exchange, 200, fixture("success-zip4.xml"));
        });
        server.start();
        port = server.getAddress().getPort();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
        executor.shutdownNow();
    }

    @Test
    @DisplayName("sends GET ?API=Verify&XML=<document> with the values and credentials, and returns the parsed success")
    void sendsVerifyRequestAndReturnsStandardizedAddress(CapturedOutput output) throws IOException {
        AtomicReference<String> method = new AtomicReference<>();
        AtomicReference<String> path = new AtomicReference<>();
        AtomicReference<String> rawQuery = new AtomicReference<>();
        byte[] body = fixture("success-zip4.xml");
        handler.set(exchange -> {
            method.set(exchange.getRequestMethod());
            path.set(exchange.getRequestURI().getRawPath());
            rawQuery.set(exchange.getRequestURI().getRawQuery());
            drainAndRespond(exchange, 200, body);
        });

        AddressValidationResult result;
        try (UspsWebToolsAddressValidationClient client = client(NORMAL_READ_TIMEOUT)) {
            result = client.validate(REQUEST);
        }

        assertThat(method.get()).isEqualTo("GET");
        assertThat(path.get()).isEqualTo(ENDPOINT_PATH);
        String query = rawQuery.get();
        assertThat(query).withFailMessage("request has no query string").isNotNull();
        assertThat(query.startsWith("API=Verify&XML="))
                .withFailMessage("query does not start with API=Verify&XML=")
                .isTrue();
        String document = URLDecoder.decode(query.substring(query.indexOf("XML=") + 4), StandardCharsets.UTF_8);
        assertDocumentContains(document, "<AddressValidateRequest ");
        assertDocumentContains(document, "USERID=\"" + USER_ID + "\"");
        assertDocumentContains(document, "PASSWORD=\"" + PASSWORD_XML_ESCAPED + "\"");
        assertDocumentContains(document, "<Revision>1</Revision>");
        assertDocumentContains(document, "<Address ID=\"0\">");
        assertDocumentContains(document, "<Address1>STE 2</Address1>");
        assertDocumentContains(document, "<Address2>8 ELMWOOD DR</Address2>");
        assertDocumentContains(document, "<City>OLD HAVEN</City>");
        assertDocumentContains(document, "<State>CT</State>");
        assertDocumentContains(document, "<Zip5>06399</Zip5>");
        assertDocumentContains(document, "<Zip4></Zip4>");

        assertThat(result.standardized()).isTrue();
        assertThat(result.address1()).isEqualTo("STE 2");
        assertThat(result.address2()).isEqualTo("8 ELMWOOD DR");
        assertThat(result.city()).isEqualTo("OLD HAVEN");
        assertThat(result.state()).isEqualTo("CT");
        assertThat(result.zip5()).isEqualTo("06399");
        assertThat(result.zip4()).isEqualTo("1234");
        assertThat(result.errorNumber()).isZero();
        assertThat(result).isEqualTo(SUCCESS_ZIP4);
        assertThat(endpointHits.get()).isEqualTo(1);

        assertNoCredentials(output, null);
    }

    @Test
    @DisplayName("an address-level error on a 200 is returned as a result, not thrown")
    void returnsAddressLevelErrorAsResult(CapturedOutput output) throws IOException {
        byte[] body = ("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<AddressValidateResponse><Address ID=\"0\">"
                + "<Address2>8 ELMWOOD DR</Address2><City></City><State>CT</State><Zip5>06399</Zip5>"
                + "<Error><Number>-2147219401</Number><Source>clsAMS</Source>"
                + "<Description>Address Not Found.</Description></Error>"
                + "</Address></AddressValidateResponse>").getBytes(StandardCharsets.UTF_8);
        handler.set(exchange -> drainAndRespond(exchange, 200, body));

        AddressValidationResult result;
        try (UspsWebToolsAddressValidationClient client = client(NORMAL_READ_TIMEOUT)) {
            result = client.validate(REQUEST);
        }

        assertThat(result.standardized()).isFalse();
        assertThat(result.errorNumber()).isEqualTo(-2147219401);
        assertThat(result.errorSource()).isEqualTo("clsAMS");
        assertThat(result.errorDescription()).isEqualTo("Address Not Found.");
        assertThat(output.getAll().lines()
                        .anyMatch(line -> line.contains("errorNumber=-2147219401 errorDescription=Address Not Found.")))
                .withFailMessage("captured output lacks the documented error number and description")
                .isTrue();
        assertNoCredentials(output, null);
    }

    @Test
    @DisplayName("line breaks and separators in an address-level Description cannot forge or split log lines")
    void addressErrorDescriptionCannotForgeLogLines(CapturedOutput output) {
        byte[] body = addressErrorBody("Address Not Found.&#10;ERROR forged entry one&#13;&#10;"
                + "WARN forged entry two&#x85;INFO three&#x2028;INFO four&#x2029;end");
        handler.set(exchange -> drainAndRespond(exchange, 200, body));
        String decoded = "Address Not Found.\nERROR forged entry one\r\nWARN forged entry two\u0085"
                + "INFO three\u2028INFO four\u2029end";

        AddressValidationResult result;
        try (UspsWebToolsAddressValidationClient client = client(NORMAL_READ_TIMEOUT)) {
            result = client.validate(REQUEST);
        }

        assertThat(result.standardized()).isFalse();
        assertThat(result.errorDescription()).isEqualTo(decoded);
        // The console ends each line with the platform separator; nothing else may break one.
        String captured = output.getAll().replace(System.lineSeparator(), "\n");
        List<String> lines = captured.lines().toList();
        assertThat(lines.stream().filter(line -> line.contains("USPS address not standardized")))
                .singleElement(InstanceOfAssertFactories.STRING)
                .contains("errorNumber=-2147219401 errorDescription=[unlisted, 89 characters]");
        assertThat(lines).noneMatch(line -> line.contains("forged"));
        assertThat(captured).doesNotContain("\r", "\u0085", "\u2028", "\u2029");
        assertNoCredentials(output, null);
    }

    @Test
    @DisplayName("an unlisted Description is logged as its length only, never as the text it echoes")
    void unlistedDescriptionIsLoggedAsLengthOnly(CapturedOutput output) {
        String description = "Address 123 MAIN ST, ANYTOWN CA 90210 for JOHN DOE see https://example.invalid/x";
        byte[] body = addressErrorBody(description);
        handler.set(exchange -> drainAndRespond(exchange, 200, body));

        AddressValidationResult result;
        try (UspsWebToolsAddressValidationClient client = client(NORMAL_READ_TIMEOUT)) {
            result = client.validate(REQUEST);
        }

        assertThat(result.standardized()).isFalse();
        assertThat(result.errorDescription()).isEqualTo(description);
        String captured = output.getAll();
        assertThat(captured).doesNotContain("MAIN ST", "90210", "JOHN DOE", "https://", "example.invalid");
        assertThat(captured).contains("errorNumber=-2147219401 errorDescription=[unlisted, 80 characters]");
        assertNoCredentials(output, null);
    }

    @Test
    @DisplayName("a response withheld past the read timeout is a fault, raised while the response is still withheld")
    void readTimeoutIsFault(CapturedOutput output)
            throws IOException, InterruptedException, ExecutionException, TimeoutException {
        byte[] body = fixture("success-zip4.xml");
        CountDownLatch handlerEntered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch handlerDone = new CountDownLatch(1);
        handler.set(exchange -> {
            try {
                exchange.getRequestBody().readAllBytes();
                handlerEntered.countDown();
                // The response is withheld until the test has observed the fault; the
                // watchdog only keeps a broken run from holding this thread forever.
                release.await(WATCHDOG.toMillis(), TimeUnit.MILLISECONDS);
                respond(exchange, 200, body);
            } catch (InterruptedException stopped) {
                // AfterEach shuts the executor down while the handler still waits.
                Thread.currentThread().interrupt();
            } catch (IOException clientGone) {
                // The client timed out and closed the connection: nothing is left to answer,
                // and the test asserts on the client side only.
            } finally {
                exchange.close();
                handlerDone.countDown();
            }
        });

        Throwable thrown;
        ExecutorService validation = Executors.newSingleThreadExecutor();
        try (UspsWebToolsAddressValidationClient client = client(TIMEOUT_CASE_CONNECT_TIMEOUT, SHORT_READ_TIMEOUT)) {
            try {
                Future<Throwable> call = validation.submit(() -> catchThrowable(() -> client.validate(REQUEST)));
                // The handler has read the request, so the connection is established and the
                // request sent: only the read timeout can end the call now.
                assertThat(handlerEntered.await(WATCHDOG.toMillis(), TimeUnit.MILLISECONDS))
                        .withFailMessage("the fake endpoint never received the request")
                        .isTrue();
                thrown = call.get(WATCHDOG.toMillis(), TimeUnit.MILLISECONDS);
                assertThat(handlerDone.getCount())
                        .withFailMessage("the handler answered before the client raised the fault")
                        .isEqualTo(1);
            } finally {
                // Released before the client closes, because close() waits for any exchange
                // still in flight.
                release.countDown();
                handlerDone.await(WATCHDOG.toMillis(), TimeUnit.MILLISECONDS);
                validation.shutdownNow();
            }
        }

        assertTransportFault(thrown);
        assertThat(thrown).hasMessage("USPS address service I/O failure: HttpTimeoutException");
        assertNoCredentials(output, thrown);
    }

    @Test
    @DisplayName("HTTP 500 is a fault")
    void serverErrorIsFault(CapturedOutput output) {
        byte[] body = "Internal Server Error".getBytes(StandardCharsets.US_ASCII);
        handler.set(exchange -> drainAndRespond(exchange, 500, body));

        Throwable thrown = validateExpectingFault(NORMAL_READ_TIMEOUT);

        assertTransportFault(thrown);
        assertThat(thrown).hasMessageContaining("500");
        assertThat(endpointHits.get()).isEqualTo(1);
        assertNoCredentials(output, thrown);
    }

    @Test
    @DisplayName("a 302 redirect is a fault and is never followed")
    void redirectIsFaultAndNotFollowed(CapturedOutput output) {
        handler.set(exchange -> {
            exchange.getResponseHeaders().set("Location", "http://127.0.0.1:" + port + REDIRECT_PATH);
            drainAndRespond(exchange, 302, new byte[0]);
        });

        Throwable thrown = validateExpectingFault(NORMAL_READ_TIMEOUT);

        assertTransportFault(thrown);
        assertThat(thrown).hasMessageContaining("302");
        assertThat(redirectedHits.get()).isZero();
        assertThat(endpointHits.get()).isEqualTo(1);
        assertNoCredentials(output, thrown);
    }

    @Test
    @DisplayName("a Web Tools root <Error> on a 200 is a fault, not an address-error result")
    void rootErrorDocumentIsFault(CapturedOutput output) throws IOException {
        byte[] body = fixture("root-error.xml");
        handler.set(exchange -> drainAndRespond(exchange, 200, body));

        Throwable thrown = validateExpectingFault(NORMAL_READ_TIMEOUT);

        assertNoCredentials(output, thrown);
    }

    @Test
    @DisplayName("a malformed 200 body is a fault")
    void malformedBodyIsFault(CapturedOutput output) throws IOException {
        byte[] body = fixture("malformed.xml");
        handler.set(exchange -> drainAndRespond(exchange, 200, body));

        Throwable thrown = validateExpectingFault(NORMAL_READ_TIMEOUT);

        assertNoCredentials(output, thrown);
    }

    @Test
    @DisplayName("a 200 body one byte over the limit is a fault")
    void bodyOverLimitIsFault(CapturedOutput output) throws IOException {
        // Trailing blanks after the root element keep the document well-formed, so only the
        // size limit can reject it.
        byte[] document = fixture("success-zip4.xml");
        assertThat(document).hasSizeLessThan(LIMIT);
        byte[] body = padded(document, LIMIT + 1);
        assertThat(body).hasSize(65_537);
        handler.set(exchange -> drainAndRespond(exchange, 200, body));

        Throwable thrown = validateExpectingFault(NORMAL_READ_TIMEOUT);

        assertTransportFault(thrown);
        assertThat(thrown).hasMessageContaining(String.valueOf(LIMIT));
        assertNoCredentials(output, thrown);
    }

    @Test
    @DisplayName("a 200 body of exactly the limit is accepted")
    void bodyAtLimitIsAccepted(CapturedOutput output) throws IOException {
        byte[] document = fixture("success-zip4.xml");
        assertThat(document).hasSizeLessThan(LIMIT);
        byte[] body = padded(document, LIMIT);
        assertThat(body).hasSize(65_536);
        handler.set(exchange -> drainAndRespond(exchange, 200, body));

        AddressValidationResult result;
        try (UspsWebToolsAddressValidationClient client = client(NORMAL_READ_TIMEOUT)) {
            result = client.validate(REQUEST);
        }

        assertThat(result).isEqualTo(SUCCESS_ZIP4);
        assertThat(result.standardized()).isTrue();
        assertNoCredentials(output, null);
    }

    @Test
    @DisplayName("a chunked 200 body one byte over the limit is a fault without Content-Length")
    void chunkedBodyOverLimitIsFault(CapturedOutput output) throws IOException {
        byte[] document = fixture("success-zip4.xml");
        assertThat(document).hasSizeLessThan(LIMIT);
        byte[] body = padded(document, LIMIT + 1);
        assertThat(body).hasSize(65_537);
        handler.set(exchange -> {
            try {
                exchange.getRequestBody().readAllBytes();
                exchange.getResponseHeaders().set("Content-Type", "text/xml");
                // Length 0 selects chunked transfer coding: no Content-Length is sent.
                exchange.sendResponseHeaders(200, 0);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(body);
                }
            } finally {
                exchange.close();
            }
        });

        Throwable thrown = validateExpectingFault(NORMAL_READ_TIMEOUT);

        assertTransportFault(thrown);
        assertThat(thrown).hasMessageContaining(String.valueOf(LIMIT));
        assertNoCredentials(output, thrown);
    }

    @Test
    @DisplayName("a 200 body cut short by the server is a fault")
    void truncatedBodyIsFault(CapturedOutput output) throws IOException {
        byte[] body = fixture("success-zip4.xml");
        handler.set(exchange -> {
            try {
                exchange.getRequestBody().readAllBytes();
                exchange.getResponseHeaders().set("Content-Type", "text/xml");
                exchange.sendResponseHeaders(200, body.length);
                OutputStream out = exchange.getResponseBody();
                out.write(body, 0, body.length / 2);
                out.flush();
            } finally {
                // Closing with bytes still owed makes the server drop the connection.
                exchange.close();
            }
        });

        Throwable thrown = validateExpectingFault(NORMAL_READ_TIMEOUT);

        assertTransportFault(thrown);
        assertNoCredentials(output, thrown);
    }

    @Test
    @DisplayName("a refused connection is a fault")
    void refusedConnectionIsFault(CapturedOutput output) throws IOException {
        Throwable thrown;
        Throwable direct;
        // A bound socket that never listens or connects holds 127.0.0.1:<port> for the whole
        // case: no other socket can bind it, and every connect to it is refused.
        try (Socket reservation = new Socket()) {
            reservation.setReuseAddress(false);
            reservation.bind(new InetSocketAddress("127.0.0.1", 0));
            InetSocketAddress reserved = new InetSocketAddress("127.0.0.1", reservation.getLocalPort());

            try (UspsWebToolsAddressValidationClient client =
                    client("http://127.0.0.1:" + reserved.getPort() + ENDPOINT_PATH, NORMAL_READ_TIMEOUT)) {
                thrown = catchThrowable(() -> client.validate(REQUEST));
            }

            // Control: the reserved endpoint itself refuses a plain connect.
            direct = catchThrowable(() -> {
                try (Socket probe = new Socket()) {
                    probe.connect(reserved, (int) NORMAL_CONNECT_TIMEOUT.toMillis());
                }
            });
        }

        assertThat(direct).isInstanceOf(ConnectException.class);
        assertTransportFault(thrown);
        // The JDK HttpClient reports a refused connect as a ConnectException caused by a
        // ClosedChannelException, and the client names the root cause's class. That reason
        // is the refused-connect signature, distinct from a timeout (HttpTimeoutException), a
        // truncated body (EOFException) and an HTTP status.
        assertThat(thrown).hasMessage("USPS address service I/O failure: ClosedChannelException");
        assertNoCredentials(output, thrown);
    }

    @Test
    @DisplayName("logSafe escapes control, format and line-separator characters and keeps every other character")
    void logSafeEscapesLineBreakingCharacters() {
        Map<String, String> escapes = new LinkedHashMap<>();
        escapes.put("CR", "\r");
        escapes.put("LF", "\n");
        escapes.put("TAB", "\t");
        escapes.put("ESC", "\u001B");
        escapes.put("DEL", "\u007F");
        escapes.put("NEL", "\u0085");
        escapes.put("LINE SEPARATOR", "\u2028");
        escapes.put("PARAGRAPH SEPARATOR", "\u2029");
        escapes.put("RIGHT-TO-LEFT OVERRIDE", "\u202E");
        escapes.put("LANGUAGE TAG (supplementary)", new String(Character.toChars(0xE0001)));
        Map<String, String> expected = Map.of(
                "CR", "\\u000D",
                "LF", "\\u000A",
                "TAB", "\\u0009",
                "ESC", "\\u001B",
                "DEL", "\\u007F",
                "NEL", "\\u0085",
                "LINE SEPARATOR", "\\u2028",
                "PARAGRAPH SEPARATOR", "\\u2029",
                "RIGHT-TO-LEFT OVERRIDE", "\\u202E",
                "LANGUAGE TAG (supplementary)", "\\uE0001");
        escapes.forEach((name, character) ->
                assertThat(UspsWebToolsAddressValidationClient.logSafe("a" + character + "b"))
                        .as(name)
                        .isEqualTo("a" + expected.get(name) + "b"));

        assertThat(UspsWebToolsAddressValidationClient.logSafe("Address Not Found.\nERROR forged\r\n"))
                .isEqualTo("Address Not Found.\\u000AERROR forged\\u000D\\u000A");
        String ascii = "Address Not Found. 123 [x] {y} ~!@#$%^&*()_+-=|;:'\",.<>/?`";
        assertThat(UspsWebToolsAddressValidationClient.logSafe(ascii)).isEqualTo(ascii);
        String accented = "Caf\u00E9 S\u00E3o Paulo Z\u00FCrich \u00C5ngstr\u00F6m \u00D1and\u00FA";
        assertThat(UspsWebToolsAddressValidationClient.logSafe(accented)).isEqualTo(accented);
        assertThat(UspsWebToolsAddressValidationClient.logSafe("")).isEmpty();
        assertThat(UspsWebToolsAddressValidationClient.logSafe(null)).isEmpty();
    }

    @Test
    @DisplayName("masks a password with an apostrophe in its request-wire, full-entity and URL-encoded forms")
    void masksApostrophePasswordInEveryWrittenForm(CapturedOutput output) {
        String wireEncoded = URLEncoder.encode(APOSTROPHE_PASSWORD_WIRE, StandardCharsets.UTF_8);
        String rawEncoded = URLEncoder.encode(APOSTROPHE_PASSWORD, StandardCharsets.UTF_8);
        String entitiesEncoded = URLEncoder.encode(APOSTROPHE_PASSWORD_ENTITIES, StandardCharsets.UTF_8);
        assertThat(wireEncoded).isEqualTo("p%27%26amp%3Bq");
        assertThat(rawEncoded).isEqualTo("p%27%26q");
        // The parsed Description echoes the wire form and its URL encoding.
        String echoed = APOSTROPHE_PASSWORD_WIRE + " and " + wireEncoded;
        AtomicReference<String> rawQuery = new AtomicReference<>();
        byte[] body = addressErrorBody("p'&amp;amp;q and p%27%26amp%3Bq");
        handler.set(exchange -> {
            rawQuery.set(exchange.getRequestURI().getRawQuery());
            drainAndRespond(exchange, 200, body);
        });

        AddressValidationResult result;
        String query;
        String document;
        String maskedDocument;
        String maskedQuery;
        String maskedEntities;
        String maskedEntitiesEncoded;
        try (UspsWebToolsAddressValidationClient client = client(
                "http://127.0.0.1:" + port + ENDPOINT_PATH, APOSTROPHE_PASSWORD, NORMAL_READ_TIMEOUT)) {
            result = client.validate(REQUEST);
            query = rawQuery.get();
            assertThat(query).withFailMessage("request has no query string").isNotNull();
            document = URLDecoder.decode(query.substring(query.indexOf("XML=") + 4), StandardCharsets.UTF_8);
            maskedDocument = client.mask(document);
            maskedQuery = client.mask(query);
            maskedEntities = client.mask("x " + APOSTROPHE_PASSWORD_ENTITIES + " y");
            maskedEntitiesEncoded = client.mask("x " + entitiesEncoded + " y");
        }

        Optional<String> passwordAttributeText = attributeText(document, "PASSWORD");
        assertThat(passwordAttributeText)
                .withFailMessage("decoded request document lacks a closed PASSWORD attribute")
                .isPresent();
        String passwordAttribute = passwordAttributeText.orElseThrow();
        assertThat(passwordAttribute.equals(UspsXmlCodec.attributeValue(APOSTROPHE_PASSWORD)))
                .withFailMessage("PASSWORD attribute differs from UspsXmlCodec.attributeValue")
                .isTrue();
        assertThat(passwordAttribute.equals(APOSTROPHE_PASSWORD_WIRE))
                .withFailMessage("PASSWORD attribute is not the literal-apostrophe wire form")
                .isTrue();
        assertThat(query.contains(wireEncoded))
                .withFailMessage("raw query lacks the URL-encoded wire form")
                .isTrue();

        assertAbsent(maskedDocument, "masked request document", APOSTROPHE_PASSWORD_WIRE, APOSTROPHE_PASSWORD);
        assertAbsent(maskedQuery, "masked raw query", wireEncoded, rawEncoded);
        assertThat(maskedEntities).isEqualTo("x " + AddressValidationProperties.MASK + " y");
        assertThat(maskedEntitiesEncoded).isEqualTo("x " + AddressValidationProperties.MASK + " y");

        assertThat(result.standardized()).isFalse();
        assertThat(result.errorDescription().equals(echoed))
                .withFailMessage("returned description is not the parsed Description")
                .isTrue();
        assertAbsent(output.getAll(), "captured output", APOSTROPHE_PASSWORD, APOSTROPHE_PASSWORD_WIRE,
                APOSTROPHE_PASSWORD_ENTITIES, rawEncoded, wireEncoded, entitiesEncoded);
        assertNoCredentials(output, null);
    }

    // ---------------------------------------------------------------- helpers

    /** Builds a client for the fake endpoint with the given read timeout. */
    private UspsWebToolsAddressValidationClient client(Duration readTimeout) {
        return client(NORMAL_CONNECT_TIMEOUT, readTimeout);
    }

    /** Builds a client for the fake endpoint with the given connect and read timeouts. */
    private UspsWebToolsAddressValidationClient client(Duration connectTimeout, Duration readTimeout) {
        return client("http://127.0.0.1:" + port + ENDPOINT_PATH, PASSWORD, connectTimeout, readTimeout);
    }

    /** Builds a client for {@code baseUrl} with the placeholder credentials. */
    private static UspsWebToolsAddressValidationClient client(String baseUrl, Duration readTimeout) {
        return client(baseUrl, PASSWORD, readTimeout);
    }

    /** Builds a client for {@code baseUrl} with the placeholder user id and {@code password}. */
    private static UspsWebToolsAddressValidationClient client(
            String baseUrl, String password, Duration readTimeout) {
        return client(baseUrl, password, NORMAL_CONNECT_TIMEOUT, readTimeout);
    }

    /**
     * Builds a client for {@code baseUrl} with the placeholder user id, {@code password} and
     * the given connect and read timeouts.
     */
    private static UspsWebToolsAddressValidationClient client(
            String baseUrl, String password, Duration connectTimeout, Duration readTimeout) {
        return new UspsWebToolsAddressValidationClient(new AddressValidationProperties(
                true,
                AddressValidationProperties.Client.USPS,
                new AddressValidationProperties.Usps(
                        baseUrl, USER_ID, password, connectTimeout, readTimeout)));
    }

    /**
     * A 200 body holding an address-level error: blank {@code City}, {@code Number}
     * {@code -2147219401}, {@code Source} {@code clsAMS} and {@code descriptionXml} as the
     * {@code Description} content, inserted as XML markup so it can carry character
     * references.
     */
    private static byte[] addressErrorBody(String descriptionXml) {
        return ("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<AddressValidateResponse><Address ID=\"0\">"
                + "<Address2>8 ELMWOOD DR</Address2><City></City><State>CT</State><Zip5>06399</Zip5>"
                + "<Error><Number>-2147219401</Number><Source>clsAMS</Source>"
                + "<Description>" + descriptionXml + "</Description></Error>"
                + "</Address></AddressValidateResponse>").getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Returns the text between the quotes of the first attribute {@code name} in the decoded
     * request document, or an empty {@link Optional} when the document lacks a blank
     * followed by {@code name="}, or that attribute lacks its closing quote. It asserts
     * nothing; the caller asserts presence.
     */
    private static Optional<String> attributeText(String document, String name) {
        String start = " " + name + "=\"";
        int from = document.indexOf(start);
        if (from < 0) {
            return Optional.empty();
        }
        int valueStart = from + start.length();
        int valueEnd = document.indexOf('"', valueStart);
        if (valueEnd < 0) {
            return Optional.empty();
        }
        return Optional.of(document.substring(valueStart, valueEnd));
    }

    /**
     * Asserts that {@code text} contains none of {@code forms}. Failure messages name
     * {@code where} and the form found, never the text, which may be a request document or
     * query.
     */
    private static void assertAbsent(String text, String where, String... forms) {
        for (String form : forms) {
            assertThat(text.contains(form))
                    .withFailMessage("%s contains %s", where, form)
                    .isFalse();
        }
    }

    /**
     * Calls the fake endpoint once and returns what {@code validate} threw, after checking
     * that it is an {@link AddressServiceUnavailableException}: a fault is never a returned
     * result.
     */
    private Throwable validateExpectingFault(Duration readTimeout) {
        Throwable thrown;
        try (UspsWebToolsAddressValidationClient client = client(readTimeout)) {
            thrown = catchThrowable(() -> client.validate(REQUEST));
        }
        assertFault(thrown);
        return thrown;
    }

    /**
     * Asserts a service fault without quoting the throwable, whose message would hold the
     * request URI if a framework exception escaped unwrapped.
     */
    private static void assertFault(Throwable thrown) {
        assertThat(thrown)
                .withFailMessage("expected AddressServiceUnavailableException but got %s",
                        thrown == null ? "a returned result" : thrown.getClass().getName())
                .isInstanceOf(AddressServiceUnavailableException.class);
        assertThat(thrown.getMessage()).isNotBlank();
    }

    /** Asserts a transport fault, which names its kind and chains no cause. */
    private static void assertTransportFault(Throwable thrown) {
        assertFault(thrown);
        assertThat(thrown.getCause()).isNull();
    }

    /**
     * Asserts that neither the captured output nor the message of {@code thrown} or of any
     * throwable in its cause chain carries a forbidden credential form. Failure messages
     * name the form by label and never quote the output.
     *
     * @param output the captured standard output and error
     * @param thrown what the call threw, or {@code null} for a returned result
     */
    private static void assertNoCredentials(CapturedOutput output, Throwable thrown) {
        String captured = output.getAll();
        FORBIDDEN_FORMS.forEach((label, form) -> assertThat(captured.contains(form))
                .withFailMessage("captured output contains the %s", label)
                .isFalse());

        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable current = thrown; current != null && seen.add(current); current = current.getCause()) {
            String message = String.valueOf(current.getMessage());
            String type = current.getClass().getName();
            FORBIDDEN_FORMS.forEach((label, form) -> assertThat(message.contains(form))
                    .withFailMessage("message of %s contains the %s", type, label)
                    .isFalse());
        }
    }

    /** Credential forms that must never appear, by label. */
    private static Map<String, String> forbiddenForms() {
        Map<String, String> forms = new LinkedHashMap<>();
        forms.put("USERID attribute name", "USERID");
        forms.put("user id", USER_ID);
        forms.put("raw password", PASSWORD);
        forms.put("XML-escaped password", PASSWORD_XML_ESCAPED);
        forms.put("URL-encoded password", URLEncoder.encode(PASSWORD, StandardCharsets.UTF_8));
        forms.put("URL-encoded XML-escaped password", URLEncoder.encode(PASSWORD_XML_ESCAPED, StandardCharsets.UTF_8));
        return Collections.unmodifiableMap(forms);
    }

    /** Asserts that the decoded request document holds {@code fragment}, without quoting it. */
    private static void assertDocumentContains(String document, String fragment) {
        assertThat(document.contains(fragment))
                .withFailMessage("decoded request document lacks %s", fragment)
                .isTrue();
    }

    /** Drains the request body, answers with {@code status} and {@code body}, and closes. */
    private static void drainAndRespond(HttpExchange exchange, int status, byte[] body) throws IOException {
        try {
            exchange.getRequestBody().readAllBytes();
            respond(exchange, status, body);
        } finally {
            exchange.close();
        }
    }

    /** Sends {@code status} with a {@code text/xml} body; an empty body sends no content. */
    private static void respond(HttpExchange exchange, int status, byte[] body) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "text/xml");
        exchange.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
        if (body.length > 0) {
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        }
    }

    /**
     * Returns {@code document} followed by ASCII blanks up to exactly {@code size} bytes. It
     * asserts nothing: {@code document} must be shorter than {@code size}, which each caller
     * asserts before padding.
     */
    private static byte[] padded(byte[] document, int size) {
        byte[] body = Arrays.copyOf(document, size);
        Arrays.fill(body, document.length, size, (byte) ' ');
        return body;
    }

    /** Reads {@code /usps/<name>} from the test classpath. */
    private static byte[] fixture(String name) throws IOException {
        try (InputStream in = Objects.requireNonNull(
                UspsWebToolsAddressValidationClientTest.class.getResourceAsStream("/usps/" + name),
                "missing test fixture usps/" + name)) {
            return in.readAllBytes();
        }
    }
}
