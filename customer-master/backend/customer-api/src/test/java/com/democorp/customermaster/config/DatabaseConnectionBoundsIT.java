package com.democorp.customermaster.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.democorp.customermaster.controller.ProblemFactory;
import com.democorp.customermaster.generator.CustomerGeneratorRunner;
import com.democorp.customermaster.repository.LockWaitingReads;
import com.democorp.customermaster.support.AbstractPostgresIT;
import com.democorp.customermaster.support.StallableRelay;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.autoconfigure.transaction.TransactionAutoConfiguration;
import org.springframework.boot.autoconfigure.transaction.TransactionManagerCustomizationAutoConfiguration;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ApplicationContext;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.TransactionManager;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Proves the two bounds {@code application.yml} gives every pgjdbc connection through
 * {@code spring.datasource.hikari.data-source-properties}, in the web context and in the
 * {@code generator} profile, and that the socket bound ends a query on a database that stops
 * answering, inside a transaction too.
 *
 * <p><b>Socket bound ({@code socketTimeout}, 45 s).</b> Hikari hands out a connection used within its
 * aliveness bypass window (500 ms by default) unchecked, so without a socket bound a query on such a
 * connection waited without end on a database that stopped answering (a paused container), instead
 * of failing into 500 DEM9999. The stall test routes a one-connection pool through a
 * {@link StallableRelay} to {@code POSTGRES}, lowers the bound by property to
 * {@link #STALL_SOCKET_TIMEOUT} so the test stays short, widens the bypass window as
 * {@code HealthProbesIT} does, and stalls the relay right after a successful query.
 *
 * <p><b>The stall inside a transaction.</b> HikariCP closes the connection whose read timed out, so the
 * transaction's rollback has no connection to run on. The transactional stall test runs the same stall
 * in a read-only {@link TransactionTemplate} through {@link LockWaitingReads}, as
 * {@code CustomerMaintenanceService.get} and the customer search run, over the application's
 * {@link TransactionManagerConfig} beside Boot's transaction auto-configuration. The stall starts inside
 * the read, after {@code SET LOCAL lock_timeout} and the savepoint: the read must not be retried as a lock
 * wait, the caller must receive the statement's own {@link DataAccessException}, whose SQLSTATE
 * {@code 08006} the 500 DEM9999 line logs, within the socket bound, and not the rollback failure of the
 * closed connection, and Spring's unredacted "Application exception overridden by rollback exception"
 * line must not appear. A shared-context test checks that this manager is the application's.
 *
 * <p><b>Server-side TCP keepalives ({@code options}).</b> pgjdbc sends
 * {@code -c tcp_keepalives_idle=5 -c tcp_keepalives_interval=2 -c tcp_keepalives_count=3} as session
 * parameters, so PostgreSQL ends a session whose client vanished without closing its socket (a
 * generator container killed during COPY) within about 11 s, rolling the load back. The tests read
 * the session values back over TCP, the only transport PostgreSQL applies them to. A client that
 * vanishes without a FIN cannot be produced in-process, so the orphan's removal itself is not
 * exercised here.
 *
 * <p>The shared-context tests use the base context of {@link AbstractPostgresIT}: no
 * {@code @MockitoBean} and no {@code @Import}, so this class adds no context variant. The other tests
 * start their own short-lived contexts over the real {@code application.yml} with an
 * {@link ApplicationContextRunner}, outside the Spring test context cache, running only
 * {@link DataSourceAutoConfiguration} (plus, for the transactional stall test, the transaction
 * auto-configurations, {@link DataSourceHealthConfig} and {@link TransactionManagerConfig}), and close
 * them, their pools and the relay before they return. No test holds a lock, and every database call of
 * the stall tests is bounded by {@link #QUERY_BUDGET}.
 */
class DatabaseConnectionBoundsIT extends AbstractPostgresIT {

    /**
     * The socket bound of {@code application.yml}, in ms, as {@link Connection#getNetworkTimeout()}
     * reports it.
     */
    private static final int CONFIGURED_NETWORK_TIMEOUT_MS = 45_000;

    /**
     * The session values {@code application.yml} sets through {@code options}, as {@code pg_settings}
     * reports them.
     */
    private static final Map<String, String> CONFIGURED_KEEPALIVES = Map.of(
            "tcp_keepalives_idle", "5",
            "tcp_keepalives_interval", "2",
            "tcp_keepalives_count", "3");

    /** Reads the session's keepalive settings. */
    private static final String KEEPALIVE_SETTINGS = "SELECT name, setting FROM pg_settings WHERE name IN"
            + " ('tcp_keepalives_idle', 'tcp_keepalives_interval', 'tcp_keepalives_count')";

    /** Answers {@code true} only on a TCP connection; a Unix-socket session has no server address. */
    private static final String IS_TCP = "SELECT inet_server_addr() IS NOT NULL";

    /** The socket bound of the stall test's pool, lowered from 45 s so the test stays short. */
    private static final Duration STALL_SOCKET_TIMEOUT = Duration.ofSeconds(2);

    /**
     * The lock timeout of the transactional stall test's reads: the test profile's, below
     * {@link #STALL_SOCKET_TIMEOUT} as {@link LockWaitingReads} requires.
     */
    private static final Duration STALL_LOCK_TIMEOUT = Duration.ofSeconds(1);

    /** Time allowed beyond the socket bound for the failed query to reach the caller. */
    private static final Duration STALL_MARGIN = Duration.ofSeconds(3);

    /**
     * Longest wait for any query of the stall test, so a missing bound fails the test instead of
     * hanging it.
     */
    private static final Duration QUERY_BUDGET = Duration.ofSeconds(15);

    /** Longest wait for a query to succeed again once the database answers again. */
    private static final Duration RECOVERY_DEADLINE = Duration.ofSeconds(30);

    /** Pause between queries while waiting for recovery. */
    private static final Duration RECOVERY_POLL = Duration.ofMillis(200);

    /**
     * Hikari's system property for the window in which a pool hands out a recently used connection
     * without checking it. Each pool reads it once, when it starts.
     */
    private static final String ALIVE_BYPASS_WINDOW_PROPERTY = "com.zaxxer.hikari.aliveBypassWindowMs";

    /**
     * The bypass window of the stall test's pool, in ms: longer than the whole test, so the stalled
     * query always receives the warm connection unchecked by the pool, as it does within the default
     * 500 ms, however slowly the host schedules the test.
     */
    private static final String STALL_TEST_BYPASS_WINDOW_MS = "600000";

    /** The statement both stall tests run. */
    private static final String SELECT_ONE = "SELECT 1";

    /** SQLSTATE {@code connection_failure}, which pgjdbc reports for the timed-out read. */
    private static final String CONNECTION_FAILURE = "08006";

    /** The bean name of the application's transaction manager, Boot's name for its own. */
    private static final String TRANSACTION_MANAGER_BEAN = "transactionManager";

    /**
     * The ERROR line {@link TransactionTemplate} writes, with the replaced exception and its message, when a
     * rollback failure replaces the statement's exception.
     */
    private static final String OVERRIDE_LINE = "Application exception overridden by rollback exception";

    /** The shared base context, for the check of its transaction manager. */
    @Autowired
    private ApplicationContext applicationContext;

    /**
     * A connection of the application's pool carries the 45-second socket bound and the server-side
     * keepalives, although {@code @ServiceConnection} replaces the datasource URL and credentials.
     *
     * @throws SQLException if the connection cannot be borrowed or queried
     */
    @Test
    void pooledConnectionsCarryTheSocketBoundAndTheServerKeepalives() throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            assertBounds(connection, CONFIGURED_NETWORK_TIMEOUT_MS);
        }
    }

    /**
     * The generator profile adds no datasource key, so its connections inherit both bounds from
     * {@code application.yml}.
     */
    @Test
    void theGeneratorProfileInheritsTheSocketBoundAndTheServerKeepalives() {
        databaseRunner(POSTGRES.getHost(), POSTGRES.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT))
                .withPropertyValues("spring.profiles.active=" + CustomerGeneratorRunner.PROFILE)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getEnvironment().getActiveProfiles())
                            .containsExactly(CustomerGeneratorRunner.PROFILE);
                    assertThat(context.getEnvironment().getProperty("spring.main.web-application-type"))
                            .as("application-generator.yml must be loaded")
                            .isEqualTo("none");
                    try (Connection connection = context.getBean(HikariDataSource.class).getConnection()) {
                        assertBounds(connection, CONFIGURED_NETWORK_TIMEOUT_MS);
                    }
                });
    }

    /**
     * A query succeeds, the database stops answering on the pool's only connection, and the next
     * query on that warm connection fails with a {@link DataAccessException} caused by the socket
     * timeout within the bound, instead of waiting; the connection leaves the pool, and once the
     * database answers again a query succeeds.
     *
     * @throws Exception if the relay cannot start or a query fails unexpectedly
     */
    @Test
    void aQueryOnAWarmConnectionFailsWithinTheSocketBoundWhenTheDatabaseStallsAndThePoolRecovers()
            throws Exception {
        ExecutorService queries = Executors.newSingleThreadExecutor(task -> daemon("bounded-query", task));
        try (StallableRelay relay = new StallableRelay(new InetSocketAddress(
                POSTGRES.getHost(), POSTGRES.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT)))) {
            stallRunner(relay.port()).run(context -> {
                try {
                    HikariDataSource pool = context.getBean(HikariDataSource.class);
                    JdbcTemplate jdbc = new JdbcTemplate(pool);
                    Callable<Integer> select = () -> jdbc.queryForObject(SELECT_ONE, Integer.class);
                    assertThat(pool.getHikariPoolMXBean())
                            .as("the first query must start the pool, so the pool reads the bypass window")
                            .isNull();

                    Integer warm = withAliveBypassWindow(() -> query(queries, select));

                    assertThat(warm).isEqualTo(1);
                    assertThat(jdbc.execute((ConnectionCallback<Integer>) Connection::getNetworkTimeout))
                            .as("the lowered socket bound must reach pgjdbc")
                            .isEqualTo((int) STALL_SOCKET_TIMEOUT.toMillis());

                    relay.stall();
                    long start = System.nanoTime();
                    Throwable failure = failureOf(queries, select);
                    Duration elapsed = Duration.ofNanos(System.nanoTime() - start);

                    assertThat(failure)
                            .as("the stalled query must fail through the socket bound")
                            .isInstanceOf(DataAccessException.class)
                            .hasRootCauseInstanceOf(SocketTimeoutException.class);
                    assertThat(elapsed)
                            .as("the stalled query must fail within the socket bound %s plus %s",
                                    STALL_SOCKET_TIMEOUT, STALL_MARGIN)
                            .isLessThan(STALL_SOCKET_TIMEOUT.plus(STALL_MARGIN));
                    assertThat(pool.getHikariPoolMXBean().getTotalConnections())
                            .as("the connection whose read timed out must leave the pool")
                            .isZero();

                    relay.release();

                    assertThat(awaitRecovery(queries, select)).isEqualTo(1);
                } finally {
                    // Unblocks a query that is still waiting, so the pool closes without delay.
                    relay.release();
                }
            });
        } finally {
            queries.shutdownNow();
        }
    }

    /**
     * The application's one transaction manager is the {@link BrokenConnectionTransactionManager} of
     * {@link TransactionManagerConfig}, under Boot's bean name, and Boot's {@link TransactionTemplate}
     * drives it: Boot's own manager backed off.
     */
    @Test
    void theApplicationsTransactionManagerIsTheBrokenConnectionManager() {
        assertThat(applicationContext.getBeansOfType(TransactionManager.class))
                .containsOnlyKeys(TRANSACTION_MANAGER_BEAN);
        assertThat(applicationContext.getBean(TRANSACTION_MANAGER_BEAN))
                .isInstanceOf(BrokenConnectionTransactionManager.class);
        assertThat(transactionTemplate.getTransactionManager())
                .isSameAs(applicationContext.getBean(TRANSACTION_MANAGER_BEAN));
    }

    /**
     * A read-only transaction reading through {@link LockWaitingReads} succeeds, the database stops
     * answering on the pool's only connection while the next such read runs on that warm connection, and
     * that read fails within the socket bound, after one attempt, with the statement's own
     * {@link DataAccessException}, whose SQLSTATE is {@value #CONNECTION_FAILURE}: the connection failure is
     * not taken for an expired lock wait, HikariCP closed the connection, the manager skipped its rollback,
     * and no rollback failure replaced the exception or logged it unredacted. The connection leaves the
     * pool, and once the database answers again a transaction succeeds.
     *
     * @throws Exception if the relay cannot start or a query fails unexpectedly
     */
    @Test
    void aTransactionOnAWarmConnectionEndsWithTheStatementsConnectionFailureWhenTheDatabaseStalls()
            throws Exception {
        ExecutorService queries = Executors.newSingleThreadExecutor(task -> daemon("bounded-transaction", task));
        LoggerContext loggerContext = (LoggerContext) LoggerFactory.getILoggerFactory();
        Logger templateLogger = loggerContext.getLogger(TransactionTemplate.class.getName());
        Level originalLevel = templateLogger.getLevel();
        ListAppender<ILoggingEvent> templateEvents = new ListAppender<>();
        // ERROR is set explicitly, so the check does not depend on whatever configured the root logger.
        templateLogger.setLevel(Level.ERROR);
        templateEvents.setContext(loggerContext);
        templateEvents.start();
        templateLogger.addAppender(templateEvents);
        try (StallableRelay relay = new StallableRelay(new InetSocketAddress(
                POSTGRES.getHost(), POSTGRES.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT)))) {
            transactionalStallRunner(relay.port()).run(context -> {
                try {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBeansOfType(TransactionManager.class))
                            .as("Boot's manager must back off beside TransactionManagerConfig")
                            .containsOnlyKeys(TRANSACTION_MANAGER_BEAN);
                    PlatformTransactionManager manager = context.getBean(PlatformTransactionManager.class);
                    assertThat(manager).isInstanceOf(BrokenConnectionTransactionManager.class);
                    HikariDataSource pool = context.getBean(HikariDataSource.class);
                    JdbcTemplate jdbc = new JdbcTemplate(pool);
                    LockWaitingReads reads = new LockWaitingReads(new AppProperties(
                            new AppProperties.Db(STALL_LOCK_TIMEOUT), new AppProperties.Search(12, 100, 9999)), jdbc);
                    TransactionTemplate readOnly = new TransactionTemplate(manager);
                    readOnly.setReadOnly(true);
                    Callable<Integer> select = () -> readOnly.execute(
                            status -> reads.read(() -> jdbc.queryForObject(SELECT_ONE, Integer.class)));
                    AtomicInteger stalledAttempts = new AtomicInteger();
                    // The stall starts inside the read, after SET LOCAL lock_timeout and the savepoint.
                    Callable<Integer> stalledSelect = () -> readOnly.execute(status -> reads.read(() -> {
                        stalledAttempts.incrementAndGet();
                        relay.stall();
                        return jdbc.queryForObject(SELECT_ONE, Integer.class);
                    }));
                    assertThat(pool.getHikariPoolMXBean())
                            .as("the first transaction must start the pool, so the pool reads the bypass window")
                            .isNull();

                    Integer warm = withAliveBypassWindow(() -> query(queries, select));

                    assertThat(warm).isEqualTo(1);

                    long start = System.nanoTime();
                    Throwable failure = failureOf(queries, stalledSelect);
                    Duration elapsed = Duration.ofNanos(System.nanoTime() - start);

                    assertThat(stalledAttempts)
                            .as("a connection failure is not an expired lock wait, so the read is not retried")
                            .hasValue(1);
                    assertThat(failure)
                            .as("the statement's own failure, not the rollback failure of the closed connection")
                            .isInstanceOf(DataAccessException.class)
                            .isNotInstanceOf(TransactionSystemException.class)
                            .hasRootCauseInstanceOf(SocketTimeoutException.class);
                    assertThat(ProblemFactory.findSqlState(failure))
                            .as("the SQLSTATE the 500 DEM9999 line logs for this failure")
                            .contains(CONNECTION_FAILURE);
                    assertThat(elapsed)
                            .as("the stalled transaction must fail within the socket bound %s plus %s",
                                    STALL_SOCKET_TIMEOUT, STALL_MARGIN)
                            .isLessThan(STALL_SOCKET_TIMEOUT.plus(STALL_MARGIN));
                    assertThat(templateEvents.list)
                            .as("no rollback failure may replace the statement's exception and log it unredacted")
                            .extracting(ILoggingEvent::getFormattedMessage)
                            .noneMatch(message -> message.contains(OVERRIDE_LINE));
                    assertThat(pool.getHikariPoolMXBean().getTotalConnections())
                            .as("the connection whose read timed out must leave the pool")
                            .isZero();

                    relay.release();

                    assertThat(awaitRecovery(queries, select)).isEqualTo(1);
                } finally {
                    // Unblocks a query that is still waiting, so the pool closes without delay.
                    relay.release();
                }
            });
        } finally {
            queries.shutdownNow();
            templateLogger.detachAppender(templateEvents);
            templateEvents.stop();
            templateLogger.setLevel(originalLevel);
        }
    }

    /**
     * Asserts that a connection carries the given socket bound, is a TCP connection, and reports the
     * configured server-side keepalives.
     *
     * @param connection              a borrowed pool connection
     * @param expectedNetworkTimeoutMs the socket bound expected, in ms
     * @throws SQLException if the connection cannot be queried
     */
    private static void assertBounds(Connection connection, int expectedNetworkTimeoutMs)
            throws SQLException {
        assertThat(connection.getNetworkTimeout())
                .as("pgjdbc socketTimeout of the pooled connection, in ms")
                .isEqualTo(expectedNetworkTimeoutMs);
        try (Statement statement = connection.createStatement()) {
            try (ResultSet tcp = statement.executeQuery(IS_TCP)) {
                assertThat(tcp.next()).isTrue();
                assertThat(tcp.getBoolean(1))
                        .as("keepalives apply to TCP sessions only, so the check needs one")
                        .isTrue();
            }
            Map<String, String> keepalives = new HashMap<>();
            try (ResultSet settings = statement.executeQuery(KEEPALIVE_SETTINGS)) {
                while (settings.next()) {
                    keepalives.put(settings.getString("name"), settings.getString("setting"));
                }
            }
            assertThat(keepalives)
                    .as("server-side TCP keepalives of the session (seconds, seconds, probes)")
                    .isEqualTo(CONFIGURED_KEEPALIVES);
        }
    }

    /**
     * Runs the datasource auto-configuration alone over the real {@code application.yml}, with the
     * {@code DB_*} variables pointing at the given address of {@code POSTGRES}.
     *
     * @param host the database host
     * @param port the database port
     * @return a new runner
     */
    private static ApplicationContextRunner databaseRunner(String host, int port) {
        return new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withConfiguration(AutoConfigurations.of(DataSourceAutoConfiguration.class))
                .withPropertyValues(
                        "DB_HOST=" + host,
                        "DB_PORT=" + port,
                        "DB_NAME=" + POSTGRES.getDatabaseName(),
                        "DB_USER=" + POSTGRES.getUsername(),
                        "DB_PASSWORD=" + POSTGRES.getPassword());
    }

    /**
     * Runs a one-connection pool that reaches {@code POSTGRES} through the relay, with the socket
     * bound lowered to {@link #STALL_SOCKET_TIMEOUT}.
     *
     * @param relayPort the loopback port of the relay
     * @return a new runner
     */
    private static ApplicationContextRunner stallRunner(int relayPort) {
        return databaseRunner(InetAddress.getLoopbackAddress().getHostAddress(), relayPort)
                .withPropertyValues(
                        "spring.datasource.hikari.maximum-pool-size=1",
                        "spring.datasource.hikari.data-source-properties.socketTimeout="
                                + STALL_SOCKET_TIMEOUT.toSeconds());
    }

    /**
     * Runs {@link #stallRunner(int)} with the application's {@link TransactionManagerConfig} beside the
     * transaction auto-configurations of the application context, so the transactions run on the
     * application's manager, customized as in the application, and with {@link DataSourceHealthConfig},
     * which registers the {@link ConnectionPoolSaturation} checks that manager consults.
     *
     * @param relayPort the loopback port of the relay
     * @return a new runner
     */
    private static ApplicationContextRunner transactionalStallRunner(int relayPort) {
        return stallRunner(relayPort)
                .withConfiguration(AutoConfigurations.of(
                        TransactionManagerCustomizationAutoConfiguration.class,
                        DataSourceTransactionManagerAutoConfiguration.class,
                        TransactionAutoConfiguration.class))
                .withUserConfiguration(DataSourceHealthConfig.class, TransactionManagerConfig.class);
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
     * Runs {@code SELECT 1} on the query thread and waits at most {@link #QUERY_BUDGET}.
     *
     * @param queries the single query thread
     * @param select  {@code SELECT 1} over the stall test's pool, directly or in a transaction
     * @return the query's result
     * @throws AssertionError if the query gives no answer within {@link #QUERY_BUDGET}
     * @throws Exception      if the query fails or the wait is interrupted
     */
    private static Integer query(ExecutorService queries, Callable<Integer> select) throws Exception {
        Future<Integer> answer = queries.submit(select);
        try {
            return answer.get(QUERY_BUDGET.toMillis(), TimeUnit.MILLISECONDS);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof Exception cause) {
                throw cause;
            }
            throw e;
        } catch (TimeoutException e) {
            answer.cancel(true);
            throw new AssertionError("The query gave no answer within " + QUERY_BUDGET, e);
        }
    }

    /**
     * Runs {@code SELECT 1} on the query thread, expecting it to fail within {@link #QUERY_BUDGET}.
     *
     * @param queries the single query thread
     * @param select  {@code SELECT 1} over the stall test's pool, directly or in a transaction
     * @return what the query threw
     * @throws AssertionError if the query succeeds or gives no answer within {@link #QUERY_BUDGET}
     * @throws Exception      if the wait is interrupted
     */
    private static Throwable failureOf(ExecutorService queries, Callable<Integer> select) throws Exception {
        Future<Integer> answer = queries.submit(select);
        try {
            Integer result = answer.get(QUERY_BUDGET.toMillis(), TimeUnit.MILLISECONDS);
            throw new AssertionError("The query on the stalled database answered " + result);
        } catch (ExecutionException e) {
            return e.getCause();
        } catch (TimeoutException e) {
            answer.cancel(true);
            throw new AssertionError("The query on the stalled database gave no answer within "
                    + QUERY_BUDGET + ": no socket bound ended it", e);
        }
    }

    /**
     * Queries until a query succeeds again, once the relay forwards again and the pool has opened a
     * new connection. A transaction that cannot obtain a connection yet fails with a
     * {@link TransactionException} rather than a {@link DataAccessException}; both are retried.
     *
     * @param queries the single query thread
     * @param select  {@code SELECT 1} over the stall test's pool, directly or in a transaction
     * @return the first successful result
     * @throws AssertionError if no query succeeds within {@link #RECOVERY_DEADLINE}
     * @throws Exception      if the wait is interrupted
     */
    private static Integer awaitRecovery(ExecutorService queries, Callable<Integer> select) throws Exception {
        long deadline = System.nanoTime() + RECOVERY_DEADLINE.toNanos();
        while (true) {
            try {
                return query(queries, select);
            } catch (DataAccessException | TransactionException e) {
                if (System.nanoTime() - deadline >= 0) {
                    throw new AssertionError("No query succeeded within " + RECOVERY_DEADLINE
                            + " after the database answered again", e);
                }
                Thread.sleep(RECOVERY_POLL.toMillis());
            }
        }
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
}
