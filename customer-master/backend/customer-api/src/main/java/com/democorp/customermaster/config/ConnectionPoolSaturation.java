package com.democorp.customermaster.config;

import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.util.Credentials;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.postgresql.Driver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.Assert;

/**
 * Tells a connection pool that is merely busy from a database that is lost: whether a failure is the
 * borrow timeout of a saturated Hikari pool, and whether PostgreSQL answers right now, asked without
 * the pool.
 *
 * <p><b>Why.</b> Every request that waits on a lock holds its pooled connection for the whole wait: an
 * add or update until the lock timeout, a read behind a generator load's {@code ACCESS EXCLUSIVE} lock
 * until the load commits. Once such requests hold every connection, the next request cannot borrow one
 * within {@code spring.datasource.hikari.connection-timeout} and fails before it reaches the database,
 * although PostgreSQL answers. {@link BrokenConnectionTransactionManager} has the read-only transaction
 * of such a search or get borrow again, so the read waits until a connection is free;
 * {@code controller.ApiExceptionHandler} answers such an add or update as the lock wait it is, 409
 * {@code DEM1001}; and {@link BoundedDataSourceHealthIndicator} keeps the readiness probe UP. All three
 * ask this class first, so a database that is lost still gives 500 {@code DEM9999} and a DOWN probe.
 *
 * <p><b>The saturated pool.</b> When a borrow times out, HikariCP throws a
 * {@link SQLTransientConnectionException} and attaches the last failure to create a connection as its
 * cause and its next exception, when there is one; a successful creation clears that failure. A
 * timeout that carries neither is therefore one in which every connection stayed busy and no
 * connection attempt failed: {@link #isSaturationTimeout(Throwable)}.
 *
 * <p><b>The probe.</b> {@link #answeringDatabase()} opens one unpooled pgjdbc connection from the
 * pool's own configuration (its {@code jdbcUrl}, credentials and {@code data-source-properties},
 * exactly as Hikari passes them to the driver), validates it with {@code Connection.isValid} and closes
 * it. Each of its waits is bounded by {@link #getProbeTimeoutSeconds()}: {@code loginTimeout} bounds
 * opening the connection, {@code connectTimeout} and {@code socketTimeout} bound the driver's socket
 * underneath, and {@code isValid} bounds the validation round trip, so the probe answers within twice
 * that bound. Concurrent callers share one probe in flight, so a burst of failing requests opens at
 * most one probe connection at a time. The JDBC URL, the credentials and exception messages are never
 * logged or returned.
 *
 * <p><b>Thread safety.</b> The only mutable state is the reference to the probe in flight, which is
 * updated atomically; one instance serves every request and health evaluation.
 */
public class ConnectionPoolSaturation {

    /** pgjdbc's connection property bounding the whole connection setup, in seconds. */
    static final String LOGIN_TIMEOUT = "loginTimeout";

    /** pgjdbc's connection property bounding the TCP connect, in seconds. */
    static final String CONNECT_TIMEOUT = "connectTimeout";

    /** pgjdbc's connection property bounding each socket read, in seconds. */
    static final String SOCKET_TIMEOUT = "socketTimeout";

    /** The three pgjdbc properties the probe overrides with its own bound. */
    private static final Set<String> PROBE_TIMEOUTS = Set.of(LOGIN_TIMEOUT, CONNECT_TIMEOUT, SOCKET_TIMEOUT);

    /** pgjdbc's connection property naming the role, as Hikari passes the pool's username. */
    private static final String USER = "user";

    /** pgjdbc's connection property holding the password, as Hikari passes the pool's password. */
    private static final String PASSWORD = "password";

    /** Most throwables {@link #isSaturationTimeout(Throwable)} inspects in one cause graph. */
    private static final int MAX_CAUSE_DEPTH = 32;

    /** Time a caller sharing another caller's probe waits beyond the probe's own two bounds. */
    private static final long SHARED_PROBE_MARGIN_MILLIS = 1_000L;

    /** Placeholder for an absent SQLSTATE in a log line. */
    private static final String ABSENT = "-";

    /** SLF4J logger. */
    private static final Logger log = LoggerFactory.getLogger(ConnectionPoolSaturation.class);

    /** The application's connection pool, whose configuration the probe connects with. */
    private final DataSource dataSource;

    /** The pgjdbc driver the probe connects through, outside the pool. */
    private final Driver driver = new Driver();

    /** The probe in flight, or {@code null} when none runs; set by the caller that runs it. */
    private final AtomicReference<CompletableFuture<Optional<String>>> probeInFlight = new AtomicReference<>();

    /**
     * Creates the checks over the application's {@link DataSource}. No connection is opened until the
     * first probe.
     *
     * @param dataSource the pool; only a {@link HikariDataSource} with a {@code jdbcUrl} can be probed
     * @throws IllegalArgumentException if {@code dataSource} is {@code null}
     */
    public ConnectionPoolSaturation(DataSource dataSource) {
        Assert.notNull(dataSource, "dataSource must not be null");
        this.dataSource = dataSource;
    }

    /**
     * Whether a failure is, or is caused by, the borrow timeout of a saturated Hikari pool: its cause
     * graph, followed through {@link Throwable#getCause()} and {@link SQLException#getNextException()},
     * holds a {@link SQLTransientConnectionException} with neither a cause nor a next exception. At most
     * {@value #MAX_CAUSE_DEPTH} throwables are inspected, each once, so a cyclic or self-referencing
     * graph ends.
     *
     * <p>A borrow timeout that carries the last connection-creation failure is a database that refuses
     * or drops new connections, never saturation; nor is any other failure.
     *
     * @param failure the failure, possibly {@code null}
     * @return {@code true} if the failure holds a borrow timeout without a connection failure
     */
    public static boolean isSaturationTimeout(Throwable failure) {
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        Deque<Throwable> pending = new ArrayDeque<>();
        if (failure != null) {
            pending.add(failure);
        }
        while (!pending.isEmpty() && visited.size() < MAX_CAUSE_DEPTH) {
            Throwable current = pending.poll();
            if (!visited.add(current)) {
                continue;
            }
            if (current instanceof SQLTransientConnectionException timeout
                    && timeout.getCause() == null && timeout.getNextException() == null) {
                return true;
            }
            if (current instanceof SQLException sql) {
                SQLException next = sql.getNextException();
                if (next != null) {
                    pending.add(next);
                }
            }
            Throwable cause = current.getCause();
            if (cause != null) {
                pending.add(cause);
            }
        }
        return false;
    }

    /**
     * Returns the bound of each of the probe's two waits, opening the connection and validating it.
     * For a Hikari pool it is the pool's {@code validation-timeout}, rounded up to whole seconds and at
     * least 1, the bound {@link BoundedDataSourceHealthIndicator} validates its own connection with. The
     * pool's current value is read on every call. A {@link DataSource} that is not a Hikari pool is
     * never probed, so it has no wait.
     *
     * @return the bound in seconds, from 1 to {@link Integer#MAX_VALUE}; 0 for a {@link DataSource} that
     *     is not a {@link HikariDataSource}
     */
    public int getProbeTimeoutSeconds() {
        return dataSource instanceof HikariDataSource pool ? validationTimeoutSeconds(pool) : 0;
    }

    /**
     * Whether PostgreSQL answers a direct, unpooled connection right now; {@link #answeringDatabase()}
     * describes the probe.
     *
     * @return {@code true} if a probe connection opened and passed validation within its bounds
     */
    public boolean databaseAnswers() {
        return answeringDatabase().isPresent();
    }

    /**
     * Probes PostgreSQL without the pool and names the database that answered. One connection is opened
     * from the pool's {@code jdbcUrl}, credentials and {@code data-source-properties}, with
     * {@code loginTimeout}, {@code connectTimeout} and {@code socketTimeout} replaced, in the URL and in
     * the properties alike, by {@link #getProbeTimeoutSeconds()}; it is validated with
     * {@code Connection.isValid} within the same bound and closed. Any failure, a {@link DataSource}
     * that is not a {@link HikariDataSource} and a pool without a {@code jdbcUrl} give no answer.
     *
     * <p>A caller that arrives while another caller's probe runs waits for that probe's result instead
     * of opening a connection of its own, for at most twice the bound plus one second; an interrupted
     * or expired wait gives no answer, and an interruption is restored on the thread.
     *
     * @return the database product name of the probe connection, such as {@code PostgreSQL}; empty if
     *     PostgreSQL did not answer within the bounds
     */
    public Optional<String> answeringDatabase() {
        CompletableFuture<Optional<String>> own = new CompletableFuture<>();
        CompletableFuture<Optional<String>> running = probeInFlight.compareAndExchange(null, own);
        if (running != null) {
            return awaitSharedProbe(running);
        }
        Optional<String> answer = Optional.empty();
        try {
            answer = probe();
            return answer;
        } finally {
            probeInFlight.compareAndSet(own, null);
            own.complete(answer);
        }
    }

    /**
     * Returns the validation bound of a Hikari pool: its {@code validation-timeout} (the bound Hikari
     * applies to its own aliveness check), rounded up to whole seconds and at least 1, because
     * {@code Connection.isValid} takes seconds and reads 0 as no limit.
     *
     * @param pool the pool
     * @return the bound in seconds, from 1 to {@link Integer#MAX_VALUE}
     */
    static int validationTimeoutSeconds(HikariDataSource pool) {
        return Math.clamp(Math.ceilDiv(pool.getValidationTimeout(), 1000L), 1, Integer.MAX_VALUE);
    }

    /**
     * Returns a JDBC URL without the query parameters the probe bounds itself: pgjdbc lets a URL
     * parameter win over a connection property of the same name, so a {@code socketTimeout} in the URL
     * would otherwise outlast the probe's bound. Every other parameter is kept, in order.
     *
     * @param jdbcUrl the pool's JDBC URL
     * @return the URL without {@code loginTimeout}, {@code connectTimeout} and {@code socketTimeout}
     */
    static String withoutProbeTimeouts(String jdbcUrl) {
        int query = jdbcUrl.indexOf('?');
        if (query < 0) {
            return jdbcUrl;
        }
        String kept = Arrays.stream(jdbcUrl.substring(query + 1).split("&"))
                .filter(parameter -> !PROBE_TIMEOUTS.contains(parameterName(parameter)))
                .collect(Collectors.joining("&"));
        return kept.isEmpty() ? jdbcUrl.substring(0, query) : jdbcUrl.substring(0, query + 1) + kept;
    }

    /**
     * Runs one probe on the calling thread.
     *
     * @return the database product name, or empty if PostgreSQL did not answer
     */
    private Optional<String> probe() {
        if (!(dataSource instanceof HikariDataSource pool)) {
            return Optional.empty();
        }
        String jdbcUrl = pool.getJdbcUrl();
        if (jdbcUrl == null || jdbcUrl.isBlank()) {
            return Optional.empty();
        }
        int seconds = validationTimeoutSeconds(pool);
        // A null connection means the driver does not accept the URL; try-with-resources skips it.
        try (Connection connection = driver.connect(withoutProbeTimeouts(jdbcUrl),
                connectionProperties(pool, seconds))) {
            if (connection == null || !connection.isValid(seconds)) {
                return Optional.empty();
            }
            return Optional.ofNullable(connection.getMetaData().getDatabaseProductName());
        } catch (SQLException | RuntimeException e) {
            // Type and SQLSTATE only: a driver message can name the host, and nothing here may carry
            // the URL or the credentials.
            log.debug("Direct database probe failed exception={} sqlState={}", e.getClass().getName(),
                    e instanceof SQLException sql && sql.getSQLState() != null ? sql.getSQLState() : ABSENT);
            return Optional.empty();
        }
    }

    /**
     * Builds the probe's connection properties as Hikari builds a pooled connection's: the pool's
     * {@code data-source-properties}, then {@value #USER} and {@value #PASSWORD} from the pool's
     * credentials unless those properties already set them; then the three timeouts, set to the
     * probe's bound.
     *
     * @param pool    the pool
     * @param seconds the probe's bound
     * @return new properties
     */
    private static Properties connectionProperties(HikariDataSource pool, int seconds) {
        Properties properties = new Properties();
        properties.putAll(pool.getDataSourceProperties());
        Credentials credentials = pool.getCredentials();
        if (credentials.getUsername() != null) {
            properties.put(USER, properties.getProperty(USER, credentials.getUsername()));
        }
        if (credentials.getPassword() != null) {
            properties.put(PASSWORD, properties.getProperty(PASSWORD, credentials.getPassword()));
        }
        String bound = Integer.toString(seconds);
        PROBE_TIMEOUTS.forEach(name -> properties.setProperty(name, bound));
        return properties;
    }

    /**
     * Waits for the result of another caller's probe.
     *
     * @param running the probe in flight
     * @return its result, or empty if the wait was interrupted or outlasted the probe's bounds
     */
    private Optional<String> awaitSharedProbe(CompletableFuture<Optional<String>> running) {
        long waitMillis = 2L * getProbeTimeoutSeconds() * 1000L + SHARED_PROBE_MARGIN_MILLIS;
        try {
            return running.get(waitMillis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } catch (ExecutionException | TimeoutException e) {
            return Optional.empty();
        }
    }

    /**
     * Returns the name of a URL query parameter, the text before its first {@code '='}.
     *
     * @param parameter one {@code name=value} element of the query
     * @return the name; the whole element when it has no {@code '='}
     */
    private static String parameterName(String parameter) {
        int equals = parameter.indexOf('=');
        return equals < 0 ? parameter : parameter.substring(0, equals);
    }
}
