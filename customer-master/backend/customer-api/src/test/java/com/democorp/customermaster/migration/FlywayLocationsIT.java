package com.democorp.customermaster.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;

import com.democorp.customermaster.support.AbstractPostgresIT;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Proves that the Flyway configuration of {@code application.yml} stops startup on a location that
 * cannot be resolved ({@code spring.flyway.fail-on-missing-locations: true}), and still starts on the
 * two real ones: {@code classpath:db/migration} alone (the default) and with {@code classpath:db/seed}
 * (as Docker Compose sets {@code SPRING_FLYWAY_LOCATIONS}).
 *
 * <p><b>Why.</b> Flyway's default skips a location it cannot resolve without a warning. Under it,
 * {@code SPRING_FLYWAY_LOCATIONS=classpath:db/migratoin} on an empty database creates only
 * {@code flyway_schema_history} and reports the schema up to date, and the API answers readiness
 * {@code UP} while every query fails with 42P01.
 *
 * <p><b>How.</b> Each scenario starts a short-lived context with an {@link ApplicationContextRunner}
 * over the real {@code application.yml} (no profile) holding only the datasource and Flyway
 * auto-configurations, against {@code POSTGRES} through the {@code DB_*} placeholders of the
 * datasource URL. {@code DB_SCHEMA} names a new schema {@code flit_<uuid>}, which {@code finally}
 * drops, so {@code customer_master} is never touched and the methods run in any order. The location
 * under test is given as {@code spring.flyway.locations}, the property {@code SPRING_FLYWAY_LOCATIONS}
 * binds to.
 *
 * <p>The class uses the base context of {@link AbstractPostgresIT}: no {@code @MockitoBean} and no
 * {@code @Import}, so it adds no context variant. The runner's contexts stay outside the Spring test
 * context cache and are closed, with their pools, before each method returns.
 */
@DisplayName("Flyway locations from application.yml: an unresolvable location fails startup")
class FlywayLocationsIT extends AbstractPostgresIT {

    /** The migrations every context applies: V1 to V4. */
    private static final String MIGRATION_LOCATION = "classpath:db/migration";

    /** The seed location Docker Compose adds: V5. */
    private static final String SEED_LOCATION = "classpath:db/seed";

    /** The form of every generated schema name: safe to double-quote. */
    private static final String SCHEMA_NAME_FORMAT = "flit_[0-9a-f]{32}";

    /** Every table of the database outside the system catalogs, in name order. */
    private static final String ALL_TABLES = "select table_schema || '.' || table_name"
            + " from information_schema.tables"
            + " where table_schema not in ('pg_catalog', 'information_schema') order by 1";

    /** The number of seed rows: Custmast.sql ids 1..300. */
    private static final int SEED_ROWS = 300;

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
        "classpath:db/migratoin | classpath:db/migratoin",
        "classpath:db/migration,classpath:db/sed | classpath:db/sed"
    })
    @DisplayName("a misspelt location fails startup naming it, before any schema or table is created")
    void unresolvableLocationFailsStartupAndCreatesNothing(String locations, String unresolvable) {
        List<String> tablesBefore = jdbcTemplate.queryForList(ALL_TABLES, String.class);
        inFreshSchema(locations, (schema, context) -> {
            assertThat(context).hasFailed();
            Throwable root = context.getStartupFailure();
            while (root.getCause() != null) {
                root = root.getCause();
            }
            assertThat(root).isInstanceOf(FlywayException.class)
                    .hasMessage("Unable to resolve location " + unresolvable + ".");

            assertThat(count("select count(*) from pg_namespace where nspname = ?", schema))
                    .as("schema %s created before the failure", schema)
                    .isZero();
            assertThat(jdbcTemplate.queryForList(ALL_TABLES, String.class))
                    .as("tables of the database after the failed startup")
                    .containsExactlyElementsOf(tablesBefore);
        });
    }

    @Test
    @DisplayName("the default location starts and applies V1-V4, without the seed")
    void defaultLocationStartsAndAppliesV1toV4() {
        inFreshSchema(null, (schema, context) -> {
            assertStartedWithGuard(context);
            assertThat(appliedVersions(schema)).containsExactly("1", "2", "3", "4");
            assertThat(count("select count(*) from " + quoted(schema) + ".custmast")).isZero();
        });
    }

    @Test
    @DisplayName("the Compose locations db/migration and db/seed start and apply V1-V5")
    void composeLocationsStartAndApplyV1toV5() {
        inFreshSchema(MIGRATION_LOCATION + "," + SEED_LOCATION, (schema, context) -> {
            assertStartedWithGuard(context);
            assertThat(appliedVersions(schema)).containsExactly("1", "2", "3", "4", "5");
            assertThat(count("select count(*) from " + quoted(schema) + ".custmast")).isEqualTo(SEED_ROWS);
        });
    }

    /**
     * Starts the datasource and Flyway over {@code application.yml} with {@code DB_SCHEMA} set to a new
     * schema, runs the scenario against the started or failed context, and drops the schema, then
     * asserts that no schema of that name is left.
     *
     * @param locations the value of {@code spring.flyway.locations}, or {@code null} to keep the value
     *                  of {@code application.yml}
     * @param scenario  the assertions, given the schema name and the context
     */
    private void inFreshSchema(String locations, SchemaScenario scenario) {
        String schema = "flit_" + UUID.randomUUID().toString().replace("-", "");
        assertThat(schema).matches(SCHEMA_NAME_FORMAT);
        ApplicationContextRunner runner = new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withConfiguration(AutoConfigurations.of(
                        DataSourceAutoConfiguration.class, FlywayAutoConfiguration.class))
                .withPropertyValues(
                        "DB_HOST=" + POSTGRES.getHost(),
                        "DB_PORT=" + POSTGRES.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT),
                        "DB_NAME=" + POSTGRES.getDatabaseName(),
                        "DB_USER=" + POSTGRES.getUsername(),
                        "DB_PASSWORD=" + POSTGRES.getPassword(),
                        "DB_SCHEMA=" + schema);
        if (locations != null) {
            runner = runner.withPropertyValues("spring.flyway.locations=" + locations);
        }
        try {
            runner.run(context -> scenario.accept(schema, context));
        } finally {
            jdbcTemplate.execute("DROP SCHEMA IF EXISTS " + quoted(schema) + " CASCADE");
        }
        assertThat(count("select count(*) from pg_namespace where nspname = ?", schema))
                .as("schemas named %s after the scenario", schema)
                .isZero();
    }

    /**
     * Asserts that the context started with one {@link Flyway} whose configuration, taken from
     * {@code application.yml}, fails on a missing location.
     *
     * @param context the context the runner started
     */
    private static void assertStartedWithGuard(AssertableApplicationContext context) {
        assertThat(context).hasNotFailed().hasSingleBean(Flyway.class);
        assertThat(context.getBean(Flyway.class).getConfiguration().isFailOnMissingLocations()).isTrue();
    }

    /**
     * Returns the versions Flyway recorded as successfully applied in the schema, in install order.
     *
     * @param schema the migrated schema
     * @return the versions, without the schema-creation marker row
     */
    private List<String> appliedVersions(String schema) {
        return jdbcTemplate.queryForList(
                "select version from " + quoted(schema) + ".flyway_schema_history"
                        + " where success and version is not null order by installed_rank",
                String.class);
    }

    /**
     * Runs a {@code count(*)} query.
     *
     * @param sql  the query
     * @param args its bound parameters
     * @return the count
     */
    private long count(String sql, Object... args) {
        Long count = jdbcTemplate.queryForObject(sql, Long.class, args);
        assertThat(count).as("result of %s", sql).isNotNull();
        return count;
    }

    /**
     * Quotes a generated schema name for use in SQL text.
     *
     * @param schema a name matching {@link #SCHEMA_NAME_FORMAT}
     * @return the name in double quotes
     * @throws IllegalArgumentException if the name does not match that format
     */
    private static String quoted(String schema) {
        if (!schema.matches(SCHEMA_NAME_FORMAT)) {
            throw new IllegalArgumentException("not a generated schema name: " + schema);
        }
        return "\"" + schema + "\"";
    }

    /** The assertions one scenario runs against its schema and the context the runner built. */
    @FunctionalInterface
    private interface SchemaScenario {

        /**
         * Runs the assertions.
         *
         * @param schema  the schema {@code DB_SCHEMA} named
         * @param context the started or failed context
         */
        void accept(String schema, AssertableApplicationContext context);
    }
}
