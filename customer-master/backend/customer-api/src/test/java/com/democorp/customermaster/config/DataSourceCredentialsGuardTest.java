package com.democorp.customermaster.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.JdbcConnectionDetails;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * Specifies {@link DataSourceCredentialsGuard}: the database role's username and password, bound from
 * {@code DB_USER} and {@code DB_PASSWORD} with no default, must have a value before Spring Boot builds
 * the {@code DataSource}, or startup fails naming the missing keys and never a value.
 *
 * <p><b>Scenarios.</b> Each missing key alone, both at once, blank values, the guard failing ahead of
 * Flyway, a replacing {@link JdbcConnectionDetails} bean (as Testcontainers {@code @ServiceConnection}
 * registers) honoured over empty properties and checked itself, and complete properties.
 *
 * <p>The runner holds {@link DataSourceAutoConfiguration} and the guard only, with no web server, as
 * the {@code generator} profile runs; the Flyway scenario adds {@link FlywayAutoConfiguration}.
 * Building the Hikari pool opens no connection, so no database is needed. Replacing details are
 * registered with {@code withBean}: this class declares no configuration class or stereotype, because
 * test classes are on the classpath when an {@code ApplicationContextRunner} runs the application's
 * component scan. Every credential, host and URL here is fictitious.
 */
@DisplayName("DataSourceCredentialsGuard: no database use without a username and a password")
class DataSourceCredentialsGuardTest {

    /** A JDBC URL that is never connected to; its host must not appear in a failure. */
    private static final String URL =
            "jdbc:postgresql://guard-test-host:5432/customermaster?currentSchema=customer_master";

    /** The host part of {@link #URL}. */
    private static final String HOST = "guard-test-host";

    /** A fictitious role name. */
    private static final String USERNAME = "guard-test-role";

    /** A fictitious password; it must never appear in a failure. */
    private static final String PASSWORD = "guard-test-secret";

    /** The details Spring Boot derives from {@code spring.datasource.*} (a package-private class). */
    private static final String PROPERTIES_DETAILS =
            "org.springframework.boot.autoconfigure.jdbc.PropertiesJdbcConnectionDetails";

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(DataSourceAutoConfiguration.class))
            .withBean(DataSourceCredentialsGuard.class)
            .withPropertyValues("spring.datasource.url=" + URL);

    @Test
    @DisplayName("empty username: fails naming spring.datasource.username and DB_USER only, with no value")
    void emptyUsernameFails() {
        runner.withPropertyValues("spring.datasource.username=", "spring.datasource.password=" + PASSWORD)
                .run(context -> {
                    String message = guardMessage(context);
                    assertThat(message)
                            .contains("spring.datasource.username", "DB_USER", PROPERTIES_DETAILS)
                            .doesNotContain("spring.datasource.password", "DB_PASSWORD");
                    assertNoValueInFailure(context, PASSWORD, URL, HOST);
                });
    }

    @Test
    @DisplayName("empty password: fails naming spring.datasource.password and DB_PASSWORD only, with no value")
    void emptyPasswordFails() {
        runner.withPropertyValues("spring.datasource.username=" + USERNAME, "spring.datasource.password=")
                .run(context -> {
                    String message = guardMessage(context);
                    assertThat(message)
                            .contains("spring.datasource.password", "DB_PASSWORD", PROPERTIES_DETAILS)
                            .doesNotContain("spring.datasource.username", "DB_USER");
                    assertNoValueInFailure(context, USERNAME, URL, HOST);
                });
    }

    @Test
    @DisplayName("both empty, as application.yml binds unset variables: one failure names both keys")
    void bothEmptyFailsNamingBoth() {
        runner.withPropertyValues("spring.datasource.username=", "spring.datasource.password=")
                .run(context -> {
                    assertThat(guardMessage(context)).isEqualTo("Database credentials missing: "
                            + "spring.datasource.username (environment variable DB_USER) and "
                            + "spring.datasource.password (environment variable DB_PASSWORD) have no value in"
                            + " connection details " + PROPERTIES_DETAILS + ". The API, Flyway and the"
                            + " generator connect as this one role, so startup stops before any database"
                            + " connection is opened.");
                    assertNoValueInFailure(context, URL, HOST);
                });
    }

    @Test
    @DisplayName("with Flyway auto-configured, the guard fails first: no migration and no connection attempt")
    void failsBeforeFlyway() {
        runner.withConfiguration(AutoConfigurations.of(FlywayAutoConfiguration.class))
                .withPropertyValues("spring.datasource.username=" + USERNAME, "spring.datasource.password=")
                .run(context -> assertThat(guardMessage(context)).contains("DB_PASSWORD"));
    }

    @Test
    @DisplayName("blank values from replacing details count as missing, and the details type is named")
    void blankDetailsFail() {
        runner.withBean(JdbcConnectionDetails.class, () -> new FixedJdbcConnectionDetails("  ", "\t", URL))
                .run(context -> {
                    assertThat(guardMessage(context))
                            .contains("DB_USER", "DB_PASSWORD", FixedJdbcConnectionDetails.class.getName());
                    assertNoValueInFailure(context, URL, HOST);
                });
    }

    @Test
    @DisplayName("replacing details are checked, not the properties: an empty password fails despite"
            + " spring.datasource.password")
    void replacingDetailsAreTheCheckedSource() {
        runner.withPropertyValues("spring.datasource.username=" + USERNAME, "spring.datasource.password=" + PASSWORD)
                .withBean(JdbcConnectionDetails.class, () -> new FixedJdbcConnectionDetails(USERNAME, "", URL))
                .run(context -> {
                    assertThat(guardMessage(context))
                            .contains("DB_PASSWORD", FixedJdbcConnectionDetails.class.getName())
                            .doesNotContain("DB_USER");
                    assertNoValueInFailure(context, USERNAME, PASSWORD, URL, HOST);
                });
    }

    @Test
    @DisplayName("replacing details with credentials start the context although the properties are empty,"
            + " as @ServiceConnection does")
    void replacingDetailsWithCredentialsStart() {
        runner.withPropertyValues("spring.datasource.username=", "spring.datasource.password=")
                .withBean(JdbcConnectionDetails.class,
                        () -> new FixedJdbcConnectionDetails(USERNAME, PASSWORD, URL))
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(JdbcConnectionDetails.class);
                    assertThat(context.getBean(JdbcConnectionDetails.class))
                            .isInstanceOf(FixedJdbcConnectionDetails.class);
                    HikariDataSource dataSource = context.getBean(HikariDataSource.class);
                    assertThat(dataSource.getUsername()).isEqualTo(USERNAME);
                    assertThat(dataSource.getPassword()).isEqualTo(PASSWORD);
                });
    }

    @Test
    @DisplayName("complete properties start the context and build the pool from them, without connecting")
    void completePropertiesStart() {
        runner.withPropertyValues("spring.datasource.username=" + USERNAME, "spring.datasource.password=" + PASSWORD)
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(JdbcConnectionDetails.class);
                    assertThat(context.getBean(JdbcConnectionDetails.class).getClass().getName())
                            .isEqualTo(PROPERTIES_DETAILS);
                    HikariDataSource dataSource = context.getBean(HikariDataSource.class);
                    assertThat(dataSource.getUsername()).isEqualTo(USERNAME);
                    assertThat(dataSource.getPassword()).isEqualTo(PASSWORD);
                    assertThat(dataSource.isRunning()).as("pool started").isFalse();
                });
    }

    /**
     * Asserts that the context failed because of the guard and returns the guard's message.
     *
     * @param context the context the runner tried to start
     * @return the message of the root cause, the guard's {@link IllegalStateException}
     */
    private static String guardMessage(AssertableApplicationContext context) {
        assertThat(context).hasFailed();
        Throwable root = context.getStartupFailure();
        while (root.getCause() != null) {
            root = root.getCause();
        }
        assertThat(root).isInstanceOf(IllegalStateException.class)
                .hasMessageStartingWith("Database credentials missing: ");
        return root.getMessage();
    }

    /**
     * Asserts that no exception in the startup failure's cause chain mentions any of the given values.
     *
     * @param context the failed context
     * @param values  credential values, URLs and hosts that must not appear
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

        private final String username;

        private final String password;

        private final String jdbcUrl;

        FixedJdbcConnectionDetails(String username, String password, String jdbcUrl) {
            this.username = username;
            this.password = password;
            this.jdbcUrl = jdbcUrl;
        }

        @Override
        public String getUsername() {
            return username;
        }

        @Override
        public String getPassword() {
            return password;
        }

        @Override
        public String getJdbcUrl() {
            return jdbcUrl;
        }
    }
}
