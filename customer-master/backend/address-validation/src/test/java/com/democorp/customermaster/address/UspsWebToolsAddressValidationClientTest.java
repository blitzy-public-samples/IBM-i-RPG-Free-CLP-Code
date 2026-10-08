package com.democorp.customermaster.address;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
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
import java.util.stream.Stream;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
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
 *   <li>Request count: the timeout, HTTP 500, the redirect and the body cut short each
 *       reach the endpoint once. A connection closed or reset after the request head and
 *       before any response byte is one fault with one WARN line, while the JDK client
 *       re-sends the request at most once, so the server sees one or two Verify
 *       requests.</li>
 *   <li>Codec faults on a 200 response: a Web Tools root {@code <Error>}, whose WARN line
 *       carries its {@code Number} and {@code Description} while the exception message
 *       names the kind of fault only; an {@code Error} the response rules reject (a
 *       {@code Number} that is not an integer, a missing {@code Description}, logged as
 *       {@code -}, and a {@code Source} of 31 characters), whose WARN line carries its
 *       {@code Number} and {@code Description} too; and bodies without a readable
 *       {@code Error} element (malformed, DOCTYPE, two {@code Address} elements, a blank
 *       {@code City} without {@code Error}), whose WARN line carries the host, status and
 *       reason only.</li>
 *   <li>An address-level error, which is a normal result and is logged at INFO with its
 *       {@code Number} and its {@code Description} as the service sent it, whatever its
 *       text, an echo of the request aside (below); an empty {@code Description} logs as
 *       empty.</li>
 *   <li>Log-line injection: a {@code Description} holding LF, CR LF, NEL, LINE SEPARATOR
 *       and PARAGRAPH SEPARATOR as character references is returned exactly as decoded,
 *       while the log shows one INFO line carrying the description with those characters
 *       escaped, no line forged from its text and none of those characters raw.
 *       {@code logSafe} escapes CR, LF, TAB, ESC, DEL, NEL, U+2028, U+2029, U+202E and a
 *       supplementary format character, and keeps ASCII and accented letters.</li>
 *   <li>Credential echo: a {@code Description} that echoes the user id and password, in an
 *       address-level error or a root {@code <Error>}, is logged with {@code ****} in their
 *       place.</li>
 *   <li>Numeric credential echoed as the {@code Number}: an address-level error whose
 *       {@code Number} echoes a numeric user id (plain, with a leading zero or with a plus
 *       sign) or a numeric password returns the parsed number while its INFO line carries
 *       {@code errorNumber=****}, and the captured output holds neither the echoed text nor
 *       the parsed number's text.</li>
 *   <li>Credential forms: with the password {@code p'&q}, the request carries the
 *       {@code PASSWORD} attribute exactly as {@link UspsXmlCodec#attributeValue} writes
 *       it, {@code p'&amp;q}; the mask removes that wire form, the raw form, the
 *       {@code &apos;} entity form and the URL encoding of each from the decoded document,
 *       the raw query and other text; and a {@code Description} echoing the wire form and
 *       its URL encoding leaves no form in the captured output.</li>
 *   <li>Request echo: a {@code Description} echoing the request the fake received, as its
 *       URL with the query percent-encoded or decoded, or as its decoded document, in an
 *       address-level error or a root {@code <Error>}, is returned, or leaves the fault
 *       message, as before, while its one log line carries
 *       {@code [request URL]?[request query]} or {@code [request document]} in place of
 *       the echo and the rest of the text as sent; the captured output then holds no base
 *       URL, Verify query, request document or street. {@code redactRequest} replaces the
 *       unclosed, entity-escaped, URL-encoded and {@code &amp;} forms, keeps other text,
 *       and ends promptly on 64 KiB of adversarial input.</li>
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
 * {@code 127.0.0.1} port held for the whole case by a bound socket that never listens. The
 * dropped-connection cases use a raw {@link ServerSocket} on {@code 127.0.0.1}, since
 * {@link HttpServer} cannot drop a connection unanswered: it reads each request head,
 * counts it, then closes the socket or resets it with {@code SO_LINGER} 0. Every wait in
 * them is bounded by the watchdog, and none sleeps.
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
                .withFailMessage("captured output lacks the error number and description")
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
        String escaped = "Address Not Found.\\u000AERROR forged entry one\\u000D\\u000A"
                + "WARN forged entry two\\u0085INFO three\\u2028INFO four\\u2029end";
        assertThat(lines.stream().filter(line -> line.contains("USPS address not standardized")))
                .singleElement(InstanceOfAssertFactories.STRING)
                .endsWith("errorNumber=-2147219401 errorDescription=" + escaped);
        // The forged texts survive only inside that one line, never as entries of their own.
        assertThat(lines.stream().filter(line -> line.contains("forged")))
                .singleElement(InstanceOfAssertFactories.STRING)
                .contains("USPS address not standardized");
        assertThat(lines).noneMatch(line -> line.startsWith("ERROR forged") || line.startsWith("WARN forged")
                || line.startsWith("INFO three") || line.startsWith("INFO four") || line.startsWith("end"));
        assertThat(captured).doesNotContain("\r", "\u0085", "\u2028", "\u2029");
        assertNoCredentials(output, null);
    }

    @Test
    @DisplayName("an address-level Description is logged as the service sent it, whatever its text")
    void addressErrorDescriptionIsLoggedAsSent(CapturedOutput output) {
        String description = "Address 123 MAIN ST, ANYTOWN CA 90210 for JOHN DOE see https://example.invalid/x";
        byte[] body = addressErrorBody(description);
        handler.set(exchange -> drainAndRespond(exchange, 200, body));

        AddressValidationResult result;
        try (UspsWebToolsAddressValidationClient client = client(NORMAL_READ_TIMEOUT)) {
            result = client.validate(REQUEST);
        }

        assertThat(result.standardized()).isFalse();
        assertThat(result.errorDescription()).isEqualTo(description);
        assertThat(capturedLines(output, "USPS address not standardized"))
                .singleElement(InstanceOfAssertFactories.STRING)
                .contains(" INFO ")
                .endsWith("status=200 errorNumber=-2147219401 errorDescription=" + description);
        assertNoCredentials(output, null);
    }

    @Test
    @DisplayName("an empty address-level Description is logged as empty")
    void emptyAddressErrorDescriptionIsLoggedAsEmpty(CapturedOutput output) {
        byte[] body = addressErrorBody("");
        handler.set(exchange -> drainAndRespond(exchange, 200, body));

        AddressValidationResult result;
        try (UspsWebToolsAddressValidationClient client = client(NORMAL_READ_TIMEOUT)) {
            result = client.validate(REQUEST);
        }

        assertThat(result.standardized()).isFalse();
        assertThat(result.errorDescription()).isEmpty();
        assertThat(capturedLines(output, "USPS address not standardized"))
                .singleElement(InstanceOfAssertFactories.STRING)
                .endsWith("errorNumber=-2147219401 errorDescription=");
        assertNoCredentials(output, null);
    }

    @Test
    @DisplayName("an address-level Description echoing the user id and password is logged with **** in their place")
    void addressErrorDescriptionEchoingCredentialsIsMasked(CapturedOutput output) {
        // XML markup: the parsed Description is "No match for TESTUSER123 / pl&ce"holder."
        byte[] body = addressErrorBody("No match for " + USER_ID + " / pl&amp;ce&quot;holder.");
        handler.set(exchange -> drainAndRespond(exchange, 200, body));

        AddressValidationResult result;
        try (UspsWebToolsAddressValidationClient client = client(NORMAL_READ_TIMEOUT)) {
            result = client.validate(REQUEST);
        }

        assertThat(result.standardized()).isFalse();
        assertThat(result.errorDescription().equals("No match for " + USER_ID + " / " + PASSWORD + "."))
                .withFailMessage("returned description is not the parsed Description")
                .isTrue();
        assertThat(capturedLines(output, "USPS address not standardized"))
                .singleElement(InstanceOfAssertFactories.STRING)
                .endsWith("errorNumber=-2147219401 errorDescription=No match for **** / ****.");
        assertNoCredentials(output, null);
    }

    /**
     * Numeric credentials a service echoes as the address-level {@code Number}: each case
     * is the {@code Number} text the fake sends, named for the case, followed by the
     * configured user id and password, one of which that text equals. The values are
     * fictitious, and 9 or 10 digits long so that no PID, port or timestamp in the console
     * output can contain them. The leading zero and the plus sign are lost when the text
     * is parsed to an int, so only masking the text as sent hides those two credentials.
     */
    static Stream<Arguments> numericCredentialsEchoedAsNumber() {
        return Stream.of(
                Arguments.of(Named.of("a numeric user id", "731942586"), "731942586", PASSWORD),
                Arguments.of(Named.of("a numeric user id with a leading zero", "0731942586"), "0731942586", PASSWORD),
                Arguments.of(Named.of("a numeric user id with a plus sign", "+975318642"), "+975318642", PASSWORD),
                Arguments.of(Named.of("a numeric password", "864209753"), USER_ID, "864209753"));
    }

    @ParameterizedTest(name = "{0} echoed as the address-level Number is logged as ****")
    @MethodSource("numericCredentialsEchoedAsNumber")
    @DisplayName("a numeric credential echoed as the address-level Number is returned parsed and logged as ****")
    void addressErrorNumberEchoingNumericCredentialIsMasked(
            String echoed, String userId, String password, CapturedOutput output) {
        byte[] body = addressErrorBody(echoed, "Address Not Found.");
        handler.set(exchange -> drainAndRespond(exchange, 200, body));
        int parsed = Integer.parseInt(echoed);

        AddressValidationResult result;
        try (UspsWebToolsAddressValidationClient client = client(
                "http://127.0.0.1:" + port + ENDPOINT_PATH, userId, password,
                NORMAL_CONNECT_TIMEOUT, NORMAL_READ_TIMEOUT)) {
            result = client.validate(REQUEST);
        }

        assertThat(result.standardized()).isFalse();
        // The result keeps the parsed Number unmasked; only the log line masks it.
        assertThat(result.errorNumber() == parsed)
                .withFailMessage("returned errorNumber is not the parsed Number")
                .isTrue();
        assertThat(result.errorDescription()).isEqualTo("Address Not Found.");
        // Checked before the line itself, whose failure message would quote a leaked value.
        assertAbsent(output.getAll(), "captured output", echoed, Integer.toString(parsed), userId, password);
        assertThat(capturedLines(output, "USPS address not standardized"))
                .singleElement(InstanceOfAssertFactories.STRING)
                .contains(" INFO ")
                .endsWith("host=127.0.0.1 status=200 errorNumber=**** errorDescription=Address Not Found.");
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
        // A timed-out exchange is cancelled, never re-sent.
        assertThat(endpointHits.get()).isEqualTo(1);
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
    @DisplayName("a Web Tools root <Error> on a 200 is a fault, logged with its Number and Description")
    void rootErrorDocumentIsFault(CapturedOutput output) throws IOException {
        byte[] body = fixture("root-error.xml");
        handler.set(exchange -> drainAndRespond(exchange, 200, body));

        Throwable thrown = validateExpectingFault(NORMAL_READ_TIMEOUT);

        // The exception message names the kind of fault only; the USPS texts go to the log.
        assertThat(thrown).hasMessage("response root is not AddressValidateResponse");
        assertThat(capturedLines(output, "USPS address validation failed"))
                .singleElement(InstanceOfAssertFactories.STRING)
                .contains(" WARN ")
                .endsWith("host=127.0.0.1 status=200 reason=response root is not AddressValidateResponse"
                        + " errorNumber=-2147218662"
                        + " errorDescription=Authorization failure.  Perhaps username and/or password is incorrect.");
        assertNoCredentials(output, thrown);
    }

    @Test
    @DisplayName("a root <Error> Description echoing the user id and password is logged with **** in their place")
    void rootErrorDescriptionEchoingCredentialsIsMasked(CapturedOutput output) {
        // XML markup: the parsed Description is "Authorization failure for TESTUSER123 / pl&ce"holder."
        byte[] body = ("<?xml version=\"1.0\" encoding=\"UTF-8\"?><Error><Number>-2147218662</Number>"
                + "<Description>Authorization failure for " + USER_ID + " / pl&amp;ce&quot;holder.</Description>"
                + "<Source>USPSCOM::DoAuth</Source></Error>").getBytes(StandardCharsets.UTF_8);
        handler.set(exchange -> drainAndRespond(exchange, 200, body));

        Throwable thrown = validateExpectingFault(NORMAL_READ_TIMEOUT);

        assertThat(thrown).hasMessage("response root is not AddressValidateResponse");
        assertThat(capturedLines(output, "USPS address validation failed"))
                .singleElement(InstanceOfAssertFactories.STRING)
                .endsWith(" errorNumber=-2147218662 errorDescription=Authorization failure for **** / ****.");
        assertNoCredentials(output, thrown);
    }

    @ParameterizedTest(name = "an address-level Description echoing the {0} is logged with markers in its place")
    @EnumSource(Echo.class)
    @DisplayName("an address-level Description echoing the request is returned as sent and logged with markers")
    void addressErrorDescriptionEchoingRequestIsRedacted(Echo echo, CapturedOutput output) {
        String baseUrl = "http://127.0.0.1:" + port + ENDPOINT_PATH;
        AtomicReference<String> echoed = new AtomicReference<>();
        handler.set(exchange -> {
            String text = echo.text(baseUrl, exchange.getRequestURI().getRawQuery());
            echoed.set(text);
            drainAndRespond(exchange, 200, addressErrorBody(xmlText(text)));
        });

        AddressValidationResult result;
        try (UspsWebToolsAddressValidationClient client = client(NORMAL_READ_TIMEOUT)) {
            result = client.validate(REQUEST);
        }

        assertThat(echoed.get().length()).isLessThanOrEqualTo(AddressValidationResult.ERROR_DESCRIPTION_WIDTH);
        assertThat(result.standardized()).isFalse();
        assertThat(result.errorNumber()).isEqualTo(-2147219401);
        assertThat(result.errorDescription().equals(echoed.get()))
                .withFailMessage("returned description is not the echoed text")
                .isTrue();
        assertNoRequestEcho(output, baseUrl);
        assertThat(capturedLines(output, "USPS address not standardized"))
                .singleElement(InstanceOfAssertFactories.STRING)
                .contains(" INFO ")
                .endsWith("host=127.0.0.1 status=200 errorNumber=-2147219401 errorDescription=" + echo.logged());
        assertNoCredentials(output, null);
    }

    @ParameterizedTest(name = "a root <Error> Description echoing the {0} is logged with markers in its place")
    @EnumSource(Echo.class)
    @DisplayName("a root <Error> Description echoing the request leaves the fault as it was and is logged with markers")
    void rootErrorDescriptionEchoingRequestIsRedacted(Echo echo, CapturedOutput output) {
        String baseUrl = "http://127.0.0.1:" + port + ENDPOINT_PATH;
        AtomicReference<String> echoed = new AtomicReference<>();
        handler.set(exchange -> {
            String text = "Authorization failure. " + echo.text(baseUrl, exchange.getRequestURI().getRawQuery());
            echoed.set(text);
            byte[] body = ("<?xml version=\"1.0\" encoding=\"UTF-8\"?><Error><Number>-2147218662</Number>"
                    + "<Description>" + xmlText(text) + "</Description>"
                    + "<Source>USPSCOM::DoAuth</Source></Error>").getBytes(StandardCharsets.UTF_8);
            drainAndRespond(exchange, 200, body);
        });

        Throwable thrown = validateExpectingFault(NORMAL_READ_TIMEOUT);

        assertThat(echoed.get()).withFailMessage("the fake endpoint never received the request").isNotNull();
        // The fault message is the codec's, as without the echo; the USPS texts go to the log only.
        assertThat(thrown).hasMessage("response root is not AddressValidateResponse");
        assertThat(thrown.getCause()).isNull();
        assertNoRequestEcho(output, baseUrl);
        assertThat(capturedLines(output, "USPS address validation failed"))
                .singleElement(InstanceOfAssertFactories.STRING)
                .contains(" WARN ")
                .endsWith("host=127.0.0.1 status=200 reason=response root is not AddressValidateResponse"
                        + " errorNumber=-2147218662 errorDescription=Authorization failure. " + echo.logged());
        assertNoCredentials(output, thrown);
    }

    /**
     * 200 bodies holding an {@code Error} the response rules reject, each named for the rule
     * it breaks and followed by the fault message and the {@code errorNumber} and
     * {@code errorDescription} log values the WARN line must end with.
     */
    static Stream<Arguments> errorRuleFaults() throws IOException {
        byte[] longSource = ("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<AddressValidateResponse><Address ID=\"0\">"
                + "<Address2>8 ELMWOOD DR</Address2><City></City><State>CT</State><Zip5>06399</Zip5>"
                + "<Error><Number>-2147219401</Number><Source>" + "S".repeat(31) + "</Source>"
                + "<Description>Address Not Found.</Description></Error>"
                + "</Address></AddressValidateResponse>").getBytes(StandardCharsets.UTF_8);
        return Stream.of(
                Arguments.of(Named.of("error-bad-number.xml, Number 80040B1A", fixture("error-bad-number.xml")),
                        "Error Number is not an integer", "80040B1A", "Invalid Address."),
                Arguments.of(Named.of("error-missing-description.xml", fixture("error-missing-description.xml")),
                        "Error element Description is missing", "-2147219401", "-"),
                Arguments.of(Named.of("a Source of 31 characters", longSource),
                        "Error element Source exceeds 30 characters", "-2147219401", "Address Not Found."));
    }

    @ParameterizedTest(name = "{0} is a fault logged with its Number and Description")
    @MethodSource("errorRuleFaults")
    @DisplayName("an Error the response rules reject is a fault, logged with its Number and Description")
    void errorRuleFaultIsLoggedWithNumberAndDescription(
            byte[] body, String reason, String number, String description, CapturedOutput output) {
        handler.set(exchange -> drainAndRespond(exchange, 200, body));

        Throwable thrown = validateExpectingFault(NORMAL_READ_TIMEOUT);

        assertThat(thrown).hasMessage(reason);
        assertThat(capturedLines(output, "USPS address validation failed"))
                .singleElement(InstanceOfAssertFactories.STRING)
                .contains(" WARN ")
                .endsWith("status=200 reason=" + reason
                        + " errorNumber=" + number + " errorDescription=" + description);
        assertNoCredentials(output, thrown);
    }

    @ParameterizedTest(name = "{0} is a fault logged with host, status and reason only")
    @ValueSource(strings = {"malformed.xml", "doctype-xxe.xml", "two-addresses.xml", "blank-city-no-error.xml"})
    @DisplayName("a codec fault without a readable Error element is logged with host, status and reason only")
    void codecFaultWithoutErrorElementIsLoggedWithReasonOnly(String name, CapturedOutput output) throws IOException {
        byte[] body = fixture(name);
        handler.set(exchange -> drainAndRespond(exchange, 200, body));

        Throwable thrown = validateExpectingFault(NORMAL_READ_TIMEOUT);

        assertThat(capturedLines(output, "USPS address validation failed"))
                .singleElement(InstanceOfAssertFactories.STRING)
                .contains(" WARN ")
                .endsWith("host=127.0.0.1 status=200 reason=" + thrown.getMessage())
                .doesNotContain("errorNumber=", "errorDescription=");
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
        // Response bytes had arrived before the connection dropped, so the JDK client does
        // not re-send the request.
        assertThat(endpointHits.get()).isEqualTo(1);
        assertNoCredentials(output, thrown);
    }

    @ParameterizedTest(name = "a connection {0} before any response byte is one fault, the request sent at most twice")
    @EnumSource(Drop.class)
    @DisplayName("a connection dropped before any response byte is one fault; the request is re-sent at most once")
    void connectionDroppedBeforeResponseIsOneFault(Drop drop, CapturedOutput output)
            throws IOException, InterruptedException, ExecutionException, TimeoutException {
        AtomicInteger verifyRequests = new AtomicInteger();
        Throwable thrown;
        ExecutorService threads = Executors.newFixedThreadPool(2);
        try {
            try (ServerSocket listener = new ServerSocket()) {
                listener.bind(new InetSocketAddress("127.0.0.1", 0));
                threads.execute(() -> acceptAndDrop(listener, drop, verifyRequests));
                try (UspsWebToolsAddressValidationClient client = client(
                        "http://127.0.0.1:" + listener.getLocalPort() + ENDPOINT_PATH, NORMAL_READ_TIMEOUT)) {
                    Future<Throwable> call = threads.submit(() -> catchThrowable(() -> client.validate(REQUEST)));
                    thrown = call.get(WATCHDOG.toMillis(), TimeUnit.MILLISECONDS);
                }
            }
        } finally {
            // The listener is closed by now, which ends the accept loop.
            threads.shutdownNow();
            assertThat(threads.awaitTermination(WATCHDOG.toMillis(), TimeUnit.MILLISECONDS))
                    .withFailMessage("the fake server or the call did not stop")
                    .isTrue();
        }

        assertTransportFault(thrown);
        assertThat(thrown.getMessage()).startsWith("USPS address service I/O failure: ");
        // The server counts each request before dropping its connection, and the call ends
        // only after the last drop, so the count is final here. The JDK client re-sends a GET
        // once when the connection drops before any response byte, never more.
        assertThat(verifyRequests.get())
                .withFailMessage("the fake server received %s Verify requests, not 1 or 2", verifyRequests.get())
                .isBetween(1, 2);
        assertThat(capturedLines(output, "USPS address validation failed"))
                .singleElement(InstanceOfAssertFactories.STRING)
                .contains(" WARN ")
                .endsWith("host=127.0.0.1 reason=" + thrown.getMessage());
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

    @Test
    @DisplayName("redactRequest replaces every written form of an echoed request and keeps all other text")
    void redactRequestReplacesEveryEchoedForm() {
        // A base URL with a query of its own, so the Verify query follows it after "&".
        String baseUrl = "https://gw.example.invalid/ShippingAPI.dll?key=1";
        Map<String, String> redactions = new LinkedHashMap<>();
        redactions.put("Cut: <AddressValidateRequest USERID=\"X\"><Revision>1</Revision><Address ID=\"0\">"
                + "<Address2>8 ELMWOOD DR", "Cut: [request document]");
        redactions.put("Cut: &lt;AddressValidateRequest USERID=&quot;X&quot;&gt;&lt;Address2&gt;8 ELMWOOD DR",
                "Cut: [request document]");
        redactions.put("Sent &lt;AddressValidateRequest USERID=&quot;X&quot;&gt;&lt;/AddressValidateRequest&gt; twice",
                "Sent [request document] twice");
        redactions.put("<AddressValidateRequest>a</AddressValidateRequest> and "
                + "<AddressValidateRequest>b</AddressValidateRequest>.", "[request document] and [request document].");
        redactions.put("Doc <AddressValidateRequest USERID=\"X\">\n<Address2>8 ELMWOOD DR</Address2>\n"
                + "</AddressValidateRequest> end", "Doc [request document] end");
        // A value spelling the closing tag of the other form does not end the document.
        redactions.put("<AddressValidateRequest PASSWORD=\"a&lt;/AddressValidateRequest&gt;b\">"
                + "<Address2>8 ELMWOOD DR</Address2></AddressValidateRequest> end", "[request document] end");
        redactions.put("&lt;AddressValidateRequest PASSWORD=&quot;a&amp;lt;/AddressValidateRequest&amp;gt;b&quot;&gt;"
                + "&lt;Address2&gt;8 ELMWOOD DR&lt;/Address2&gt;&lt;/AddressValidateRequest&gt; end",
                "[request document] end");
        redactions.put("XML value %3CAddressValidateRequest+USERID%3D%22X%22%3E%3CAddress2%3E8+ELMWOOD+DR rejected",
                "XML value [request document] rejected");
        redactions.put("GET " + baseUrl + "&amp;API=Verify&amp;XML=%3CAddressValidateRequest+USERID%3D%22X%22%3E"
                + "%3C%2FAddressValidateRequest%3E failed", "GET [request URL]&amp;[request query] failed");
        redactions.put("API=Verify&amp;XML=<AddressValidateRequest USERID=\"X\"><Address2>8 ELMWOOD DR</Address2>"
                + "</AddressValidateRequest>&amp;x=1 end", "[request query] end");
        redactions.put("q API=Verify&XML=%3cAddressValidateRequest+USERID%3d%22X%22%3e", "q [request query]");
        redactions.put("q API=Verify&XML=", "q [request query]");
        redactions.put("Endpoint " + baseUrl + " is down", "Endpoint [request URL] is down");
        List<String> unchanged = List.of(
                "Address Not Found.",
                "Address 123 MAIN ST, ANYTOWN CA 90210 see https://example.invalid/x",
                "response root is not AddressValidateResponse",
                "API=Other&XML=1 and AddressValidateRequest without its bracket",
                "");

        try (UspsWebToolsAddressValidationClient client = client(baseUrl, NORMAL_READ_TIMEOUT)) {
            redactions.forEach((text, redacted) ->
                    assertThat(client.redactRequest(text)).as(text).isEqualTo(redacted));
            unchanged.forEach(text -> assertThat(client.redactRequest(text)).as(text).isEqualTo(text));
            assertThat(client.redactRequest(null)).isEmpty();

            // 64 KiB of input built to make a backtracking pattern explode: each must end at
            // once. The watchdog only keeps a broken pattern from hanging the build.
            assertTimeoutPreemptively(WATCHDOG, () -> {
                assertThat(client.redactRequest(
                        "<AddressValidateRequest" + "</AddressValidateRequest".repeat(2_700)))
                        .isEqualTo("[request document]");
                assertThat(client.redactRequest("&lt;AddressValidateRequest".repeat(2_500)))
                        .isEqualTo("[request document]");
                assertThat(client.redactRequest("API=Verify&XML=".repeat(4_300)))
                        .isEqualTo("[request query]");
                assertThat(client.redactRequest("%3CAddressValidateRequest ".repeat(2_500)))
                        .isEqualTo("[request document] ".repeat(2_500));
            });
        }
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
        return client(baseUrl, USER_ID, password, connectTimeout, readTimeout);
    }

    /**
     * Builds a client for {@code baseUrl} with {@code userId}, {@code password} and the given
     * connect and read timeouts.
     */
    private static UspsWebToolsAddressValidationClient client(
            String baseUrl, String userId, String password, Duration connectTimeout, Duration readTimeout) {
        return new UspsWebToolsAddressValidationClient(new AddressValidationProperties(
                true,
                AddressValidationProperties.Client.USPS,
                new AddressValidationProperties.Usps(
                        baseUrl, userId, password, connectTimeout, readTimeout)));
    }

    /**
     * A 200 body holding an address-level error: blank {@code City}, {@code Number}
     * {@code -2147219401}, {@code Source} {@code clsAMS} and {@code descriptionXml} as the
     * {@code Description} content, inserted as XML markup so it can carry character
     * references.
     */
    private static byte[] addressErrorBody(String descriptionXml) {
        return addressErrorBody("-2147219401", descriptionXml);
    }

    /**
     * A 200 body holding an address-level error: blank {@code City}, {@code numberText} as
     * the {@code Number} content, {@code Source} {@code clsAMS} and {@code descriptionXml}
     * as the {@code Description} content, both inserted as XML markup.
     */
    private static byte[] addressErrorBody(String numberText, String descriptionXml) {
        return ("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<AddressValidateResponse><Address ID=\"0\">"
                + "<Address2>8 ELMWOOD DR</Address2><City></City><State>CT</State><Zip5>06399</Zip5>"
                + "<Error><Number>" + numberText + "</Number><Source>clsAMS</Source>"
                + "<Description>" + descriptionXml + "</Description></Error>"
                + "</Address></AddressValidateResponse>").getBytes(StandardCharsets.UTF_8);
    }

    /** Escapes {@code &}, {@code <} and {@code >}, so that {@code text} can stand as XML element content. */
    private static String xmlText(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /**
     * Asserts that the captured output holds no part of a request the fake echoed: the base
     * URL, the Verify query, the request document decoded or URL-encoded, or the street as
     * the query or the document carries it. Failure messages name the form found, never the
     * output, which would quote the echo.
     */
    private static void assertNoRequestEcho(CapturedOutput output, String baseUrl) {
        assertAbsent(output.getAll(), "captured output", baseUrl, "ShippingAPI.dll?", "API=Verify",
                "AddressValidateRequest", "%3CAddressValidateRequest", "8+ELMWOOD+DR", "8 ELMWOOD DR");
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
     * The captured console lines that contain {@code marker}, in order. Lines are split on
     * the platform separator the console appender ends each line with.
     */
    private static List<String> capturedLines(CapturedOutput output, String marker) {
        return output.getAll().replace(System.lineSeparator(), "\n").lines()
                .filter(line -> line.contains(marker))
                .toList();
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

    /**
     * How the fake service echoes, inside a {@code Description}, the request it received,
     * and the text the client must log in place of that echo.
     */
    enum Echo {
        /** The request URL as received, its query percent-encoded. */
        ENCODED_URL("Request URL: [request URL]?[request query]"),
        /** The request URL with its query percent-decoded, so the document reads as XML. */
        DECODED_URL("Request URL: [request URL]?[request query]"),
        /** The decoded request document alone, with text on both sides. */
        DECODED_DOCUMENT("Rejected [request document] as sent");

        private final String logged;

        Echo(String logged) {
            this.logged = logged;
        }

        /** The echo text for a request to {@code baseUrl} whose raw query was {@code rawQuery}. */
        String text(String baseUrl, String rawQuery) {
            String decoded = URLDecoder.decode(rawQuery, StandardCharsets.UTF_8);
            return switch (this) {
                case ENCODED_URL -> "Request URL: " + baseUrl + "?" + rawQuery;
                case DECODED_URL -> "Request URL: " + baseUrl + "?" + decoded;
                case DECODED_DOCUMENT -> "Rejected " + decoded.substring(decoded.indexOf("XML=") + 4) + " as sent";
            };
        }

        /** The text the log line must carry in place of the echo. */
        String logged() {
            return logged;
        }
    }

    /** How the raw fake server ends a connection once it has read the request head. */
    enum Drop {
        /** A normal close: the client reads end of stream. */
        CLOSED,
        /** A close with {@code SO_LINGER} 0: the client reads a connection reset. */
        RESET
    }

    /**
     * Accepts connections on {@code listener} until it is closed. For each one it reads the
     * request head, counts it in {@code verifyRequests} when its request line is the Verify
     * {@code GET}, then drops the connection as {@code drop} says without sending a byte.
     * Reads are bounded by {@link #WATCHDOG}, so a client that never sends its head cannot
     * hold the loop.
     */
    private static void acceptAndDrop(ServerSocket listener, Drop drop, AtomicInteger verifyRequests) {
        while (!listener.isClosed()) {
            try (Socket connection = listener.accept()) {
                connection.setSoTimeout((int) WATCHDOG.toMillis());
                String head = readRequestHead(connection.getInputStream());
                if (head.startsWith("GET " + ENDPOINT_PATH + "?API=Verify&XML=")) {
                    verifyRequests.incrementAndGet();
                }
                if (drop == Drop.RESET) {
                    // A zero linger time makes close() send RST instead of FIN.
                    connection.setSoLinger(true, 0);
                }
            } catch (IOException closedOrDropped) {
                // The listener was closed, which ends the loop, or one connection failed,
                // which the client side reports.
            }
        }
    }

    /**
     * Reads one request head, up to and including the blank line that ends it, at most
     * 64 KiB, and returns it as ISO-8859-1 text; at end of stream it returns what arrived.
     * Callers inspect its request line, which carries the credentials, and never print it.
     */
    private static String readRequestHead(InputStream in) throws IOException {
        InputStream buffered = new BufferedInputStream(in);
        ByteArrayOutputStream head = new ByteArrayOutputStream();
        // Bytes of the terminating CR LF CR LF matched so far.
        int matched = 0;
        while (matched < 4 && head.size() < 65_536) {
            int next = buffered.read();
            if (next < 0) {
                break;
            }
            head.write(next);
            char expected = matched % 2 == 0 ? '\r' : '\n';
            matched = next == expected ? matched + 1 : (next == '\r' ? 1 : 0);
        }
        return head.toString(StandardCharsets.ISO_8859_1);
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
