package com.democorp.customermaster.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.democorp.customermaster.config.AppProperties;
import com.democorp.customermaster.repository.LockWaitingReads.InvalidLockTimeoutException;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.sql.Statement;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.slf4j.LoggerFactory;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.UnexpectedRollbackException;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Specifies {@link LockWaitingReads}: a read inside the caller's transaction is retried from a savepoint
 * after every expired lock wait and only then, every other failure propagates unchanged, and the bean
 * refuses a lock timeout that is not below the pgjdbc socket bound or not between 1 ms and
 * {@link Integer#MAX_VALUE} ms, with an {@link InvalidLockTimeoutException}.
 *
 * <p><b>How the transaction runs.</b> Spring's {@link JdbcTransactionManager} and {@link JdbcTemplate}
 * run over a mocked {@link DataSource} whose one mocked {@link Connection} records every call, so the
 * cases see the real begin, savepoint, commit and rollback paths, and the rollback-only mark a failing
 * participating transaction sets, with no database. An expired lock wait is modelled as the database
 * reports it: an {@link SQLException} with SQLSTATE {@value #LOCK_NOT_AVAILABLE}, which Spring's
 * PostgreSQL translation leaves uncategorized, or the {@link CannotAcquireLockException} of
 * {@link CustomerIdAllocator}'s guard.
 *
 * <p><b>The guard.</b> An unstarted {@link HikariDataSource} carries the {@code socketTimeout} under
 * test, in its {@code data-source-properties} or its JDBC URL; no pool is started and no connection is
 * opened.
 *
 * <p>Pure JUnit 5, Mockito, AssertJ and Logback: no Spring context, no database, no Docker. The list
 * appender and the logger level are global state, so {@link #restoreLogging()} puts them back after every
 * case.
 */
@DisplayName("LockWaitingReads: retry after an expired lock wait, nothing else; lock timeout below the socket bound")
final class LockWaitingReadsTest {

    /** SQLSTATE {@code lock_not_available}. */
    private static final String LOCK_NOT_AVAILABLE = "55P03";

    /** SQLSTATE {@code connection_failure}, as pgjdbc reports a timed-out socket read. */
    private static final String CONNECTION_FAILURE = "08006";

    /** The lock timeout of the cases: the test profile's 1 s. */
    private static final Duration LOCK_TIMEOUT = Duration.ofSeconds(1);

    /** The statement the bean issues for {@link #LOCK_TIMEOUT}. */
    private static final String SET_LOCK_TIMEOUT = "SET LOCAL lock_timeout = '1000ms'";

    /** The name Spring's connection holder gives the first savepoint of a transaction. */
    private static final String FIRST_SAVEPOINT = "SAVEPOINT_1";

    /** What the reads of the cases return. */
    private static final String ROW = "AAAD";

    /** Text of the failures that no log line of the bean may repeat. */
    private static final String STATEMENT_SECRET = "SELECT secret_column FROM custmast";

    /** A JDBC URL carrying no socket bound. */
    private static final String PLAIN_URL = "jdbc:postgresql://localhost:5432/customermaster?currentSchema=customer_master";

    private final LoggerContext loggerContext = (LoggerContext) LoggerFactory.getILoggerFactory();

    private final Logger readsLogger = loggerContext.getLogger(LockWaitingReads.class);

    private final ListAppender<ILoggingEvent> readsEvents = new ListAppender<>();

    private Level originalLevel;

    private DataSource dataSource;

    private Connection connection;

    private Statement statement;

    private Savepoint savepoint;

    private JdbcTransactionManager manager;

    private TransactionTemplate readOnly;

    private LockWaitingReads reads;

    @BeforeEach
    void setUp() throws SQLException {
        originalLevel = readsLogger.getLevel();
        // DEBUG is set explicitly, so the cases do not depend on whatever configured the root logger.
        readsLogger.setLevel(Level.DEBUG);
        readsEvents.setContext(loggerContext);
        readsEvents.start();
        readsLogger.addAppender(readsEvents);

        dataSource = mock(DataSource.class);
        connection = mock(Connection.class);
        statement = mock(Statement.class);
        savepoint = mock(Savepoint.class);
        when(dataSource.getConnection()).thenReturn(connection);
        // As a pooled connection is handed out: in auto-commit mode, which the manager switches off.
        when(connection.getAutoCommit()).thenReturn(true);
        when(connection.createStatement()).thenReturn(statement);
        when(connection.setSavepoint(anyString())).thenReturn(savepoint);

        manager = new JdbcTransactionManager(dataSource);
        readOnly = new TransactionTemplate(manager);
        readOnly.setReadOnly(true);
        reads = new LockWaitingReads(appProperties(LOCK_TIMEOUT), new JdbcTemplate(dataSource));
    }

    @AfterEach
    void restoreLogging() {
        readsLogger.detachAppender(readsEvents);
        readsEvents.stop();
        readsLogger.setLevel(originalLevel);
    }

    @Test
    @DisplayName("55P03: rolled back to the savepoint and read again until the read succeeds, then released")
    void anExpiredLockWaitIsRetriedFromTheSavepointUntilTheReadSucceeds() throws SQLException {
        AtomicInteger attempts = new AtomicInteger();
        Supplier<String> read = () -> {
            if (attempts.incrementAndGet() <= 2) {
                throw lockTimeoutExpired();
            }
            return ROW;
        };

        String result = readOnly.execute(status -> reads.read(read));

        assertThat(result).isEqualTo(ROW);
        assertThat(attempts).as("two expired waits, then the read that got the lock").hasValue(3);
        InOrder order = inOrder(connection, statement);
        order.verify(statement).execute(SET_LOCK_TIMEOUT);
        order.verify(connection).setSavepoint(FIRST_SAVEPOINT);
        order.verify(connection, times(2)).rollback(savepoint);
        order.verify(connection).releaseSavepoint(savepoint);
        order.verify(connection).commit();
        verify(statement).execute(anyString());
        verify(connection).setSavepoint(anyString());
        verify(connection, never()).rollback();
        assertThat(readsEvents.list)
                .as("one DEBUG line per re-check, with the lock timeout and the attempt only")
                .hasSize(2)
                .allSatisfy(event -> {
                    assertThat(event.getLevel()).isEqualTo(Level.DEBUG);
                    assertThat(event.getFormattedMessage())
                            .contains("lock_timeout=1000ms")
                            .doesNotContain(STATEMENT_SECRET)
                            .doesNotContain(LOCK_NOT_AVAILABLE);
                    assertThat(event.getThrowableProxy()).isNull();
                })
                .extracting(ILoggingEvent::getFormattedMessage)
                .anySatisfy(message -> assertThat(message).endsWith("attempt 2"))
                .anySatisfy(message -> assertThat(message).endsWith("attempt 3"));
    }

    @Test
    @DisplayName("a PessimisticLockingFailureException is an expired lock wait too")
    void aPessimisticLockingFailureIsRetried() throws SQLException {
        AtomicInteger attempts = new AtomicInteger();
        Supplier<String> read = () -> {
            if (attempts.incrementAndGet() == 1) {
                throw new CannotAcquireLockException("lock_timeout expired for [" + STATEMENT_SECRET + "]");
            }
            return ROW;
        };

        String result = readOnly.execute(status -> reads.read(read));

        assertThat(result).isEqualTo(ROW);
        assertThat(attempts).hasValue(2);
        verify(connection).rollback(savepoint);
        verify(connection).releaseSavepoint(savepoint);
        verify(connection).commit();
    }

    @Test
    @DisplayName("a lock wait inside a participating repository transaction does not doom the caller's commit")
    void theRollbackOnlyMarkOfAFailedParticipatingReadIsUndoneWithTheSavepoint() throws SQLException {
        // As a Spring Data repository method joins the caller's transaction through its own
        // @Transactional: its failure marks the shared transaction rollback-only.
        TransactionTemplate participating = new TransactionTemplate(manager);
        AtomicInteger attempts = new AtomicInteger();
        Supplier<String> read = () -> participating.execute(status -> {
            if (attempts.incrementAndGet() == 1) {
                throw lockTimeoutExpired();
            }
            return ROW;
        });

        String result = readOnly.execute(status -> reads.read(read));

        assertThat(result).isEqualTo(ROW);
        assertThat(attempts).hasValue(2);
        verify(connection).commit();
        verify(connection, never()).rollback();
    }

    @Test
    @DisplayName("a rollback-only mark set before the read stays set")
    void aRollbackOnlyMarkFromBeforeTheReadIsKept() throws SQLException {
        TransactionTemplate participating = new TransactionTemplate(manager);
        AtomicInteger attempts = new AtomicInteger();
        Supplier<String> read = () -> {
            if (attempts.incrementAndGet() == 1) {
                throw lockTimeoutExpired();
            }
            return ROW;
        };

        Throwable thrown = catchThrowable(() -> readOnly.execute(status -> {
            Throwable earlier = catchThrowable(() -> participating.execute(inner -> {
                throw new IllegalStateException("an earlier failure the caller caught");
            }));
            assertThat(earlier).isInstanceOf(IllegalStateException.class);
            return reads.read(read);
        }));

        assertThat(thrown)
                .as("the earlier failure still rolls the caller's transaction back")
                .isInstanceOf(UnexpectedRollbackException.class);
        assertThat(attempts).hasValue(2);
        verify(connection).rollback(savepoint);
        verify(connection).rollback();
        verify(connection, never()).commit();
    }

    @Test
    @DisplayName("a stalled database's 08006 propagates as thrown: one attempt, no savepoint rollback")
    void aConnectionFailurePropagatesUnchangedWithoutRetry() throws SQLException {
        DataAccessResourceFailureException stalled = new DataAccessResourceFailureException(
                "I/O error reading [" + STATEMENT_SECRET + "]",
                new SQLException("An I/O error occurred while sending to the backend.", CONNECTION_FAILURE));
        AtomicInteger attempts = new AtomicInteger();
        Supplier<String> read = () -> {
            attempts.incrementAndGet();
            throw stalled;
        };

        Throwable thrown = catchThrowable(() -> readOnly.execute(status -> reads.read(read)));

        assertThat(thrown).isSameAs(stalled);
        assertThat(attempts).hasValue(1);
        verify(connection, never()).rollback(any(Savepoint.class));
        verify(connection, never()).releaseSavepoint(any(Savepoint.class));
        verify(connection).rollback();
        assertThat(readsEvents.list).as("nothing is logged for a failure that is not retried").isEmpty();
    }

    @Test
    @DisplayName("any other runtime failure propagates as thrown, after one attempt")
    void anyOtherFailurePropagatesUnchangedWithoutRetry() throws SQLException {
        IllegalStateException broken = new IllegalStateException("mapping failed");
        AtomicInteger attempts = new AtomicInteger();
        Supplier<String> read = () -> {
            attempts.incrementAndGet();
            throw broken;
        };

        Throwable thrown = catchThrowable(() -> readOnly.execute(status -> reads.read(read)));

        assertThat(thrown).isSameAs(broken);
        assertThat(attempts).hasValue(1);
        verify(connection, never()).rollback(any(Savepoint.class));
        verify(connection).rollback();
    }

    @Test
    @DisplayName("an interrupted thread stops at its next expired wait, which propagates")
    void anInterruptedThreadStopsAtTheNextExpiredWait() throws SQLException {
        UncategorizedSQLException expired = lockTimeoutExpired();
        AtomicInteger attempts = new AtomicInteger();
        Supplier<String> read = () -> {
            attempts.incrementAndGet();
            Thread.currentThread().interrupt();
            throw expired;
        };

        Throwable thrown;
        try {
            thrown = catchThrowable(() -> readOnly.execute(status -> reads.read(read)));
        } finally {
            // Clears the flag, so no later case runs on an interrupted thread.
            assertThat(Thread.interrupted()).as("the interrupt stays set for the caller").isTrue();
        }

        assertThat(thrown).isSameAs(expired);
        assertThat(attempts).hasValue(1);
        verify(connection, never()).rollback(any(Savepoint.class));
        verify(connection).rollback();
    }

    @Test
    @DisplayName("no transaction: IllegalTransactionStateException before anything runs")
    void aReadOutsideATransactionIsRejectedBeforeAnythingRuns() {
        AtomicInteger attempts = new AtomicInteger();

        Throwable thrown = catchThrowable(() -> reads.read(() -> {
            attempts.incrementAndGet();
            return ROW;
        }));

        assertThat(thrown).isInstanceOf(IllegalTransactionStateException.class)
                .hasMessageContaining("Spring-managed transaction");
        assertThat(attempts).hasValue(0);
        verifyNoInteractions(dataSource, connection, statement);
    }

    @Test
    @DisplayName("a failed rollback to the savepoint is translated and ends the read")
    void aFailedRollbackToTheSavepointIsTranslated() throws SQLException {
        SQLException broken = new SQLException("An I/O error occurred while sending to the backend.",
                CONNECTION_FAILURE);
        doThrow(broken).when(connection).rollback(savepoint);
        AtomicInteger attempts = new AtomicInteger();
        Supplier<String> read = () -> {
            attempts.incrementAndGet();
            throw lockTimeoutExpired();
        };

        Throwable thrown = catchThrowable(() -> readOnly.execute(status -> reads.read(read)));

        assertThat(thrown).isInstanceOf(DataAccessException.class).hasCause(broken);
        assertThat(attempts).hasValue(1);
        verify(connection, never()).releaseSavepoint(any(Savepoint.class));
    }

    @Test
    @DisplayName("a failed release of the savepoint is translated and ends the read")
    void aFailedReleaseOfTheSavepointIsTranslated() throws SQLException {
        SQLException broken = new SQLException("An I/O error occurred while sending to the backend.",
                CONNECTION_FAILURE);
        doThrow(broken).when(connection).releaseSavepoint(savepoint);

        Throwable thrown = catchThrowable(() -> readOnly.execute(status -> reads.read(() -> ROW)));

        assertThat(thrown).isInstanceOf(DataAccessException.class).hasCause(broken);
        verify(connection, never()).commit();
    }

    @Test
    @DisplayName("lock timeout equal to or above the socket bound: startup fails naming both settings,"
            + " as an InvalidLockTimeoutException, which is an IllegalStateException")
    void aLockTimeoutNotBelowTheSocketBoundFailsStartup() {
        for (Duration lockTimeout : new Duration[] {Duration.ofSeconds(45), Duration.ofSeconds(46)}) {
            try (HikariDataSource pool = pool(PLAIN_URL, "45")) {
                assertThatIllegalStateException()
                        .isThrownBy(() -> new LockWaitingReads(appProperties(lockTimeout), new JdbcTemplate(pool)))
                        .isInstanceOf(InvalidLockTimeoutException.class)
                        .withMessageContaining("customer-master.db.lock-timeout (DB_LOCK_TIMEOUT)")
                        .withMessageContaining("spring.datasource.hikari.data-source-properties.socketTimeout")
                        .withMessageEndingWith(lockTimeout + " is not below PT45S");
            }
        }
    }

    @Test
    @DisplayName("lock timeout below the socket bound, no bound, or no Hikari pool: accepted")
    void aLockTimeoutBelowTheSocketBoundOrWithoutABoundIsAccepted() {
        try (HikariDataSource bounded = pool(PLAIN_URL, "45");
                HikariDataSource unbounded = pool(PLAIN_URL, null);
                HikariDataSource zero = pool(PLAIN_URL, "0")) {
            assertThatCode(() -> new LockWaitingReads(appProperties(Duration.ofSeconds(5)), new JdbcTemplate(bounded)))
                    .doesNotThrowAnyException();
            assertThatCode(() -> new LockWaitingReads(appProperties(Duration.ofMillis(44_999)),
                    new JdbcTemplate(bounded))).doesNotThrowAnyException();
            assertThatCode(() -> new LockWaitingReads(appProperties(Duration.ofMinutes(10)),
                    new JdbcTemplate(unbounded))).doesNotThrowAnyException();
            assertThatCode(() -> new LockWaitingReads(appProperties(Duration.ofMinutes(10)),
                    new JdbcTemplate(zero))).doesNotThrowAnyException();
        }
        assertThatCode(() -> new LockWaitingReads(appProperties(Duration.ofMinutes(10)),
                new JdbcTemplate(mock(DataSource.class)))).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a socketTimeout in the JDBC URL wins over the data-source property, as in pgjdbc")
    void theJdbcUrlsSocketTimeoutIsTheBoundChecked() {
        try (HikariDataSource urlBound = pool(PLAIN_URL + "&socketTimeout=3", "45")) {
            assertThat(LockWaitingReads.socketTimeoutSeconds(urlBound)).isEqualTo(3);
            assertThatExceptionOfType(InvalidLockTimeoutException.class)
                    .isThrownBy(() -> new LockWaitingReads(appProperties(Duration.ofSeconds(5)),
                            new JdbcTemplate(urlBound)))
                    .withMessageEndingWith("PT5S is not below PT3S");
        }
    }

    @Test
    @DisplayName("a socketTimeout that is not whole seconds fails startup without echoing the value")
    void aSocketTimeoutThatIsNotWholeSecondsFailsStartup() {
        try (HikariDataSource pool = pool(PLAIN_URL, "forty-five")) {
            assertThatExceptionOfType(InvalidLockTimeoutException.class)
                    .isThrownBy(() -> new LockWaitingReads(appProperties(Duration.ofSeconds(5)), new JdbcTemplate(pool)))
                    .withMessageContaining("spring.datasource.hikari.data-source-properties.socketTimeout")
                    .withMessageNotContaining("forty-five");
            assertThatExceptionOfType(InvalidLockTimeoutException.class)
                    .isThrownBy(() -> LockWaitingReads.socketTimeoutSeconds(pool))
                    .withMessageNotContaining("forty-five");
        }
    }

    @Test
    @DisplayName("the lock-timeout statement: whole milliseconds from 1 ms to Integer.MAX_VALUE ms")
    void theLockTimeoutStatementIsRangeChecked() {
        assertThat(LockWaitingReads.lockTimeoutSql(Duration.ofSeconds(5))).isEqualTo("SET LOCAL lock_timeout = '5000ms'");
        assertThat(LockWaitingReads.lockTimeoutSql(Duration.ofMillis(1))).isEqualTo("SET LOCAL lock_timeout = '1ms'");
        assertThat(LockWaitingReads.lockTimeoutSql(Duration.ofMillis(Integer.MAX_VALUE)))
                .isEqualTo("SET LOCAL lock_timeout = '" + Integer.MAX_VALUE + "ms'");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> LockWaitingReads.lockTimeoutSql(Duration.ofNanos(999_999)))
                .withMessageStartingWith("customer-master.db.lock-timeout must be between 1 ms and");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> LockWaitingReads.lockTimeoutSql(Duration.ofMillis(Integer.MAX_VALUE + 1L)));
    }

    @Test
    @DisplayName("a configured lock timeout outside 1 ms to Integer.MAX_VALUE ms fails startup as an"
            + " InvalidLockTimeoutException carrying the range failure")
    void aLockTimeoutOutOfRangeFailsStartup() {
        for (Duration lockTimeout : new Duration[] {Duration.ZERO, Duration.ofSeconds(-1),
                Duration.ofMillis(Integer.MAX_VALUE + 1L)}) {
            assertThatExceptionOfType(InvalidLockTimeoutException.class)
                    .isThrownBy(() -> new LockWaitingReads(appProperties(lockTimeout), new JdbcTemplate(dataSource)))
                    .withMessage("customer-master.db.lock-timeout must be between 1 ms and " + Integer.MAX_VALUE
                            + " ms, was " + lockTimeout)
                    .withCauseInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    @DisplayName("lock-wait detection: 55P03 or a PessimisticLockingFailureException anywhere in the cause graph")
    void lockWaitsAreFoundInTheCauseGraphOnly() {
        SQLException chained = new SQLException("batch failed", "40001");
        chained.setNextException(new SQLException("canceling statement due to lock timeout", LOCK_NOT_AVAILABLE));
        RuntimeException selfCaused = new RuntimeException("cycle") {
            private static final long serialVersionUID = 1L;

            @Override
            public synchronized Throwable getCause() {
                return this;
            }
        };

        assertThat(LockWaitingReads.isLockWait(lockTimeoutExpired())).isTrue();
        assertThat(LockWaitingReads.isLockWait(new CannotAcquireLockException("guard"))).isTrue();
        assertThat(LockWaitingReads.isLockWait(new IllegalStateException("wrapped", chained))).isTrue();
        assertThat(LockWaitingReads.isLockWait(new DataAccessResourceFailureException("stalled",
                new SQLException("I/O error", CONNECTION_FAILURE)))).isFalse();
        assertThat(LockWaitingReads.isLockWait(selfCaused)).isFalse();
        assertThat(LockWaitingReads.isLockWait(null)).isFalse();
    }

    /**
     * An expired lock wait as a {@link JdbcTemplate} read reports it: Spring's PostgreSQL translation
     * leaves {@value #LOCK_NOT_AVAILABLE} uncategorized.
     *
     * @return a new exception carrying the SQLSTATE
     */
    private static UncategorizedSQLException lockTimeoutExpired() {
        return new UncategorizedSQLException("StatementCallback", STATEMENT_SECRET,
                new SQLException("ERROR: canceling statement due to lock timeout", LOCK_NOT_AVAILABLE));
    }

    /**
     * Settings with the given lock timeout and the default search settings.
     *
     * @param lockTimeout {@code customer-master.db.lock-timeout}
     * @return new settings
     */
    private static AppProperties appProperties(Duration lockTimeout) {
        return new AppProperties(new AppProperties.Db(lockTimeout), new AppProperties.Search(12, 100, 9999));
    }

    /**
     * An unstarted Hikari pool with a JDBC URL and, when given, a {@code socketTimeout} data-source
     * property.
     *
     * @param jdbcUrl       the pool's JDBC URL
     * @param socketTimeout the property's text, or {@code null} for none
     * @return the pool, which opens no connection until one is borrowed
     */
    private static HikariDataSource pool(String jdbcUrl, String socketTimeout) {
        HikariDataSource pool = new HikariDataSource();
        pool.setJdbcUrl(jdbcUrl);
        if (socketTimeout != null) {
            pool.addDataSourceProperty("socketTimeout", socketTimeout);
        }
        return pool;
    }
}
