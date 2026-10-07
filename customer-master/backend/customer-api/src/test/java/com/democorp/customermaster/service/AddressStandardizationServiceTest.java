package com.democorp.customermaster.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.democorp.customermaster.address.AddressServiceUnavailableException;
import com.democorp.customermaster.address.AddressValidationClient;
import com.democorp.customermaster.address.AddressValidationProperties;
import com.democorp.customermaster.address.AddressValidationProperties.Client;
import com.democorp.customermaster.address.AddressValidationProperties.Usps;
import com.democorp.customermaster.address.AddressValidationRequest;
import com.democorp.customermaster.address.AddressValidationResult;
import com.democorp.customermaster.address.StubAddressValidationClient;
import com.democorp.customermaster.address.UspsWebToolsAddressValidationClient;
import com.democorp.customermaster.domain.Address;
import com.democorp.customermaster.service.exception.CustomerValidationException;
import com.democorp.customermaster.service.exception.CustomerValidationException.FieldError;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.json.JsonParserFactory;
import org.springframework.core.io.ClassPathResource;

/**
 * Specifies {@link AddressStandardizationService}, the target of the USPS variant's {@code Edit_Address}
 * routine [USPS_Address/MTNCUSTR.SQLRPGLE:471-499].
 *
 * <p>The source clears {@code AdrIn}, moves the street into {@code Address2} (the 40-character
 * {@code SD_ADDR} into the 30-character {@code USAdrValDS.Address2} [Copy_Mbrs/USADRVALDS.RPGLE:5]),
 * the city and state as keyed and the first five characters of the ZIP, then calls {@code USAdrVal}. A
 * returned City means success: the street, city and state are overwritten and the ZIP becomes
 * {@code Zip5-Zip4} when a ZIP+4 came back. Without a City, DEM9898 carries the USPS description and
 * the cursor goes to ADDR with ADDR, CITY, STATE and ZIP in reverse image. The target adds the
 * standardized-State check (DEM0503) that its state foreign key requires, and lets a service fault
 * propagate unchanged so that it becomes 502 APP0502, never DEM9898.
 *
 * <p>Three kinds of client exercise the mapping:
 * <ul>
 *   <li>a Mockito mock, for every mapping rule and failure path;</li>
 *   <li>the real {@link UspsWebToolsAddressValidationClient} against a JDK {@link HttpServer} bound to
 *       the IPv4 loopback address on an ephemeral port, which proves what actually goes over the wire
 *       for a 38-character street;</li>
 *   <li>the real {@link StubAddressValidationClient} with its bundled fixture keyed on the first 30
 *       characters of that street.</li>
 * </ul>
 *
 * <p>Plain JUnit 5, Mockito and AssertJ: no Spring application context, no database, no Docker. The
 * only network use is the loopback fake; nothing reaches {@code secure.shippingapis.com}, whose URL
 * appears only as the never-contacted base URL of the mocked and stubbed cases.
 */
@DisplayName("AddressStandardizationService: the Edit_Address mapping")
final class AddressStandardizationServiceTest {

    /** The default Web Tools endpoint; never contacted, because those cases use a mock or the stub. */
    private static final String UNUSED_BASE_URL = AddressValidationProperties.DEFAULT_BASE_URL;

    /** Connect and read timeout of every test configuration, short so a broken fake fails fast. */
    private static final Duration TIMEOUT = Duration.ofSeconds(2);

    /** A 38-character street: longer than the 30-character {@code Address2} the source sends. */
    private static final String STREET_38 = "4400 SOUTHEAST LAKEVIEW TERRACE UNIT 9";

    /** The first 30 characters of {@link #STREET_38}, which is all the request may carry. */
    private static final String STREET_38_FIRST_30 = "4400 SOUTHEAST LAKEVIEW TERRAC";

    /** The standardized street the loopback fake and the stub fixture both return for that street. */
    private static final String STANDARDIZED_STREET = "4400 SE LAKEVIEW TER";

    /** The four fields a DEM9898 highlights, in the order the source sets them; the first gets focus. */
    private static final List<String> ADDRESS_FIELDS = List.of("addr", "city", "state", "zip");

    private AddressValidationClient client;
    private StateService stateService;

    @BeforeEach
    void setUp() {
        // Plain mocks (no MockitoExtension): stubbing is per test, and the disabled case, which must not
        // touch either mock, is not tripped by unused-stub strictness.
        client = mock(AddressValidationClient.class);
        stateService = mock(StateService.class);
    }

    // ---------------------------------------------------------------------------------------------
    // Fixture helpers
    // ---------------------------------------------------------------------------------------------

    /** Wraps one client in the {@link ObjectProvider} the service resolves it through. */
    private static ObjectProvider<AddressValidationClient> provider(AddressValidationClient c) {
        return new StaticListableBeanFactory(Map.of("c", c)).getBeanProvider(AddressValidationClient.class);
    }

    /**
     * Builds the module settings. The password is always empty: no credential, real or fake, is needed
     * by any case here.
     */
    private static AddressValidationProperties props(
            boolean enabled, Client clientType, String baseUrl, String userId) {
        return new AddressValidationProperties(
                enabled, clientType, new Usps(baseUrl, userId, "", TIMEOUT, TIMEOUT));
    }

    /** Settings for the mocked-client and stub cases: enabled, stub type, default URL never contacted. */
    private static AddressValidationProperties enabledStubProps() {
        return props(true, Client.STUB, UNUSED_BASE_URL, "");
    }

    /** The service under test, built with its real constructor. */
    private AddressStandardizationService service(
            AddressValidationClient c, AddressValidationProperties properties) {
        return new AddressStandardizationService(properties, provider(c), stateService);
    }

    /** The service over the mocked client with standardization enabled. */
    private AddressStandardizationService mockedService() {
        return service(client, enabledStubProps());
    }

    /** Runs the mocked service once and returns the request the client received. */
    private AddressValidationRequest capturedRequestFor(Address in) {
        mockedService().standardize(in);
        ArgumentCaptor<AddressValidationRequest> request =
                ArgumentCaptor.forClass(AddressValidationRequest.class);
        verify(client).validate(request.capture());
        return request.getValue();
    }

    @Test
    @DisplayName("the 38-character street literals have the lengths the scenarios rely on")
    void streetLiteralsHaveExpectedLengths() {
        assertThat(STREET_38).hasSize(38);
        assertThat(STREET_38_FIRST_30).hasSize(30);
        assertThat(STREET_38).startsWith(STREET_38_FIRST_30);
    }

    // ---------------------------------------------------------------------------------------------
    // Success: the returned values overwrite the address
    // ---------------------------------------------------------------------------------------------

    @Nested
    @DisplayName("on success (a City was returned)")
    final class OnSuccess {

        @Test
        @DisplayName("ZIP becomes Zip5-Zip4 when a ZIP+4 is returned")
        void composesZipPlus4() {
            when(client.validate(any())).thenReturn(
                    AddressValidationResult.success("", "12 MAIN ST", "OLD LYME", "CT", "06371", "1234"));
            when(stateService.exists("CT")).thenReturn(true);

            AddressStandardizationService.Result result =
                    mockedService().standardize(new Address("12 MAIN STREET", "OLD LYME", "CT", "06371"));

            assertThat(result.standardized()).isTrue();
            assertThat(result.address().zip()).isEqualTo("06371-1234");
        }

        @Test
        @DisplayName("ZIP stays Zip5 when the returned Zip4 is blank")
        void keepsZip5WhenZip4Blank() {
            when(client.validate(any())).thenReturn(
                    AddressValidationResult.success("", "12 MAIN ST", "OLD LYME", "CT", "06371", ""));
            when(stateService.exists("CT")).thenReturn(true);

            AddressStandardizationService.Result result =
                    mockedService().standardize(new Address("12 MAIN STREET", "OLD LYME", "CT", "06371"));

            assertThat(result.standardized()).isTrue();
            assertThat(result.address().zip()).isEqualTo("06371");
        }

        @Test
        @DisplayName("street, city and state are overwritten with the returned Address2, City and State")
        void overwritesAddressCityAndState() {
            when(client.validate(any())).thenReturn(
                    AddressValidationResult.success("", "12 MAIN ST", "LYME", "CT", "06371", "1234"));
            when(stateService.exists("CT")).thenReturn(true);

            AddressStandardizationService.Result result =
                    mockedService().standardize(new Address("12 MAIN STREET", "OLD LYME", "NY", "06371"));

            assertThat(result.standardized()).isTrue();
            assertThat(result.address()).isEqualTo(new Address("12 MAIN ST", "LYME", "CT", "06371-1234"));
            // The standardized-State check runs on the returned state, not on the keyed one.
            verify(stateService).exists("CT");
        }

        @Test
        @DisplayName("a 30-character returned city is cut to the 20-character column")
        void cutsReturnedCityToTwentyCharacters() {
            String city30 = "ABCDEFGHIJKLMNOPQRSTUVWXYZABCD";
            assertThat(city30).hasSize(30);
            when(client.validate(any())).thenReturn(
                    AddressValidationResult.success("", "12 MAIN ST", city30, "CT", "06371", ""));
            when(stateService.exists("CT")).thenReturn(true);

            AddressStandardizationService.Result result =
                    mockedService().standardize(new Address("12 MAIN STREET", "OLD LYME", "CT", "06371"));

            assertThat(result.address().city())
                    .isEqualTo("ABCDEFGHIJKLMNOPQRST")
                    .hasSize(AddressStandardizationService.CITY_COLUMN_WIDTH);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Request: what Edit_Address puts into AdrIn
    // ---------------------------------------------------------------------------------------------

    @Nested
    @DisplayName("the request sent to the client")
    final class Request {

        @BeforeEach
        void answerWithASuccess() {
            when(client.validate(any())).thenReturn(
                    AddressValidationResult.success("", "12 MAIN ST", "OLD LYME", "CT", "06371", ""));
            when(stateService.exists("CT")).thenReturn(true);
        }

        @Test
        @DisplayName("leaves Address1 and Zip4 blank, sends city and state, and only the first five ZIP characters")
        void mapsTheAddressOntoTheRequest() {
            AddressValidationRequest request =
                    capturedRequestFor(new Address("12 MAIN STREET", "OLD LYME", "CT", "06371-9999"));

            assertThat(request.address1()).isEmpty();
            assertThat(request.address2()).isEqualTo("12 MAIN STREET");
            assertThat(request.city()).isEqualTo("OLD LYME");
            assertThat(request.state()).isEqualTo("CT");
            assertThat(request.zip5()).isEqualTo("06371");
            assertThat(request.zip4()).isEmpty();
        }

        @Test
        @DisplayName("uppercases and trims every value before it is cut")
        void uppercasesAndTrimsBeforeTheCut() {
            AddressValidationRequest request =
                    capturedRequestFor(new Address("  12 main street ", " old lyme ", " ct ", " 06371-9999"));

            assertThat(request).isEqualTo(
                    new AddressValidationRequest("", "12 MAIN STREET", "OLD LYME", "CT", "06371", ""));
        }

        @Test
        @DisplayName("sends a 30-character street intact")
        void sendsThirtyCharacterStreetIntact() {
            AddressValidationRequest request =
                    capturedRequestFor(new Address(STREET_38_FIRST_30, "CEDAR BLUFF", "OR", "97999"));

            assertThat(request.address2()).isEqualTo(STREET_38_FIRST_30);
        }

        @Test
        @DisplayName("sends only the first 30 characters of a 38-character street")
        void cutsThirtyEightCharacterStreetToThirty() {
            AddressValidationRequest request =
                    capturedRequestFor(new Address(STREET_38, "CEDAR BLUFF", "OR", "97999"));

            assertThat(request.address2()).isEqualTo(STREET_38_FIRST_30);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Failures
    // ---------------------------------------------------------------------------------------------

    @Nested
    @DisplayName("on failure")
    final class OnFailure {

        @Test
        @DisplayName("an address error raises DEM9898 with the USPS description on addr, city, state and zip, addr first")
        void addressErrorRaisesDem9898OnFourFields() {
            when(client.validate(any())).thenReturn(AddressValidationResult.error(
                    "", "", "", "", "", "", -2147219401, "clsAMS", "Address Not Found."));
            AddressStandardizationService service = mockedService();
            Address in = new Address("1 NOWHERE LANE", "OLD LYME", "CT", "06371");

            assertThatThrownBy(() -> service.standardize(in))
                    .isInstanceOfSatisfying(CustomerValidationException.class, e -> {
                        assertThat(e.code()).isEqualTo("DEM9898");
                        assertThat(e.rule()).isEqualTo(CustomerValidationException.ADDRESS_RULE);
                        assertThat(e.args()).containsExactly("Address Not Found.");
                        assertThat(e.errors()).extracting(FieldError::field)
                                .containsExactlyElementsOf(ADDRESS_FIELDS);
                        assertThat(e.errors()).extracting(FieldError::code)
                                .containsOnly("DEM9898");
                    });
            // The state of a rejected address is never looked up.
            verifyNoInteractions(stateService);
        }

        @Test
        @DisplayName("a standardized state absent from STATES raises DEM0503 on state alone")
        void standardizedStateAbsentRaisesDem0503() {
            when(client.validate(any())).thenReturn(
                    AddressValidationResult.success("", "12 MAIN ST", "OLD LYME", "ZZ", "06371", ""));
            when(stateService.exists("ZZ")).thenReturn(false);
            AddressStandardizationService service = mockedService();
            Address in = new Address("12 MAIN STREET", "OLD LYME", "CT", "06371");

            assertThatThrownBy(() -> service.standardize(in))
                    .isInstanceOfSatisfying(CustomerValidationException.class, e -> {
                        assertThat(e.code()).isEqualTo("DEM0503");
                        assertThat(e.rule()).isEqualTo(CustomerValidationException.ADDRESS_RULE);
                        assertThat(e.args()).isEmpty();
                        assertThat(e.errors()).containsExactly(new FieldError("state", "DEM0503"));
                    });
            verify(stateService).exists("ZZ");
        }

        @Test
        @DisplayName("a service fault propagates unchanged and never becomes DEM9898")
        void serviceFaultPropagatesUnchanged() {
            AddressServiceUnavailableException fault = new AddressServiceUnavailableException("USPS call failed");
            when(client.validate(any())).thenThrow(fault);
            AddressStandardizationService service = mockedService();
            Address in = new Address("12 MAIN STREET", "OLD LYME", "CT", "06371");

            assertThatThrownBy(() -> service.standardize(in))
                    .isSameAs(fault)
                    .isNotInstanceOf(CustomerValidationException.class);
            verifyNoInteractions(stateService);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Disabled
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("with standardization disabled the input is returned unchanged and nothing is called")
    void disabledReturnsInputWithoutCallingAnything() {
        Address in = new Address("12 MAIN STREET", "OLD LYME", "CT", "06371");

        AddressStandardizationService.Result result =
                service(client, props(false, Client.STUB, UNUSED_BASE_URL, "")).standardize(in);

        assertThat(result.standardized()).isFalse();
        assertThat(result.address()).isSameAs(in);
        verifyNoInteractions(client, stateService);
    }

    // ---------------------------------------------------------------------------------------------
    // A 38-character street through the real clients
    // ---------------------------------------------------------------------------------------------

    @Nested
    @DisplayName("a 38-character street through the real clients")
    final class ThirtyEightCharacterStreet {

        /** Path of the Web Tools endpoint the loopback fake serves. */
        private static final String ENDPOINT_PATH = "/ShippingAPI.dll";

        /** Extracts the street line from the decoded request document. */
        private static final Pattern ADDRESS2 = Pattern.compile("<Address2>(.*?)</Address2>", Pattern.DOTALL);

        /** The fake's canned Web Tools answer: a standardized street with ZIP+4 4321. */
        private static final String CANNED_RESPONSE = "<AddressValidateResponse><Address ID=\"0\">"
                + "<Address1></Address1>"
                + "<Address2>" + STANDARDIZED_STREET + "</Address2>"
                + "<City>PORTLAND</City><State>OR</State><Zip5>97266</Zip5><Zip4>4321</Zip4>"
                + "</Address></AddressValidateResponse>";

        @Test
        @DisplayName("the Web Tools client sends its first 30 characters as Address2 and the street is overwritten")
        void webToolsClientSendsFirstThirtyCharacters() throws IOException {
            AtomicInteger requests = new AtomicInteger();
            AtomicReference<String> sentAddress2 = new AtomicReference<>();
            AtomicReference<String> sentApi = new AtomicReference<>();
            AtomicReference<Throwable> handlerFailure = new AtomicReference<>();

            // Bound to the IPv4 literal, not InetAddress.getLoopbackAddress(): with IPv6 preferred that
            // returns ::1, and the fake would then not listen on the 127.0.0.1 the base URL names.
            HttpServer server = HttpServer.create(
                    new InetSocketAddress(InetAddress.getByAddress(new byte[] {127, 0, 0, 1}), 0), 0);
            server.createContext(ENDPOINT_PATH, exchange -> {
                requests.incrementAndGet();
                try {
                    recordRequest(exchange, sentApi, sentAddress2);
                    reply(exchange, 200, CANNED_RESPONSE);
                } catch (RuntimeException | IOException e) {
                    // Assertions run on the test thread; the handler only records what went wrong.
                    handlerFailure.set(e);
                    reply(exchange, 500, "");
                }
            });
            server.start();
            try {
                String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + ENDPOINT_PATH;
                AddressValidationProperties properties = props(true, Client.USPS, baseUrl, "TESTUSER");
                when(stateService.exists("OR")).thenReturn(true);

                AddressStandardizationService.Result result;
                try (UspsWebToolsAddressValidationClient webTools =
                        new UspsWebToolsAddressValidationClient(properties)) {
                    result = service(webTools, properties)
                            .standardize(new Address(STREET_38, "PORTLAND", "OR", "97266"));
                }

                assertThat(handlerFailure.get()).as("failure inside the fake USPS handler").isNull();
                assertThat(requests.get()).as("requests received by the fake").isEqualTo(1);
                assertThat(sentApi.get()).isEqualTo("Verify");
                assertThat(sentAddress2.get()).isEqualTo(STREET_38_FIRST_30);
                assertThat(result.standardized()).isTrue();
                assertThat(result.address().addr()).isEqualTo(STANDARDIZED_STREET);
                assertThat(result.address().city()).isEqualTo("PORTLAND");
                assertThat(result.address().state()).isEqualTo("OR");
                assertThat(result.address().zip()).isEqualTo("97266-4321");
            } finally {
                server.stop(0);
            }
        }

        @Test
        @DisplayName("the stub matches the fixture keyed on its first 30 characters and the street is overwritten")
        void stubMatchesFixtureKeyedOnFirstThirtyCharacters() throws IOException {
            Map<?, ?> fixture = bundledFixtureFor(STREET_38_FIRST_30);
            Map<?, ?> input = (Map<?, ?>) fixture.get("input");
            Map<?, ?> output = (Map<?, ?>) fixture.get("output");
            String outputState = text(output, "state");
            String outputZip4 = text(output, "zip4");
            when(stateService.exists(outputState)).thenReturn(true);

            AddressStandardizationService.Result result =
                    service(new StubAddressValidationClient(), enabledStubProps()).standardize(new Address(
                            STREET_38, text(input, "city"), text(input, "state"), text(input, "zip5")));

            String expectedZip = outputZip4.isBlank()
                    ? text(output, "zip5")
                    : text(output, "zip5") + "-" + outputZip4;
            assertThat(result.standardized()).isTrue();
            assertThat(result.address().addr())
                    .isEqualTo(text(output, "address2"))
                    .isEqualTo(STANDARDIZED_STREET);
            assertThat(result.address().city()).isEqualTo(
                    AddressStandardizationService.cut(text(output, "city"),
                            AddressStandardizationService.CITY_COLUMN_WIDTH));
            assertThat(result.address().state()).isEqualTo(outputState);
            assertThat(result.address().zip()).isEqualTo(expectedZip);
        }

        /**
         * Records the {@code API} value and the decoded {@code Address2} of one Web Tools request. The
         * query is {@code API=Verify&XML=<url-encoded document>}; the document itself holds no raw
         * {@code &}, because the encoder escapes it.
         */
        private static void recordRequest(
                HttpExchange exchange, AtomicReference<String> api, AtomicReference<String> address2) {
            String rawQuery = exchange.getRequestURI().getRawQuery();
            if (rawQuery == null) {
                throw new IllegalStateException("request has no query");
            }
            for (String parameter : rawQuery.split("&")) {
                if (parameter.startsWith("API=")) {
                    api.set(URLDecoder.decode(parameter.substring("API=".length()), StandardCharsets.UTF_8));
                } else if (parameter.startsWith("XML=")) {
                    String document =
                            URLDecoder.decode(parameter.substring("XML=".length()), StandardCharsets.UTF_8);
                    Matcher matcher = ADDRESS2.matcher(document);
                    if (!matcher.find()) {
                        throw new IllegalStateException("request document has no Address2 element");
                    }
                    address2.set(matcher.group(1));
                }
            }
        }

        /** Sends {@code body} as {@code text/xml} with {@code status} and closes the exchange. */
        private static void reply(HttpExchange exchange, int status, String body) throws IOException {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/xml; charset=UTF-8");
            exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        }

        /**
         * Reads the stub's bundled fixtures from the classpath, as the stub itself does, and returns the
         * one whose {@code input.address2} is {@code address2}.
         */
        private static Map<?, ?> bundledFixtureFor(String address2) throws IOException {
            String json = new ClassPathResource("stub/usps-stub-fixtures.json")
                    .getContentAsString(StandardCharsets.UTF_8);
            for (Object element : JsonParserFactory.getJsonParser().parseList(json)) {
                if (element instanceof Map<?, ?> fixture
                        && fixture.get("input") instanceof Map<?, ?> input
                        && address2.equals(input.get("address2"))
                        && fixture.get("output") instanceof Map<?, ?>) {
                    return fixture;
                }
            }
            throw new AssertionError(
                    "stub/usps-stub-fixtures.json has no fixture with input.address2 \"" + address2 + "\"");
        }

        /** Returns a string member of a fixture object, failing clearly when it is absent. */
        private static String text(Map<?, ?> object, String member) {
            Object value = object.get(member);
            if (!(value instanceof String s)) {
                throw new AssertionError("fixture member \"" + member + "\" is missing or not a string");
            }
            return s;
        }
    }
}
