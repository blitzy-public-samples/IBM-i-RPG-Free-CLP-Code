package com.democorp.customermaster.controller;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.time.Duration;

import com.democorp.customermaster.config.BoundedDataSourceHealthIndicator;
import com.democorp.customermaster.config.DataSourceHealthConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.autoconfigure.availability.AvailabilityHealthContributorAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.availability.AvailabilityProbesAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.health.HealthContributorAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.health.HealthEndpointAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.jdbc.DataSourceHealthContributorAutoConfiguration;
import org.springframework.boot.actuate.health.CompositeHealth;
import org.springframework.boot.actuate.health.HealthComponent;
import org.springframework.boot.actuate.health.HealthContributorRegistry;
import org.springframework.boot.actuate.health.HealthEndpoint;
import org.springframework.boot.actuate.health.Status;
import org.springframework.boot.actuate.jdbc.DataSourceHealthIndicator;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.availability.ApplicationAvailabilityAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.LivenessState;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;

/**
 * Proves, with PostgreSQL unreachable, that the readiness probe reports DOWN because of its {@code db}
 * member, that liveness stays UP, and that readiness answers within a bounded wait.
 *
 * <p><b>Configuration under test.</b> {@link ConfigDataApplicationContextInitializer} loads the real
 * {@code application.yml}: the probe groups ({@code management.endpoint.health.group.*}) and the
 * Hikari timeouts ({@code spring.datasource.hikari.connection-timeout} and
 * {@code validation-timeout}). Only the datasource, availability and health auto-configurations run,
 * with {@link DataSourceHealthConfig}, which supplies the production {@code db} check
 * ({@link BoundedDataSourceHealthIndicator}) in place of Spring Boot's. No web server, Flyway or other
 * application bean starts, and the shared Testcontainers database is never touched. The stall of a
 * warm connection is proved by {@code HealthProbesIT}, which needs a live database.
 *
 * <p><b>The outage.</b> {@code DB_HOST} and {@code DB_PORT} point the datasource URL at a loopback
 * port that nothing listens on, so every connection attempt is refused. The test sets
 * {@code initialization-fail-timeout=-1}, which starts the pool without a first connection: a health
 * check then waits for a pooled connection exactly as it does in an application whose database is
 * lost after startup. Under Hikari's default 30-second connection timeout that wait outlasted a
 * healthcheck; {@link #PROBE_BUDGET} fails the test if the configured bound is removed or raised.
 *
 * <p>The application availability states are published as {@code SpringApplication} publishes them
 * once the application is ready, so {@code readinessState} and {@code livenessState} are UP and the
 * database check alone decides the outcome.
 */
class HealthProbesOutageTest {

    /**
     * Longest answer accepted from a probe: Spring Boot's slow-health-indicator warning threshold, a
     * third of Docker's default 30-second healthcheck timeout.
     */
    private static final Duration PROBE_BUDGET = Duration.ofSeconds(10);

    /** Time allowed beyond the configured connection timeout for the health evaluation itself. */
    private static final Duration EVALUATION_MARGIN = Duration.ofSeconds(2);

    /** The name Spring Boot gives the health contributor of the single DataSource. */
    private static final String DB = "db";

    /**
     * Runs the health stack over the real {@code application.yml} and a database that refuses
     * connections.
     */
    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withUserConfiguration(DataSourceHealthConfig.class)
            .withConfiguration(AutoConfigurations.of(
                    DataSourceAutoConfiguration.class,
                    ApplicationAvailabilityAutoConfiguration.class,
                    HealthContributorAutoConfiguration.class,
                    HealthEndpointAutoConfiguration.class,
                    DataSourceHealthContributorAutoConfiguration.class,
                    AvailabilityHealthContributorAutoConfiguration.class,
                    AvailabilityProbesAutoConfiguration.class))
            .withPropertyValues(
                    "DB_HOST=127.0.0.1",
                    "DB_PORT=" + closedLoopbackPort(),
                    "DB_USER=customermaster",
                    "DB_PASSWORD=customermaster-demo",
                    "spring.datasource.hikari.initialization-fail-timeout=-1");

    /**
     * The {@code db} contributor under test is the production check, and Spring Boot's own check
     * backed off.
     */
    @Test
    void theDbContributorIsTheBoundedCheck() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(BoundedDataSourceHealthIndicator.class);
            assertThat(context).doesNotHaveBean(DataSourceHealthIndicator.class);
            assertThat(context.getBean(HealthContributorRegistry.class).getContributor(DB))
                    .isSameAs(context.getBean("dbHealthIndicator"));
        });
    }

    @Test
    void configuredConnectionWaitsFitTheProbeBudget() {
        runner.run(context -> {
            HikariDataSource dataSource = context.getBean(HikariDataSource.class);
            long connectionTimeout = dataSource.getConnectionTimeout();
            long validationTimeout = dataSource.getValidationTimeout();
            Duration checkValidation = Duration.ofSeconds(
                    context.getBean(BoundedDataSourceHealthIndicator.class).getValidationTimeoutSeconds());

            assertThat(validationTimeout)
                    .as("Hikari requires validation-timeout below connection-timeout")
                    .isLessThan(connectionTimeout);
            assertThat(Duration.ofMillis(connectionTimeout + validationTimeout).plus(checkValidation))
                    .as("worst-case wait of the db check: a pooled connection's aliveness check that"
                            + " starts just before the connection timeout expires, then the check's"
                            + " own validation of the connection it holds")
                    .isLessThan(PROBE_BUDGET);
        });
    }

    @Test
    void readinessIsDownWithinTheProbeBudgetWhileLivenessStaysUp() {
        runner.run(context -> {
            AvailabilityChangeEvent.publish(context, LivenessState.CORRECT);
            AvailabilityChangeEvent.publish(context, ReadinessState.ACCEPTING_TRAFFIC);
            HealthEndpoint health = context.getBean(HealthEndpoint.class);
            // No connection ever opens, so no aliveness check runs: the wait is the connection timeout.
            Duration configuredBound = Duration.ofMillis(
                    context.getBean(HikariDataSource.class).getConnectionTimeout());

            long start = System.nanoTime();
            HealthComponent readiness = health.healthForPath("readiness");
            Duration elapsed = Duration.ofNanos(System.nanoTime() - start);

            assertThat(readiness.getStatus()).isEqualTo(Status.DOWN);
            assertThat(readiness).isInstanceOf(CompositeHealth.class);
            CompositeHealth readinessMembers = (CompositeHealth) readiness;
            assertThat(readinessMembers.getComponents()).containsOnlyKeys("readinessState", DB);
            assertThat(readinessMembers.getComponents().get(DB).getStatus()).isEqualTo(Status.DOWN);
            assertThat(readinessMembers.getComponents().get("readinessState").getStatus())
                    .isEqualTo(Status.UP);
            assertThat(elapsed)
                    .as("readiness must answer within the configured connection timeout %s plus %s",
                            configuredBound, EVALUATION_MARGIN)
                    .isLessThan(configuredBound.plus(EVALUATION_MARGIN));
            assertThat(elapsed)
                    .as("readiness must answer within %s while the database is unreachable",
                            PROBE_BUDGET)
                    .isLessThan(PROBE_BUDGET);

            HealthComponent liveness = health.healthForPath("liveness");

            assertThat(liveness.getStatus()).isEqualTo(Status.UP);
            assertThat(liveness).isInstanceOf(CompositeHealth.class);
            assertThat(((CompositeHealth) liveness).getComponents()).containsOnlyKeys("livenessState");
        });
    }

    /**
     * Returns a loopback port with no listener: the port of a just-closed ephemeral server socket, so
     * a connection to it is refused.
     *
     * @return the closed port
     * @throws IllegalStateException if no ephemeral port can be bound
     */
    private static int closedLoopbackPort() {
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException("No ephemeral loopback port could be bound", e);
        }
    }
}
