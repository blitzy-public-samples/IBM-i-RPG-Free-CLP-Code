package com.democorp.customermaster.address;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import com.democorp.customermaster.address.AddressValidationProperties.Client;
import com.democorp.customermaster.address.AddressValidationProperties.Usps;

/**
 * Specifies the masked descriptions and the value-free validation errors of
 * {@link AddressValidationProperties} and its nested {@link Usps} settings.
 *
 * <p>The base URL stays configurable for a compatible gateway, so user-info and a query
 * are accepted and {@link Usps#baseUrl()} returns them intact. Either part can carry a
 * credential, so {@link Usps#toString()}, which the outer record's description embeds,
 * shows each one as {@code ****} and keeps only the scheme, host, port and path.
 *
 * <p><b>Test values.</b> Every user name, password and token is fictitious. A URL that
 * carries user-info is assembled at run time, so no source line holds a complete URL
 * with an embedded credential.
 *
 * <p>Pure JUnit 5 and AssertJ over Spring Boot's {@link Binder}: no application
 * context, no network.
 */
@DisplayName("AddressValidationProperties: masked descriptions and value-free validation")
final class AddressValidationPropertiesTest {

    /** Fictitious gateway user name placed in the base URL's user-info. */
    private static final String URL_USER = "gwuser";

    /** Fictitious gateway password placed in the base URL's user-info. */
    private static final String URL_PASSWORD = "S3cr3tPw";

    /** Fictitious gateway token placed in the base URL's query. */
    private static final String QUERY_SECRET = "QuerySecret42";

    /** Fictitious secret placed in a fragment, which validation rejects. */
    private static final String FRAGMENT_SECRET = "FragmentSecret7";

    /** User-info of the test URLs, joined at run time. */
    private static final String USER_INFO = String.join(":", URL_USER, URL_PASSWORD);

    /** A base URL whose user-info carries a user name and a password. */
    private static final String USER_INFO_URL =
            "https://" + USER_INFO + "@gateway.example.test:8443/ShippingAPI.dll";

    /** A base URL whose query carries a token. */
    private static final String QUERY_URL =
            "https://gateway.example.test/ShippingAPI.dll?token=" + QUERY_SECRET;

    /** A base URL carrying secrets in both its user-info and its query. */
    private static final String BOTH_URL =
            "https://" + USER_INFO + "@gateway.example.test:8443/ShippingAPI.dll?token=" + QUERY_SECRET;

    /** The description of {@link #BOTH_URL}. */
    private static final String BOTH_REDACTED = "https://****@gateway.example.test:8443/ShippingAPI.dll?****";

    /** Fictitious value of the dedicated user id setting. */
    private static final String USER_ID = "test-user-id";

    /** Fictitious value of the dedicated password setting. */
    private static final String PASSWORD = "test-password";

    /** Message of every base URL rejected after parsing. */
    private static final String NOT_HTTP_MESSAGE = AddressValidationProperties.PREFIX
            + ".usps.base-url must be an absolute http or https URL with a host and no fragment";

    /** Message of a base URL that is not a valid URI. */
    private static final String NOT_VALID_MESSAGE = AddressValidationProperties.PREFIX
            + ".usps.base-url is not a valid URL";

    /**
     * Configured base URLs that carry user-info or a query, each with its expected
     * description.
     */
    static Stream<Arguments> redactedUrls() {
        return Stream.of(
                Arguments.of("user-info with user name and password", USER_INFO_URL,
                        "https://****@gateway.example.test:8443/ShippingAPI.dll"),
                Arguments.of("user-info with user name only",
                        "https://" + URL_USER + "@gateway.example.test/ShippingAPI.dll",
                        "https://****@gateway.example.test/ShippingAPI.dll"),
                Arguments.of("empty user-info", "https://@gateway.example.test/ShippingAPI.dll",
                        "https://****@gateway.example.test/ShippingAPI.dll"),
                Arguments.of("query token", QUERY_URL,
                        "https://gateway.example.test/ShippingAPI.dll?****"),
                Arguments.of("empty query", "https://gateway.example.test/ShippingAPI.dll?",
                        "https://gateway.example.test/ShippingAPI.dll?****"),
                Arguments.of("user-info and query", BOTH_URL, BOTH_REDACTED),
                Arguments.of("user-info and query on an IPv6 literal",
                        "http://" + USER_INFO + "@[2001:db8::1]:8080/ShippingAPI.dll?token=" + QUERY_SECRET,
                        "http://****@[2001:db8::1]:8080/ShippingAPI.dll?****"),
                Arguments.of("query on a URL without a path",
                        "https://gateway.example.test?token=" + QUERY_SECRET,
                        "https://gateway.example.test?****"));
    }

    // ---------------------------------------------------------------------------------------------
    // Base URL redaction
    // ---------------------------------------------------------------------------------------------

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("redactedUrls")
    @DisplayName("nested and outer descriptions mask user-info and query and keep host, port and path")
    void descriptionsMaskUserInfoAndQuery(String label, String configured, String redacted) {
        Usps usps = usps(configured, USER_ID, PASSWORD);
        AddressValidationProperties properties = new AddressValidationProperties(true, Client.USPS, usps);

        assertThat(usps.toString())
                .startsWith("Usps[baseUrl=" + redacted + ", ")
                .satisfies(AddressValidationPropertiesTest::assertNoSecret);
        assertThat(properties.toString())
                .contains("usps=Usps[baseUrl=" + redacted + ", ")
                .satisfies(AddressValidationPropertiesTest::assertNoSecret);
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("redactedUrls")
    @DisplayName("baseUrl() returns the configured URL with user-info and query intact")
    void accessorKeepsConfiguredUrl(String label, String configured, String redacted) {
        AddressValidationProperties properties =
                new AddressValidationProperties(true, Client.USPS, usps(configured, USER_ID, PASSWORD));

        assertThat(properties.usps().baseUrl()).isEqualTo(configured);
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = {
        AddressValidationProperties.DEFAULT_BASE_URL,
        "http://gateway.example.test:8080/ShippingAPI.dll",
        "https://gateway.example.test",
        "http://[2001:db8::1]:8080/ShippingAPI.dll",
        "https://gateway.example.test/Shipping%20API.dll"
    })
    @DisplayName("a base URL without user-info or query is described unchanged")
    void plainUrlIsDescribedUnchanged(String configured) {
        Usps usps = usps(configured, "", "");

        assertThat(usps.toString()).startsWith("Usps[baseUrl=" + configured + ", ");
        assertThat(new AddressValidationProperties(true, Client.STUB, usps).toString())
                .contains("usps=Usps[baseUrl=" + configured + ", ");
    }

    @Test
    @DisplayName("a surrounding blank is stripped before the URL is stored and described")
    void strippedUrlIsRedacted() {
        Usps usps = usps("  " + BOTH_URL + "  ", USER_ID, PASSWORD);

        assertThat(usps.baseUrl()).isEqualTo(BOTH_URL);
        assertThat(usps.toString()).startsWith("Usps[baseUrl=" + BOTH_REDACTED + ", ");
    }

    // ---------------------------------------------------------------------------------------------
    // Credential masking
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("defaults describe the default URL and both empty credentials as \"\"")
    void defaultsDescription() {
        assertThat(Usps.defaults().toString()).isEqualTo(
                "Usps[baseUrl=https://secure.shippingapis.com/ShippingAPI.dll, userId=\"\", password=\"\","
                        + " connectTimeout=PT5S, readTimeout=PT10S]");
    }

    @Test
    @DisplayName("configured credentials and URL secrets are all masked in the full descriptions")
    void fullDescriptionsMaskEverySecret() {
        Usps usps = usps(BOTH_URL, USER_ID, PASSWORD);
        String nested = "Usps[baseUrl=" + BOTH_REDACTED + ", userId=****, password=****,"
                + " connectTimeout=PT5S, readTimeout=PT10S]";

        assertThat(usps.toString()).isEqualTo(nested);
        assertThat(new AddressValidationProperties(true, Client.USPS, usps).toString())
                .isEqualTo("AddressValidationProperties[enabled=true, client=USPS, usps=" + nested + "]");
        assertThat(usps.userId()).isEqualTo(USER_ID);
        assertThat(usps.password()).isEqualTo(PASSWORD);
    }

    @Test
    @DisplayName("a set user id shows as **** and an empty password as \"\"")
    void mixedCredentials() {
        assertThat(usps(QUERY_URL, USER_ID, "").toString())
                .contains("userId=****, password=\"\",")
                .satisfies(AddressValidationPropertiesTest::assertNoSecret);
    }

    // ---------------------------------------------------------------------------------------------
    // Settings binding
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("settings bound from properties keep the URL intact and describe it masked")
    void boundSettingsHideSecrets() {
        Map<String, String> settings = settings(BOTH_URL);
        settings.put("customer-master.address.usps.connect-timeout", "2s");

        AddressValidationProperties properties = bind(settings);

        assertThat(properties.client()).isEqualTo(Client.USPS);
        assertThat(properties.usps().baseUrl()).isEqualTo(BOTH_URL);
        assertThat(properties.usps().userId()).isEqualTo(USER_ID);
        assertThat(properties.usps().connectTimeout()).isEqualTo(Duration.ofSeconds(2));
        assertThat(properties.usps().readTimeout()).isEqualTo(AddressValidationProperties.DEFAULT_READ_TIMEOUT);
        assertThat(properties.toString())
                .isEqualTo("AddressValidationProperties[enabled=true, client=USPS, usps=Usps[baseUrl="
                        + BOTH_REDACTED + ", userId=****, password=****, connectTimeout=PT2S, readTimeout=PT10S]]")
                .satisfies(AddressValidationPropertiesTest::assertNoSecret);
    }

    // ---------------------------------------------------------------------------------------------
    // Value-free validation errors
    // ---------------------------------------------------------------------------------------------

    /** Base URLs validation rejects, each with the message it must raise. */
    static Stream<Arguments> rejectedUrls() {
        return Stream.of(
                Arguments.of("fragment carrying a secret",
                        QUERY_URL + "#" + FRAGMENT_SECRET, NOT_HTTP_MESSAGE),
                Arguments.of("non-http scheme with user-info",
                        "ftp://" + USER_INFO + "@gateway.example.test/ShippingAPI.dll", NOT_HTTP_MESSAGE),
                Arguments.of("user-info on a host the URI parser cannot read",
                        "https://" + USER_INFO + "@gateway_host.example.test/ShippingAPI.dll", NOT_HTTP_MESSAGE),
                Arguments.of("relative reference with a query",
                        "/ShippingAPI.dll?token=" + QUERY_SECRET, NOT_HTTP_MESSAGE),
                Arguments.of("illegal character inside user-info",
                        "https://" + URL_USER + ":" + URL_PASSWORD + " x@gateway.example.test/ShippingAPI.dll",
                        NOT_VALID_MESSAGE));
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("rejectedUrls")
    @DisplayName("a rejected base URL raises a message that does not echo the value")
    void rejectedUrlMessageIsValueFree(String label, String configured, String message) {
        assertThatThrownBy(() -> usps(configured, USER_ID, PASSWORD))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(message);
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("rejectedUrls")
    @DisplayName("a rejected bound base URL fails binding without echoing the value")
    void rejectedBoundUrlIsValueFree(String label, String configured, String message) {
        assertThatThrownBy(() -> bind(settings(configured)))
                .isInstanceOf(BindException.class)
                .satisfies(AddressValidationPropertiesTest::assertValueFreeFailure)
                .rootCause()
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(message);
    }

    @Test
    @DisplayName("client=usps without a user id fails binding without echoing the configured URL")
    void missingUserIdFailureIsValueFree() {
        Map<String, String> settings = settings(BOTH_URL);
        settings.remove("customer-master.address.usps.user-id");

        assertThatThrownBy(() -> bind(settings))
                .isInstanceOf(BindException.class)
                .satisfies(AddressValidationPropertiesTest::assertValueFreeFailure)
                .rootCause()
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(AddressValidationProperties.PREFIX + ".usps.user-id must be set when "
                        + AddressValidationProperties.PREFIX + ".client=usps");
    }

    // ---------------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------------

    private static Usps usps(String baseUrl, String userId, String password) {
        return new Usps(baseUrl, userId, password,
                AddressValidationProperties.DEFAULT_CONNECT_TIMEOUT, AddressValidationProperties.DEFAULT_READ_TIMEOUT);
    }

    /** Settings that select the USPS client with the given base URL and both credentials. */
    private static Map<String, String> settings(String baseUrl) {
        Map<String, String> settings = new LinkedHashMap<>();
        settings.put("customer-master.address.client", "usps");
        settings.put("customer-master.address.usps.base-url", baseUrl);
        settings.put("customer-master.address.usps.user-id", USER_ID);
        settings.put("customer-master.address.usps.password", PASSWORD);
        return settings;
    }

    private static AddressValidationProperties bind(Map<String, String> settings) {
        return new Binder(new MapConfigurationPropertySource(settings))
                .bind(AddressValidationProperties.PREFIX, AddressValidationProperties.class)
                .get();
    }

    /** Asserts that a description holds none of the fictitious secrets or credentials. */
    private static void assertNoSecret(String description) {
        assertThat(description).doesNotContain(URL_USER, URL_PASSWORD, QUERY_SECRET, "token=",
                FRAGMENT_SECRET, USER_ID, PASSWORD);
    }

    /**
     * Asserts that a binding failure carries no configuration property, whose value the
     * startup failure report would print, and that no message in its cause chain echoes
     * a secret.
     */
    private static void assertValueFreeFailure(Throwable failure) {
        assertThat(((BindException) failure).getProperty()).isNull();
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            assertThat(String.valueOf(cause.getMessage())).satisfies(AddressValidationPropertiesTest::assertNoSecret);
        }
    }
}
