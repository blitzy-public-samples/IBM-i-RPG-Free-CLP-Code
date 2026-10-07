package com.democorp.customermaster.address;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.annotation.ImportCandidates;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;

import com.democorp.customermaster.address.AddressValidationProperties.Client;
import com.democorp.customermaster.address.AddressValidationProperties.Usps;

/**
 * Specifies the bean selection of {@link AddressValidationAutoConfiguration}, which
 * replaces two IBM i mechanisms of the address service:
 * <ul>
 *   <li>the ADRVAL_BND binding directory, which bound the USADRVAL service program to
 *       its callers at bind time [USPS_Address/CRTBNDDIR.CLLE:4-7], now the choice of
 *       exactly one {@link AddressValidationClient} bean, the Java counterpart of the
 *       USAdrVal prototype [Copy_Mbrs/USADRVAL_P.RPGLE:2-4];</li>
 *   <li>the USPS_ID and USPS_PWD data areas read on every call
 *       [USPS_Address/USADRVAL.SQLRPGLE:43-46,66-69], now
 *       {@code customer-master.address.usps.user-id} and {@code .password}, bound into
 *       {@link AddressValidationProperties}.</li>
 * </ul>
 *
 * <p><b>Selection rules under test.</b>
 * <ul>
 *   <li>No property, or {@code customer-master.address.client=stub} in any letter case,
 *       registers {@link StubAddressValidationClient}.</li>
 *   <li>{@code client=usps} registers {@link UspsWebToolsAddressValidationClient}, and
 *       startup fails fast when {@code usps.user-id} is missing or empty, where the
 *       source would have sent an empty {@code USERID} to USPS.</li>
 *   <li>Any other {@code client} text fails startup with a message that names the
 *       property but not the value: an unknown value, an empty one, or {@code stub} and
 *       {@code usps} with surrounding blanks or separators, which the bean conditions
 *       would not match, so the context never starts without a client.</li>
 *   <li>A client bean the application defines itself wins over both, because each
 *       auto-configured bean is {@code @ConditionalOnMissingBean}.</li>
 *   <li>{@code enabled=false} switches standardization off in customer-api only; the
 *       client bean is still registered.</li>
 *   <li>The class is listed in
 *       {@code META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports},
 *       so a consuming application's component scan skips it and the bean is created
 *       once, under auto-configuration ordering and conditions.</li>
 * </ul>
 *
 * <p>The runner holds only the auto-configuration: it is the sole registrar of
 * {@link AddressValidationProperties}, so no {@code @EnableConfigurationProperties} is
 * added here. Pure JUnit 5, AssertJ and {@link ApplicationContextRunner}: no application
 * class, no mocks, and no network. No scenario calls
 * {@link UspsWebToolsAddressValidationClient#validate}; building that client only
 * configures a JDK HTTP client. The one credential value, {@value #TEST_USER_ID}, is
 * fictitious.
 */
@DisplayName("AddressValidationAutoConfiguration: one client bean, chosen by customer-master.address.client")
class AddressValidationAutoConfigurationTest {

    /** Property selecting the client implementation. */
    private static final String CLIENT = AddressValidationProperties.PREFIX + ".client";

    /** Property holding the USPS Web Tools user id. */
    private static final String USER_ID = AddressValidationProperties.PREFIX + ".usps.user-id";

    /** Property switching standardization on or off in customer-api. */
    private static final String ENABLED = AddressValidationProperties.PREFIX + ".enabled";

    /** Fictitious USPS Web Tools user id; the only credential value in this class. */
    private static final String TEST_USER_ID = "TESTUSER123";

    /** Startup failure message when {@code client} is not {@code stub} or {@code usps} as written. */
    private static final String INVALID_CLIENT_MESSAGE = CLIENT + " must be stub or usps";

    /** Startup failure message when {@code client=usps} has no user id. */
    private static final String MISSING_USER_ID_MESSAGE =
            USER_ID + " must be set when " + CLIENT + "=usps";

    /** Fully qualified name the auto-configuration imports file must list. */
    private static final String AUTO_CONFIGURATION_CLASS_NAME =
            "com.democorp.customermaster.address.AddressValidationAutoConfiguration";

    /** Result of {@link #CUSTOM_CLIENT}, recognisable as neither stub nor USPS output. */
    private static final AddressValidationResult CUSTOM_RESULT =
            AddressValidationResult.success("", "1 CUSTOM RD", "OLD HAVEN", "CT", "06399", "");

    /** The client an application defines itself; it must replace the auto-configured one. */
    private static final AddressValidationClient CUSTOM_CLIENT = request -> CUSTOM_RESULT;

    /** A request for exercising {@link #CUSTOM_CLIENT} through the context. */
    private static final AddressValidationRequest REQUEST =
            new AddressValidationRequest("", "1 ANY STREET", "ANYTOWN", "CT", "06399", "");

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AddressValidationAutoConfiguration.class));

    /**
     * An application's own {@link AddressValidationClient}, registered as a user
     * configuration so that it is processed before the auto-configuration, as in a Spring
     * Boot application.
     */
    @Configuration(proxyBeanMethods = false)
    static class UserClientConfiguration {

        @Bean
        AddressValidationClient customClient() {
            return CUSTOM_CLIENT;
        }
    }

    @Nested
    @DisplayName("Default: no customer-master.address property set")
    class Defaults {

        @Test
        @DisplayName("registers exactly one client, the stub")
        void registersTheStubClient() {
            runner.run(context -> {
                assertThat(context).hasNotFailed().hasSingleBean(AddressValidationClient.class);
                assertThat(context.getBean(AddressValidationClient.class))
                        .isInstanceOf(StubAddressValidationClient.class);
                assertThat(context).hasSingleBean(StubAddressValidationClient.class)
                        .doesNotHaveBean(UspsWebToolsAddressValidationClient.class);
            });
        }

        @Test
        @DisplayName("binds the documented defaults: enabled, stub, the Web Tools URL and 5s/10s timeouts")
        void bindsTheDefaultProperties() {
            runner.run(context -> {
                assertThat(context).hasNotFailed().hasSingleBean(AddressValidationProperties.class);
                AddressValidationProperties properties = context.getBean(AddressValidationProperties.class);
                assertThat(properties.enabled()).isTrue();
                assertThat(properties.client()).isEqualTo(Client.STUB);

                Usps usps = properties.usps();
                assertThat(usps).isNotNull();
                assertThat(usps.baseUrl()).isEqualTo("https://secure.shippingapis.com/ShippingAPI.dll");
                assertThat(usps.connectTimeout()).isEqualTo(Duration.ofSeconds(5));
                assertThat(usps.readTimeout()).isEqualTo(Duration.ofSeconds(10));
                assertThat(usps.userId()).isEmpty();
                assertThat(usps.password()).isEmpty();
            });
        }

        @ParameterizedTest(name = "client={0}")
        @ValueSource(strings = {"stub", "STUB", "Stub"})
        @DisplayName("client=stub selects the stub in any letter case")
        void stubIsSelectedIgnoringCase(String value) {
            runner.withPropertyValues(CLIENT + "=" + value).run(context -> {
                assertThat(context).hasNotFailed()
                        .hasSingleBean(AddressValidationClient.class)
                        .hasSingleBean(StubAddressValidationClient.class)
                        .doesNotHaveBean(UspsWebToolsAddressValidationClient.class);
                assertThat(context.getBean(AddressValidationProperties.class).client())
                        .isEqualTo(Client.STUB);
            });
        }

        @Test
        @DisplayName("enabled=false still registers the client; only customer-api skips standardization")
        void disabledStandardizationKeepsTheClientBean() {
            runner.withPropertyValues(ENABLED + "=false").run(context -> {
                assertThat(context).hasNotFailed()
                        .hasSingleBean(AddressValidationClient.class)
                        .hasSingleBean(StubAddressValidationClient.class);
                assertThat(context.getBean(AddressValidationProperties.class).enabled()).isFalse();
            });
        }
    }

    @Nested
    @DisplayName("client=usps: the USPS Web Tools client")
    class UspsClient {

        @Test
        @DisplayName("without a user id, startup fails and names the user-id property")
        void missingUserIdFailsStartup() {
            runner.withPropertyValues(CLIENT + "=usps").run(context -> {
                assertThat(context).hasFailed();
                assertMissingUserIdFailure(context.getStartupFailure());
            });
        }

        @Test
        @DisplayName("with an empty user id, as an empty USPS_USER_ID gives, startup fails")
        void emptyUserIdFailsStartup() {
            runner.withPropertyValues(CLIENT + "=usps", USER_ID + "=").run(context -> {
                assertThat(context).hasFailed();
                assertMissingUserIdFailure(context.getStartupFailure());
            });
        }

        @ParameterizedTest(name = "client={0}")
        @ValueSource(strings = {"usps", "USPS"})
        @DisplayName("with a user id, registers exactly one client, the Web Tools client")
        void userIdRegistersTheWebToolsClient(String value) {
            runner.withPropertyValues(CLIENT + "=" + value, USER_ID + "=" + TEST_USER_ID).run(context -> {
                assertThat(context).hasNotFailed().hasSingleBean(AddressValidationClient.class);
                assertThat(context.getBean(AddressValidationClient.class))
                        .isInstanceOf(UspsWebToolsAddressValidationClient.class);
                assertThat(context).doesNotHaveBean(StubAddressValidationClient.class);

                AddressValidationProperties properties = context.getBean(AddressValidationProperties.class);
                assertThat(properties.client()).isEqualTo(Client.USPS);
                assertThat(properties.usps().userId()).isEqualTo(TEST_USER_ID);
            });
        }

        /**
         * Asserts that the startup failure stems from the missing user id: one message in
         * the cause chain names the {@code user-id} property, and the root cause is the
         * settings' own {@link IllegalStateException}.
         */
        private void assertMissingUserIdFailure(Throwable failure) {
            assertThat(failure).isNotNull();
            assertThat(causeMessages(failure)).anyMatch(message -> message.contains("user-id"));
            assertThat(failure).rootCause()
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage(MISSING_USER_ID_MESSAGE);
        }
    }

    @Nested
    @DisplayName("A client bean defined by the application")
    class UserOverride {

        @Test
        @DisplayName("replaces the default stub")
        void userClientReplacesTheStub() {
            runner.withUserConfiguration(UserClientConfiguration.class).run(context -> {
                assertThat(context).hasNotFailed().hasSingleBean(AddressValidationClient.class);
                AddressValidationClient client = context.getBean(AddressValidationClient.class);
                assertThat(client).isSameAs(CUSTOM_CLIENT);
                assertThat(client.validate(REQUEST)).isEqualTo(CUSTOM_RESULT);
                assertThat(context).doesNotHaveBean(StubAddressValidationClient.class)
                        .doesNotHaveBean(UspsWebToolsAddressValidationClient.class);
            });
        }

        @Test
        @DisplayName("replaces the Web Tools client when client=usps")
        void userClientReplacesTheWebToolsClient() {
            runner.withUserConfiguration(UserClientConfiguration.class)
                    .withPropertyValues(CLIENT + "=usps", USER_ID + "=" + TEST_USER_ID)
                    .run(context -> {
                        assertThat(context).hasNotFailed().hasSingleBean(AddressValidationClient.class);
                        assertThat(context.getBean(AddressValidationClient.class)).isSameAs(CUSTOM_CLIENT);
                        assertThat(context).doesNotHaveBean(UspsWebToolsAddressValidationClient.class)
                                .doesNotHaveBean(StubAddressValidationClient.class);
                    });
        }
    }

    @Nested
    @DisplayName("Invalid selection")
    class InvalidSelection {

        @Test
        @DisplayName("an unknown client value fails startup instead of leaving no client")
        void unknownClientValueFailsStartup() {
            runner.withPropertyValues(CLIENT + "=foo").run(context -> {
                assertThat(context).hasFailed();
                assertThat(causeMessages(context.getStartupFailure()))
                        .anyMatch(message -> message.contains(CLIENT));
            });
        }

        /**
         * Each value matches neither {@code @ConditionalOnProperty} condition, which
         * compares the text as written ignoring letter case only, so binding it would
         * start a context without a client. A user id is set so that the {@code usps}
         * forms cannot fail for its absence instead. The values are supplied through a
         * raw property source, because {@code withPropertyValues} trims them.
         */
        @ParameterizedTest(name = "client=\"{0}\"")
        @ValueSource(strings = {" stub ", " usps", "stub\t", "us-ps", "s_t_u_b", ""})
        @DisplayName("stub or usps not exactly as written, or empty, fails startup without echoing the value")
        void inexactClientValueFailsStartup(String value) {
            runner.withInitializer(context -> context.getEnvironment().getPropertySources().addFirst(
                            new MapPropertySource("raw", Map.of(CLIENT, value, USER_ID, TEST_USER_ID))))
                    .run(context -> {
                        assertThat(context).hasFailed();
                        Throwable failure = context.getStartupFailure();
                        assertThat(failure).rootCause()
                                .isInstanceOf(IllegalArgumentException.class)
                                .hasMessage(INVALID_CLIENT_MESSAGE);
                        assertValueFreeFailure(failure, value);
                    });
        }

        /**
         * Asserts that the startup failure cannot show the configured value: the binding
         * failure carries no configuration property, whose value the startup failure
         * report would print, and no message in the cause chain contains the value
         * outside the fixed invalid-client message (an empty value can only be checked
         * the first way).
         */
        private void assertValueFreeFailure(Throwable failure, String value) {
            List<BindException> bindFailures = new ArrayList<>();
            for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
                if (cause instanceof BindException bindFailure) {
                    bindFailures.add(bindFailure);
                }
            }
            assertThat(bindFailures).isNotEmpty()
                    .allSatisfy(bindFailure -> assertThat(bindFailure.getProperty()).isNull());
            if (!value.isEmpty()) {
                assertThat(causeMessages(failure))
                        .map(message -> message.replace(INVALID_CLIENT_MESSAGE, ""))
                        .noneMatch(message -> message.contains(value));
            }
        }
    }

    @Nested
    @DisplayName("Auto-configuration registration")
    class Registration {

        @Test
        @DisplayName("the AutoConfiguration.imports file lists the class")
        void importsFileListsTheAutoConfiguration() {
            ImportCandidates candidates = ImportCandidates.load(AutoConfiguration.class, getClass().getClassLoader());
            assertThat(candidates.getCandidates()).contains(AUTO_CONFIGURATION_CLASS_NAME);
            assertThat(AddressValidationAutoConfiguration.class.getName()).isEqualTo(AUTO_CONFIGURATION_CLASS_NAME);
        }
    }

    /**
     * Collects the message of every throwable in the cause chain of {@code failure}, from
     * the outermost to the root; a {@code null} message is collected as {@code ""}.
     *
     * @param failure the startup failure; may be {@code null}, giving an empty list
     * @return the messages in chain order
     */
    private static List<String> causeMessages(Throwable failure) {
        List<String> messages = new ArrayList<>();
        Throwable current = failure;
        while (current != null) {
            String message = current.getMessage();
            messages.add(message == null ? "" : message);
            Throwable cause = current.getCause();
            current = (cause == current) ? null : cause;
        }
        return messages;
    }
}
