package com.democorp.customermaster.repository;

import com.democorp.customermaster.config.AppProperties;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.postgresql.Driver;
import org.postgresql.PGProperty;
import org.postgresql.util.PSQLException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Runs a read of {@code custmast} so that it waits for a table lock for as long as the lock is held,
 * while every wait for PostgreSQL's answer stays shorter than the socket bound: a read queued behind a
 * generator load waits until the load commits, and only a database that stops answering ends it early.
 *
 * <p><b>Why.</b> pgjdbc's {@code socketTimeout} (45 s in {@code application.yml}) bounds every wait for
 * a byte from PostgreSQL, so a database that stops answering fails a request with 500 DEM9999 instead of
 * holding it without end. A statement waiting for a lock receives no byte until the lock is granted, so a
 * read queued behind a load's {@code ACCESS EXCLUSIVE} lock would fail the same way once a healthy load
 * outlasted that bound. Reads only ever wait on a table lock (a load's, or a manual {@code LOCK TABLE}
 * or DDL): under MVCC they never wait on a row lock.
 *
 * <p><b>How.</b> {@link #read(Supplier)} runs inside the caller's transaction:
 * <ol>
 *   <li>{@code SET LOCAL lock_timeout} from {@code customer-master.db.lock-timeout}, so PostgreSQL itself
 *       answers every lock wait within that time, with SQLSTATE {@value #LOCK_NOT_AVAILABLE};</li>
 *   <li>a savepoint on the transaction's connection, then the read;</li>
 *   <li>when the read ends in an expired lock wait ({@link #isLockWait(Throwable)}), a rollback to the
 *       savepoint, which keeps the {@code SET LOCAL} settings issued before it, and the read again. Each
 *       such answer proves the server alive, and the read keeps waiting until the holder commits or
 *       rolls back. It rejoins the lock queue each time, so a lock request queued behind it meanwhile,
 *       such as a second load, may be granted first;</li>
 *   <li>once the read succeeds, the release of the savepoint.</li>
 * </ol>
 * Every other failure propagates unchanged and without a rollback to the savepoint: a socket timeout on
 * a stalled database is SQLSTATE {@code 08006}, its connection is already closed, and the request still
 * answers 500 DEM9999 within the socket bound. A thread that is interrupted stops at its next expired
 * wait, which then propagates. Each re-check is logged at DEBUG with the lock timeout and the attempt
 * number only, never SQL text or values.
 *
 * <p><b>Rollback-only mark.</b> A read through a Spring Data repository method joins the caller's
 * transaction through that method's own {@code @Transactional}, and the method's failure marks the whole
 * transaction rollback-only, which would turn the read that succeeds next into an
 * {@code UnexpectedRollbackException} at commit. The rollback to the savepoint therefore restores the
 * mark to what it was before the attempt, as Spring's own rollback to the savepoint of a nested
 * transaction does.
 *
 * <p><b>Startup guard.</b> The lock timeout must be below the socket bound pgjdbc applies: the
 * {@code socketTimeout} of {@code spring.datasource.hikari.data-source-properties}, or of the JDBC URL,
 * which pgjdbc lets win. Otherwise PostgreSQL's answer to a lock wait could arrive after the bound has
 * given up on the connection, which would turn a healthy wait into 500 DEM9999 for these reads and for
 * an add or update that is owed 409 DEM1001, so the bean fails startup naming both settings. A
 * {@link DataSource} that is not a Hikari pool, or a bound of 0 (none), passes. A lock timeout outside
 * 1 ms to {@link Integer#MAX_VALUE} ms fails startup as well. Every such failure is an
 * {@link InvalidLockTimeoutException}.
 *
 * <p><b>Shared with the writes.</b> {@link #lockTimeoutSql(Duration)} formats the one
 * {@code SET LOCAL lock_timeout} statement and {@link #isLockWait(Throwable)} recognises an expired lock
 * wait, for these reads and for the add and update transactions of {@code CustomerMaintenanceService},
 * which map an expired wait to 409 DEM1001.
 *
 * <p>Example, inside the read-only transaction of a get:
 * <pre>{@code
 * Optional<Customer> found = lockWaitingReads.read(() -> customerRepository.findById(id));
 * }</pre>
 *
 * <p>The bean holds only its template, its datasource and the lock timeout fixed at construction, so
 * one instance serves all threads.
 */
@Component
public class LockWaitingReads {

    /** PostgreSQL {@code lock_not_available}: a {@code lock_timeout} expired. */
    static final String LOCK_NOT_AVAILABLE = "55P03";

    /** The lock-timeout setting, as the startup guard names it. */
    static final String LOCK_TIMEOUT_SETTING = "customer-master.db.lock-timeout (DB_LOCK_TIMEOUT)";

    /** The socket-bound setting, as the startup guard names it. */
    static final String SOCKET_TIMEOUT_SETTING = "spring.datasource.hikari.data-source-properties.socketTimeout";

    /** Most throwables {@link #isLockWait(Throwable)} inspects in one cause graph. */
    private static final int MAX_CAUSE_DEPTH = 32;

    /** Smallest lock timeout accepted; PostgreSQL reads {@code 0} as "wait forever". */
    private static final Duration MIN_LOCK_TIMEOUT = Duration.ofMillis(1);

    /** Largest lock timeout accepted: PostgreSQL's {@code lock_timeout} is an {@code int} of ms. */
    private static final Duration MAX_LOCK_TIMEOUT = Duration.ofMillis(Integer.MAX_VALUE);

    /** Re-checks at DEBUG; never SQL text, values or exception messages. */
    private static final Logger log = LoggerFactory.getLogger(LockWaitingReads.class);

    /** Runs {@code SET LOCAL lock_timeout} on the connection bound to the caller's transaction. */
    private final JdbcTemplate jdbcTemplate;

    /** The template's datasource, the key the caller's transaction binds its connection under. */
    private final DataSource dataSource;

    /** {@code SET LOCAL lock_timeout = '<n>ms'}, formatted once from configuration. */
    private final String setLockTimeoutSql;

    /** The lock timeout in ms, for the DEBUG line of a re-check. */
    private final long lockTimeoutMillis;

    /**
     * Creates the bean, fixes its lock-timeout statement from configuration and checks that timeout
     * against the socket bound.
     *
     * @param appProperties {@code customer-master.db.lock-timeout}, the re-check interval
     * @param jdbcTemplate  the template Spring Boot configures on the application's datasource; its
     *                      connections take part in the caller's Spring-managed transaction
     * @throws NullPointerException        if any argument, the {@code db} settings, their lock timeout or
     *                                     the template's datasource is {@code null}
     * @throws InvalidLockTimeoutException if the lock timeout is not between 1 ms and
     *                                     {@link Integer#MAX_VALUE} ms, with the range failure of
     *                                     {@link #lockTimeoutSql(Duration)} as its cause, or is not below
     *                                     the socket bound, or the socket bound is not a whole number of
     *                                     seconds
     */
    public LockWaitingReads(AppProperties appProperties, JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate");
        this.dataSource = Objects.requireNonNull(jdbcTemplate.getDataSource(), "jdbcTemplate.dataSource");
        Objects.requireNonNull(appProperties, "appProperties");
        AppProperties.Db db = Objects.requireNonNull(appProperties.db(), "appProperties.db");
        Duration lockTimeout = Objects.requireNonNull(db.lockTimeout(), "lockTimeout");
        try {
            this.setLockTimeoutSql = lockTimeoutSql(lockTimeout);
        } catch (IllegalArgumentException outOfRange) {
            // A configured value, not a caller's argument: startup fails as for the socket bound.
            throw new InvalidLockTimeoutException(outOfRange.getMessage(), outOfRange);
        }
        this.lockTimeoutMillis = lockTimeout.toMillis();
        requireBelowSocketTimeout(lockTimeout, dataSource);
    }

    /**
     * Runs a read inside the caller's transaction, waiting through every table lock until the holder
     * commits or rolls back: {@code SET LOCAL lock_timeout}, a savepoint, then the read, rolled back to
     * the savepoint and run again after each expired lock wait, and the savepoint released once the read
     * succeeds.
     *
     * <p>The read must run on the transaction's connection, through a {@link JdbcTemplate} or a Spring
     * Data repository over the application's datasource, and may therefore run more than once. Settings
     * the caller issued with {@code SET LOCAL} before this call, such as the search's
     * {@code plan_cache_mode}, stay in force for every attempt.
     *
     * @param read the read, which returns its result or throws an unchecked exception
     * @param <T>  the read's result type
     * @return what the read returned on the attempt that got past every table lock
     * @throws NullPointerException             if {@code read} is {@code null}
     * @throws IllegalTransactionStateException if no Spring-managed transaction holds a connection of
     *                                          the application's datasource, for example on an instance
     *                                          that is not the Spring proxy; nothing has run
     * @throws DataAccessException              if {@code SET LOCAL lock_timeout}, the savepoint, the
     *                                          rollback to it or its release fails
     * @throws RuntimeException                 whatever the read throws other than an expired lock wait,
     *                                          unchanged, such as the {@code 08006} failure of a stalled
     *                                          database; an expired lock wait itself when the thread is
     *                                          interrupted
     */
    public <T> T read(Supplier<T> read) {
        Objects.requireNonNull(read, "read");
        ConnectionHolder transaction = transactionConnection();
        jdbcTemplate.execute(setLockTimeoutSql);
        Savepoint savepoint = createSavepoint(transaction);
        for (long attempt = 1; ; attempt++) {
            boolean rollbackOnlyBefore = transaction.isRollbackOnly();
            T result;
            try {
                result = read.get();
            } catch (RuntimeException failure) {
                // Only PostgreSQL's answer to a lock wait is retried. Anything else, a stalled database's
                // 08006 included, ends the read as thrown, and so does that answer once the thread is
                // interrupted: the caller's transaction then rolls back as a whole.
                if (!isLockWait(failure) || Thread.currentThread().isInterrupted()) {
                    throw failure;
                }
                rollbackTo(transaction, savepoint);
                if (!rollbackOnlyBefore) {
                    transaction.resetRollbackOnly();
                }
                log.debug("customer.read table lock still held after lock_timeout={}ms; waiting again,"
                        + " attempt {}", lockTimeoutMillis, attempt + 1);
                continue;
            }
            release(transaction, savepoint);
            return result;
        }
    }

    /**
     * Formats {@code SET LOCAL lock_timeout} from the configured duration. {@code SET} takes no bind
     * parameters, so the value is a whole number of milliseconds formatted from configuration, never
     * caller text.
     *
     * <p>The range is checked so the configured wait is the wait PostgreSQL applies: a duration under
     * 1 ms would format as {@code 0ms}, which PostgreSQL reads as "no timeout" and would let an add or
     * update, or a read's re-check, wait on a lock indefinitely; a duration over
     * {@link Integer#MAX_VALUE} ms exceeds the parameter's range and would fail every statement that
     * sets it. Sub-millisecond parts are dropped.
     *
     * @param lockTimeout {@code customer-master.db.lock-timeout}
     * @return the statement, for example {@code SET LOCAL lock_timeout = '5000ms'}
     * @throws NullPointerException     if {@code lockTimeout} is {@code null}
     * @throws IllegalArgumentException if the duration is under 1 ms or over
     *                                  {@link Integer#MAX_VALUE} ms
     */
    public static String lockTimeoutSql(Duration lockTimeout) {
        Objects.requireNonNull(lockTimeout, "lockTimeout");
        // Fails at startup rather than silently disabling the lock-wait limit: the add and update
        // transactions promise a bounded wait before 409 DEM1001, and a read re-checks a held table lock
        // only when PostgreSQL answers its wait.
        if (lockTimeout.compareTo(MIN_LOCK_TIMEOUT) < 0 || lockTimeout.compareTo(MAX_LOCK_TIMEOUT) > 0) {
            throw new IllegalArgumentException("customer-master.db.lock-timeout must be between "
                    + MIN_LOCK_TIMEOUT.toMillis() + " ms and " + MAX_LOCK_TIMEOUT.toMillis()
                    + " ms, was " + lockTimeout);
        }
        return "SET LOCAL lock_timeout = '" + lockTimeout.toMillis() + "ms'";
    }

    /**
     * Whether a failure is an expired lock wait.
     *
     * <p>Walks the cause graph, including {@link SQLException#getNextException()}, because Spring Data
     * JDBC wraps write failures in {@code DbActionExecutionException} and Spring's PostgreSQL translation
     * leaves {@value #LOCK_NOT_AVAILABLE} uncategorized. A failure is a lock wait when the graph holds a
     * {@link PessimisticLockingFailureException}, the type of the
     * {@link org.springframework.dao.CannotAcquireLockException} that {@link CustomerIdAllocator} raises
     * for its guard, or an {@link SQLException} whose SQLSTATE is {@value #LOCK_NOT_AVAILABLE}. At most
     * {@value #MAX_CAUSE_DEPTH} throwables are inspected, each once, so a cyclic or self-referencing cause
     * chain ends.
     *
     * @param failure the failure, possibly {@code null}
     * @return {@code true} if the failure is, or is caused by, a lock wait
     */
    public static boolean isLockWait(Throwable failure) {
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
            if (current instanceof PessimisticLockingFailureException) {
                return true;
            }
            if (current instanceof SQLException sql) {
                if (LOCK_NOT_AVAILABLE.equals(sql.getSQLState())) {
                    return true;
                }
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
     * Returns the socket bound pgjdbc applies to the connections of a Hikari pool, in seconds: the
     * {@code socketTimeout} of the pool's JDBC URL when it carries one, since pgjdbc lets a URL parameter
     * win, otherwise that of its {@code data-source-properties}. As pgjdbc does, only text values are
     * read.
     *
     * @param dataSource the application's datasource
     * @return the bound in seconds; 0 when none is set or the datasource is not a {@link HikariDataSource}
     * @throws InvalidLockTimeoutException if the bound is not a whole number of seconds; the message names
     *                                     the setting and not its value
     */
    static int socketTimeoutSeconds(DataSource dataSource) {
        if (!(dataSource instanceof HikariDataSource pool)) {
            return 0;
        }
        Properties connectionProperties = new Properties();
        connectionProperties.putAll(pool.getDataSourceProperties());
        String jdbcUrl = pool.getJdbcUrl();
        // null when the URL is not a pgjdbc URL; the pool's properties then stand alone.
        Properties withUrl = jdbcUrl == null ? null : Driver.parseURL(jdbcUrl, connectionProperties);
        try {
            return PGProperty.SOCKET_TIMEOUT.getInt(withUrl != null ? withUrl : connectionProperties);
        } catch (PSQLException notWholeSeconds) {
            throw new InvalidLockTimeoutException(SOCKET_TIMEOUT_SETTING + " (or socketTimeout in the JDBC URL)"
                    + " must be a whole number of seconds");
        }
    }

    /**
     * Fails startup unless the lock timeout is below the socket bound, when there is one.
     *
     * @param lockTimeout {@code customer-master.db.lock-timeout}
     * @param dataSource  the application's datasource
     * @throws InvalidLockTimeoutException if the lock timeout is not below the socket bound, or the socket
     *                                     bound is not a whole number of seconds
     */
    private static void requireBelowSocketTimeout(Duration lockTimeout, DataSource dataSource) {
        int seconds = socketTimeoutSeconds(dataSource);
        if (seconds <= 0) {
            return;
        }
        Duration socketTimeout = Duration.ofSeconds(seconds);
        if (lockTimeout.compareTo(socketTimeout) >= 0) {
            throw new InvalidLockTimeoutException(LOCK_TIMEOUT_SETTING + " must be below " + SOCKET_TIMEOUT_SETTING
                    + ", so that PostgreSQL answers every lock wait before the socket bound gives up on the"
                    + " connection: " + lockTimeout + " is not below " + socketTimeout);
        }
    }

    /**
     * Returns the connection holder of the caller's transaction on the application's datasource.
     *
     * @return the holder bound by the transaction manager
     * @throws IllegalTransactionStateException if no Spring-managed transaction is active, or none holds
     *                                          a connection of the datasource
     */
    private ConnectionHolder transactionConnection() {
        // Without the caller's transaction, SET LOCAL and the savepoint would end with each statement.
        if (TransactionSynchronizationManager.isActualTransactionActive()
                && TransactionSynchronizationManager.getResource(dataSource) instanceof ConnectionHolder holder) {
            return holder;
        }
        throw new IllegalTransactionStateException(
                "LockWaitingReads.read requires a Spring-managed transaction; call it from a @Transactional bean");
    }

    /**
     * Sets a savepoint on the transaction's connection, named and counted by the holder as Spring's
     * nested transactions are, so the names never collide.
     *
     * @param transaction the caller's transaction
     * @return the savepoint
     * @throws DataAccessException if the savepoint cannot be set
     */
    private Savepoint createSavepoint(ConnectionHolder transaction) {
        try {
            return transaction.createSavepoint();
        } catch (SQLException e) {
            throw translate("Set savepoint before a lock-waiting read", e);
        }
    }

    /**
     * Rolls the transaction back to the savepoint, which undoes the failed attempt and keeps the
     * savepoint and every setting issued before it.
     *
     * @param transaction the caller's transaction
     * @param savepoint   the savepoint of the read
     * @throws DataAccessException if the rollback fails
     */
    private void rollbackTo(ConnectionHolder transaction, Savepoint savepoint) {
        try {
            transaction.getConnection().rollback(savepoint);
        } catch (SQLException e) {
            throw translate("Roll back to the savepoint of a lock-waiting read", e);
        }
    }

    /**
     * Releases the savepoint once the read has succeeded.
     *
     * @param transaction the caller's transaction
     * @param savepoint   the savepoint of the read
     * @throws DataAccessException if the release fails, as on a connection that broke after the read
     */
    private void release(ConnectionHolder transaction, Savepoint savepoint) {
        try {
            transaction.getConnection().releaseSavepoint(savepoint);
        } catch (SQLException e) {
            throw translate("Release the savepoint of a lock-waiting read", e);
        }
    }

    /**
     * Translates a failure of a savepoint operation as the template translates a statement's.
     *
     * @param task what was attempted, for the exception message
     * @param e    the driver's exception
     * @return the translated exception, carrying {@code e} and its SQLSTATE as its cause
     */
    private DataAccessException translate(String task, SQLException e) {
        DataAccessException translated = jdbcTemplate.getExceptionTranslator().translate(task, null, e);
        return translated != null ? translated : new UncategorizedSQLException(task, null, e);
    }

    /**
     * The bean's startup failure: {@code customer-master.db.lock-timeout} ({@code DB_LOCK_TIMEOUT}) is
     * not between 1 ms and {@link Integer#MAX_VALUE} ms, or is not below the socket bound, or the socket
     * bound it is checked against is not a whole number of seconds.
     *
     * <p>The message is one line that names the settings, never a credential or the JDBC URL. It stays an
     * {@link IllegalStateException}, the type Spring Boot and existing callers see; the subtype exists so
     * that the generator CLI, which prints this message as its one outcome line, recognises the failure by
     * type rather than by text.
     */
    public static final class InvalidLockTimeoutException extends IllegalStateException {

        /** Serialization version. */
        private static final long serialVersionUID = 1L;

        /**
         * Creates the exception.
         *
         * @param message the one-line message naming the failing setting and the bound it misses
         */
        public InvalidLockTimeoutException(String message) {
            super(message);
        }

        /**
         * Creates the exception for a failure first raised by another check.
         *
         * @param message the one-line message naming the failing setting and the bound it misses
         * @param cause   the check's own failure, such as the range failure of
         *                {@link LockWaitingReads#lockTimeoutSql(Duration)}
         */
        public InvalidLockTimeoutException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
