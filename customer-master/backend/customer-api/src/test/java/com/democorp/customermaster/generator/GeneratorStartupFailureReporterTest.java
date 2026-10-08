package com.democorp.customermaster.generator;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.ThrowableProxy;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.read.ListAppender;
import com.democorp.customermaster.config.AppProperties;
import com.democorp.customermaster.config.DataSourceCredentialsGuard;
import com.democorp.customermaster.config.DataSourceCredentialsGuard.MissingCredentialsException;
import com.democorp.customermaster.config.DataSourceSchemaGuard;
import com.democorp.customermaster.config.DataSourceSchemaGuard.InvalidSchemaException;
import com.democorp.customermaster.repository.LockWaitingReads;
import com.democorp.customermaster.repository.LockWaitingReads.InvalidLockTimeoutException;
import com.zaxxer.hikari.HikariDataSource;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeanInstantiationException;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.beans.factory.UnsatisfiedDependencyException;
import org.springframework.boot.Banner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.SpringBootExceptionReporter;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.JdbcConnectionDetails;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.io.support.SpringFactoriesLoader;
import org.springframework.core.io.support.SpringFactoriesLoader.ArgumentResolver;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.util.StringUtils;

/**
 * Specifies {@link GeneratorStartupFailureReporter}: a generator start that fails for want of its
 * database prints one line naming the cause, as the generator's other failures do, instead of Spring
 * Boot's {@code Application run failed} and a stack trace; every other failure and every other context
 * is left to Spring Boot.
 *
 * <p><b>Scenarios.</b> The failures a wrong {@code DB_PASSWORD}, an unknown {@code DB_HOST}, a refused
 * {@code DB_PORT}, a missing {@code DB_NAME}, an empty {@code DB_USER}, an empty {@code DB_SCHEMA} and a
 * {@code DB_LOCK_TIMEOUT} the lock-timeout check of {@link LockWaitingReads} refuses raise, wrapped as
 * Spring wraps them during the context refresh; the schema guard's and the lock-timeout check's failures
 * on their own; the precedence of credentials over schema over lock timeout over connection failures;
 * failures that are not about the database; the web context; a {@code null} context; URLs whose query or
 * user information carries a secret; cyclic and chained cause graphs; the DEBUG lines of a reported
 * failure and of a failure while reporting, which carry the exception graph value-free (types, stack
 * frames, SQLSTATEs) whatever data and line breaks its messages hold; the
 * {@code META-INF/spring.factories} registration and its order ahead of Spring Boot's
 * {@code FailureAnalyzers}; and starts through {@link SpringApplication} with and without the profile,
 * with the real {@link DataSourceSchemaGuard} or the real {@link LockWaitingReads}.
 *
 * <p>Plain JUnit 5 and AssertJ over the reporter's package-private constructor, printing to a
 * {@link ByteArrayOutputStream}; no database and no Docker. The exceptions are built as pgjdbc and
 * Spring raise them. This class and its nested source classes carry no stereotype, because test classes
 * are on the classpath when other tests run the application's component scan. Every host, credential
 * and URL here is fictitious.
 */
@DisplayName("GeneratorStartupFailureReporter: a database startup failure of the generator is one line")
final class GeneratorStartupFailureReporterTest {

    /** The fictitious database address of most scenarios. */
    private static final String AUTHORITY = "reporter-test-host:20044";

    /** The datasource URL as {@code application.yml} resolves it. */
    private static final String URL =
            "jdbc:postgresql://" + AUTHORITY + "/customermaster?currentSchema=customer_master";

    /** A fictitious password; it must never be printed. */
    private static final String SECRET = "reporter-test-secret";

    /** The message PostgreSQL sends for a wrong password (SQLSTATE 28P01). */
    private static final String AUTH_FAILED = "FATAL: password authentication failed for user \"customermaster\"";

    /** The message pgjdbc gives for a connect that never reached a server. */
    private static final String ATTEMPT_FAILED = "The connection attempt failed.";

    /** Spring Boot's own reporter, a package-private class, asked after this one. */
    private static final String FAILURE_ANALYZERS = "org.springframework.boot.diagnostics.FailureAnalyzers";

    /**
     * Message text of the DEBUG cases that no log line may carry; every CR or LF in those messages is
     * followed by {@code FORGED}, so a line break that survived would carry it too.
     */
    private static final List<String> SENTINELS = List.of(
            "TOP-SENTINEL", "CONNECTION-SENTINEL", "CAUSE-SENTINEL", "NEXT-SENTINEL", "SUPPRESSED-SENTINEL",
            "CONTEXT-SENTINEL", "FORGED");

    /** Receives the reporter's line. */
    private final ByteArrayOutputStream printed = new ByteArrayOutputStream();

    @Test
    @DisplayName("wrong password: one line with host:port and the server's 28P01 message")
    void wrongPasswordIsOneLine() {
        RuntimeException failure = startupFailure(
                new PSQLException(AUTH_FAILED, PSQLState.INVALID_PASSWORD));

        assertThat(reporter(URL, CustomerGeneratorRunner.PROFILE).reportException(failure)).isTrue();

        assertThat(printedLines()).containsExactly("Cannot connect to database " + AUTHORITY + ": " + AUTH_FAILED);
    }

    @Test
    @DisplayName("unknown host: names the host the resolver could not find")
    void unknownHostIsOneLine() {
        RuntimeException failure = startupFailure(new PSQLException(ATTEMPT_FAILED,
                PSQLState.CONNECTION_UNABLE_TO_CONNECT, new UnknownHostException("nohost-qa")));

        assertThat(reporter("jdbc:postgresql://nohost-qa:5432/customermaster?currentSchema=customer_master",
                CustomerGeneratorRunner.PROFILE).reportException(failure)).isTrue();

        assertThat(printedLines())
                .containsExactly("Cannot connect to database nohost-qa:5432: unknown host nohost-qa");
    }

    @Test
    @DisplayName("refused port: the socket failure, in lower case after the colon")
    void refusedPortIsOneLine() {
        RuntimeException failure = startupFailure(new PSQLException("Connection to localhost:20049 refused. Check"
                + " that the hostname and port are correct and that the postmaster is accepting TCP/IP connections.",
                PSQLState.CONNECTION_UNABLE_TO_CONNECT, new ConnectException("Connection refused")));

        assertThat(reporter("jdbc:postgresql://localhost:20049/customermaster", CustomerGeneratorRunner.PROFILE)
                .reportException(failure)).isTrue();

        assertThat(printedLines()).containsExactly("Cannot connect to database localhost:20049: connection refused");
    }

    @Test
    @DisplayName("connect timeout: the socket timeout is the reason")
    void connectTimeoutIsOneLine() {
        RuntimeException failure = startupFailure(new PSQLException(ATTEMPT_FAILED,
                PSQLState.CONNECTION_UNABLE_TO_CONNECT, new SocketTimeoutException("Connect timed out")));

        assertThat(reporter(URL, CustomerGeneratorRunner.PROFILE).reportException(failure)).isTrue();

        assertThat(printedLines()).containsExactly("Cannot connect to database " + AUTHORITY + ": connect timed out");
    }

    @Test
    @DisplayName("missing database (3D000 under CannotGetJdbcConnectionException): the deepest SQL message")
    void missingDatabaseIsOneLine() {
        RuntimeException failure = startupFailure(
                new SQLException("FATAL: database \"nodb\" does not exist", "3D000"));

        assertThat(reporter(URL, CustomerGeneratorRunner.PROFILE).reportException(failure)).isTrue();

        assertThat(printedLines())
                .containsExactly("Cannot connect to database " + AUTHORITY
                        + ": FATAL: database \"nodb\" does not exist");
    }

    @Test
    @DisplayName("empty DB_USER: prints the credentials guard's own message")
    void missingCredentialsPrintGuardMessage() {
        JdbcConnectionDetails details = mock(JdbcConnectionDetails.class);
        when(details.getUsername()).thenReturn("");
        when(details.getPassword()).thenReturn(SECRET);
        when(details.getJdbcUrl()).thenReturn(URL);
        DataSourceCredentialsGuard guard = new DataSourceCredentialsGuard();
        MissingCredentialsException missing = catchThrowableOfType(MissingCredentialsException.class,
                () -> guard.postProcessAfterInitialization(details, "jdbcConnectionDetails"));
        RuntimeException failure = new BeanCreationException("dataSource", "Unsatisfied dependency",
                new BeanCreationException("jdbcConnectionDetails", missing.getMessage(), missing));

        assertThat(reporter(URL, CustomerGeneratorRunner.PROFILE).reportException(failure)).isTrue();

        assertThat(printedLines()).containsExactly(missing.getMessage());
        assertThat(missing.getMessage())
                .startsWith("Database credentials missing: spring.datasource.username (environment variable DB_USER)")
                .doesNotContain(SECRET, URL);
    }

    @Test
    @DisplayName("empty DB_SCHEMA, wrapped as the context refresh wraps it: prints the schema guard's own message")
    void invalidSchemaPrintsGuardMessage() {
        InvalidSchemaException invalid = schemaGuardFailure();
        RuntimeException failure = new BeanCreationException("dataSource", "Unsatisfied dependency",
                new BeanCreationException("jdbcConnectionDetails", invalid.getMessage(), invalid));

        assertThat(reporter(URL, CustomerGeneratorRunner.PROFILE).reportException(failure)).isTrue();

        assertThat(printedLines()).containsExactly(invalid.getMessage());
        assertThat(invalid.getMessage())
                .startsWith("Database schema invalid: spring.datasource.hikari.schema is empty or blank.")
                .contains("environment variable DB_SCHEMA")
                .doesNotContain(SECRET, URL);
    }

    @Test
    @DisplayName("the schema guard's failure itself, unwrapped: the same one line")
    void invalidSchemaAloneIsOneLine() {
        InvalidSchemaException invalid = schemaGuardFailure();

        assertThat(reporter(URL, CustomerGeneratorRunner.PROFILE).reportException(invalid)).isTrue();

        assertThat(printedLines()).containsExactly(invalid.getMessage());
    }

    @Test
    @DisplayName("credentials and schema failures in one cause graph: the credentials line, in either order;"
            + " a schema failure under a connection failure: the schema line")
    void credentialsFailureTakesPrecedenceOverSchemaFailure() {
        MissingCredentialsException missingBelow = credentialsGuardFailure();
        InvalidSchemaException schemaAbove = schemaGuardFailure();
        schemaAbove.initCause(missingBelow);
        InvalidSchemaException schemaBelow = schemaGuardFailure();
        MissingCredentialsException missingAbove = credentialsGuardFailure();
        missingAbove.initCause(schemaBelow);
        InvalidSchemaException underConnection = schemaGuardFailure();
        GeneratorStartupFailureReporter reporter = reporter(URL, CustomerGeneratorRunner.PROFILE);

        assertThat(reporter.reportException(new BeanCreationException("dataSource", "failed", schemaAbove))).isTrue();
        assertThat(reporter.reportException(new BeanCreationException("dataSource", "failed", missingAbove))).isTrue();
        assertThat(reporter.reportException(new BeanCreationException("jdbcDialect", "failed",
                new CannotGetJdbcConnectionException("Failed to obtain JDBC Connection", underConnection)))).isTrue();

        assertThat(printedLines()).containsExactly(
                missingBelow.getMessage(), missingAbove.getMessage(), underConnection.getMessage());
        assertThat(missingBelow.getMessage()).startsWith("Database credentials missing: ");
        assertThat(underConnection.getMessage()).startsWith("Database schema invalid: ");
    }

    @Test
    @DisplayName("empty DB_SCHEMA outside the generator profile: left to Spring Boot")
    void invalidSchemaOutsideGeneratorIsLeftToSpringBoot() {
        InvalidSchemaException invalid = schemaGuardFailure();
        RuntimeException failure = new BeanCreationException("dataSource", "Unsatisfied dependency",
                new BeanCreationException("jdbcConnectionDetails", invalid.getMessage(), invalid));

        assertThat(reporter(URL).reportException(failure)).isFalse();
        assertThat(reporter(URL, "test").reportException(invalid)).isFalse();

        assertThat(printedLines()).isEmpty();
    }

    @Test
    @DisplayName("DB_LOCK_TIMEOUT not below the socket bound, wrapped as the context refresh wraps it: the check's"
            + " own message after 'Database lock timeout invalid: '")
    void invalidLockTimeoutPrintsPrefixedMessage() {
        InvalidLockTimeoutException sixty = lockTimeoutGuardFailure(Duration.ofSeconds(60));
        InvalidLockTimeoutException fortyFive = lockTimeoutGuardFailure(Duration.ofSeconds(45));
        GeneratorStartupFailureReporter reporter = reporter(URL, CustomerGeneratorRunner.PROFILE);

        assertThat(reporter.reportException(lockWaitingReadsStartupFailure(sixty))).isTrue();
        assertThat(reporter.reportException(lockWaitingReadsStartupFailure(fortyFive))).isTrue();

        assertThat(printedLines()).containsExactly(
                "Database lock timeout invalid: " + sixty.getMessage(),
                "Database lock timeout invalid: " + fortyFive.getMessage());
        assertThat(sixty.getMessage())
                .startsWith("customer-master.db.lock-timeout (DB_LOCK_TIMEOUT) must be below"
                        + " spring.datasource.hikari.data-source-properties.socketTimeout")
                .endsWith("PT1M is not below PT45S")
                .doesNotContain(SECRET, URL);
        assertThat(fortyFive.getMessage()).endsWith("PT45S is not below PT45S");
        assertThat(printed.toString(UTF_8)).doesNotContain(SECRET, "currentSchema");
    }

    @Test
    @DisplayName("the lock-timeout check's failure itself, unwrapped, for each of its faults: one prefixed line")
    void invalidLockTimeoutAloneIsOneLine() {
        InvalidLockTimeoutException aboveBound = lockTimeoutGuardFailure(Duration.ofSeconds(60));
        InvalidLockTimeoutException zero = lockTimeoutGuardFailure(Duration.ZERO);
        InvalidLockTimeoutException negative = lockTimeoutGuardFailure(Duration.ofSeconds(-1));
        InvalidLockTimeoutException fractionalBound = lockTimeoutGuardFailure(Duration.ofSeconds(5), "4.5");
        GeneratorStartupFailureReporter reporter = reporter(URL, CustomerGeneratorRunner.PROFILE);

        for (InvalidLockTimeoutException invalid : List.of(aboveBound, zero, negative, fractionalBound)) {
            assertThat(reporter.reportException(invalid)).isTrue();
        }

        assertThat(printedLines()).containsExactly(
                "Database lock timeout invalid: " + aboveBound.getMessage(),
                "Database lock timeout invalid: customer-master.db.lock-timeout must be between 1 ms and "
                        + Integer.MAX_VALUE + " ms, was PT0S",
                "Database lock timeout invalid: customer-master.db.lock-timeout must be between 1 ms and "
                        + Integer.MAX_VALUE + " ms, was PT-1S",
                "Database lock timeout invalid: spring.datasource.hikari.data-source-properties.socketTimeout"
                        + " (or socketTimeout in the JDBC URL) must be a whole number of seconds");
        assertThat(zero).hasCauseInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("lock-timeout failure in one cause graph with others: credentials and schema lines win over it,"
            + " it wins over a connection failure above or below it")
    void lockTimeoutFailureSitsBetweenSchemaAndConnectionFailures() {
        InvalidSchemaException schemaAbove = schemaGuardFailure();
        schemaAbove.initCause(lockTimeoutGuardFailure(Duration.ofSeconds(60)));
        MissingCredentialsException credentialsAbove = credentialsGuardFailure();
        credentialsAbove.initCause(lockTimeoutGuardFailure(Duration.ofSeconds(60)));
        InvalidLockTimeoutException lockAboveSchema = lockTimeoutGuardFailure(Duration.ofSeconds(60));
        InvalidSchemaException schemaBelow = schemaGuardFailure();
        lockAboveSchema.initCause(schemaBelow);
        InvalidLockTimeoutException underConnection = lockTimeoutGuardFailure(Duration.ofSeconds(60));
        InvalidLockTimeoutException aboveConnection = lockTimeoutGuardFailure(Duration.ofSeconds(45));
        aboveConnection.initCause(new PSQLException(AUTH_FAILED, PSQLState.INVALID_PASSWORD));
        GeneratorStartupFailureReporter reporter = reporter(URL, CustomerGeneratorRunner.PROFILE);

        assertThat(reporter.reportException(new BeanCreationException("dataSource", "failed", schemaAbove))).isTrue();
        assertThat(reporter.reportException(new BeanCreationException("dataSource", "failed", credentialsAbove)))
                .isTrue();
        assertThat(reporter.reportException(lockWaitingReadsStartupFailure(lockAboveSchema))).isTrue();
        assertThat(reporter.reportException(new BeanCreationException("jdbcDialect", "failed",
                new CannotGetJdbcConnectionException("Failed to obtain JDBC Connection", underConnection)))).isTrue();
        assertThat(reporter.reportException(lockWaitingReadsStartupFailure(aboveConnection))).isTrue();

        assertThat(printedLines()).containsExactly(
                schemaAbove.getMessage(),
                credentialsAbove.getMessage(),
                schemaBelow.getMessage(),
                "Database lock timeout invalid: " + underConnection.getMessage(),
                "Database lock timeout invalid: " + aboveConnection.getMessage());
        assertThat(printed.toString(UTF_8)).doesNotContain("Cannot connect to database", AUTH_FAILED);
    }

    @Test
    @DisplayName("DB_LOCK_TIMEOUT not below the socket bound outside the generator profile: left to Spring Boot")
    void invalidLockTimeoutOutsideGeneratorIsLeftToSpringBoot() {
        InvalidLockTimeoutException invalid = lockTimeoutGuardFailure(Duration.ofSeconds(60));

        assertThat(reporter(URL).reportException(lockWaitingReadsStartupFailure(invalid))).isFalse();
        assertThat(reporter(URL, "test").reportException(invalid)).isFalse();

        assertThat(printedLines()).isEmpty();
    }

    @Test
    @DisplayName("not a database failure (option validation, plain state, SQL grammar): false, nothing printed")
    void otherFailuresAreLeftToSpringBoot() {
        GeneratorStartupFailureReporter reporter = reporter(URL, CustomerGeneratorRunner.PROFILE);

        assertThat(reporter.reportException(new BeanCreationException("customerGeneratorRunner",
                "Could not bind properties to 'GeneratorProperties'",
                new IllegalStateException("count must be greater than or equal to 1")))).isFalse();
        assertThat(reporter.reportException(new IllegalStateException("Failed to execute ApplicationRunner")))
                .isFalse();
        assertThat(reporter.reportException(new BeanCreationException("stateService", "init failed",
                new BadSqlGrammarException("states", "SELECT 1", new SQLException("relation missing", "42P01")))))
                .isFalse();

        assertThat(printedLines()).isEmpty();
    }

    @Test
    @DisplayName("web context (no generator profile): the same failure is left to Spring Boot")
    void webContextIsLeftToSpringBoot() {
        RuntimeException failure = startupFailure(new PSQLException(AUTH_FAILED, PSQLState.INVALID_PASSWORD));

        assertThat(reporter(URL).reportException(failure)).isFalse();
        assertThat(reporter(URL, "test").reportException(failure)).isFalse();

        assertThat(printedLines()).isEmpty();
    }

    @Test
    @DisplayName("null context, null failure, or a context that cannot answer: false, nothing printed, no throw")
    void unusableInputIsLeftToSpringBoot() {
        RuntimeException failure = startupFailure(new PSQLException(AUTH_FAILED, PSQLState.INVALID_PASSWORD));
        ConfigurableApplicationContext closed = mock(ConfigurableApplicationContext.class);
        when(closed.getEnvironment()).thenThrow(new IllegalStateException("context closed"));

        assertThat(new GeneratorStartupFailureReporter(null, printStream()).reportException(failure)).isFalse();
        assertThat(reporter(URL, CustomerGeneratorRunner.PROFILE).reportException(null)).isFalse();
        assertThat(new GeneratorStartupFailureReporter(closed, printStream()).reportException(failure)).isFalse();

        assertThat(printedLines()).isEmpty();
    }

    @Test
    @DisplayName("a password in the URL query is never printed; only the authority is")
    void queryPasswordIsNeverPrinted() {
        RuntimeException failure = startupFailure(new PSQLException(AUTH_FAILED, PSQLState.INVALID_PASSWORD));

        assertThat(reporter(URL + "&user=customermaster&password=" + SECRET, CustomerGeneratorRunner.PROFILE)
                .reportException(failure)).isTrue();

        assertThat(printedLines()).containsExactly("Cannot connect to database " + AUTHORITY + ": " + AUTH_FAILED);
        assertThat(printed.toString(UTF_8)).doesNotContain(SECRET, "password=", "currentSchema", "user=");
    }

    @Test
    @DisplayName("user information before the host, or no parseable authority: the address is left out")
    void unsafeOrUnparseableAuthorityIsOmitted() {
        RuntimeException failure = startupFailure(new PSQLException(AUTH_FAILED, PSQLState.INVALID_PASSWORD));

        assertThat(reporter("jdbc:postgresql://customermaster:" + SECRET + "@" + AUTHORITY + "/customermaster",
                CustomerGeneratorRunner.PROFILE).reportException(failure)).isTrue();
        assertThat(reporter("jdbc:postgresql://localhost:abc/customermaster", CustomerGeneratorRunner.PROFILE)
                .reportException(failure)).isTrue();
        assertThat(reporter(null, CustomerGeneratorRunner.PROFILE).reportException(failure)).isTrue();

        assertThat(printedLines()).containsExactly(
                "Cannot connect to database: " + AUTH_FAILED,
                "Cannot connect to database: " + AUTH_FAILED,
                "Cannot connect to database: " + AUTH_FAILED);
        assertThat(printed.toString(UTF_8)).doesNotContain(SECRET);
    }

    @Test
    @DisplayName("authority: hosts and ports only, from the prefix to the path, query or fragment")
    void authorityKeepsHostsAndPortsOnly() {
        assertThat(GeneratorStartupFailureReporter.authority(URL)).contains(AUTHORITY);
        assertThat(GeneratorStartupFailureReporter.authority("jdbc:postgresql://db/customermaster")).contains("db");
        assertThat(GeneratorStartupFailureReporter.authority("jdbc:postgresql://a.example:5432,b.example/x"))
                .contains("a.example:5432,b.example");
        assertThat(GeneratorStartupFailureReporter.authority("jdbc:postgresql://[::1]:5432/x"))
                .contains("[::1]:5432");
        assertThat(GeneratorStartupFailureReporter.authority("jdbc:postgresql://db:5432?password=" + SECRET))
                .contains("db:5432");
        assertThat(GeneratorStartupFailureReporter.authority("jdbc:postgresql://db:5432#frag")).contains("db:5432");

        assertThat(GeneratorStartupFailureReporter.authority("jdbc:postgresql:customermaster")).isEmpty();
        assertThat(GeneratorStartupFailureReporter.authority("jdbc:mysql://db:3306/x")).isEmpty();
        assertThat(GeneratorStartupFailureReporter.authority("jdbc:postgresql:///x")).isEmpty();
        assertThat(GeneratorStartupFailureReporter.authority("jdbc:postgresql://db:5432?pw=a@b")).isEmpty();
        assertThat(GeneratorStartupFailureReporter.authority("jdbc:postgresql://bad host:5432/x")).isEmpty();
        assertThat(GeneratorStartupFailureReporter.authority(null)).isEmpty();
    }

    @Test
    @DisplayName("cause graphs: a 28P01 behind SQLException.getNextException is found; a cycle ends")
    void causeGraphsAreWalkedSafely() {
        SQLException batch = new SQLException("Batch entry 0 failed", "08006");
        batch.setNextException(new PSQLException(AUTH_FAILED, PSQLState.INVALID_PASSWORD));
        assertThat(GeneratorStartupFailureReporter.describe(new BeanCreationException("jdbcDialect", "failed", batch),
                environment(URL, CustomerGeneratorRunner.PROFILE)))
                .contains("Cannot connect to database " + AUTHORITY + ": " + AUTH_FAILED);

        IllegalStateException first = new IllegalStateException("first");
        IllegalStateException second = new IllegalStateException("second", first);
        first.initCause(second);
        Optional<String> cyclic = GeneratorStartupFailureReporter.describe(first,
                environment(URL, CustomerGeneratorRunner.PROFILE));
        assertThat(cyclic).isEmpty();

        assertThat(GeneratorStartupFailureReporter.describe(null, environment(URL, CustomerGeneratorRunner.PROFILE)))
                .isEmpty();
    }

    @Test
    @DisplayName("DEBUG of a reported failure: the printed line once, then only the graph's types, SQLSTATEs, frames")
    void reportedFailureIsLoggedValueFree() {
        PSQLException authorization = new PSQLException(AUTH_FAILED, PSQLState.INVALID_PASSWORD,
                new IOException("CAUSE-SENTINEL\r\nFORGED cause line"));
        authorization.setNextException(new SQLException("NEXT-SENTINEL\nFORGED next line", "08006"));
        BeanCreationException failure = new BeanCreationException("jdbcDialect", "TOP-SENTINEL\rFORGED top line",
                new CannotGetJdbcConnectionException("CONNECTION-SENTINEL\r\nFORGED connection line", authorization));
        failure.addSuppressed(new IllegalStateException("SUPPRESSED-SENTINEL\nFORGED suppressed line"));
        assertThat(ThrowableProxyUtil.asString(new ThrowableProxy(failure)))
                .as("the original graph would carry the sentinels")
                .contains("TOP-SENTINEL", "CAUSE-SENTINEL", "SUPPRESSED-SENTINEL", "\nFORGED");
        String line = "Cannot connect to database " + AUTHORITY + ": " + AUTH_FAILED;

        List<ILoggingEvent> events = reporterDebugEvents(() -> assertThat(
                reporter(URL, CustomerGeneratorRunner.PROFILE).reportException(failure)).isTrue());

        assertThat(printedLines()).containsExactly(line);
        assertThat(events).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.DEBUG);
            assertThat(event.getFormattedMessage()).isEqualTo("generator.startup failed: " + line);
            String rendering = rendering(event);
            assertThat(StringUtils.countOccurrencesOf(rendering, line)).isOne();
            assertThat(rendering)
                    .contains(BeanCreationException.class.getName() + " [message withheld]")
                    .contains(CannotGetJdbcConnectionException.class.getName() + " [message withheld]")
                    .contains(PSQLException.class.getName() + " [SQLSTATE 28P01, vendor code 0; message withheld]")
                    .contains("java.sql.SQLException [SQLSTATE 08006, vendor code 0; message withheld]")
                    .contains("java.io.IOException [message withheld]")
                    .contains("java.lang.IllegalStateException [message withheld]")
                    .doesNotContain(SENTINELS);
            assertThat(rendering.lines())
                    .anyMatch(text -> text.contains("\tat " + GeneratorStartupFailureReporterTest.class.getName()))
                    .noneMatch(text -> text.strip().startsWith("FORGED"));
        });
    }

    @Test
    @DisplayName("DEBUG of a failure while reporting: false, nothing printed, the graph logged value-free")
    void failureWhileReportingIsLoggedValueFree() {
        IllegalStateException closed = new IllegalStateException("CONTEXT-SENTINEL\r\nFORGED context line",
                new SQLException("CAUSE-SENTINEL\nFORGED cause line", "08003"));
        closed.addSuppressed(new IllegalArgumentException("SUPPRESSED-SENTINEL\rFORGED suppressed line"));
        ConfigurableApplicationContext context = mock(ConfigurableApplicationContext.class);
        when(context.getEnvironment()).thenThrow(closed);
        RuntimeException failure = startupFailure(new PSQLException(AUTH_FAILED, PSQLState.INVALID_PASSWORD));

        List<ILoggingEvent> events = reporterDebugEvents(() -> assertThat(
                new GeneratorStartupFailureReporter(context, printStream()).reportException(failure)).isFalse());

        assertThat(printedLines()).isEmpty();
        assertThat(events).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.DEBUG);
            assertThat(event.getFormattedMessage()).isEqualTo("generator.startup failure left to Spring Boot's report");
            String rendering = rendering(event);
            assertThat(rendering)
                    .contains("java.lang.IllegalStateException [message withheld]")
                    .contains("java.sql.SQLException [SQLSTATE 08003, vendor code 0; message withheld]")
                    .contains("java.lang.IllegalArgumentException [message withheld]")
                    .contains("\tat ")
                    .doesNotContain(SENTINELS)
                    .doesNotContain(AUTH_FAILED);
            assertThat(rendering.lines()).noneMatch(text -> text.strip().startsWith("FORGED"));
        });
    }

    @Test
    @DisplayName("META-INF/spring.factories registers the reporter, and Spring Boot asks it before FailureAnalyzers")
    void registeredAheadOfFailureAnalyzers() {
        GenericApplicationContext context = new GenericApplicationContext();
        context.setEnvironment(environment(URL, CustomerGeneratorRunner.PROFILE));

        List<SpringBootExceptionReporter> reporters = SpringFactoriesLoader
                .forDefaultResourceLocation(getClass().getClassLoader())
                .load(SpringBootExceptionReporter.class,
                        ArgumentResolver.of(ConfigurableApplicationContext.class, context));

        assertThat(reporters).hasSizeGreaterThanOrEqualTo(2);
        assertThat(reporters.get(0)).isInstanceOf(GeneratorStartupFailureReporter.class);
        assertThat(reporters).extracting(reporter -> reporter.getClass().getName()).contains(FAILURE_ANALYZERS);
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    @DisplayName("SpringApplication under the generator profile: the one line, no 'Application run failed', rethrown")
    void generatorStartPrintsOneLine(CapturedOutput output) {
        SpringApplication application = failingApplication();

        assertThatThrownBy(() -> application.run("--spring.profiles.active=" + CustomerGeneratorRunner.PROFILE,
                "--spring.datasource.url=" + URL + "&password=" + SECRET))
                .isInstanceOf(BeanCreationException.class)
                .hasRootCauseInstanceOf(PSQLException.class);

        String expected = "Cannot connect to database " + AUTHORITY + ": " + AUTH_FAILED;
        assertThat(output.getOut().lines().filter(expected::equals)).hasSize(1);
        assertThat(output.getAll())
                .doesNotContain("Application run failed", "Caused by:", "\tat ", SECRET);
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    @DisplayName("SpringApplication under the generator profile, the real schema guard and an empty pool schema:"
            + " the guard's one line, no stack trace, rethrown")
    void generatorStartWithEmptySchemaPrintsOneLine(CapturedOutput output) {
        SpringApplication application =
                quietApplication(DataSourceSchemaGuard.class, DataSourceAutoConfiguration.class);

        assertThatThrownBy(() -> application.run("--spring.profiles.active=" + CustomerGeneratorRunner.PROFILE,
                "--spring.datasource.url=" + URL, "--spring.datasource.username=customermaster",
                "--spring.datasource.password=" + SECRET, "--spring.datasource.hikari.schema="))
                .isInstanceOf(BeanCreationException.class)
                .hasRootCauseInstanceOf(InvalidSchemaException.class);

        List<String> lines = output.getOut().lines()
                .filter(line -> line.startsWith("Database schema invalid: "))
                .toList();
        assertThat(lines).hasSize(1);
        assertThat(lines.get(0))
                .startsWith("Database schema invalid: spring.datasource.hikari.schema is empty or blank.")
                .contains("environment variable DB_SCHEMA");
        assertThat(output.getAll())
                .doesNotContain("Application run failed", "Caused by:", "\tat ", SECRET, "Cannot connect to database");
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    @DisplayName("SpringApplication under the generator profile, the real lock-timeout check, the application's"
            + " 45 s socket bound and a 60s lock timeout: one prefixed line, no stack trace, rethrown")
    void generatorStartWithLockTimeoutNotBelowSocketBoundPrintsOneLine(CapturedOutput output) {
        SpringApplication application = lockTimeoutApplication();

        assertThatThrownBy(() -> application.run("--spring.profiles.active=" + CustomerGeneratorRunner.PROFILE,
                "--spring.datasource.url=" + URL, "--spring.datasource.username=customermaster",
                "--spring.datasource.password=" + SECRET, "--customer-master.db.lock-timeout=60s"))
                .isInstanceOf(BeanCreationException.class)
                .hasRootCauseInstanceOf(InvalidLockTimeoutException.class);

        String expected = "Database lock timeout invalid: customer-master.db.lock-timeout (DB_LOCK_TIMEOUT) must be"
                + " below spring.datasource.hikari.data-source-properties.socketTimeout, so that PostgreSQL answers"
                + " every lock wait before the socket bound gives up on the connection: PT1M is not below PT45S";
        assertThat(output.getOut().lines().filter(expected::equals)).hasSize(1);
        assertThat(output.getAll())
                .doesNotContain("Application run failed", "Caused by:", "\tat ", SECRET, "Cannot connect to database");
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    @DisplayName("SpringApplication without the generator profile and a 60s lock timeout: Spring Boot's report is"
            + " unchanged")
    void otherStartWithLockTimeoutNotBelowSocketBoundIsUnchanged(CapturedOutput output) {
        SpringApplication application = lockTimeoutApplication();

        assertThatThrownBy(() -> application.run("--spring.datasource.url=" + URL,
                "--spring.datasource.username=customermaster", "--spring.datasource.password=" + SECRET,
                "--customer-master.db.lock-timeout=60s"))
                .isInstanceOf(BeanCreationException.class)
                .hasRootCauseInstanceOf(InvalidLockTimeoutException.class);

        assertThat(output.getAll()).contains("Application run failed").doesNotContain("Database lock timeout invalid");
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    @DisplayName("SpringApplication without the generator profile: Spring Boot's report is unchanged")
    void otherStartIsUnchanged(CapturedOutput output) {
        SpringApplication application = failingApplication();

        assertThatThrownBy(() -> application.run("--spring.datasource.url=" + URL))
                .isInstanceOf(BeanCreationException.class);

        assertThat(output.getAll()).contains("Application run failed").doesNotContain("Cannot connect to database");
    }

    /**
     * Wraps a database failure as the generator's context refresh does: the dialect bean fails to obtain
     * a connection, and the beans that depend on it fail in turn.
     *
     * @param sqlFailure what the driver raised
     * @return the exception {@code SpringApplication.run} fails with
     */
    private static RuntimeException startupFailure(SQLException sqlFailure) {
        return new BeanCreationException("cszSource",
                "Unsatisfied dependency expressed through constructor parameter 0",
                new BeanCreationException("jdbcDialect", "Failed to instantiate [Dialect]",
                        new CannotGetJdbcConnectionException("Failed to obtain JDBC Connection", sqlFailure)));
    }

    /**
     * Creates a reporter over a context with the given environment, printing to {@link #printed}.
     *
     * @param url      the {@code spring.datasource.url}, or {@code null} for none
     * @param profiles the active profiles
     * @return the reporter
     */
    private GeneratorStartupFailureReporter reporter(String url, String... profiles) {
        GenericApplicationContext context = new GenericApplicationContext();
        context.setEnvironment(environment(url, profiles));
        return new GeneratorStartupFailureReporter(context, printStream());
    }

    /**
     * Creates an environment.
     *
     * @param url      the {@code spring.datasource.url}, or {@code null} for none
     * @param profiles the active profiles
     * @return the environment
     */
    private static MockEnvironment environment(String url, String... profiles) {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles(profiles);
        if (url != null) {
            environment.setProperty(GeneratorStartupFailureReporter.URL_PROPERTY, url);
        }
        return environment;
    }

    /**
     * Returns a stream into {@link #printed}.
     *
     * @return the stream
     */
    private PrintStream printStream() {
        return new PrintStream(printed, true, UTF_8);
    }

    /**
     * Returns what the reporters printed, line by line.
     *
     * @return the printed lines
     */
    private List<String> printedLines() {
        return printed.toString(UTF_8).lines().toList();
    }

    /**
     * Runs an action with the reporter's logger at DEBUG and collects what it logs there, then restores the
     * logger's level, so the case does not depend on whatever configured logging before it.
     *
     * @param action the action that reports a failure
     * @return the events the reporter's logger received while the action ran
     */
    private static List<ILoggingEvent> reporterDebugEvents(Runnable action) {
        LoggerContext loggerContext = (LoggerContext) LoggerFactory.getILoggerFactory();
        Logger reporterLogger = loggerContext.getLogger(GeneratorStartupFailureReporter.class);
        ListAppender<ILoggingEvent> events = new ListAppender<>();
        Level originalLevel = reporterLogger.getLevel();
        events.setContext(loggerContext);
        events.start();
        reporterLogger.setLevel(Level.DEBUG);
        reporterLogger.addAppender(events);
        try {
            action.run();
        } finally {
            reporterLogger.detachAppender(events);
            events.stop();
            reporterLogger.setLevel(originalLevel);
        }
        return List.copyOf(events.list);
    }

    /**
     * Renders a logging event's message and throwable as Logback's console pattern does.
     *
     * @param event the event
     * @return the formatted message, then the throwable's rendering when the event has one
     */
    private static String rendering(ILoggingEvent event) {
        IThrowableProxy throwable = event.getThrowableProxy();
        return event.getFormattedMessage() + System.lineSeparator()
                + (throwable == null ? "" : ThrowableProxyUtil.asString(throwable));
    }

    /**
     * Builds an application whose only bean fails as the generator's dialect bean does on a wrong
     * password, with no web server, banner, startup log or shutdown hook.
     *
     * @return the application
     */
    private static SpringApplication failingApplication() {
        return quietApplication(FailingDialectSource.class);
    }

    /**
     * Builds an application over the given sources with no web server, banner, startup log or shutdown
     * hook.
     *
     * @param sources the primary sources
     * @return the application
     */
    private static SpringApplication quietApplication(Class<?>... sources) {
        SpringApplication application = new SpringApplication(sources);
        application.setWebApplicationType(WebApplicationType.NONE);
        application.setBannerMode(Banner.Mode.OFF);
        application.setLogStartupInfo(false);
        application.setRegisterShutdownHook(false);
        return application;
    }

    /**
     * Raises the schema guard's failure for an empty {@code spring.datasource.hikari.schema}, the setting
     * an empty {@code DB_SCHEMA} reaches, over connection details whose URL names a schema and must not
     * appear.
     *
     * @return the guard's exception
     */
    private static InvalidSchemaException schemaGuardFailure() {
        JdbcConnectionDetails details = mock(JdbcConnectionDetails.class);
        when(details.getJdbcUrl()).thenReturn(URL);
        MockEnvironment environment = environment(URL, CustomerGeneratorRunner.PROFILE);
        environment.setProperty("spring.datasource.hikari.schema", "");
        DataSourceSchemaGuard guard = new DataSourceSchemaGuard();
        guard.setEnvironment(environment);
        InvalidSchemaException invalid = catchThrowableOfType(InvalidSchemaException.class,
                () -> guard.postProcessAfterInitialization(details, "jdbcConnectionDetails"));
        assertThat(invalid).as("schema guard failure").isNotNull();
        return invalid;
    }

    /**
     * Raises the credentials guard's failure for an empty username.
     *
     * @return the guard's exception
     */
    private static MissingCredentialsException credentialsGuardFailure() {
        JdbcConnectionDetails details = mock(JdbcConnectionDetails.class);
        when(details.getUsername()).thenReturn("");
        when(details.getPassword()).thenReturn(SECRET);
        when(details.getJdbcUrl()).thenReturn(URL);
        DataSourceCredentialsGuard guard = new DataSourceCredentialsGuard();
        MissingCredentialsException missing = catchThrowableOfType(MissingCredentialsException.class,
                () -> guard.postProcessAfterInitialization(details, "jdbcConnectionDetails"));
        assertThat(missing).as("credentials guard failure").isNotNull();
        return missing;
    }

    /**
     * Raises the lock-timeout check's failure through the real {@link LockWaitingReads} constructor, over
     * an unstarted Hikari pool with the application's 45-second socket bound, whose URL carries a password
     * that must not appear.
     *
     * @param lockTimeout {@code customer-master.db.lock-timeout}, not below 45 s or outside 1 ms to
     *                    {@link Integer#MAX_VALUE} ms
     * @return the check's exception
     */
    private static InvalidLockTimeoutException lockTimeoutGuardFailure(Duration lockTimeout) {
        return lockTimeoutGuardFailure(lockTimeout, "45");
    }

    /**
     * Raises the lock-timeout check's failure through the real {@link LockWaitingReads} constructor, over
     * an unstarted Hikari pool whose URL carries a password that must not appear; no connection is opened.
     *
     * @param lockTimeout   {@code customer-master.db.lock-timeout}
     * @param socketTimeout the pool's {@code socketTimeout} data-source property
     * @return the check's exception
     */
    private static InvalidLockTimeoutException lockTimeoutGuardFailure(Duration lockTimeout, String socketTimeout) {
        try (HikariDataSource pool = new HikariDataSource()) {
            pool.setJdbcUrl(URL + "&password=" + SECRET);
            pool.addDataSourceProperty("socketTimeout", socketTimeout);
            AppProperties settings = new AppProperties(new AppProperties.Db(lockTimeout),
                    new AppProperties.Search(12, 100, 9999));
            InvalidLockTimeoutException invalid = catchThrowableOfType(InvalidLockTimeoutException.class,
                    () -> new LockWaitingReads(settings, new JdbcTemplate(pool)));
            assertThat(invalid).as("lock-timeout check failure").isNotNull();
            return invalid;
        }
    }

    /**
     * Wraps the lock-timeout check's failure as the generator's context refresh does: the search
     * repository's dependency on {@code lockWaitingReads} cannot be satisfied because its constructor threw.
     *
     * @param invalid the check's exception
     * @return the exception {@code SpringApplication.run} fails with
     */
    private static RuntimeException lockWaitingReadsStartupFailure(InvalidLockTimeoutException invalid) {
        return new UnsatisfiedDependencyException(null, "customerSearchRepository", "lockWaitingReads",
                new BeanCreationException("lockWaitingReads",
                        "Failed to instantiate [" + LockWaitingReads.class.getName() + "]: Constructor threw exception",
                        new BeanInstantiationException(LockWaitingReads.class, "Constructor threw exception",
                                invalid)));
    }

    /**
     * Builds an application over the real {@link LockWaitingReads}, Spring Boot's datasource
     * auto-configuration and {@link LockTimeoutSource}, with no web server, banner, startup log or
     * shutdown hook. The pool is never started, so no connection is opened.
     *
     * @return the application
     */
    private static SpringApplication lockTimeoutApplication() {
        return quietApplication(LockTimeoutSource.class, LockWaitingReads.class, DataSourceAutoConfiguration.class);
    }

    /**
     * The one bean of {@link #failingApplication()}: its constructor fails as {@code JdbcConfig.jdbcDialect}
     * does when PostgreSQL rejects the password. It carries no stereotype, so no component scan finds it.
     */
    static final class FailingDialectSource {

        /**
         * Fails.
         *
         * @throws CannotGetJdbcConnectionException always, caused by a 28P01 {@link PSQLException}
         */
        FailingDialectSource() {
            throw new CannotGetJdbcConnectionException("Failed to obtain JDBC Connection",
                    new PSQLException(AUTH_FAILED, PSQLState.INVALID_PASSWORD));
        }
    }

    /**
     * The settings and template {@link LockWaitingReads} needs in {@link #lockTimeoutApplication()}:
     * registers {@link AppProperties}, bound from {@code customer-master.*} as the application binds it,
     * and a {@link JdbcTemplate} over the auto-configured datasource. It carries no stereotype, so no
     * component scan finds it.
     */
    @EnableConfigurationProperties(AppProperties.class)
    static final class LockTimeoutSource {

        /**
         * The template {@link LockWaitingReads} reads its datasource from.
         *
         * @param dataSource the auto-configured, unstarted Hikari pool
         * @return the template
         */
        @Bean
        JdbcTemplate jdbcTemplate(DataSource dataSource) {
            return new JdbcTemplate(dataSource);
        }
    }
}
