package com.democorp.customermaster.controller;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.democorp.customermaster.config.BoundedDataSourceHealthIndicator;
import com.democorp.customermaster.config.DataSourceHealthConfig;
import com.democorp.customermaster.support.AbstractPostgresIT;
import com.fasterxml.jackson.databind.JsonNode;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.autoconfigure.availability.AvailabilityHealthContributorAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.availability.AvailabilityProbesAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.health.HealthContributorAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.health.HealthEndpointAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.jdbc.DataSourceHealthContributorAutoConfiguration;
import org.springframework.boot.actuate.health.CompositeHealth;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthComponent;
import org.springframework.boot.actuate.health.HealthContributorRegistry;
import org.springframework.boot.actuate.health.HealthEndpoint;
import org.springframework.boot.actuate.health.HealthEndpointGroup;
import org.springframework.boot.actuate.health.HealthEndpointGroups;
import org.springframework.boot.actuate.health.Status;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.availability.ApplicationAvailabilityAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.LivenessState;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Proves the health probes the Compose {@code app} healthcheck relies on
 * ({@code curl -fsS http://localhost:8080/actuator/health/readiness}): both probes answer anonymous
 * callers with 200 {@code UP} and no component detail, readiness includes the {@code db} DataSource
 * check next to {@code readinessState}, liveness stays independent of the database, and readiness
 * turns DOWN within a bound when PostgreSQL stops answering on a connection the pool just used.
 *
 * <p><b>Why.</b> With only {@code management.endpoint.health.probes.enabled}, Spring Boot's readiness
 * group holds {@code readinessState} alone, so readiness stayed UP while PostgreSQL was down.
 * {@code application.yml} therefore declares both groups. A refused database (readiness DOWN, liveness
 * UP, within a bounded wait) is proved by {@code HealthProbesOutageTest}, which never stops the shared
 * Testcontainers database.
 *
 * <p><b>The stalled database.</b> Spring Boot's own {@code db} check validates with
 * {@code Connection.isValid(0)}, which has no deadline, and Hikari hands out a connection used within
 * its aliveness bypass window (500 ms by default) unchecked, so a probe right after a successful one
 * hung for as long as PostgreSQL gave no answer. The stall test routes a one-connection pool through a
 * {@link StallableRelay} to {@code POSTGRES} and stalls the relay right after a successful probe,
 * which the shared container never notices.
 *
 * <p>The base tests use the base context of {@link AbstractPostgresIT}: no {@code @MockitoBean} and
 * no {@code @Import}, so this class adds no context variant. The stall test starts its own
 * short-lived context with a {@link WebApplicationContextRunner}, outside the Spring test context
 * cache, and closes it, its pool and the relay before it returns.
 */
class HealthProbesIT extends AbstractPostgresIT {

    /** The readiness probe path, as the Compose healthcheck calls it. */
    private static final String READINESS = "/actuator/health/readiness";

    /** The liveness probe path. */
    private static final String LIVENESS = "/actuator/health/liveness";

    /** The name Spring Boot gives the health contributor of the single DataSource. */
    private static final String DB = "db";

    /** The readiness group, as {@link HealthEndpoint#healthForPath(String...)} names it. */
    private static final String READINESS_GROUP = "readiness";

    /** The liveness group, as {@link HealthEndpoint#healthForPath(String...)} names it. */
    private static final String LIVENESS_GROUP = "liveness";

    /**
     * Hikari's system property for the window in which a pool hands out a recently used connection
     * without checking it. Each pool reads it once, when it starts.
     */
    private static final String ALIVE_BYPASS_WINDOW_PROPERTY = "com.zaxxer.hikari.aliveBypassWindowMs";

    /**
     * The bypass window of the stall test's pool, in ms: longer than the whole test, so the stalled
     * probe always receives the warm connection unchecked by the pool, as it does within the default
     * 500 ms, however slowly the host schedules the test.
     */
    private static final String STALL_TEST_BYPASS_WINDOW_MS = "600000";

    /**
     * Longest answer accepted from any probe: Spring Boot's slow-health-indicator warning threshold,
     * a third of Docker's default 30-second healthcheck timeout.
     */
    private static final Duration PROBE_BUDGET = Duration.ofSeconds(10);

    /** Time allowed beyond the check's validation bound for the health evaluation itself. */
    private static final Duration EVALUATION_MARGIN = Duration.ofSeconds(2);

    /** Longest wait for readiness to return to UP once the database answers again. */
    private static final Duration RECOVERY_DEADLINE = Duration.ofSeconds(30);

    /** Pause between readiness probes while waiting for recovery. */
    private static final Duration RECOVERY_POLL = Duration.ofMillis(200);

    /** The health endpoint groups as served, including the probe groups. */
    @Autowired
    private HealthEndpointGroups healthEndpointGroups;

    /** Every health contributor of the application, by name. */
    @Autowired
    private HealthContributorRegistry healthContributorRegistry;

    @Test
    void readinessAnswersAnonymousCallersWithUpAndNoComponentDetail() {
        ResponseEntity<String> response = anonymous().get().uri(READINESS)
                .retrieve().toEntity(String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = json(response);
        assertThat(body.path("status").asText()).isEqualTo("UP");
        assertThat(body.has("components"))
                .as("an anonymous probe must not expose database details: %s", body)
                .isFalse();
    }

    @Test
    void livenessAnswersAnonymousCallersWithUpAndNoComponentDetail() {
        ResponseEntity<String> response = anonymous().get().uri(LIVENESS)
                .retrieve().toEntity(String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = json(response);
        assertThat(body.path("status").asText()).isEqualTo("UP");
        assertThat(body.has("components"))
                .as("an anonymous probe must not expose component details: %s", body)
                .isFalse();
    }

    @Test
    void readinessGroupIncludesTheDatabaseCheck() {
        HealthEndpointGroup readiness = healthEndpointGroups.get("readiness");

        assertThat(readiness).isNotNull();
        assertThat(readiness.isMember("readinessState")).isTrue();
        assertThat(readiness.isMember(DB)).isTrue();
        assertThat(healthContributorRegistry.getContributor(DB))
                .as("the readiness member %s must exist as a health contributor", DB)
                .isNotNull()
                .as("the readiness member %s must be the bounded check", DB)
                .isInstanceOf(BoundedDataSourceHealthIndicator.class);
    }

    @Test
    void livenessGroupExcludesTheDatabaseCheck() {
        HealthEndpointGroup liveness = healthEndpointGroups.get("liveness");

        assertThat(liveness).isNotNull();
        assertThat(liveness.isMember("livenessState")).isTrue();
        assertThat(liveness.isMember(DB)).isFalse();
        assertThat(liveness.isMember("readinessState")).isFalse();
    }

    /**
     * A probe succeeds, the database stops answering on the pool's only connection, and the next
     * readiness probe reports DOWN within the {@code db} check's validation bound, with the connection
     * evicted and liveness still UP; once the database answers again, readiness is UP again.
     *
     * @throws Exception if the relay cannot start or a probe fails
     */
    @Test
    void readinessTurnsDownWithinTheCheckBoundWhenAWarmConnectionStallsAndThenRecovers()
            throws Exception {
        ExecutorService probes = Executors.newSingleThreadExecutor(
                task -> daemon("health-probe", task));
        try (StallableRelay relay = new StallableRelay(new InetSocketAddress(
                POSTGRES.getHost(), POSTGRES.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT)))) {
            stallRunner(relay.port()).run(context -> {
                try {
                    AvailabilityChangeEvent.publish(context, LivenessState.CORRECT);
                    AvailabilityChangeEvent.publish(context, ReadinessState.ACCEPTING_TRAFFIC);
                    HealthEndpoint health = context.getBean(HealthEndpoint.class);
                    HikariDataSource pool = context.getBean(HikariDataSource.class);
                    Duration checkBound = Duration.ofSeconds(context
                            .getBean(BoundedDataSourceHealthIndicator.class).getValidationTimeoutSeconds());
                    assertThat(pool.getHikariPoolMXBean())
                            .as("the first probe must start the pool, so the pool reads the bypass window")
                            .isNull();

                    HealthComponent warm = withAliveBypassWindow(
                            () -> probe(probes, health, READINESS_GROUP));

                    assertThat(warm.getStatus()).isEqualTo(Status.UP);
                    assertThat(dbDetails(warm))
                            .containsEntry("database", "PostgreSQL")
                            .containsEntry("validationQuery", "isValid()");

                    relay.stall();
                    long start = System.nanoTime();
                    HealthComponent stalled = probe(probes, health, READINESS_GROUP);
                    Duration elapsed = Duration.ofNanos(System.nanoTime() - start);

                    assertThat(stalled.getStatus()).isEqualTo(Status.DOWN);
                    assertThat(stalled).isInstanceOf(CompositeHealth.class);
                    assertThat(((CompositeHealth) stalled).getComponents())
                            .containsOnlyKeys("readinessState", DB);
                    assertThat(((CompositeHealth) stalled).getComponents().get("readinessState")
                            .getStatus()).isEqualTo(Status.UP);
                    assertThat(((CompositeHealth) stalled).getComponents().get(DB).getStatus())
                            .isEqualTo(Status.DOWN);
                    assertThat(dbDetails(stalled))
                            .as("the check's own bounded validation, not a failed borrow, reports the stall")
                            .containsEntry("validationQuery", "isValid()")
                            .doesNotContainKey("error");
                    assertThat(elapsed)
                            .as("readiness must answer within the check's validation bound %s plus %s",
                                    checkBound, EVALUATION_MARGIN)
                            .isLessThan(checkBound.plus(EVALUATION_MARGIN));
                    assertThat(pool.getHikariPoolMXBean().getTotalConnections())
                            .as("the connection that failed validation must leave the pool")
                            .isZero();

                    HealthComponent liveness = probe(probes, health, LIVENESS_GROUP);

                    assertThat(liveness.getStatus()).isEqualTo(Status.UP);
                    assertThat(liveness).isInstanceOf(CompositeHealth.class);
                    assertThat(((CompositeHealth) liveness).getComponents())
                            .containsOnlyKeys("livenessState");

                    relay.release();
                    HealthComponent recovered = awaitReadinessUp(probes, health);

                    assertThat(dbDetails(recovered)).containsEntry("database", "PostgreSQL");
                } finally {
                    // Unblocks a probe that is still waiting, so the pool closes without delay.
                    relay.release();
                }
            });
        } finally {
            probes.shutdownNow();
        }
    }

    /**
     * Runs the health stack over the real {@code application.yml}, with the production {@code db}
     * check from {@link DataSourceHealthConfig}, and a one-connection pool that reaches
     * {@code POSTGRES} through the relay. Only the datasource, availability and health
     * auto-configurations run, so no web server, Flyway or other application bean starts.
     *
     * @param relayPort the loopback port of the relay
     * @return a new runner
     */
    private static WebApplicationContextRunner stallRunner(int relayPort) {
        return new WebApplicationContextRunner()
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
                        "DB_HOST=" + InetAddress.getLoopbackAddress().getHostAddress(),
                        "DB_PORT=" + relayPort,
                        "DB_NAME=" + POSTGRES.getDatabaseName(),
                        "DB_USER=" + POSTGRES.getUsername(),
                        "DB_PASSWORD=" + POSTGRES.getPassword(),
                        "spring.datasource.hikari.maximum-pool-size=1");
    }

    /**
     * Runs an action with {@value #ALIVE_BYPASS_WINDOW_PROPERTY} set to
     * {@value #STALL_TEST_BYPASS_WINDOW_MS}, then restores the property's previous state.
     *
     * @param action the action that starts the pool
     * @param <T>    the action's result type
     * @return the action's result
     * @throws Exception if the action throws
     */
    private static <T> T withAliveBypassWindow(Callable<T> action) throws Exception {
        String previous = System.getProperty(ALIVE_BYPASS_WINDOW_PROPERTY);
        System.setProperty(ALIVE_BYPASS_WINDOW_PROPERTY, STALL_TEST_BYPASS_WINDOW_MS);
        try {
            return action.call();
        } finally {
            if (previous == null) {
                System.clearProperty(ALIVE_BYPASS_WINDOW_PROPERTY);
            } else {
                System.setProperty(ALIVE_BYPASS_WINDOW_PROPERTY, previous);
            }
        }
    }

    /**
     * Evaluates one health group on the probe thread and waits at most {@link #PROBE_BUDGET}, so a
     * check without a deadline fails the test instead of hanging it.
     *
     * @param probes the single probe thread
     * @param health the health endpoint of the stall test's context
     * @param group  the group to evaluate
     * @return the group's health, with every member and its details
     * @throws AssertionError if the group gives no answer within {@link #PROBE_BUDGET}
     * @throws Exception      if the evaluation fails or the wait is interrupted
     */
    private static HealthComponent probe(ExecutorService probes, HealthEndpoint health, String group)
            throws Exception {
        Future<HealthComponent> answer = probes.submit(() -> health.healthForPath(group));
        try {
            return answer.get(PROBE_BUDGET.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            answer.cancel(true);
            throw new AssertionError(
                    "The " + group + " probe gave no answer within " + PROBE_BUDGET, e);
        }
    }

    /**
     * Probes readiness until it is UP again, once the relay forwards again and the pool has opened a
     * new connection.
     *
     * @param probes the single probe thread
     * @param health the health endpoint of the stall test's context
     * @return the first UP readiness
     * @throws AssertionError if readiness is not UP within {@link #RECOVERY_DEADLINE}
     * @throws Exception      if an evaluation fails or the wait is interrupted
     */
    private static HealthComponent awaitReadinessUp(ExecutorService probes, HealthEndpoint health)
            throws Exception {
        long deadline = System.nanoTime() + RECOVERY_DEADLINE.toNanos();
        HealthComponent readiness = probe(probes, health, READINESS_GROUP);
        while (!Status.UP.equals(readiness.getStatus())) {
            if (System.nanoTime() - deadline >= 0) {
                throw new AssertionError("Readiness did not return to UP within " + RECOVERY_DEADLINE
                        + " after the database answered again; db details: " + dbDetails(readiness));
            }
            Thread.sleep(RECOVERY_POLL.toMillis());
            readiness = probe(probes, health, READINESS_GROUP);
        }
        return readiness;
    }

    /**
     * Returns the details of the {@code db} member of an evaluated readiness group.
     *
     * @param readiness the readiness group's health, evaluated with details
     * @return the {@code db} member's details
     * @throws AssertionError if the group has no {@code db} member with details
     */
    private static Map<String, Object> dbDetails(HealthComponent readiness) {
        assertThat(readiness).isInstanceOf(CompositeHealth.class);
        HealthComponent db = ((CompositeHealth) readiness).getComponents().get(DB);
        assertThat(db).as("readiness member %s", DB).isInstanceOf(Health.class);
        return ((Health) db).getDetails();
    }

    /**
     * Creates a daemon thread, so a thread left blocked by a failed test never keeps the JVM alive.
     *
     * @param name the thread name
     * @param task the work of the thread
     * @return the unstarted thread
     */
    private static Thread daemon(String name, Runnable task) {
        Thread thread = new Thread(task, name);
        thread.setDaemon(true);
        return thread;
    }

    /**
     * A loopback TCP relay to the shared database that can stall: while stalled it keeps every socket
     * open but forwards no byte in either direction, as a paused or partitioned database server
     * behaves. Bytes read while stalled are held and forwarded on release, so connections that
     * outlive the stall carry on. Every thread is a daemon, and {@link #close()} closes every socket.
     */
    private static final class StallableRelay implements AutoCloseable {

        /** Longest wait for the relay's own connection to the database. */
        private static final int CONNECT_TIMEOUT_MS = 5_000;

        /** Size of the buffer of each relay direction. */
        private static final int BUFFER_SIZE = 8_192;

        /** The listening loopback socket the pool connects to. */
        private final ServerSocket server;

        /** The database address. */
        private final InetSocketAddress target;

        /** Every open client and database socket, closed by {@link #close()}. */
        private final Set<Socket> sockets = ConcurrentHashMap.newKeySet();

        /** Guards {@link #stalled}; forwarding threads wait on it while stalled. */
        private final Object gate = new Object();

        /** Whether forwarding is suspended; guarded by {@link #gate}. */
        private boolean stalled;

        /** Set once by {@link #close()}. */
        private volatile boolean closed;

        /**
         * Starts the relay on an ephemeral loopback port.
         *
         * @param target the database address
         * @throws IOException if no loopback port can be bound
         */
        StallableRelay(InetSocketAddress target) throws IOException {
            this.target = target;
            this.server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
            daemon("relay-accept", this::acceptConnections).start();
        }

        /**
         * Returns the port the pool connects to.
         *
         * @return the listening loopback port
         */
        int port() {
            return server.getLocalPort();
        }

        /** Stops forwarding in both directions; every socket stays open. */
        void stall() {
            synchronized (gate) {
                stalled = true;
            }
        }

        /** Resumes forwarding, starting with the bytes held during the stall. */
        void release() {
            synchronized (gate) {
                stalled = false;
                gate.notifyAll();
            }
        }

        /**
         * Accepts connections until the relay closes, pairing each with a new database connection
         * and one forwarding thread per direction.
         */
        private void acceptConnections() {
            while (!closed) {
                Socket client;
                try {
                    client = server.accept();
                } catch (IOException e) {
                    // The server socket was closed by close(): no further connection is accepted.
                    return;
                }
                Socket database = new Socket();
                sockets.add(client);
                sockets.add(database);
                try {
                    database.connect(target, CONNECT_TIMEOUT_MS);
                } catch (IOException e) {
                    // The database refused or timed out: the client sees its connection closed.
                    closeQuietly(client);
                    closeQuietly(database);
                    continue;
                }
                if (closed) {
                    closeQuietly(client);
                    closeQuietly(database);
                    return;
                }
                daemon("relay-to-database", () -> forward(client, database)).start();
                daemon("relay-to-client", () -> forward(database, client)).start();
            }
        }

        /**
         * Copies bytes from one socket to the other, holding them while the relay is stalled, until
         * either side closes; then closes both.
         *
         * @param from the socket read
         * @param to   the socket written
         */
        private void forward(Socket from, Socket to) {
            byte[] buffer = new byte[BUFFER_SIZE];
            try {
                InputStream in = from.getInputStream();
                OutputStream out = to.getOutputStream();
                int count;
                while ((count = in.read(buffer)) != -1) {
                    awaitForwarding();
                    out.write(buffer, 0, count);
                    out.flush();
                }
            } catch (IOException e) {
                // One side closed or reset the connection: this direction ends, and finally closes both.
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                closeQuietly(from);
                closeQuietly(to);
            }
        }

        /**
         * Waits while the relay is stalled and open.
         *
         * @throws InterruptedException if the forwarding thread is interrupted
         */
        private void awaitForwarding() throws InterruptedException {
            synchronized (gate) {
                while (stalled && !closed) {
                    gate.wait();
                }
            }
        }

        /** Stops accepting, releases waiting threads and closes every socket. */
        @Override
        public void close() {
            closed = true;
            release();
            closeQuietly(server);
            sockets.forEach(StallableRelay::closeQuietly);
        }

        /**
         * Closes a socket that may already be closed or reset.
         *
         * @param closeable the socket
         */
        private static void closeQuietly(Closeable closeable) {
            try {
                closeable.close();
            } catch (IOException e) {
                // Already closed or reset by the peer: nothing is left to release.
            }
        }
    }
}
