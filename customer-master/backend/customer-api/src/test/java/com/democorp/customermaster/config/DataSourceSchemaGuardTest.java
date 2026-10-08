package com.democorp.customermaster.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariDataSource;
import java.util.List;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.JdbcConnectionDetails;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.SystemEnvironmentPropertySource;

/**
 * Specifies {@link DataSourceSchemaGuard}: a schema setting that {@code application.yml} feeds from
 * {@code DB_SCHEMA} and that is set but empty or blank, or begins or ends with a space or a control
 * character, stops startup before Spring Boot builds the {@code DataSource}, naming the failing keys by
 * fault and {@code DB_SCHEMA} and never a value, while every other schema name, an unset variable and a
 * URL without {@code currentSchema} still start.
 *
 * <p><b>Scenarios.</b> Through the production {@code application.yml}: an empty, a blank and a padded
 * (leading or trailing space or tab) {@code DB_SCHEMA}, the guard failing ahead of Flyway, the
 * {@code generator} profile, an unset variable ({@code customer_master}), {@code qa_other} and mixed-case
 * {@code QaUpper}, and a replacing {@link JdbcConnectionDetails} bean whose URL carries no
 * {@code currentSchema} (as Testcontainers {@code @ServiceConnection} registers). Through properties alone:
 * the forms of the {@code currentSchema} URL parameter the PostgreSQL driver reads, empty, blank or
 * padded, both faults at once, a blank, a padded indexed and an empty {@code spring.flyway.schemas}, and no
 * schema setting at all.
 *
 * <p>The runner holds {@link DataSourceAutoConfiguration} and the guard only, with no web server, as the
 * {@code generator} profile runs; the Flyway scenarios add {@link FlywayAutoConfiguration}. Building the
 * Hikari pool opens no connection, so no database is needed. {@code DB_SCHEMA}, and any other value whose
 * blanks matter, is given as a property source of its own, because a runner property value is trimmed.
 * Replacing details are registered with {@code withBean}: this class declares no configuration class or
 * stereotype, because test classes are on the classpath when an {@code ApplicationContextRunner} runs the
 * application's component scan. Every credential, host and URL here is fictitious.
 */
@DisplayName("DataSourceSchemaGuard: no database use with an empty, blank or padded schema")
class DataSourceSchemaGuardTest {

    /** A host that is never connected to; it must not appear in a failure. */
    private static final String HOST = "schema-guard-test-host";

    /** A JDBC URL with no query, on {@link #HOST}; it must not appear in a failure. */
    private static final String URL = "jdbc:postgresql://" + HOST + ":5432/customermaster";

    /** A fictitious role name. */
    private static final String USERNAME = "schema-guard-test-role";

    /** A fictitious password; it must never appear in a failure. */
    private static final String PASSWORD = "schema-guard-test-secret";

    /** A valid schema the property-only scenarios give the pool and Flyway. */
    private static final String SCHEMA = "qa_other";

    /** The details Spring Boot derives from {@code spring.datasource.*} (a package-private class). */
    private static final String PROPERTIES_DETAILS =
            "org.springframework.boot.autoconfigure.jdbc.PropertiesJdbcConnectionDetails";

    /** The failure phrase for the pool's schema. */
    private static final String HIKARI_SCHEMA = "spring.datasource.hikari.schema";

    /** The failure phrase for the URL parameter, without the details type that follows it. */
    private static final String URL_PARAMETER =
            "the currentSchema parameter of the JDBC URL (spring.datasource.url) in connection details ";

    /** The failure phrase for Flyway's schema list. */
    private static final String FLYWAY_ENTRY = "an entry of spring.flyway.schemas";

    /** The fault phrase for one setting with a space or control character at either end. */
    private static final String BEGINS_OR_ENDS = " begins or ends with a space or a control character such as a tab";

    /** The failure's text after "application.yml sets it" or "... them". */
    private static final String REMEDY = " from the environment variable DB_SCHEMA, whose default customer_master"
            + " applies only while the variable is unset: give DB_SCHEMA a schema name that neither begins nor ends"
            + " with a space or a control character, or unset it. Startup stops before any database connection is"
            + " opened, so no schema is created.";

    /** The guard alone over the properties each scenario gives, with no configuration file. */
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(DataSourceAutoConfiguration.class))
            .withBean(DataSourceSchemaGuard.class)
            .withPropertyValues("spring.datasource.username=" + USERNAME, "spring.datasource.password=" + PASSWORD);

    /** The guard over the production {@code application.yml}, given the other {@code DB_*} values it reads. */
    private final ApplicationContextRunner applicationYml = runner
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withPropertyValues("DB_HOST=" + HOST, "DB_USER=" + USERNAME, "DB_PASSWORD=" + PASSWORD);

    @Test
    @DisplayName("empty DB_SCHEMA through application.yml: fails naming the pool schema, the URL parameter and"
            + " DB_SCHEMA")
    void emptyDbSchemaFails() {
        withDbSchema(applicationYml, "").run(context -> {
            assertThat(guardMessage(context)).isEqualTo("Database schema invalid: " + HIKARI_SCHEMA + " and "
                    + URL_PARAMETER + PROPERTIES_DETAILS + " are empty or blank. application.yml sets them from"
                    + " the environment variable DB_SCHEMA, whose default customer_master applies only while the"
                    + " variable is unset: give DB_SCHEMA a schema name that neither begins nor ends with a space or"
                    + " a control character, or unset it. Startup stops before any database connection is opened,"
                    + " so no schema is created.");
            assertNoValueInFailure(context, URL, HOST, PASSWORD);
        });
    }

    @ParameterizedTest(name = "DB_SCHEMA \"{0}\"")
    @ValueSource(strings = {" ", "   ", "\t"})
    @DisplayName("blank DB_SCHEMA through application.yml: fails naming all three settings, with no value")
    void blankDbSchemaFails(String blank) {
        withDbSchema(applicationYml, blank).run(context -> {
            assertThat(guardMessage(context))
                    .startsWith("Database schema invalid: " + HIKARI_SCHEMA + ", " + URL_PARAMETER
                            + PROPERTIES_DETAILS + " and " + FLYWAY_ENTRY + " are empty or blank.")
                    .contains("DB_SCHEMA");
            assertNoValueInFailure(context, URL, HOST, PASSWORD);
        });
    }

    @ParameterizedTest(name = "DB_SCHEMA \"{0}\"")
    @ValueSource(strings = {" qa_pad", "qa_pad ", "\tqa_pad", "qa_pad\t"})
    @DisplayName("padded DB_SCHEMA through application.yml: fails naming the pool schema and the URL parameter"
            + " (Flyway's comma-separated list arrives trimmed), with no value")
    void paddedDbSchemaFails(String padded) {
        withDbSchema(applicationYml, padded).run(context -> {
            assertThat(guardMessage(context)).isEqualTo("Database schema invalid: " + HIKARI_SCHEMA + " and "
                    + URL_PARAMETER + PROPERTIES_DETAILS + " begin or end with a space or a control character such as"
                    + " a tab. application.yml sets them" + REMEDY);
            assertNoValueInFailure(context, URL, HOST, PASSWORD, "qa_pad");
        });
    }

    @Test
    @DisplayName("with Flyway auto-configured, the guard fails first: no migration and no connection attempt")
    void failsBeforeFlyway() {
        withDbSchema(applicationYml.withConfiguration(AutoConfigurations.of(FlywayAutoConfiguration.class)), "")
                .run(context -> assertThat(guardMessage(context)).contains(HIKARI_SCHEMA, "DB_SCHEMA"));
    }

    @Test
    @DisplayName("generator profile (Flyway disabled): an empty DB_SCHEMA still fails, an unset one starts")
    void appliesInTheGeneratorProfile() {
        ApplicationContextRunner generator = applicationYml
                .withConfiguration(AutoConfigurations.of(FlywayAutoConfiguration.class))
                .withPropertyValues("spring.profiles.active=generator");
        generator.run(context -> {
            assertThat(context).hasNotFailed().doesNotHaveBean(Flyway.class);
            assertThat(context.getBean(HikariDataSource.class).getSchema()).isEqualTo("customer_master");
        });
        withDbSchema(generator, "").run(context -> assertThat(guardMessage(context)).contains("DB_SCHEMA"));
    }

    @Test
    @DisplayName("unset DB_SCHEMA: application.yml gives customer_master to the URL, the pool and Flyway")
    void unsetDbSchemaUsesCustomerMaster() {
        applicationYml.run(context -> assertSchemaEverywhere(context, "customer_master"));
    }

    @ParameterizedTest(name = "DB_SCHEMA {0}")
    @ValueSource(strings = {"qa_other", "QaUpper"})
    @DisplayName("a schema name, mixed case included, reaches the URL, the pool and Flyway unchanged")
    void namedSchemaStarts(String schema) {
        withDbSchema(applicationYml, schema).run(context -> assertSchemaEverywhere(context, schema));
    }

    @Test
    @DisplayName("replacing details without currentSchema (as @ServiceConnection) start on the pool schema")
    void replacingDetailsWithoutCurrentSchemaStart() {
        applicationYml
                .withBean(JdbcConnectionDetails.class, () -> new FixedJdbcConnectionDetails(URL + "?loggerLevel=OFF"))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(JdbcConnectionDetails.class))
                            .isInstanceOf(FixedJdbcConnectionDetails.class);
                    assertThat(context.getBean(HikariDataSource.class).getSchema()).isEqualTo("customer_master");
                });
    }

    @Test
    @DisplayName("replacing details without currentSchema and an empty DB_SCHEMA: fails on the pool schema alone")
    void replacingDetailsWithEmptyDbSchemaFail() {
        withDbSchema(applicationYml, "")
                .withBean(JdbcConnectionDetails.class, () -> new FixedJdbcConnectionDetails(URL + "?loggerLevel=OFF"))
                .run(context -> {
                    assertThat(guardMessage(context))
                            .startsWith("Database schema invalid: " + HIKARI_SCHEMA + " is empty or blank."
                                    + " application.yml sets it from the environment variable DB_SCHEMA")
                            .doesNotContain("currentSchema", FLYWAY_ENTRY);
                    assertNoValueInFailure(context, URL, HOST, PASSWORD);
                });
    }

    @Test
    @DisplayName("replacing details without currentSchema and a padded DB_SCHEMA: fails on the pool schema alone")
    void replacingDetailsWithPaddedDbSchemaFail() {
        withDbSchema(applicationYml, " qa_pad")
                .withBean(JdbcConnectionDetails.class, () -> new FixedJdbcConnectionDetails(URL + "?loggerLevel=OFF"))
                .run(context -> {
                    assertThat(guardMessage(context))
                            .isEqualTo("Database schema invalid: " + HIKARI_SCHEMA + BEGINS_OR_ENDS
                                    + ". application.yml sets it" + REMEDY);
                    assertNoValueInFailure(context, URL, HOST, PASSWORD, "qa_pad");
                });
    }

    @Test
    @DisplayName("replacing details whose URL has an empty currentSchema fail, and the details type is named")
    void replacingDetailsWithEmptyCurrentSchemaFail() {
        runner.withPropertyValues("spring.datasource.hikari.schema=" + SCHEMA)
                .withBean(JdbcConnectionDetails.class, () -> new FixedJdbcConnectionDetails(URL + "?currentSchema="))
                .run(context -> {
                    assertThat(guardMessage(context))
                            .contains(URL_PARAMETER + FixedJdbcConnectionDetails.class.getName())
                            .doesNotContain(HIKARI_SCHEMA);
                    assertNoValueInFailure(context, URL, HOST, PASSWORD);
                });
    }

    @ParameterizedTest(name = "URL query \"{0}\"")
    @ValueSource(strings = {
        "?currentSchema=",
        "?currentSchema",
        "?currentSchema=+",
        "?currentSchema=%20%09",
        "?ssl=false&currentSchema=",
        "?currentSchema=" + SCHEMA + "&currentSchema="
    })
    @DisplayName("a currentSchema the driver reads as empty or blank fails, whatever the pool schema")
    void blankCurrentSchemaParameterFails(String query) {
        runner.withPropertyValues("spring.datasource.url=" + URL + query,
                        "spring.datasource.hikari.schema=" + SCHEMA, "spring.flyway.schemas=" + SCHEMA)
                .run(context -> {
                    assertThat(guardMessage(context))
                            .startsWith("Database schema invalid: " + URL_PARAMETER + PROPERTIES_DETAILS
                                    + " is empty or blank.")
                            .doesNotContain(HIKARI_SCHEMA, FLYWAY_ENTRY);
                    assertNoValueInFailure(context, URL, HOST, PASSWORD);
                });
    }

    @ParameterizedTest(name = "URL query \"{0}\"")
    @ValueSource(strings = {
        "?currentSchema=%20" + SCHEMA,
        "?currentSchema=" + SCHEMA + "%20",
        "?currentSchema=+" + SCHEMA,
        "?currentSchema=%09" + SCHEMA,
        "?currentSchema=" + SCHEMA + "%0A",
        "?currentSchema=" + SCHEMA + "&currentSchema=%20" + SCHEMA
    })
    @DisplayName("a currentSchema the driver reads with a space or control character at either end fails,"
            + " whatever the pool schema, with no value")
    void paddedCurrentSchemaParameterFails(String query) {
        runner.withPropertyValues("spring.datasource.url=" + URL + query,
                        "spring.datasource.hikari.schema=" + SCHEMA, "spring.flyway.schemas=" + SCHEMA)
                .run(context -> {
                    assertThat(guardMessage(context)).isEqualTo("Database schema invalid: " + URL_PARAMETER
                            + PROPERTIES_DETAILS + BEGINS_OR_ENDS + ". application.yml sets it" + REMEDY);
                    assertNoValueInFailure(context, URL, HOST, PASSWORD, SCHEMA);
                });
    }

    @Test
    @DisplayName("an empty pool schema and a padded currentSchema: each setting is named under its own fault")
    void blankAndPaddedSettingsFailTogether() {
        runner.withPropertyValues("spring.datasource.url=" + URL + "?currentSchema=%20" + SCHEMA,
                        "spring.datasource.hikari.schema=")
                .run(context -> {
                    assertThat(guardMessage(context)).isEqualTo("Database schema invalid: " + HIKARI_SCHEMA
                            + " is empty or blank, and " + URL_PARAMETER + PROPERTIES_DETAILS + BEGINS_OR_ENDS
                            + ". application.yml sets them" + REMEDY);
                    assertNoValueInFailure(context, URL, HOST, PASSWORD, SCHEMA);
                });
    }

    @ParameterizedTest(name = "URL query \"{0}\"")
    @ValueSource(strings = {
        "",
        "?ssl=false",
        "?currentSchema=" + SCHEMA,
        "?currentSchema=Qa%20Upper",
        "?currentSchema=&currentSchema=" + SCHEMA,
        "?currentSchema=%20" + SCHEMA + "&currentSchema=" + SCHEMA,
        "?currentschema="
    })
    @DisplayName("a URL without currentSchema, or whose last currentSchema names a schema, starts")
    void currentSchemaParameterNamingASchemaOrAbsentStarts(String query) {
        runner.withPropertyValues("spring.datasource.url=" + URL + query, "spring.datasource.hikari.schema=" + SCHEMA)
                .run(context -> assertThat(context).hasNotFailed().hasSingleBean(HikariDataSource.class));
    }

    @Test
    @DisplayName("a blank entry in spring.flyway.schemas fails naming that setting alone")
    void blankFlywayEntryFails() {
        runner.withPropertyValues("spring.datasource.url=" + URL, "spring.datasource.hikari.schema=" + SCHEMA,
                        "spring.flyway.schemas=" + SCHEMA + ",")
                .run(context -> assertThat(guardMessage(context)).isEqualTo("Database schema invalid: "
                        + FLYWAY_ENTRY + " is empty or blank. application.yml sets it" + REMEDY));
    }

    @Test
    @DisplayName("a padded indexed entry in spring.flyway.schemas, which Spring Boot keeps as written, fails"
            + " naming that setting alone")
    void paddedIndexedFlywayEntryFails() {
        withUntrimmed(runner.withPropertyValues("spring.datasource.url=" + URL,
                        "spring.datasource.hikari.schema=" + SCHEMA),
                Map.of("spring.flyway.schemas[0]", SCHEMA, "spring.flyway.schemas[1]", SCHEMA + "\t"))
                .run(context -> {
                    assertThat(guardMessage(context)).isEqualTo("Database schema invalid: " + FLYWAY_ENTRY
                            + BEGINS_OR_ENDS + ". application.yml sets it" + REMEDY);
                    assertNoValueInFailure(context, URL, HOST, PASSWORD, SCHEMA);
                });
    }

    @Test
    @DisplayName("an empty spring.flyway.schemas starts: Flyway then uses the pool's schema")
    void emptyFlywaySchemasStart() {
        runner.withPropertyValues("spring.datasource.url=" + URL, "spring.datasource.hikari.schema=" + SCHEMA,
                        "spring.flyway.schemas=")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(flywaySchemas(context)).isEmpty();
                    assertThat(context.getBean(HikariDataSource.class).getSchema()).isEqualTo(SCHEMA);
                });
    }

    @Test
    @DisplayName("no schema setting at all starts: the server's default search_path applies")
    void noSchemaSettingStarts() {
        runner.withPropertyValues("spring.datasource.url=" + URL).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(HikariDataSource.class).getSchema()).isNull();
        });
    }

    /**
     * Gives the runner {@code DB_SCHEMA} as an environment variable, ahead of every other property source.
     *
     * @param base  the runner to extend
     * @param value the variable's value, kept exactly, blanks included
     * @return the extended runner
     */
    private static ApplicationContextRunner withDbSchema(ApplicationContextRunner base, String value) {
        SystemEnvironmentPropertySource variable =
                new SystemEnvironmentPropertySource("dbSchema-systemEnvironment", Map.of("DB_SCHEMA", value));
        return base.withInitializer(context -> context.getEnvironment().getPropertySources().addFirst(variable));
    }

    /**
     * Gives the runner properties whose values are kept exactly, ahead of every other property source.
     *
     * @param base       the runner to extend
     * @param properties the property names and values, blanks included
     * @return the extended runner
     */
    private static ApplicationContextRunner withUntrimmed(ApplicationContextRunner base,
            Map<String, Object> properties) {
        MapPropertySource source = new MapPropertySource("untrimmed", properties);
        return base.withInitializer(context -> context.getEnvironment().getPropertySources().addFirst(source));
    }

    /**
     * Asserts that the context started, that the URL, the pool and Flyway's list all carry the schema
     * exactly, and that the pool has not connected.
     *
     * @param context the context the runner started
     * @param schema  the expected schema
     */
    private static void assertSchemaEverywhere(AssertableApplicationContext context, String schema) {
        assertThat(context).hasNotFailed().hasSingleBean(JdbcConnectionDetails.class);
        assertThat(context.getBean(JdbcConnectionDetails.class).getJdbcUrl())
                .isEqualTo(URL + "?currentSchema=" + schema);
        HikariDataSource dataSource = context.getBean(HikariDataSource.class);
        assertThat(dataSource.getSchema()).isEqualTo(schema);
        assertThat(dataSource.isRunning()).as("pool started").isFalse();
        assertThat(flywaySchemas(context)).containsExactly(schema);
    }

    /**
     * Binds {@code spring.flyway.schemas} as Spring Boot binds it for Flyway.
     *
     * @param context the started context
     * @return the bound list, empty when the property is absent or empty
     */
    private static List<String> flywaySchemas(AssertableApplicationContext context) {
        return Binder.get(context.getEnvironment())
                .bind("spring.flyway.schemas", Bindable.listOf(String.class))
                .orElse(List.of());
    }

    /**
     * Asserts that the context failed because of the guard and returns the guard's message.
     *
     * @param context the context the runner tried to start
     * @return the message of the root cause, the guard's {@link DataSourceSchemaGuard.InvalidSchemaException},
     *         an {@link IllegalStateException}
     */
    private static String guardMessage(AssertableApplicationContext context) {
        assertThat(context).hasFailed();
        Throwable root = context.getStartupFailure();
        while (root.getCause() != null) {
            root = root.getCause();
        }
        assertThat(root).isInstanceOf(IllegalStateException.class)
                .isInstanceOf(DataSourceSchemaGuard.InvalidSchemaException.class)
                .hasMessageStartingWith("Database schema invalid: ");
        return root.getMessage();
    }

    /**
     * Asserts that no exception in the startup failure's cause chain mentions any of the given values.
     *
     * @param context the failed context
     * @param values  URLs, hosts and credential values that must not appear
     */
    private static void assertNoValueInFailure(AssertableApplicationContext context, String... values) {
        for (Throwable failure = context.getStartupFailure(); failure != null; failure = failure.getCause()) {
            assertThat(String.valueOf(failure)).doesNotContain(values);
        }
    }

    /**
     * Connection details an application or a test framework registers in place of Spring Boot's
     * properties-based ones, as Testcontainers {@code @ServiceConnection} does.
     */
    private static final class FixedJdbcConnectionDetails implements JdbcConnectionDetails {

        private final String jdbcUrl;

        FixedJdbcConnectionDetails(String jdbcUrl) {
            this.jdbcUrl = jdbcUrl;
        }

        @Override
        public String getUsername() {
            return USERNAME;
        }

        @Override
        public String getPassword() {
            return PASSWORD;
        }

        @Override
        public String getJdbcUrl() {
            return jdbcUrl;
        }
    }
}
