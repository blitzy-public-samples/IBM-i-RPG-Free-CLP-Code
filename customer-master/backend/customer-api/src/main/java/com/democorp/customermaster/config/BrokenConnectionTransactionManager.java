package com.democorp.customermaster.config;

import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.SQLException;
import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.datasource.JdbcTransactionObjectSupport;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.util.Assert;

/**
 * The application's transaction manager: Spring's {@link JdbcTransactionManager}, except that it does
 * not roll back a transaction whose connection is already closed, and that a read-only transaction
 * waits for a pooled connection while the pool is merely saturated.
 *
 * <p><b>Why.</b> When a statement fails because its connection broke (pgjdbc's socket bound expired on
 * a database that stopped answering, or the session ended; SQLSTATE class {@code 08}), HikariCP marks
 * the connection broken, evicts it, and swaps the pooled connection's physical connection for a closed
 * one. Spring then rolls the transaction back on that connection, whose {@code rollback()} throws
 * {@code SQLException("Connection is closed")} with no SQLSTATE, and that failure replaces the
 * statement's:
 * <ul>
 *   <li>{@code TransactionInterceptor} ({@code @Transactional}) and {@code TransactionTemplate} log the
 *       statement's exception at ERROR as "Application exception overridden by rollback exception",
 *       message included: SQL text, and for a Spring Data JDBC write the aggregate, that is customer
 *       data. That line bypasses the one redacted ERROR line {@code controller.ApiExceptionHandler}
 *       writes per 500.</li>
 *   <li>The caller receives the rollback failure, whose cause chain carries no SQLSTATE, so the
 *       500 DEM9999 line reports {@code sqlState=-} instead of the {@code 08006} of the broken
 *       connection.</li>
 *   <li>A commit that fails on a broken connection is rolled back the same way, since Spring rolls
 *       back after a commit failure translated to a {@code DataAccessException}, as a connection
 *       failure is, and the manager itself logs "Commit exception overridden by rollback exception"
 *       with the commit's exception before throwing the rollback failure.</li>
 * </ul>
 *
 * <p><b>Why skipping is safe.</b> A closed connection cannot roll back, and its transaction needs no
 * rollback: no {@code COMMIT} can reach it any more, and PostgreSQL rolls it back when the session ends.
 * The skip is logged at DEBUG, without any exception message. The statement's own exception then
 * reaches the caller unchanged, as when a rollback succeeds.
 *
 * <p><b>Read-only transactions wait for a pooled connection.</b> Searches, gets and the state list run
 * in read-only transactions, and a read behind a generator load's {@code ACCESS EXCLUSIVE} lock holds
 * its pooled connection until the load commits. Once such reads hold every connection, the next
 * transaction cannot borrow one within {@code spring.datasource.hikari.connection-timeout}, although
 * PostgreSQL answers. {@link #doBegin(Object, TransactionDefinition)} therefore borrows again for a
 * read-only transaction for as long as all three hold:
 * <ul>
 *   <li>the failure is the borrow timeout of a saturated pool,
 *       {@link ConnectionPoolSaturation#isSaturationTimeout(Throwable)};</li>
 *   <li>the thread is not interrupted;</li>
 *   <li>PostgreSQL answers a direct probe, {@link ConnectionPoolSaturation#databaseAnswers()}.</li>
 * </ul>
 * Such a read waits until the load commits, as it waits on the lock itself once it has a connection.
 * It holds no connection between two borrows: a failed borrow leaves the transaction without one. Each
 * new borrow is logged at DEBUG with its attempt number and the pool's connection timeout, without any
 * exception message. A pool that cannot create connections, a database that does not answer the probe
 * and an interrupted thread end the begin with the borrow's {@link CannotCreateTransactionException},
 * which the API answers 500 DEM9999. A transaction that is not read-only, an add, an update or a
 * generator load, begins as in {@link JdbcTransactionManager}: its borrow still gives up after the
 * connection timeout, and the API answers such an add or update 409 DEM1001 while PostgreSQL answers.
 *
 * <p><b>Everything else is {@link JdbcTransactionManager}'s.</b> A connection that is open, or whose
 * {@link Connection#isClosed()} itself fails, is rolled back by the inherited {@code doRollback}, and a
 * failure of that rollback is translated and thrown as before. Each borrow with the preparation of the
 * borrowed connection, the whole begin of a transaction that is not read-only, commit, the rollback of a
 * failed commit on an open connection, exception translation, suspension, and the cleanup that resets
 * and releases the connection are inherited unchanged. For a closed connection that reset fails quietly
 * at DEBUG, as it always has, and HikariCP has already removed the connection from the pool.
 *
 * <p>Created only by {@link TransactionManagerConfig}. Thread safety: as {@link JdbcTransactionManager};
 * the only state this class adds is the final, thread-safe {@link ConnectionPoolSaturation}.
 */
public class BrokenConnectionTransactionManager extends JdbcTransactionManager {

    /** Serialization version; Spring's transaction managers are serializable. */
    private static final long serialVersionUID = 1L;

    /** The connection timeout the DEBUG line of a new borrow reports for a pool that is not Hikari's. */
    private static final String ABSENT = "-";

    /**
     * DEBUG diagnostics of the closed-connection check and of a read-only transaction's new borrow only;
     * they carry no exception message.
     */
    private static final Logger log = LoggerFactory.getLogger(BrokenConnectionTransactionManager.class);

    /**
     * The checks that tell a saturated pool from a lost database. Transient, as Spring's base class keeps
     * its logger: the checks are not serializable, and neither is the Hikari pool the base class keeps,
     * so this manager is never serialized in practice.
     */
    private final transient ConnectionPoolSaturation poolSaturation;

    /**
     * Creates the manager over the given data source, as
     * {@link JdbcTransactionManager#JdbcTransactionManager(DataSource)} does, with the saturation checks
     * its read-only transactions consult before they borrow again.
     *
     * @param dataSource the application's {@link DataSource}, the Hikari pool Boot configures
     * @param poolSaturation the checks over that pool, shared with the health check and the exception
     *                       handler
     * @throws IllegalArgumentException if {@code dataSource} or {@code poolSaturation} is {@code null}
     */
    public BrokenConnectionTransactionManager(DataSource dataSource, ConnectionPoolSaturation poolSaturation) {
        super(dataSource);
        Assert.notNull(poolSaturation, "poolSaturation must not be null");
        this.poolSaturation = poolSaturation;
    }

    /**
     * Begins a transaction on a pooled connection. A transaction that is not read-only begins as in
     * {@link JdbcTransactionManager}. A read-only transaction borrows again after each borrow timeout of
     * a saturated pool for as long as its thread is not interrupted and PostgreSQL answers a direct
     * probe, so it waits for a connection as long as the pool stays saturated; nothing is held between
     * two borrows.
     *
     * @param transaction the transaction object of {@code doGetTransaction}
     * @param definition the transaction's definition
     * @throws CannotCreateTransactionException if no connection could be borrowed or prepared; for a
     *         read-only transaction, the failure of the last borrow, once that failure is not a
     *         saturated pool's borrow timeout, the thread is interrupted or PostgreSQL does not answer
     *         the probe
     */
    @Override
    protected void doBegin(Object transaction, TransactionDefinition definition) {
        if (!definition.isReadOnly()) {
            super.doBegin(transaction, definition);
            return;
        }
        for (long attempt = 1; ; attempt++) {
            try {
                super.doBegin(transaction, definition);
                return;
            } catch (CannotCreateTransactionException failure) {
                // The base class has already released anything it borrowed and left the transaction
                // without a connection, so another borrow starts from the same state as the first.
                if (!ConnectionPoolSaturation.isSaturationTimeout(failure)
                        || Thread.currentThread().isInterrupted()
                        || !poolSaturation.databaseAnswers()) {
                    throw failure;
                }
                log.debug("Read-only transaction found every pooled connection busy while the database"
                        + " answers; borrowing again attempt={} connectionTimeoutMs={}", attempt,
                        connectionTimeoutMillis());
            }
        }
    }

    /**
     * Rolls the transaction back on its connection, unless that connection already reports itself
     * closed; then nothing is sent and nothing is thrown.
     *
     * @param status the status of the transaction to roll back
     * @throws org.springframework.dao.DataAccessException if the rollback of an open connection fails
     *         and the exception translator has a category for the failure
     * @throws org.springframework.transaction.TransactionSystemException if the rollback of an open
     *         connection fails and the translator has no category for it
     */
    @Override
    protected void doRollback(DefaultTransactionStatus status) {
        Connection connection =
                ((JdbcTransactionObjectSupport) status.getTransaction()).getConnectionHolder().getConnection();
        if (isClosed(connection)) {
            log.debug("Rollback skipped: the transaction's connection is already closed, so its"
                    + " uncommitted work ends with the session");
            return;
        }
        super.doRollback(status);
    }

    /**
     * Reports whether a connection is closed, treating a failed check as open so the normal rollback
     * runs.
     *
     * @param connection the transaction's connection
     * @return {@code true} only if {@link Connection#isClosed()} answers {@code true}
     */
    private static boolean isClosed(Connection connection) {
        try {
            return connection.isClosed();
        } catch (SQLException | RuntimeException e) {
            log.debug("Could not check whether the transaction's connection is closed ({}); rolling back",
                    e.getClass().getName());
            return false;
        }
    }

    /**
     * Returns the pool's connection timeout, the longest wait of one borrow, for the DEBUG line of a new
     * borrow. The pool's current value is read on every call.
     *
     * @return the timeout in ms of a Hikari pool; {@value #ABSENT} for any other {@link DataSource}
     */
    private String connectionTimeoutMillis() {
        return obtainDataSource() instanceof HikariDataSource pool
                ? Long.toString(pool.getConnectionTimeout())
                : ABSENT;
    }
}
