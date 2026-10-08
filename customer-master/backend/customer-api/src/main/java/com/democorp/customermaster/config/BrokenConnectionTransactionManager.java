package com.democorp.customermaster.config;

import java.sql.Connection;
import java.sql.SQLException;
import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.datasource.JdbcTransactionObjectSupport;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

/**
 * The application's transaction manager: Spring's {@link JdbcTransactionManager}, except that it does
 * not roll back a transaction whose connection is already closed.
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
 * <p><b>Everything else is {@link JdbcTransactionManager}'s.</b> A connection that is open, or whose
 * {@link Connection#isClosed()} itself fails, is rolled back by the inherited {@code doRollback}, and a
 * failure of that rollback is translated and thrown as before. Begin, commit, the rollback of a failed
 * commit on an open connection, exception translation, suspension, and the cleanup that resets and
 * releases the connection are inherited unchanged. For a closed connection that reset fails quietly at
 * DEBUG, as it always has, and HikariCP has already removed the connection from the pool.
 *
 * <p>Created only by {@link TransactionManagerConfig}. Thread safety: as {@link JdbcTransactionManager};
 * this class adds no state.
 */
public class BrokenConnectionTransactionManager extends JdbcTransactionManager {

    /** Serialization version; Spring's transaction managers are serializable, and this class adds no state. */
    private static final long serialVersionUID = 1L;

    /** DEBUG diagnostics of the closed-connection check only; they carry no exception message. */
    private static final Logger log = LoggerFactory.getLogger(BrokenConnectionTransactionManager.class);

    /**
     * Creates the manager over the given data source, as
     * {@link JdbcTransactionManager#JdbcTransactionManager(DataSource)} does.
     *
     * @param dataSource the application's {@link DataSource}, the Hikari pool Boot configures
     */
    public BrokenConnectionTransactionManager(DataSource dataSource) {
        super(dataSource);
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
}
