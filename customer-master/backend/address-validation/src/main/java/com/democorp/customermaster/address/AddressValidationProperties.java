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
 * <p>Replaces the data areas that held the USPS Web Tools user id and password, now
 * {@link Usps#userId()} and {@link Usps#password()}; the endpoint URL hard-coded in the
 * HTTP call, now {@link Usps#baseUrl()}; and the bind-time choice of the address service
 * program, now {@link #client()}.
 *
 * <p><b>Defaults.</b> {@code enabled} ({@code ADDRESS_VALIDATION_ENABLED}) defaults to
 * {@code true} and {@code client} ({@code ADDRESS_VALIDATION_CLIENT}) to {@code stub};
 * {@link Usps} lists the {@code usps.*} settings, their variables and defaults. The
 * environment variables reach these properties through {@code ${ENV:default}} placeholders
 * in customer-api's {@code application.yml}, whose defaults equal the ones declared here,
 * so the module behaves the same without that file, as in its own tests. The user id and
 * the password deliberately have no default: no credential is ever carried in source or
 * configuration files.
 *
 * <p><b>Registration.</b> {@link AddressValidationAutoConfiguration} is the only registrar,
 * so the record is bound and validated only in a context that contains it.
 *
 * <h2>Validation (fail fast)</h2>
 * The {@link ConstructorBinding} constructor and the compact canonical constructors
 * validate the values, and any exception they throw aborts application startup:
 * <ul>
 *   <li>a {@code client} text the {@linkplain Client selector rule} rejects, an empty one
 *       included, raises {@link IllegalArgumentException};</li>
 *   <li>{@code client=usps} with a blank {@code usps.user-id} raises
 *       {@link IllegalStateException};</li>
 *   <li>a timeout that is zero or negative, a base URL that is not an absolute
 *       {@code http}/{@code https} URL, or a base URL whose explicit port is not
 *       between 1 and 65535, raises {@link IllegalArgumentException}.</li>
 * </ul>
 * Bean Validation is not used, because this library keeps no validator on its classpath.
 *
 * <p>USPS retired the Web Tools endpoint at {@value #DEFAULT_BASE_URL} on 2026-01-25.
 * Complete the pre-enablement checks in {@code customer-master/docs/developer-guide.md}
 * before setting {@code client=usps}.
 *
 * @param enabled whether customer-api standardizes addresses during review; when
 *                {@code false} it skips standardization and the client bean is still
 *                registered
 * @param client  which {@link AddressValidationClient} implementation is registered
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
     * canonical one. {@code client} arrives as the configured text and is parsed by the
     * {@linkplain Client selector rule}; the parsed values then pass the checks of the
     * canonical constructor.
     *
     * @param enabled whether customer-api standardizes addresses during review
     * @param client  the selector text the {@linkplain Client selector rule} accepts;
     *                {@code stub} when the property is absent
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
     * register.
     *
     * <p><b>Selector rule.</b> The binding constructor accepts the configured text only
     * when it equals {@code stub} or {@code usps} ignoring letter case, exactly as written:
     * no surrounding blanks, no separators, not empty. That is the comparison
     * {@code @ConditionalOnProperty(havingValue = ...)} applies to the same text, so a
     * value that binds always selects exactly one client bean, and any other value fails
     * startup with a message that names the property, never the value. Spring Boot's
     * lenient enum conversion is deliberately not used: it trims the text and drops
     * separators, so it would accept values such as {@code " stub "} or {@code us-ps} that
     * select no bean.
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
