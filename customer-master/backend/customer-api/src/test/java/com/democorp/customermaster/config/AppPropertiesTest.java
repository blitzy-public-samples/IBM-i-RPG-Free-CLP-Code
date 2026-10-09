package com.democorp.customermaster.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.UnboundConfigurationPropertiesException;
import org.springframework.boot.context.properties.bind.validation.BindValidationException;
import org.springframework.boot.context.properties.source.ConfigurationProperty;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.convert.ConversionFailedException;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.validation.ObjectError;

/**
 * Specifies the startup validation of {@link AppProperties}, the {@code customer-master.db.*} and
 * {@code customer-master.search.*} settings, together with {@link AppPropertiesUnknownKeyAdvisor},
 * which rejects a key under those two subtrees that {@code AppProperties} does not bind.
 *
 * <p>Each case starts a bare {@link ApplicationContextRunner} that registers {@code AppProperties}
 * as {@code CustomerMasterApplication} does, through {@code @EnableConfigurationProperties}, plus
 * the advisor bean its component scan finds, and supplies the settings under test as property
 * values. A rejected setting fails the context, and the case reads the binding failure from it.
 *
 * <p>The nested registration class carries no stereotype: test classes are on the classpath when
 * an application component scan runs without a test-type exclude filter, and this class must add
 * nothing to such a scan.
 *
 * <p>Pure JUnit 5, AssertJ and Spring Boot's context runner: no database, no Docker.
 */
@DisplayName("AppProperties: customer-master.db and .search bind and validate at startup")
final class AppPropertiesTest {

    /** Name of the property source holding the process environment. */
    private static final String SYSTEM_ENVIRONMENT =
            StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME;

    /** Registers AppProperties as CustomerMasterApplication does. */
    @EnableConfigurationProperties(AppProperties.class)
    static class AppPropertiesRegistration {
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(AppPropertiesRegistration.class,
                    AppPropertiesUnknownKeyAdvisor.class);

    @Test
    @DisplayName("Defaults: lock-timeout 5s, default-size 12, max-size 100, max-rows 9999")
    void defaultsBind() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            AppProperties properties = context.getBean(AppProperties.class);
            assertThat(properties.db().lockTimeout()).isEqualTo(Duration.ofSeconds(5));
            assertThat(properties.search().defaultSize()).isEqualTo(12);
            assertThat(properties.search().maxSize()).isEqualTo(100);
            assertThat(properties.search().maxRows()).isEqualTo(9999);
        });
    }

    @Nested
    @DisplayName("Search limits: 1 <= default-size <= max-size <= Integer.MAX_VALUE - 1")
    class SearchLimits {

        @Test
        @DisplayName("default-size 12 with max-size 1 fails, naming both keys and neither value")
        void defaultSizeAboveMaxSizeFails() {
            runner.withPropertyValues(
                            "customer-master.search.default-size=12",
                            "customer-master.search.max-size=1")
                    .run(context -> assertThat(validationMessages(context)).singleElement()
                            .isEqualTo("customer-master.search.default-size must not be greater"
                                    + " than customer-master.search.max-size"));
        }

        @Test
        @DisplayName("default-size 101 above the default max-size 100 fails")
        void defaultSizeAboveDefaultMaxSizeFails() {
            runner.withPropertyValues("customer-master.search.default-size=101")
                    .run(context -> assertThat(validationMessages(context)).singleElement()
                            .asString()
                            .contains("customer-master.search.default-size")
                            .contains("customer-master.search.max-size"));
        }

        @Test
        @DisplayName("default-size equal to max-size starts and binds both")
        void defaultSizeEqualToMaxSizeStarts() {
            runner.withPropertyValues(
                            "customer-master.search.default-size=5",
                            "customer-master.search.max-size=5")
                    .run(context -> {
                        assertThat(context).hasNotFailed();
                        AppProperties.Search search = context.getBean(AppProperties.class).search();
                        assertThat(search.defaultSize()).isEqualTo(5);
                        assertThat(search.maxSize()).isEqualTo(5);
                    });
        }

        @Test
        @DisplayName("max-size 2147483647 fails: the look-ahead size + 1 would overflow an int")
        void maxSizeAtIntegerMaxFails() {
            runner.withPropertyValues(
                            "customer-master.search.max-size=2147483647",
                            "customer-master.search.max-rows=2147483647")
                    .run(context -> {
                        BindValidationException failure = validationFailure(context);
                        // The constraint code and its bound, not the validator's bundle message,
                        // which is written in the JVM's default locale.
                        assertThat(failure.getValidationErrors().getAllErrors()).singleElement()
                                .satisfies(error -> {
                                    assertThat(error.getCodes()).contains("Max.search.maxSize");
                                    assertThat(error.getArguments()).contains(2147483646L);
                                });
                    });
        }

        @Test
        @DisplayName("max-size 2147483646 with max-rows 2147483647 starts")
        void maxSizeOneBelowIntegerMaxStarts() {
            runner.withPropertyValues(
                            "customer-master.search.max-size=2147483646",
                            "customer-master.search.max-rows=2147483647")
                    .run(context -> {
                        assertThat(context).hasNotFailed();
                        AppProperties.Search search = context.getBean(AppProperties.class).search();
                        assertThat(search.maxSize()).isEqualTo(Integer.MAX_VALUE - 1);
                        assertThat(search.maxRows()).isEqualTo(Integer.MAX_VALUE);
                        assertThat(search.defaultSize()).isEqualTo(12);
                    });
        }

        @Test
        @DisplayName("default-size 0 still fails the lower bound")
        void defaultSizeZeroFails() {
            runner.withPropertyValues("customer-master.search.default-size=0")
                    .run(context -> assertThat(
                                    validationFailure(context).getValidationErrors().getAllErrors())
                            .anySatisfy(error -> {
                                // The constraint code and its bound, locale independent as above.
                                assertThat(error.getCodes()).contains("Min.search.defaultSize");
                                assertThat(error.getArguments()).contains(1L);
                            }));
        }
    }

    @Nested
    @DisplayName("Unknown keys: rejected under customer-master.db and .search only")
    class UnknownKeys {

        @Test
        @DisplayName("customer-master.db.lock-timout fails, naming the key, its value and origin")
        void dbTypoFails() {
            runner.withPropertyValues("customer-master.db.lock-timout=500ms")
                    .run(context -> assertThat(unboundProperties(context)).singleElement()
                            .satisfies(property -> {
                                assertThat(property.getName())
                                        .hasToString("customer-master.db.lock-timout");
                                assertThat(property.getValue()).isEqualTo("500ms");
                                assertThat(property.getOrigin()).isNotNull();
                                assertThat(property.getOrigin().toString())
                                        .contains("customer-master.db.lock-timout");
                            }));
        }

        @Test
        @DisplayName("customer-master.search.max-sise fails, naming the key")
        void searchTypoFails() {
            runner.withPropertyValues("customer-master.search.max-sise=5")
                    .run(context -> assertThat(unboundNames(context))
                            .containsExactly("customer-master.search.max-sise"));
        }

        @Test
        @DisplayName("Typos in both subtrees are reported together, in name order")
        void typosInBothSubtreesReportedTogether() {
            runner.withPropertyValues(
                            "customer-master.search.max-sise=5",
                            "customer-master.db.lock-timout=500ms")
                    .run(context -> assertThat(unboundNames(context)).containsExactly(
                            "customer-master.db.lock-timout", "customer-master.search.max-sise"));
        }

        @Test
        @DisplayName("An unknown key is reported before the validation failure it causes")
        void unknownKeyReportedBeforeValidation() {
            runner.withPropertyValues(
                            "customer-master.search.defualt-size=1",
                            "customer-master.search.max-size=1")
                    .run(context -> assertThat(unboundNames(context))
                            .containsExactly("customer-master.search.defualt-size"));
        }

        @Test
        @DisplayName("Sibling subtrees and the customer-master root stay lenient")
        void siblingKeysTolerated() {
            runner.withPropertyValues(
                            "customer-master.address.client=stub",
                            "customer-master.address.clinet=stub",
                            "customer-master.security.users[0].username=inq",
                            "customer-master.generator.count=40",
                            "customer-master.unrelated=1")
                    .run(context -> {
                        assertThat(context).hasNotFailed();
                        assertThat(context.getBean(AppProperties.class).search().defaultSize())
                                .isEqualTo(12);
                    });
        }

        @Test
        @DisplayName("Relaxed forms of the known keys bind and are not reported")
        void relaxedFormsBind() {
            runner.withPropertyValues(
                            "customer-master.db.lockTimeout=2s",
                            "customer-master.search.maxSize=50",
                            "customer-master.search.default_size=10")
                    .run(context -> {
                        assertThat(context).hasNotFailed();
                        AppProperties properties = context.getBean(AppProperties.class);
                        assertThat(properties.db().lockTimeout()).isEqualTo(Duration.ofSeconds(2));
                        assertThat(properties.search().maxSize()).isEqualTo(50);
                        assertThat(properties.search().defaultSize()).isEqualTo(10);
                    });
        }

        @Test
        @DisplayName("A source named systemEnvironment is not checked, as in Boot's strict mode")
        void systemEnvironmentNamedSourceTolerated() {
            runner.withInitializer(context -> context.getEnvironment().getPropertySources()
                            .replace(SYSTEM_ENVIRONMENT, new MapPropertySource(SYSTEM_ENVIRONMENT,
                                    Map.of("customer-master.db.lock-timout", "1s"))))
                    .run(context -> assertThat(context).hasNotFailed());
        }

        @Test
        @DisplayName("Environment variables still bind; a misspelt variable is not checked")
        void environmentVariablesBindAndTypoTolerated() {
            runner.withInitializer(context -> context.getEnvironment().getPropertySources()
                            .replace(SYSTEM_ENVIRONMENT, new SystemEnvironmentPropertySource(
                                    SYSTEM_ENVIRONMENT,
                                    Map.of("CUSTOMER_MASTER_DB_LOCK_TIMEOUT", "3s",
                                            "CUSTOMER_MASTER_DB_LOCK_TIMOUT", "1s"))))
                    .run(context -> {
                        assertThat(context).hasNotFailed();
                        assertThat(context.getBean(AppProperties.class).db().lockTimeout())
                                .isEqualTo(Duration.ofSeconds(3));
                    });
        }

        @Test
        @DisplayName("JVM system properties are not checked, as in Boot's strict mode")
        void systemPropertiesTolerated() {
            runner.withSystemProperties("customer-master.search.max-sise=5")
                    .run(context -> assertThat(context).hasNotFailed());
        }

        @Test
        @DisplayName("Without AppProperties the advisor is inert")
        void inertWithoutAppProperties() {
            new ApplicationContextRunner()
                    .withUserConfiguration(AppPropertiesUnknownKeyAdvisor.class)
                    .withPropertyValues("customer-master.db.lock-timout=500ms")
                    .run(context -> {
                        assertThat(context).hasNotFailed();
                        assertThat(context).doesNotHaveBean(AppProperties.class);
                    });
        }
    }

    @Nested
    @DisplayName("Empty values: a known key is never an unknown key; an empty lock-timeout takes "
            + "its 5s default")
    class EmptyValues {

        @Test
        @DisplayName("An empty customer-master.db.lock-timeout starts with the 5s default")
        void emptyLockTimeoutStartsWithDefault() {
            runner.withPropertyValues("customer-master.db.lock-timeout=")
                    .run(context -> {
                        assertThat(context).hasNotFailed();
                        assertThat(context.getBean(AppProperties.class).db().lockTimeout())
                                .isEqualTo(Duration.ofSeconds(5));
                    });
        }

        @Test
        @DisplayName("An empty DB_LOCK_TIMEOUT behind ${DB_LOCK_TIMEOUT:5s} starts with 5s")
        void emptyLockTimeoutVariableStartsWithDefault() {
            runner.withInitializer(context -> context.getEnvironment().getPropertySources()
                            .replace(SYSTEM_ENVIRONMENT, new SystemEnvironmentPropertySource(
                                    SYSTEM_ENVIRONMENT, Map.of("DB_LOCK_TIMEOUT", ""))))
                    .withPropertyValues("customer-master.db.lock-timeout=${DB_LOCK_TIMEOUT:5s}")
                    .run(context -> {
                        assertThat(context).hasNotFailed();
                        assertThat(context.getEnvironment()
                                .getProperty("customer-master.db.lock-timeout")).isEmpty();
                        assertThat(context.getBean(AppProperties.class).db().lockTimeout())
                                .isEqualTo(Duration.ofSeconds(5));
                    });
        }

        @Test
        @DisplayName("An empty customer-master.search.max-size fails to convert, naming the key "
                + "and the empty value, not as an unknown key")
        void emptySearchKeyFailsConversion() {
            runner.withPropertyValues("customer-master.search.max-size=")
                    .run(context -> {
                        assertThat(context).hasFailed();
                        Throwable cause = context.getStartupFailure();
                        while (cause != null && !(cause instanceof BindException)) {
                            cause = cause.getCause();
                        }
                        assertThat(cause).isInstanceOf(BindException.class)
                                .hasCauseInstanceOf(ConversionFailedException.class);
                        BindException failure = (BindException) cause;
                        assertThat(failure.getName())
                                .hasToString("customer-master.search.max-size");
                        assertThat(failure.getProperty()).isNotNull();
                        assertThat(failure.getProperty().getValue()).isEqualTo("");
                        assertThat(context.getStartupFailure()).rootCause()
                                .isNotInstanceOf(UnboundConfigurationPropertiesException.class);
                    });
        }

        @Test
        @DisplayName("A misspelt key with an empty value still fails, naming the key and origin")
        void emptyTypoFails() {
            runner.withPropertyValues("customer-master.db.lock-timout=")
                    .run(context -> assertThat(unboundProperties(context)).singleElement()
                            .satisfies(property -> {
                                assertThat(property.getName())
                                        .hasToString("customer-master.db.lock-timout");
                                assertThat(property.getValue()).isEqualTo("");
                                assertThat(property.getOrigin()).isNotNull();
                                assertThat(property.getOrigin().toString())
                                        .contains("customer-master.db.lock-timout");
                            }));
        }
    }

    /**
     * Returns the cause of the given type that stopped the context.
     *
     * @param context a context whose startup failed while binding AppProperties
     * @param type    the expected root cause type
     * @param <T>     that type
     * @return the root cause
     */
    private static <T extends Throwable> T rootCause(
            AssertableApplicationContext context, Class<T> type) {
        assertThat(context).hasFailed();
        assertThat(context.getStartupFailure()).hasRootCauseInstanceOf(type);
        Throwable cause = context.getStartupFailure();
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        return type.cast(cause);
    }

    /**
     * Returns the bean-validation failure that stopped the context.
     *
     * @param context a context whose startup failed while binding AppProperties
     * @return the validation failure at the root of its cause chain
     */
    private static BindValidationException validationFailure(AssertableApplicationContext context) {
        return rootCause(context, BindValidationException.class);
    }

    /**
     * Returns the messages of the bean-validation errors that stopped the context.
     *
     * @param context a context whose startup failed while binding AppProperties
     * @return the default message of every validation error, in reporting order
     */
    private static List<String> validationMessages(AssertableApplicationContext context) {
        return validationFailure(context).getValidationErrors().getAllErrors().stream()
                .map(ObjectError::getDefaultMessage)
                .toList();
    }

    /**
     * Returns the unknown settings that stopped the context.
     *
     * @param context a context whose startup failed while binding AppProperties
     * @return the unbound properties, in name order
     */
    private static List<ConfigurationProperty> unboundProperties(
            AssertableApplicationContext context) {
        return List.copyOf(
                rootCause(context, UnboundConfigurationPropertiesException.class)
                        .getUnboundProperties());
    }

    /**
     * Returns the names of the unknown settings that stopped the context.
     *
     * @param context a context whose startup failed while binding AppProperties
     * @return the unbound property names, in name order
     */
    private static List<String> unboundNames(AssertableApplicationContext context) {
        return unboundProperties(context).stream()
                .map(property -> property.getName().toString())
                .toList();
    }
}
