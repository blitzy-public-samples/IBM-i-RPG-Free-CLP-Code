package com.democorp.customermaster.address;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

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
 * [USPS_Address/USADRVAL.SQLRPGLE:74-137]. Where the source calls {@code SQLProblem} after a
 * failed HTTP call or XMLTABLE [USADRVAL.SQLRPGLE:94-96,114-116,134-136], which dumps the
 * program and ends it with a CPF9898 escape message [Service_Pgms/SRV_SQL.SQLRPGLE:20-57],
 * the target throws {@link AddressServiceUnavailableException}, which customer-api maps to
 * 502 APP0502; a fault is never returned as a result.
 *
 * <p><b>Test values.</b> The credentials {@code TESTUSER123}, {@code pl&ce"holder} and
 * {@code p'&q} are fictitious placeholders. The passwords carry {@code &}, {@code "} and an
 * apostrophe so that their raw, request-wire ({@link UspsXmlCodec#attributeValue}),
 * full-entity and URL-encoded forms differ. The address is the fictitious base row of the
 * {@code usps/*.xml} fixtures. The test never prints a captured query or request document,
 * and its failure messages name what is missing or found rather than quoting either.
 *
 * <p><b>Fake server.</b> A JDK {@link HttpServer} on {@code 127.0.0.1} and an ephemeral
 * port, with a cached thread pool so a handler that waits on a latch blocks nothing else;
 * {@code /redirected} counts the hits a followed redirect would make. The refused
 * connection targets a {@code 127.0.0.1} port held for the whole case by a bound socket
 * that never listens. The dropped-connection cases use a raw {@link ServerSocket}, since
 * {@link HttpServer} cannot drop a connection unanswered. Every wait is bounded by the
 * watchdog, and none sleeps.
 *
 * <p><b>Captured output.</b> The captured output holds every line Logback writes to the
 * console in its default setup, which Spring Boot's {@code RootLogLevelConfigurator} puts
 * at root level INFO; the test leaves that level as it is. Plain JUnit 5 and AssertJ: no
 * application context, no mocks, no network beyond the loopback interface.
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

    /**
     * A Description carrying personal data: the street and city {@link #REQUEST} submitted,
     * then a street, a name, a phone number and a URL it did not submit.
     */
    private static final String PERSONAL_DATA_DESCRIPTION = "Address Not Found. 8 ELMWOOD DR, OLD HAVEN"
            + " near 123 MAIN ST for JOHN DOE call 555-0100 see https://example.invalid/x";

    /** The log projection of {@link #PERSONAL_DATA_DESCRIPTION} for a call with {@link #REQUEST}. */
    private static final String PERSONAL_DATA_LOGGED =
            "Address Not Found. [address], [address] [unlisted, 47 characters] [URL]";

    /** The personal data in {@link #PERSONAL_DATA_DESCRIPTION}, none of which may reach the log. */
    private static final String[] PERSONAL_DATA =
            {"8 ELMWOOD DR", "OLD HAVEN", "123 MAIN ST", "JOHN DOE", "555-0100", "example.invalid"};

    /**
     * A Description whose contact name and e-mail address are spelled with words of the
     * documented descriptions ({@code can}, {@code see}, {@code be}, {@code it}), after a
     * documented sentence and inside a {@code mailto:} URI.
     */
    private static final String CONTACT_DESCRIPTION = "Address Not Found. Contact CAN SEE at mailto:can@be.it";

    /** The log projection of {@link #CONTACT_DESCRIPTION}. */
    private static final String CONTACT_LOGGED = "Address Not Found. [unlisted, 18 characters] [URL]";

    /** The contact data in {@link #CONTACT_DESCRIPTION}, none of which may reach the log. */
    private static final String[] CONTACT_DATA = {"CAN SEE", "can@be.it", "mailto"};

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
        // Each documented sentence logs as sent and each ERROR as an unlisted stretch, so only
        // the escaping stands between these characters and a forged line.
        byte[] body = addressErrorBody("Address Not Found.&#10;ERROR Invalid City.&#13;&#10;"
                + "ERROR Invalid State Code.&#x85;Invalid Zip Code.&#x2028;Invalid Address.&#x2029;Address Not Found.");
        handler.set(exchange -> drainAndRespond(exchange, 200, body));
        String decoded = "Address Not Found.\nERROR Invalid City.\r\nERROR Invalid State Code.\u0085"
                + "Invalid Zip Code.\u2028Invalid Address.\u2029Address Not Found.";

        AddressValidationResult result;
        try (UspsWebToolsAddressValidationClient client = client(NORMAL_READ_TIMEOUT)) {
            result = client.validate(REQUEST);
        }

        assertThat(result.standardized()).isFalse();
        assertThat(result.errorDescription()).isEqualTo(decoded);
        // The console ends each line with the platform separator; nothing else may break one.
        String captured = output.getAll().replace(System.lineSeparator(), "\n");
        List<String> lines = captured.lines().toList();
        String escaped = "Address Not Found.\\u000A[unlisted, 5 characters] Invalid City.\\u000D\\u000A"
                + "[unlisted, 5 characters] Invalid State Code.\\u0085"
                + "Invalid Zip Code.\\u2028Invalid Address.\\u2029Address Not Found.";
        assertThat(lines.stream().filter(line -> line.contains("USPS address not standardized")))
                .singleElement(InstanceOfAssertFactories.STRING)
                .endsWith("errorNumber=-2147219401 errorDescription=" + escaped);
        // The forged texts survive only inside that one line, never as entries of their own.
        assertThat(lines.stream().filter(line -> line.contains("Invalid")))
                .singleElement(InstanceOfAssertFactories.STRING)
                .contains("USPS address not standardized");
        assertThat(lines).noneMatch(line -> line.startsWith("ERROR") || line.startsWith("[unlisted")
                || line.startsWith("Invalid") || line.startsWith("Address Not Found."));
        assertThat(captured).doesNotContain("\r", "\u0085", "\u2028", "\u2029");
        assertNoCredentials(output, null);
    }

    @Test
    @DisplayName("an address-level Description is returned as sent and logged as its safe projection")
    void addressErrorDescriptionIsLoggedAsSafeProjection(CapturedOutput output) {
        byte[] body = addressErrorBody(PERSONAL_DATA_DESCRIPTION);
        handler.set(exchange -> drainAndRespond(exchange, 200, body));

        AddressValidationResult result;
        try (UspsWebToolsAddressValidationClient client = client(NORMAL_READ_TIMEOUT)) {
            result = client.validate(REQUEST);
        }

        assertThat(result.standardized()).isFalse();
        assertThat(result.errorDescription()).isEqualTo(PERSONAL_DATA_DESCRIPTION);
        // Checked before the line itself, whose failure message would quote a leaked value.
        assertAbsent(output.getAll(), "captured output", PERSONAL_DATA);
        assertThat(capturedLines(output, "USPS address not standardized"))
                .singleElement(InstanceOfAssertFactories.STRING)
                .contains(" INFO ")
                .endsWith("status=200 errorNumber=-2147219401 errorDescription=" + PERSONAL_DATA_LOGGED);
        assertNoCredentials(output, null);
    }

    @Test
    @DisplayName("an address-level Description spelling contact data with documented words is logged as its safe projection")
    void addressErrorDescriptionWithContactDataIsLoggedAsSafeProjection(CapturedOutput output) {
        byte[] body = addressErrorBody(CONTACT_DESCRIPTION);
        handler.set(exchange -> drainAndRespond(exchange, 200, body));

        AddressValidationResult result;
        try (UspsWebToolsAddressValidationClient client = client(NORMAL_READ_TIMEOUT)) {
            result = client.validate(REQUEST);
        }

        assertThat(result.standardized()).isFalse();
        assertThat(result.errorDescription()).isEqualTo(CONTACT_DESCRIPTION);
        // Checked before the line itself, whose failure message would quote a leaked value.
        assertAbsent(output.getAll(), "captured output", CONTACT_DATA);
        assertThat(capturedLines(output, "USPS address not standardized"))
                .singleElement(InstanceOfAssertFactories.STRING)
                .contains(" INFO ")
                .endsWith("USPS address not standardized: host=127.0.0.1 status=200 errorNumber=-2147219401"
                        + " errorDescription=" + CONTACT_LOGGED);
        assertNoCredentials(output, null);
    }

    /**
     * The seven documented descriptions, written out here rather than read from the client
     * so that each is checked against its source text.
     */
    static Stream<String> documentedDescriptions() {
        return Stream.of(
                "Address Not Found.",
                "Invalid Address.",
                "Invalid City.",
                "Invalid State Code.",
                "Invalid Zip Code.",
                "Authorization failure.  Perhaps username and/or password is incorrect.",
                "XML Syntax Error: Please check the XML request to see if it can be parsed.");
    }

    @Test
    @DisplayName("the client's documented descriptions are exactly the cited USPS texts")
    void documentedDescriptionsAreTheCitedTexts() {
        assertThat(UspsWebToolsAddressValidationClient.DOCUMENTED_DESCRIPTIONS)
                .containsExactlyElementsOf(documentedDescriptions().toList());
    }

    @Test
    @DisplayName("the client's documented sentences are its documented descriptions split after each full stop")
    void documentedSentencesAreTheDescriptionsSplitAfterEachFullStop() {
        assertThat(UspsWebToolsAddressValidationClient.DOCUMENTED_SENTENCES).containsExactly(
                "Address Not Found.",
                "Invalid Address.",
                "Invalid City.",
                "Invalid State Code.",
                "Invalid Zip Code.",
                "Authorization failure.",
                "Perhaps username and/or password is incorrect.",
                "XML Syntax Error: Please check the XML request to see if it can be parsed.");
    }

    @ParameterizedTest(name = "\"{0}\" is logged as sent")
    @MethodSource("documentedDescriptions")
    @DisplayName("a documented address-level Description is logged exactly as sent")
    void documentedDescriptionIsLoggedAsSent(String description, CapturedOutput output) {
        byte[] body = addressErrorBody(xmlText(description));
        handler.set(exchange -> drainAndRespond(exchange, 200, body));

        AddressValidationResult result;
        try (UspsWebToolsAddressValidationClient client = client(NORMAL_READ_TIMEOUT)) {
            result = client.validate(REQUEST);
        }

        assertThat(result.errorDescription()).isEqualTo(description);
        assertThat(capturedLines(output, "USPS address not standardized"))
                .singleElement(InstanceOfAssertFactories.STRING)
                .endsWith("errorNumber=-2147219401 errorDescription=" + description);
        assertNoCredentials(output, null);
    }

    /**
     * Description texts, each named for what it exercises and followed by the projection
     * its INFO line must end with, for a call with {@link #REQUEST}.
     */
    static Stream<Arguments> descriptionProjections() {
        return Stream.of(
                Arguments.of(Named.of("one undocumented character", "Invalid City. X."),
                        "Invalid City. [unlisted, 1 character]."),
                Arguments.of(Named.of("supplementary letters, counted in code points",
                                "Invalid City. \uD835\uDC00\uD835\uDC01."),
                        "Invalid City. [unlisted, 2 characters]."),
                Arguments.of(Named.of("a combining mark inside a word", "Invalid City. Cafe\u0301."),
                        "Invalid City. [unlisted, 5 characters]."),
                Arguments.of(Named.of("a www. address and an upper-case scheme",
                                "See www.example.invalid/a or HTTPS://example.invalid/b. Invalid Address."),
                        "[unlisted, 3 characters] [URL] [unlisted, 2 characters] [URL] Invalid Address."),
                Arguments.of(Named.of("tel: and mailto: URIs, in any case",
                                "Invalid Address. Call tel:+1-555-0100 or MAILTO:can@be.it"),
                        "Invalid Address. [unlisted, 4 characters] [URL] [unlisted, 2 characters] [URL]"),
                Arguments.of(Named.of("a bare e-mail address", "Invalid Address. Write to can@be.it."),
                        "Invalid Address. [unlisted, 18 characters]."),
                Arguments.of(Named.of("words around the submitted State and ZIP", "Invalid Zip Code 06399 for CT."),
                        "[unlisted, 16 characters] [address] [unlisted, 3 characters] [address]."),
                Arguments.of(Named.of("a submitted value inside a longer word or number", "Invalid City. OLD HAVENS 106399."),
                        "Invalid City. [unlisted, 17 characters]."),
                Arguments.of(Named.of("documented words outside their sentences",
                                "Invalid Code. Perhaps CAN SEE the password"),
                        "[unlisted, 42 characters]"),
                Arguments.of(Named.of("a documented sentence cut short", "Invalid State."),
                        "[unlisted, 13 characters]."),
                Arguments.of(Named.of("a documented sentence in another case", "INVALID CITY."),
                        "[unlisted, 12 characters]."),
                Arguments.of(Named.of("a documented sentence right after a letter", "XInvalid City."),
                        "[unlisted, 13 characters]."));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("descriptionProjections")
    @DisplayName("an undocumented address-level Description is logged with markers in place of its other words")
    void addressErrorDescriptionProjection(String description, String logged, CapturedOutput output) {
        byte[] body = addressErrorBody(xmlText(description));
        handler.set(exchange -> drainAndRespond(exchange, 200, body));

        AddressValidationResult result;
        try (UspsWebToolsAddressValidationClient client = client(NORMAL_READ_TIMEOUT)) {
            result = client.validate(REQUEST);
        }

        assertThat(result.errorDescription()).isEqualTo(description);
        assertThat(capturedLines(output, "USPS address not standardized"))
                .singleElement(InstanceOfAssertFactories.STRING)
                .endsWith("errorNumber=-2147219401 errorDescription=" + logged);
        assertNoCredentials(output, null);
    }

    /**
     * A street holding {@code '}, {@code &} and {@code <}, so that its raw, element-text,
     * full-entity and URL-encoded forms all differ, followed by the forms a service could
     * echo, each named.
     */
    static Stream<Arguments> submittedAddressForms() {
        String street = "8 O'NEIL & <OAK> DR";
        String elementText = "8 O'NEIL &amp; &lt;OAK&gt; DR";
        String entities = "8 O&apos;NEIL &amp; &lt;OAK&gt; DR";
        AddressValidationRequest escaped = new AddressValidationRequest("", street, "OLD HAVEN", "CT", "06399", "");
        return Stream.of(
                Arguments.of(Named.of("street URL-encoded with +", "8+ELMWOOD+DR"), REQUEST),
                Arguments.of(Named.of("street URL-encoded with %20", "8%20ELMWOOD%20DR"), REQUEST),
                Arguments.of(Named.of("street raw", street), escaped),
                Arguments.of(Named.of("street as escaped element text", elementText), escaped),
                Arguments.of(Named.of("street with every entity", entities), escaped),
                Arguments.of(Named.of("street raw, URL-encoded", URLEncoder.encode(street, StandardCharsets.UTF_8)),
                        escaped),
                Arguments.of(Named.of("street as element text, URL-encoded",
                        URLEncoder.encode(elementText, StandardCharsets.UTF_8)), escaped),
                Arguments.of(Named.of("street with every entity, URL-encoded with %20",
                        URLEncoder.encode(entities, StandardCharsets.UTF_8).replace("+", "%20")), escaped));
    }

    @ParameterizedTest(name = "{0} is logged as [address]")
    @MethodSource("submittedAddressForms")
    @DisplayName("a Description echoing a submitted value in an escaped or encoded form logs [address]")
    void addressErrorDescriptionEchoingSubmittedFormIsRedacted(
            String echoed, AddressValidationRequest request, CapturedOutput output) {
        String description = "Address Not Found. " + echoed + " and " + echoed;
        byte[] body = addressErrorBody(xmlText(description));
        handler.set(exchange -> drainAndRespond(exchange, 200, body));

        AddressValidationResult result;
        try (UspsWebToolsAddressValidationClient client = client(NORMAL_READ_TIMEOUT)) {
            result = client.validate(request);
        }

        assertThat(result.errorDescription()).isEqualTo(description);
        assertAbsent(output.getAll(), "captured output", echoed, "ELMWOOD", "NEIL", "OAK");
        assertThat(capturedLines(output, "USPS address not standardized"))
                .singleElement(InstanceOfAssertFactories.STRING)
                .endsWith("errorNumber=-2147219401 errorDescription=Address Not Found."
                        + " [address] [unlisted, 3 characters] [address]");
        assertNoCredentials(output, null);
    }

    @Test
    @DisplayName("each call redacts the values its own request submitted, and only those")
    void eachCallRedactsItsOwnSubmittedValues(CapturedOutput output) {
        AddressValidationRequest other = new AddressValidationRequest("", "77 QUAIL RUN", "WESTBROOK", "CT", "06498", "");
        byte[] body = addressErrorBody("Address Not Found. 8 ELMWOOD DR, OLD HAVEN or 77 QUAIL RUN, WESTBROOK");
        handler.set(exchange -> drainAndRespond(exchange, 200, body));

        try (UspsWebToolsAddressValidationClient client = client(NORMAL_READ_TIMEOUT)) {
            client.validate(REQUEST);
            client.validate(other);
        }

        assertThat(capturedLines(output, "USPS address not standardized"))
                .extracting(line -> line.substring(line.indexOf("errorDescription=")))
                .containsExactly(
                        "errorDescription=Address Not Found. [address], [address] [unlisted, 26 characters]",
                        "errorDescription=Address Not Found. [unlisted, 26 characters] [address], [address]");
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
        // "No match for" is no documented sentence, so it logs as one unlisted stretch.
        assertThat(capturedLines(output, "USPS address not standardized"))
                .singleElement(InstanceOfAssertFactories.STRING)
                .endsWith("errorNumber=-2147219401 errorDescription=[unlisted, 12 characters] **** / ****.");
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
                .endsWith(" errorNumber=-2147218662 errorDescription=[unlisted, 25 characters] **** / ****.");
        assertNoCredentials(output, thrown);
    }

    /**
     * 200 bodies the codec rejects whose {@code Error} carries
     * {@link #PERSONAL_DATA_DESCRIPTION}, each named for the fault and followed by its
     * message.
     */
    static Stream<Arguments> faultsWithPersonalData() {
        String description = xmlText(PERSONAL_DATA_DESCRIPTION);
        byte[] rootError = ("<?xml version=\"1.0\" encoding=\"UTF-8\"?><Error><Number>-2147219401</Number>"
                + "<Description>" + description + "</Description>"
                + "<Source>USPSCOM::DoAuth</Source></Error>").getBytes(StandardCharsets.UTF_8);
        byte[] longSource = ("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<AddressValidateResponse><Address ID=\"0\">"
                + "<Address2>8 ELMWOOD DR</Address2><City></City><State>CT</State><Zip5>06399</Zip5>"
                + "<Error><Number>-2147219401</Number><Source>" + "S".repeat(31) + "</Source>"
                + "<Description>" + description + "</Description></Error>"
                + "</Address></AddressValidateResponse>").getBytes(StandardCharsets.UTF_8);
        return Stream.of(
                Arguments.of(Named.of("a Web Tools root <Error>", rootError),
                        "response root is not AddressValidateResponse"),
                Arguments.of(Named.of("an Error with a Source of 31 characters", longSource),
                        "Error element Source exceeds 30 characters"));
    }

    @ParameterizedTest(name = "{0} is a fault whose Description is logged as its safe projection")
    @MethodSource("faultsWithPersonalData")
    @DisplayName("a fault's Description carrying personal data is logged as its safe projection at WARN")
    void faultDescriptionIsLoggedAsSafeProjection(byte[] body, String reason, CapturedOutput output) {
        handler.set(exchange -> drainAndRespond(exchange, 200, body));

        Throwable thrown = validateExpectingFault(NORMAL_READ_TIMEOUT);

        assertThat(thrown).hasMessage(reason);
        assertAbsent(output.getAll(), "captured output", PERSONAL_DATA);
        assertThat(capturedLines(output, "USPS address validation failed"))
                .singleElement(InstanceOfAssertFactories.STRING)
                .contains(" WARN ")
                .endsWith("host=127.0.0.1 status=200 reason=" + reason
                        + " errorNumber=-2147219401 errorDescription=" + PERSONAL_DATA_LOGGED);
        assertNoCredentials(output, thrown);
    }

    @Test
    @DisplayName("a Number that is not an integer is logged as its safe projection, never as sent")
    void invalidNumberIsLoggedAsSafeProjection(CapturedOutput output) {
        byte[] body = addressErrorBody("8 ELMWOOD DR 555-0100", "Invalid Address.");
        handler.set(exchange -> drainAndRespond(exchange, 200, body));

        Throwable thrown = validateExpectingFault(NORMAL_READ_TIMEOUT);

        assertThat(thrown).hasMessage("Error Number is not an integer");
        assertAbsent(output.getAll(), "captured output", "8 ELMWOOD DR", "555-0100");
        assertThat(capturedLines(output, "USPS address validation failed"))
                .singleElement(InstanceOfAssertFactories.STRING)
                .contains(" WARN ")
                .endsWith("status=200 reason=Error Number is not an integer"
                        + " errorNumber=[address] [unlisted, 8 characters] errorDescription=Invalid Address.");
        assertNoCredentials(output, thrown);
    }

    /**
     * 200 bodies the codec rejects whose {@code Error} carries {@link #CONTACT_DESCRIPTION},
     * each named for the fault and followed by its message and the {@code errorNumber} log
     * value: a Web Tools root {@code <Error>}, and an address-level {@code Error} whose
     * {@code Number} is the contact name.
     */
    static Stream<Arguments> faultsWithContactData() {
        byte[] rootError = ("<?xml version=\"1.0\" encoding=\"UTF-8\"?><Error><Number>-2147218662</Number>"
                + "<Description>" + CONTACT_DESCRIPTION + "</Description>"
                + "<Source>USPSCOM::DoAuth</Source></Error>").getBytes(StandardCharsets.UTF_8);
        return Stream.of(
                Arguments.of(Named.of("a Web Tools root <Error>", rootError),
                        "response root is not AddressValidateResponse", "-2147218662"),
                Arguments.of(Named.of("an Error whose Number is the contact name",
                                addressErrorBody("CAN SEE", CONTACT_DESCRIPTION)),
                        "Error Number is not an integer", "[unlisted, 7 characters]"));
    }

    @ParameterizedTest(name = "{0} is a fault whose contact data is logged as markers")
    @MethodSource("faultsWithContactData")
    @DisplayName("a fault's Number and Description spelling contact data with documented words are logged as markers at WARN")
    void faultContactDataIsLoggedAsSafeProjection(byte[] body, String reason, String number, CapturedOutput output) {
        handler.set(exchange -> drainAndRespond(exchange, 200, body));

        Throwable thrown = validateExpectingFault(NORMAL_READ_TIMEOUT);

        assertThat(thrown).hasMessage(reason);
        // Checked before the line itself, whose failure message would quote a leaked value.
        assertAbsent(output.getAll(), "captured output", CONTACT_DATA);
        assertThat(capturedLines(output, "USPS address validation failed"))
                .singleElement(InstanceOfAssertFactories.STRING)
                .contains(" WARN ")
                .endsWith("USPS address validation failed: host=127.0.0.1 status=200 reason=" + reason
                        + " errorNumber=" + number + " errorDescription=" + CONTACT_LOGGED);
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

        String baseUrl = "http://127.0.0.1:" + port + ENDPOINT_PATH;
        // Built from the client's settings, as the client builds its own.
        UspsTextRedactor redactor = new UspsTextRedactor(new AddressValidationProperties.Usps(
                baseUrl, USER_ID, APOSTROPHE_PASSWORD, NORMAL_CONNECT_TIMEOUT, NORMAL_READ_TIMEOUT));

        AddressValidationResult result;
        try (UspsWebToolsAddressValidationClient client = client(baseUrl, APOSTROPHE_PASSWORD, NORMAL_READ_TIMEOUT)) {
            result = client.validate(REQUEST);
        }
        String query = rawQuery.get();
        assertThat(query).withFailMessage("request has no query string").isNotNull();
        String document = URLDecoder.decode(query.substring(query.indexOf("XML=") + 4), StandardCharsets.UTF_8);
        String maskedDocument = redactor.mask(document);
        String maskedQuery = redactor.mask(query);
        String maskedEntities = redactor.mask("x " + APOSTROPHE_PASSWORD_ENTITIES + " y");
        String maskedEntitiesEncoded = redactor.mask("x " + entitiesEncoded + " y");

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

    private UspsWebToolsAddressValidationClient client(Duration readTimeout) {
        return client(NORMAL_CONNECT_TIMEOUT, readTimeout);
    }

    private UspsWebToolsAddressValidationClient client(Duration connectTimeout, Duration readTimeout) {
        return client("http://127.0.0.1:" + port + ENDPOINT_PATH, PASSWORD, connectTimeout, readTimeout);
    }

    private static UspsWebToolsAddressValidationClient client(String baseUrl, Duration readTimeout) {
        return client(baseUrl, PASSWORD, readTimeout);
    }

    private static UspsWebToolsAddressValidationClient client(
            String baseUrl, String password, Duration readTimeout) {
        return client(baseUrl, password, NORMAL_CONNECT_TIMEOUT, readTimeout);
    }

    private static UspsWebToolsAddressValidationClient client(
            String baseUrl, String password, Duration connectTimeout, Duration readTimeout) {
        return client(baseUrl, USER_ID, password, connectTimeout, readTimeout);
    }

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

    private static void assertDocumentContains(String document, String fragment) {
        assertThat(document.contains(fragment))
                .withFailMessage("decoded request document lacks %s", fragment)
                .isTrue();
    }

    /**
     * How the fake service echoes, inside a {@code Description}, the request it received,
     * and the projection the client must log for that echo.
     */
    enum Echo {
        /** The request URL as received, its query percent-encoded. */
        ENCODED_URL("[unlisted, 11 characters]: [request URL]?[request query]"),
        /** The request URL with its query percent-decoded, so the document reads as XML. */
        DECODED_URL("[unlisted, 11 characters]: [request URL]?[request query]"),
        /** The decoded request document alone, with undocumented words on both sides. */
        DECODED_DOCUMENT("[unlisted, 8 characters] [request document] [unlisted, 7 characters]");

        private final String logged;

        Echo(String logged) {
            this.logged = logged;
        }

        String text(String baseUrl, String rawQuery) {
            String decoded = URLDecoder.decode(rawQuery, StandardCharsets.UTF_8);
            return switch (this) {
                case ENCODED_URL -> "Request URL: " + baseUrl + "?" + rawQuery;
                case DECODED_URL -> "Request URL: " + baseUrl + "?" + decoded;
                case DECODED_DOCUMENT -> "Rejected " + decoded.substring(decoded.indexOf("XML=") + 4) + " as sent";
            };
        }

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

    private static byte[] fixture(String name) throws IOException {
        try (InputStream in = Objects.requireNonNull(
                UspsWebToolsAddressValidationClientTest.class.getResourceAsStream("/usps/" + name),
                "missing test fixture usps/" + name)) {
            return in.readAllBytes();
        }
    }
}
