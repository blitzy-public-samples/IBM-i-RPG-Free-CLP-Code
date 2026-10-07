package com.democorp.customermaster.address;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Typed settings of the address-validation module, bound from {@value #PREFIX}.
 *
 * <p>This record replaces three things the IBM i address service relied on:
 * <ul>
 *   <li>the data areas that held the USPS Web Tools user id and password, read and
 *       trimmed on every call, now {@link Usps#userId()} and {@link Usps#password()};</li>
 *   <li>the endpoint URL hard-coded in the HTTP call, now {@link Usps#baseUrl()};</li>
 *   <li>the bind-time choice of the address service program through its binding
 *       directory, now {@link #client()}, which {@link AddressValidationAutoConfiguration}
 *       reads to register exactly one {@link AddressValidationClient} bean.</li>
 * </ul>
 *
 * <h2>Properties</h2>
 * <table>
 *   <caption>Properties, the environment variables customer-api maps onto them, and defaults</caption>
 *   <tr><th>Property</th><th>Environment variable</th><th>Default</th></tr>
 *   <tr><td>{@code customer-master.address.enabled}</td><td>{@code ADDRESS_VALIDATION_ENABLED}</td><td>{@code true}</td></tr>
 *   <tr><td>{@code customer-master.address.client}</td><td>{@code ADDRESS_VALIDATION_CLIENT}</td><td>{@code stub}</td></tr>
 *   <tr><td>{@code customer-master.address.usps.base-url}</td><td>{@code USPS_BASE_URL}</td><td>{@value #DEFAULT_BASE_URL}</td></tr>
 *   <tr><td>{@code customer-master.address.usps.user-id}</td><td>{@code USPS_USER_ID}</td><td>empty</td></tr>
 *   <tr><td>{@code customer-master.address.usps.password}</td><td>{@code USPS_PASSWORD}</td><td>empty</td></tr>
 *   <tr><td>{@code customer-master.address.usps.connect-timeout}</td><td>{@code USPS_CONNECT_TIMEOUT}</td><td>{@code 5s}</td></tr>
 *   <tr><td>{@code customer-master.address.usps.read-timeout}</td><td>{@code USPS_READ_TIMEOUT}</td><td>{@code 10s}</td></tr>
 * </table>
 * The environment variables reach these properties through {@code ${ENV:default}}
 * placeholders in customer-api's {@code application.yml}. The defaults declared here
 * equal those placeholder defaults, so the module behaves the same when it is used
 * without that file, as in its own tests. The user id and the password deliberately
 * have no default: no credential is ever carried in source or configuration files.
 *
 * <h2>Registration</h2>
 * The record carries no stereotype annotation and is not found by any properties
 * scan. {@link AddressValidationAutoConfiguration} is its only registrar, through
 * {@code @EnableConfigurationProperties(AddressValidationProperties.class)}, so it is
 * bound and validated only in a context that contains the auto-configuration.
 *
 * <h2>Validation (fail fast)</h2>
 * Binding uses this record's {@link ConstructorBinding} constructor, which reads
 * {@code client} as text, and the canonical constructors, whose compact forms validate
 * the values. Any exception they throw aborts application startup:
 * <ul>
 *   <li>a {@code client} value other than {@code stub} or {@code usps} in any letter
 *       case, exactly as written, raises {@link IllegalArgumentException}. This covers
 *       an unknown value such as {@code foo}, an empty value, and a value with
 *       surrounding blanks or separators such as {@code " stub "} or {@code us-ps}:
 *       {@code @ConditionalOnProperty} compares the same text ignoring letter case only,
 *       so such a value would otherwise select no client bean;</li>
 *   <li>{@code client=usps} with a blank {@code usps.user-id} raises
 *       {@link IllegalStateException};</li>
 *   <li>a timeout that is zero or negative, a base URL that is not an absolute
 *       {@code http}/{@code https} URL, or a base URL whose explicit port is not
 *       between 1 and 65535, raises {@link IllegalArgumentException}.</li>
 * </ul>
 * Bean Validation is not used, because this library keeps no validator on its classpath.
 *
 * <h2>Before setting {@code client=usps}</h2>
 * USPS retired the Web Tools APIs, including the {@code AddressValidateRequest}
 * endpoint at {@value #DEFAULT_BASE_URL}, on 2026-01-25. The real client implements
 * the XML contract the original service documented and keeps the base URL
 * configurable, but it cannot be assumed to reach a live endpoint. Complete the
 * pre-enablement checks in {@code customer-master/docs/developer-guide.md} (endpoint,
 * registration and licensing, contract differences, credential exposure in the query
 * string, the success test) before switching the client from the default stub.
 *
 * @param enabled whether customer-api standardizes addresses during review. When
 *                {@code false}, customer-api skips standardization entirely and the
 *                maintenance flow is exactly the field-rule flow without address
 *                standardization; the client bean is still registered.
 * @param client  which {@link AddressValidationClient} implementation is registered;
 *                bound from {@code stub} or {@code usps} in any letter case
 *                ({@code stub}, {@code STUB}, {@code usps}), exactly as written
 * @param usps    settings of the USPS Web Tools client; always present, populated
 *                with its defaults when no {@code usps.*} property is set
 */
@ConfigurationProperties(AddressValidationProperties.PREFIX)
public record AddressValidationProperties(
        boolean enabled,
        Client client,
        Usps usps) {

    /** Property prefix of this record. */
    public static final String PREFIX = "customer-master.address";

    /**
     * The endpoint the original service called, kept as the default for
     * {@code customer-master.address.usps.base-url}. Retired by USPS on 2026-01-25.
     */
    public static final String DEFAULT_BASE_URL = "https://secure.shippingapis.com/ShippingAPI.dll";

    /** Default connect timeout of the USPS client ({@code 5s}). */
    public static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(5);

    /** Default read timeout of the USPS client ({@code 10s}). */
    public static final Duration DEFAULT_READ_TIMEOUT = Duration.ofSeconds(10);

    /**
     * Text {@link Usps#toString()} shows in place of a configured user id or password
     * and of the user-info and query of the base URL.
     */
    static final String MASK = "****";

    /**
     * Validates the bound settings.
     *
     * @throws IllegalArgumentException when {@code client} is {@code null}
     * @throws IllegalStateException    when {@code client} is {@link Client#USPS} and no
     *                                  user id is configured
     */
    public AddressValidationProperties {
        if (client == null) {
            throw new IllegalArgumentException(PREFIX + ".client must be stub or usps");
        }
        if (usps == null) {
            usps = Usps.defaults();
        }
        if (client == Client.USPS && usps.userId().isBlank()) {
            throw new IllegalStateException(
                    PREFIX + ".usps.user-id must be set when " + PREFIX + ".client=usps");
        }
    }

    /**
     * Binds the settings from configuration; Spring Boot uses this constructor, not the
     * canonical one. {@code client} arrives as the configured text and is accepted only
     * when it equals {@code stub} or {@code usps} ignoring letter case, exactly as
     * written: the comparison {@link AddressValidationAutoConfiguration}'s
     * {@code @ConditionalOnProperty} conditions apply to the same text. A value that
     * binds therefore always selects exactly one client bean, and any other value fails
     * startup instead of leaving the context without a client. The parsed values then
     * pass the checks of the canonical constructor.
     *
     * @param enabled whether customer-api standardizes addresses during review
     * @param client  {@code stub} or {@code usps} in any letter case, with no surrounding
     *                blanks or separators; {@code stub} when the property is absent
     * @param usps    settings of the USPS Web Tools client; populated with its defaults
     *                when no {@code usps.*} property is set
     * @throws IllegalArgumentException when {@code client} is any other text, including
     *                                  an empty one; the message names the property but
     *                                  never echoes the value
     * @throws IllegalStateException    when {@code client} is {@code usps} and no user id
     *                                  is configured
     */
    @ConstructorBinding
    public AddressValidationProperties(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("stub") String client,
            @DefaultValue Usps usps) {
        this(enabled, parseClient(client), usps);
    }

    /**
     * Parses the configured client selector with {@link String#equalsIgnoreCase(String)}
     * and no trimming or other normalization, the comparison
     * {@code @ConditionalOnProperty(havingValue = ...)} applies.
     *
     * @param raw the configured text, or {@code null} when a caller passes none
     * @return the selected client
     * @throws IllegalArgumentException when {@code raw} is not {@code stub} or
     *                                  {@code usps} in any letter case; the message
     *                                  never echoes the value
     */
    private static Client parseClient(String raw) {
        if ("stub".equalsIgnoreCase(raw)) {
            return Client.STUB;
        }
        if ("usps".equalsIgnoreCase(raw)) {
            return Client.USPS;
        }
        throw new IllegalArgumentException(PREFIX + ".client must be stub or usps");
    }

    /**
     * The {@link AddressValidationClient} implementations the auto-configuration can
     * register. The binding constructor selects a value only when the configured text
     * equals {@code stub} or {@code usps} ignoring letter case, exactly as written (no
     * surrounding blanks, no separators, not empty), which is the comparison
     * {@code @ConditionalOnProperty} applies, so a value that binds always selects
     * exactly one bean. Spring Boot's lenient enum conversion is deliberately not used:
     * it trims the text and drops separators, so it would accept values such as
     * {@code " stub "} or {@code us-ps} that select no bean.
     */
    public enum Client {

        /**
         * {@link StubAddressValidationClient}: deterministic fixtures, no network
         * access. The default for local runs, Compose and every test suite.
         */
        STUB,

        /**
         * {@link UspsWebToolsAddressValidationClient}: the USPS Web Tools XML API at
         * {@link Usps#baseUrl()}. Requires {@link Usps#userId()}.
         */
        USPS
    }

    /**
     * Settings of the USPS Web Tools client, bound from {@code customer-master.address.usps}.
     *
     * <p>The compact constructor normalizes and validates the values: a {@code null}
     * string becomes {@code ""}; the user id and the password are stripped of
     * surrounding whitespace, as the original service trimmed the data-area values
     * before every call; a blank base URL (for example an empty {@code USPS_BASE_URL})
     * falls back to {@link #DEFAULT_BASE_URL}. The password is sent with every request
     * even though the original service noted that USPS appeared to ignore it.
     *
     * <p>{@link #toString()} never prints the user id, the password, or the user-info
     * or query of the base URL, any of which can carry a credential.
     *
     * @param baseUrl        endpoint of the Web Tools {@code ShippingAPI.dll}
     *                       ({@code USPS_BASE_URL}); an absolute {@code http} or
     *                       {@code https} URL without a fragment, whose port, when
     *                       given, lies between 1 and 65535
     * @param userId         Web Tools user id ({@code USPS_USER_ID}); no default, required
     *                       when {@code client=usps}
     * @param password       Web Tools password ({@code USPS_PASSWORD}); no default
     * @param connectTimeout TCP connect timeout ({@code USPS_CONNECT_TIMEOUT}, default
     *                       {@code 5s}); must be positive
     * @param readTimeout    response timeout ({@code USPS_READ_TIMEOUT}, default
     *                       {@code 10s}); must be positive
     */
    public record Usps(
            @DefaultValue(DEFAULT_BASE_URL) String baseUrl,
            String userId,
            String password,
            @DefaultValue("5s") Duration connectTimeout,
            @DefaultValue("10s") Duration readTimeout) {

        /**
         * Normalizes and validates the bound values.
         *
         * @throws IllegalArgumentException when a timeout is {@code null}, zero or
         *                                  negative, the base URL is not an
         *                                  absolute {@code http}/{@code https} URL,
         *                                  or its explicit port is not between 1
         *                                  and 65535
         */
        public Usps {
            baseUrl = (baseUrl == null || baseUrl.isBlank()) ? DEFAULT_BASE_URL : baseUrl.strip();
            requireHttpUrl(baseUrl);
            userId = (userId == null) ? "" : userId.strip();
            password = (password == null) ? "" : password.strip();
            requirePositive(connectTimeout, PREFIX + ".usps.connect-timeout");
            requirePositive(readTimeout, PREFIX + ".usps.read-timeout");
        }

        /**
         * The settings used when no {@code customer-master.address.usps.*} property is
         * bound: the default base URL, no credentials, and the default timeouts.
         *
         * @return the default USPS settings
         */
        public static Usps defaults() {
            return new Usps(DEFAULT_BASE_URL, "", "", DEFAULT_CONNECT_TIMEOUT, DEFAULT_READ_TIMEOUT);
        }

        /**
         * Describes the settings with every credential masked, so the record can appear
         * in logs, failure analysis and test output without exposing one. A configured
         * user id or password shows as {@value AddressValidationProperties#MASK}; an
         * empty one shows as {@code ""}, which tells an operator that it is missing. The
         * base URL shows its scheme, host, port and path only: user-info, if present,
         * shows as {@value AddressValidationProperties#MASK} before the {@code @}, and a
         * query, even an empty one, as {@value AddressValidationProperties#MASK} after
         * the {@code ?}, because either part can carry a gateway credential.
         * {@link #baseUrl()} still returns the URL as configured.
         *
         * @return the masked description
         */
        @Override
        public String toString() {
            return "Usps[baseUrl=" + redactUrl(baseUrl)
                    + ", userId=" + mask(userId)
                    + ", password=" + mask(password)
                    + ", connectTimeout=" + connectTimeout
                    + ", readTimeout=" + readTimeout + "]";
        }

        /**
         * Masks a credential for display.
         *
         * @param credential the normalized (never {@code null}) credential
         * @return {@value AddressValidationProperties#MASK} when set, {@code ""} when empty
         */
        private static String mask(String credential) {
            return credential.isEmpty() ? "\"\"" : MASK;
        }

        /**
         * Redacts the base URL for display. The scheme, the host (an IPv6 literal keeps
         * its brackets), the port when one is set and the raw path are kept. User-info
         * becomes {@value AddressValidationProperties#MASK} followed by {@code @}, and a
         * query, including an empty one, becomes {@code ?} followed by
         * {@value AddressValidationProperties#MASK}. A fragment never occurs, because
         * {@link #requireHttpUrl(String)} rejects it.
         *
         * @param url the normalized and validated base URL
         * @return the redacted URL, or {@value AddressValidationProperties#MASK} alone
         *         when the value cannot be parsed into a scheme and a host, so the raw
         *         value is never echoed
         */
        private static String redactUrl(String url) {
            URI uri;
            try {
                uri = new URI(url);
            } catch (URISyntaxException ex) {
                return MASK;
            }
            if (uri.getScheme() == null || uri.getHost() == null) {
                return MASK;
            }
            StringBuilder redacted = new StringBuilder(uri.getScheme()).append("://");
            if (uri.getRawUserInfo() != null) {
                redacted.append(MASK).append('@');
            }
            redacted.append(uri.getHost());
            if (uri.getPort() != -1) {
                redacted.append(':').append(uri.getPort());
            }
            redacted.append(uri.getRawPath());
            if (uri.getRawQuery() != null) {
                redacted.append('?').append(MASK);
            }
            return redacted.toString();
        }

        /**
         * Rejects a missing, zero or negative timeout; the JDK HTTP client accepts
         * neither as a connect or read timeout.
         *
         * @param timeout  the bound timeout
         * @param property the property name reported in the exception message
         */
        private static void requirePositive(Duration timeout, String property) {
            if (timeout == null || timeout.isZero() || timeout.isNegative()) {
                throw new IllegalArgumentException(property + " must be positive");
            }
        }

        /**
         * Rejects a base URL the client cannot extend. The client appends
         * {@code ?API=Verify&XML=...} (or {@code &...} when a query is already present),
         * so the value must be an absolute {@code http}/{@code https} URL with a host and
         * no fragment. A port, when one is given, must lie between 1 and 65535; the URI
         * parser also accepts 0 and values above 65535, to which the JDK HTTP client
         * cannot connect. Checking it here fails startup instead of the first address
         * review. The value itself is left out of the message, because an operator could
         * have placed credentials in its user-info or query part.
         *
         * @param url the normalized base URL
         */
        private static void requireHttpUrl(String url) {
            String property = PREFIX + ".usps.base-url";
            URI uri;
            try {
                uri = new URI(url);
            } catch (URISyntaxException ex) {
                throw new IllegalArgumentException(property + " is not a valid URL");
            }
            String scheme = uri.getScheme();
            boolean http = "http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme);
            if (!http || uri.getHost() == null || uri.getRawFragment() != null) {
                throw new IllegalArgumentException(
                        property + " must be an absolute http or https URL with a host and no fragment");
            }
            // -1 means no port was given, an empty one ("https://host:/path") included. The
            // parser keeps any other decimal port that fits an int, out of range or not.
            int port = uri.getPort();
            if (port != -1 && (port < 1 || port > 65535)) {
                throw new IllegalArgumentException(property + " must have a port between 1 and 65535");
            }
        }
    }
}
