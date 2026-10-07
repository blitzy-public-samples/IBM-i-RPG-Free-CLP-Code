package com.democorp.customermaster.generator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.ConfigurationPropertiesBindHandlerAdvisor;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.validation.BindValidationException;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.SimpleCommandLinePropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.validation.FieldError;

/**
 * Specifies how {@code customer-master.generator.count} binds in the generator context: a blank value
 * means "not given" and yields the default 300, while flag precedence and numeric validation stay as
 * they are.
 *
 * <p><b>Why.</b> The mandated bridge {@code count: ${count:${GENERATOR_COUNT:300}}} falls back only when
 * a name is absent, so {@code GENERATOR_COUNT=} resolves to {@code ""}, which the primitive
 * {@code int count} cannot take. {@code GeneratorProperties.BlankCountAdvisor} turns that blank into the
 * {@code @DefaultValue("300")}.
 *
 * <p><b>Same registration as production.</b> Every generator context here is built from
 * {@link CustomerGeneratorRunner} itself under profile {@value CustomerGeneratorRunner#PROFILE}, so its own
 * {@code @EnableConfigurationProperties} and {@code @Import} register the record and the advisor. Its
 * collaborators are Mockito mocks and a system clock; an {@link ApplicationContextRunner} never calls
 * application runners, so nothing is loaded.
 *
 * <p><b>Sources.</b> The bridge cases read the real {@code application-generator.yml} from the classpath
 * and put, in Spring Boot's order, a command line ({@link SimpleCommandLinePropertySource}) above a
 * process environment ({@link SystemEnvironmentPropertySource}) above that file. The host's own
 * environment and system properties are removed, so a {@code GENERATOR_*} variable of the machine
 * running the build cannot leak in. The direct cases set {@code customer-master.generator.count} itself,
 * whitespace included, through a map source.
 *
 * <p>JUnit 5, AssertJ, Mockito and a Spring application context: no database, no Docker, no process.
 */
@DisplayName("GeneratorProperties: blank count binds as 300; flags, variables and validation unchanged")
final class GeneratorPropertiesBindingTest {

    /** The fully qualified key of the row count. */
    private static final String COUNT_KEY = GeneratorProperties.PREFIX + ".count";

    /** The generator profile file on the classpath, which holds the option bridge. */
    private static final String PROFILE_FILE = "application-generator.yml";

    /** The record default of {@code count}. */
    private static final int DEFAULT_COUNT = 300;

    @ParameterizedTest(name = "count = \"{0}\" binds as 300")
    @ValueSource(strings = {"", " ", "   ", "\t"})
    @DisplayName("an empty or whitespace-only count is not given, so the default 300 applies")
    void blankCountBindsDefault(String blank) {
        assertCount(direct(blank), DEFAULT_COUNT);
    }

    @ParameterizedTest(name = "count = \"{0}\" binds as {0}")
    @ValueSource(strings = {"60", "1", "1679616"})
    @DisplayName("a number in 1..1,679,616 binds as given")
    void numericCountBinds(String count) {
        assertCount(direct(count), Integer.parseInt(count));
    }

    @Test
    @DisplayName("a non-blank count that is not a number fails startup naming customer-master.generator.count")
    void nonNumericCountFails() {
        assertConversionFailure(direct("abc"));
    }

    @ParameterizedTest(name = "count = {0} fails validation")
    @ValueSource(strings = {"0", "1679617"})
    @DisplayName("a count outside 1..1,679,616 fails validation naming customer-master.generator.count")
    void outOfRangeCountFails(String count) {
        assertValidationFailure(direct(count));
    }

    @Test
    @DisplayName("bridge: GENERATOR_COUNT empty and no flag yields 300")
    void bridgeEmptyVariableYieldsDefault() {
        assertCount(bridged(Map.of("GENERATOR_COUNT", "")), DEFAULT_COUNT);
    }

    @Test
    @DisplayName("bridge: GENERATOR_COUNT of blanks only yields 300")
    void bridgeBlankVariableYieldsDefault() {
        assertCount(bridged(Map.of("GENERATOR_COUNT", "   ")), DEFAULT_COUNT);
    }

    @Test
    @DisplayName("bridge: GENERATOR_COUNT absent and no flag yields 300")
    void bridgeAbsentVariableYieldsDefault() {
        assertCount(bridged(Map.of()), DEFAULT_COUNT);
    }

    @Test
    @DisplayName("bridge: --count=60 wins over an empty GENERATOR_COUNT")
    void bridgeFlagWinsOverEmptyVariable() {
        assertCount(bridged(Map.of("GENERATOR_COUNT", ""), "--count=60"), 60);
    }

    @Test
    @DisplayName("bridge: --customer-master.generator.count=60 wins over an empty GENERATOR_COUNT")
    void bridgeQualifiedPropertyWinsOverEmptyVariable() {
        assertCount(bridged(Map.of("GENERATOR_COUNT", ""), "--" + COUNT_KEY + "=60"), 60);
    }

    @Test
    @DisplayName("bridge: an explicitly empty --count= yields 300")
    void bridgeEmptyFlagYieldsDefault() {
        assertCount(bridged(Map.of("GENERATOR_COUNT", "40"), "--count="), DEFAULT_COUNT);
    }

    @Test
    @DisplayName("bridge: a bare --count binds as 300 (CustomerGeneratorRunner rejects the missing value)")
    void bridgeBareFlagYieldsDefault() {
        assertCount(bridged(Map.of("GENERATOR_COUNT", ""), "--count"), DEFAULT_COUNT);
    }

    @Test
    @DisplayName("bridge: GENERATOR_COUNT=40 and no flag yields 40")
    void bridgeVariableBinds() {
        assertCount(bridged(Map.of("GENERATOR_COUNT", "40")), 40);
    }

    @Test
    @DisplayName("bridge: --count=60 wins over GENERATOR_COUNT=40")
    void bridgeFlagWinsOverVariable() {
        assertCount(bridged(Map.of("GENERATOR_COUNT", "40"), "--count=60"), 60);
    }

    @Test
    @DisplayName("bridge: all four GENERATOR_* variables empty bind as not given; only count takes its default")
    void bridgeAllVariablesEmpty() {
        Map<String, Object> environment = Map.of(
                "GENERATOR_COUNT", "",
                "GENERATOR_START_ID", "",
                "GENERATOR_CSZ_FILE", "",
                "GENERATOR_SEED", "");
        bridged(environment).run(context -> {
            assertThat(context).hasNotFailed();
            GeneratorProperties properties = context.getBean(GeneratorProperties.class);
            assertThat(properties.count()).isEqualTo(DEFAULT_COUNT);
            assertThat(properties.startId()).isEmpty();
            assertThat(properties.hasStartId()).isFalse();
            assertThat(properties.cszFile()).isEmpty();
            assertThat(properties.cszLocation()).isEqualTo(GeneratorProperties.DEFAULT_CSZ_FILE);
            assertThat(properties.seed()).isNull();
        });
    }

    @Test
    @DisplayName("bridge: GENERATOR_COUNT=abc still fails startup naming customer-master.generator.count")
    void bridgeNonNumericVariableFails() {
        assertConversionFailure(bridged(Map.of("GENERATOR_COUNT", "abc")));
    }

    @Test
    @DisplayName("bridge: GENERATOR_COUNT=0 still fails validation naming customer-master.generator.count")
    void bridgeOutOfRangeVariableFails() {
        assertValidationFailure(bridged(Map.of("GENERATOR_COUNT", "0")));
    }

    @Test
    @DisplayName("the generator context holds exactly one bind-handler advisor, the blank-count advisor")
    void generatorContextHoldsAdvisor() {
        bridged(Map.of("GENERATOR_COUNT", "")).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(CustomerGeneratorRunner.class);
            assertThat(context).hasSingleBean(GeneratorProperties.class);
            assertThat(context).getBeans(ConfigurationPropertiesBindHandlerAdvisor.class)
                    .hasSize(1)
                    .allSatisfy((name, advisor) ->
                            assertThat(advisor).isInstanceOf(GeneratorProperties.BlankCountAdvisor.class));
        });
    }

    @Test
    @DisplayName("without the generator profile there is no runner, no GeneratorProperties and no advisor")
    void contextWithoutGeneratorRegistrationHasNoAdvisor() {
        withCollaborators(new ApplicationContextRunner())
                .withUserConfiguration(CustomerGeneratorRunner.class)
                .withPropertyValues(COUNT_KEY + "=")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(CustomerGeneratorRunner.class);
                    assertThat(context).doesNotHaveBean(GeneratorProperties.class);
                    assertThat(context).doesNotHaveBean(ConfigurationPropertiesBindHandlerAdvisor.class);
                    assertThat(context).doesNotHaveBean(GeneratorProperties.BlankCountAdvisor.class);
                });
    }

    /**
     * A generator context with {@code customer-master.generator.count} set directly to {@code value}.
     *
     * @param value the value of the key, kept verbatim (no trimming)
     * @return the context runner
     */
    private static ApplicationContextRunner direct(String value) {
        return generatorContext().withInitializer(context -> context.getEnvironment().getPropertySources()
                .addFirst(new MapPropertySource("directCount", Map.of(COUNT_KEY, value))));
    }

    /**
     * A generator context whose options come through the real bridge in {@value #PROFILE_FILE}.
     *
     * @param environment the process environment the bridge's {@code GENERATOR_*} placeholders see
     * @param args        the command line, highest precedence
     * @return the context runner
     */
    private static ApplicationContextRunner bridged(Map<String, Object> environment, String... args) {
        return generatorContext().withInitializer(context -> {
            MutablePropertySources sources = context.getEnvironment().getPropertySources();
            sources.remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
            sources.remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
            sources.addFirst(generatorProfileFile());
            sources.addFirst(new SystemEnvironmentPropertySource("generatorTestEnvironment",
                    new HashMap<>(environment)));
            sources.addFirst(new SimpleCommandLinePropertySource(args));
        });
    }

    /**
     * The generator registration exactly as production declares it: {@link CustomerGeneratorRunner}
     * under its profile.
     *
     * @return the context runner
     */
    private static ApplicationContextRunner generatorContext() {
        return withCollaborators(new ApplicationContextRunner())
                .withPropertyValues("spring.profiles.active=" + CustomerGeneratorRunner.PROFILE)
                .withUserConfiguration(CustomerGeneratorRunner.class);
    }

    /**
     * Adds the runner's collaborators, none of which is called while the context starts.
     *
     * @param runner the context runner to extend
     * @return the extended context runner
     */
    private static ApplicationContextRunner withCollaborators(ApplicationContextRunner runner) {
        return runner
                .withBean(CszSource.class, () -> mock(CszSource.class))
                .withBean(CustomerLoader.class, () -> mock(CustomerLoader.class))
                .withBean(JdbcTemplate.class, () -> mock(JdbcTemplate.class))
                .withBean(Clock.class, Clock::systemUTC);
    }

    /**
     * Loads {@value #PROFILE_FILE} from the classpath as Spring Boot's config data does.
     *
     * @return its single document
     */
    private static PropertySource<?> generatorProfileFile() {
        try {
            List<PropertySource<?>> documents = new YamlPropertySourceLoader()
                    .load(PROFILE_FILE, new ClassPathResource(PROFILE_FILE));
            assertThat(documents).hasSize(1);
            return documents.get(0);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    private static void assertCount(ApplicationContextRunner runner, int expected) {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(GeneratorProperties.class).count()).isEqualTo(expected);
        });
    }

    /** Startup fails converting the count, and the failure names {@value #COUNT_KEY}. */
    private static void assertConversionFailure(ApplicationContextRunner runner) {
        runner.run(context -> {
            assertThat(context).hasFailed();
            assertThat(causes(context.getStartupFailure()))
                    .filteredOn(BindException.class::isInstance)
                    .extracting(cause -> ((BindException) cause).getName().toString())
                    .contains(COUNT_KEY);
        });
    }

    /** Startup fails validating the bound record, with a field error on {@value #COUNT_KEY}. */
    private static void assertValidationFailure(ApplicationContextRunner runner) {
        runner.run(context -> {
            assertThat(context).hasFailed();
            assertThat(causes(context.getStartupFailure()))
                    .filteredOn(BindValidationException.class::isInstance)
                    .flatMap(cause -> ((BindValidationException) cause).getValidationErrors().getAllErrors())
                    .filteredOn(FieldError.class::isInstance)
                    .extracting(error -> error.getObjectName() + "." + ((FieldError) error).getField())
                    .containsExactly(COUNT_KEY);
        });
    }

    /** The failure and its causes, outermost first. */
    private static List<Throwable> causes(Throwable failure) {
        List<Throwable> chain = new ArrayList<>();
        for (Throwable cause = failure; cause != null && !chain.contains(cause); cause = cause.getCause()) {
            chain.add(cause);
        }
        return chain;
    }
}
