package com.democorp.customermaster.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Specifies {@link BrokenConnectionTransactionManager}: a transaction whose connection is already closed
 * ends without a rollback call and without an exception of its own, while every other path is
 * {@link JdbcTransactionManager}'s.
 *
 * <p><b>The case that matters.</b> HikariCP swaps a broken connection's physical connection for a closed
 * one, whose {@code rollback()} throws {@code SQLException("Connection is closed")} with no SQLSTATE. The
 * mocked {@link Connection} reproduces that: {@code isClosed()} answers {@code true} and {@code rollback()}
 * throws that exception. {@link TransactionTemplate} must then rethrow the statement's own exception, and
 * its "Application exception overridden by rollback exception" ERROR line, which quotes that exception's
 * message, must not appear. The same case over the base manager shows the failure the subclass removes.
 *
 * <p>Pure JUnit 5, Mockito, AssertJ and Logback over a mocked {@link DataSource}: no Spring context, no
 * database, no Docker. The base manager's exception translator is Spring's
 * {@code SQLExceptionSubclassTranslator}, which needs no database. The list appenders and logger levels
 * are global state, so {@link #restoreLogging()} puts them back after every case.
 */
@DisplayName("BrokenConnectionTransactionManager: no rollback on a closed connection, JdbcTransactionManager otherwise")
final class BrokenConnectionTransactionManagerTest {

    /** The logger of {@link TransactionTemplate}, which writes the override line. */
    private static final String TEMPLATE_LOGGER = TransactionTemplate.class.getName();

    /** The logger of the manager under test, which writes the skip line. */
    private static final String MANAGER_LOGGER = BrokenConnectionTransactionManager.class.getName();

    /** The ERROR line Spring writes when a rollback failure replaces the application's exception. */
    private static final String OVERRIDE_LINE = "Application exception overridden by rollback exception";

    /** The message HikariCP's closed connection throws from every JDBC call, with no SQLSTATE. */
    private static final String CONNECTION_IS_CLOSED = "Connection is closed";

    /** SQLSTATE {@code connection_failure}, as pgjdbc reports an I/O error on the socket. */
    private static final String CONNECTION_FAILURE = "08006";

    /**
     * The ERROR line Spring writes when the rollback of a failed commit fails and replaces the commit's
     * exception.
     */
    private static final String COMMIT_OVERRIDE_LINE = "Commit exception overridden by rollback exception";

    /** The start of the manager's DEBUG line for a skipped rollback. */
    private static final String SKIP_LINE = "Rollback skipped";

    /** The start of the manager's DEBUG line for a failed closed-check. */
    private static final String CHECK_FAILED_LINE = "Could not check whether the transaction's connection is closed";

    /** Text of the statement's exception that no log line of the manager may repeat. */
    private static final String STATEMENT_SECRET = "SELECT secret_column FROM custmast";

    private final LoggerContext loggerContext = (LoggerContext) LoggerFactory.getILoggerFactory();

    private final Logger templateLogger = loggerContext.getLogger(TEMPLATE_LOGGER);

    private final Logger managerLogger = loggerContext.getLogger(MANAGER_LOGGER);

    private final ListAppender<ILoggingEvent> templateEvents = new ListAppender<>();

    private final ListAppender<ILoggingEvent> managerEvents = new ListAppender<>();

    private Level originalTemplateLevel;

    private Level originalManagerLevel;

    private DataSource dataSource;

    private Connection connection;

    private BrokenConnectionTransactionManager manager;

    @BeforeEach
    void setUp() throws SQLException {
        originalTemplateLevel = templateLogger.getLevel();
        originalManagerLevel = managerLogger.getLevel();
        // Explicit levels, so the cases do not depend on whatever configured the root logger.
        templateLogger.setLevel(Level.ERROR);
        managerLogger.setLevel(Level.DEBUG);
        for (ListAppender<ILoggingEvent> appender : List.of(templateEvents, managerEvents)) {
            appender.setContext(loggerContext);
            appender.start();
        }
        templateLogger.addAppender(templateEvents);
        managerLogger.addAppender(managerEvents);

        dataSource = mock(DataSource.class);
        connection = mock(Connection.class);
        when(dataSource.getConnection()).thenReturn(connection);
        // As a pooled connection is handed out: in auto-commit mode, which the manager switches off.
        when(connection.getAutoCommit()).thenReturn(true);
        manager = new BrokenConnectionTransactionManager(dataSource);
    }

    @AfterEach
    void restoreLogging() {
        templateLogger.detachAppender(templateEvents);
        managerLogger.detachAppender(managerEvents);
        templateEvents.stop();
        managerEvents.stop();
        templateLogger.setLevel(originalTemplateLevel);
        managerLogger.setLevel(originalManagerLevel);
    }

    /**
     * Makes the mocked connection behave as HikariCP's pooled connection after the pool marked it broken.
     *
     * @throws SQLException never; declared by the mocked methods
     */
    private void breakConnection() throws SQLException {
        when(connection.isClosed()).thenReturn(true);
        doThrow(new SQLException(CONNECTION_IS_CLOSED)).when(connection).rollback();
    }

    /**
     * The exception a statement on a broken connection reaches the transaction boundary with.
     *
     * @return a translated connection failure whose cause carries SQLSTATE {@value #CONNECTION_FAILURE}
     */
    private static DataAccessResourceFailureException statementFailure() {
        return new DataAccessResourceFailureException(STATEMENT_SECRET,
                new SQLException("An I/O error occurred while sending to the backend.", CONNECTION_FAILURE));
    }

    @Test
    @DisplayName("a closed connection: no rollback call, no exception, connection still released and unbound")
    void aClosedConnectionIsNotRolledBack() throws SQLException {
        TransactionStatus status = manager.getTransaction(TransactionDefinition.withDefaults());
        breakConnection();

        assertThatNoException().isThrownBy(() -> manager.rollback(status));

        verify(connection, never()).rollback();
        verify(connection).close();
        assertThat(status.isCompleted()).isTrue();
        assertThat(TransactionSynchronizationManager.hasResource(dataSource)).isFalse();
        assertThat(managerLines(SKIP_LINE))
                .as("the skip is logged once at DEBUG, with no exception attached")
                .singleElement()
                .satisfies(event -> {
                    assertThat(event.getLevel()).isEqualTo(Level.DEBUG);
                    assertThat(event.getThrowableProxy()).isNull();
                });
    }

    @Test
    @DisplayName("an open connection is rolled back")
    void anOpenConnectionIsRolledBack() throws SQLException {
        TransactionStatus status = manager.getTransaction(TransactionDefinition.withDefaults());
        when(connection.isClosed()).thenReturn(false);

        manager.rollback(status);

        verify(connection).rollback();
        verify(connection).close();
        assertThat(managerLines(SKIP_LINE)).isEmpty();
        assertThat(managerLines(CHECK_FAILED_LINE)).isEmpty();
    }

    @Test
    @DisplayName("a failed rollback of an open connection is translated and thrown, as by JdbcTransactionManager")
    void aFailedRollbackOfAnOpenConnectionStillFails() throws SQLException {
        TransactionStatus status = manager.getTransaction(TransactionDefinition.withDefaults());
        when(connection.isClosed()).thenReturn(false);
        doThrow(new SQLException("rollback lost", CONNECTION_FAILURE)).when(connection).rollback();

        Throwable thrown = catchThrowable(() -> manager.rollback(status));

        assertThat(thrown).isInstanceOf(DataAccessResourceFailureException.class);
        assertThat(thrown.getCause()).isInstanceOf(SQLException.class)
                .extracting(cause -> ((SQLException) cause).getSQLState()).isEqualTo(CONNECTION_FAILURE);
        verify(connection).close();
    }

    @Test
    @DisplayName("isClosed() failing: the normal rollback runs")
    void aFailedClosedCheckFallsThroughToTheRollback() throws SQLException {
        TransactionStatus status = manager.getTransaction(TransactionDefinition.withDefaults());
        when(connection.isClosed()).thenThrow(new SQLException(STATEMENT_SECRET));

        manager.rollback(status);

        verify(connection).rollback();
        verify(connection).close();
        assertThat(managerLines(CHECK_FAILED_LINE))
                .as("the failed check is logged once at DEBUG by exception type, without its message")
                .singleElement()
                .satisfies(event -> {
                    assertThat(event.getLevel()).isEqualTo(Level.DEBUG);
                    assertThat(event.getThrowableProxy()).isNull();
                    assertThat(event.getFormattedMessage()).contains(SQLException.class.getName());
                });
        assertThat(managerEvents.list)
                .extracting(ILoggingEvent::getFormattedMessage)
                .noneMatch(message -> message.contains(STATEMENT_SECRET));
        assertThat(managerLines(SKIP_LINE)).isEmpty();
    }

    @Test
    @DisplayName("isClosed() throwing a runtime exception: the normal rollback runs")
    void aRuntimeFailureOfTheClosedCheckFallsThroughToTheRollback() throws SQLException {
        TransactionStatus status = manager.getTransaction(TransactionDefinition.withDefaults());
        when(connection.isClosed()).thenThrow(new IllegalStateException(STATEMENT_SECRET));

        manager.rollback(status);

        verify(connection).rollback();
        verify(connection).close();
    }

    @Test
    @DisplayName("commit is unchanged: committed, never rolled back, closed-check never consulted")
    void commitIsUnchanged() throws SQLException {
        TransactionStatus status = manager.getTransaction(TransactionDefinition.withDefaults());

        manager.commit(status);

        verify(connection).setAutoCommit(false);
        verify(connection).commit();
        verify(connection, never()).rollback();
        verify(connection, never()).isClosed();
        verify(connection).setAutoCommit(true);
        verify(connection).close();
        assertThat(TransactionSynchronizationManager.hasResource(dataSource)).isFalse();
    }

    @Test
    @DisplayName("a failed commit on an open connection is rolled back and reported, as by JdbcTransactionManager")
    void aFailedCommitOnAnOpenConnectionIsRolledBackAsBefore() throws SQLException {
        TransactionStatus status = manager.getTransaction(TransactionDefinition.withDefaults());
        doThrow(new SQLException("commit lost", CONNECTION_FAILURE)).when(connection).commit();
        when(connection.isClosed()).thenReturn(false);

        Throwable thrown = catchThrowable(() -> manager.commit(status));

        // The translated commit failure is a RuntimeException, after which Spring always rolls back.
        assertThat(thrown).isInstanceOf(DataAccessResourceFailureException.class).hasRootCauseMessage("commit lost");
        verify(connection).rollback();
        verify(connection).close();
    }

    @Test
    @DisplayName("a failed commit on a broken connection is reported as the commit's failure, with no rollback")
    void aFailedCommitOnABrokenConnectionIsReportedWithoutARollback() throws SQLException {
        TransactionStatus status = manager.getTransaction(TransactionDefinition.withDefaults());
        doThrow(new SQLException("commit lost", CONNECTION_FAILURE)).when(connection).commit();
        breakConnection();

        Throwable thrown = catchThrowable(() -> manager.commit(status));

        assertThat(thrown)
                .as("the commit's failure is reported, not the closed connection's rollback failure")
                .isInstanceOf(DataAccessResourceFailureException.class)
                .hasRootCauseMessage("commit lost");
        verify(connection, never()).rollback();
        verify(connection).close();
        assertThat(managerLines(COMMIT_OVERRIDE_LINE)).isEmpty();
        assertThat(managerLines(SKIP_LINE)).hasSize(1);
        assertThat(TransactionSynchronizationManager.hasResource(dataSource)).isFalse();
    }

    @Test
    @DisplayName("TransactionTemplate over a broken connection rethrows the statement's exception and logs no override")
    void theStatementsExceptionReachesTheCallerUnchanged() throws SQLException {
        DataAccessResourceFailureException failure = statementFailure();
        TransactionTemplate template = readOnlyTemplate(manager);

        Throwable thrown = catchThrowable(() -> template.execute(status -> {
            breakConnectionUnchecked();
            throw failure;
        }));

        assertThat(thrown).isSameAs(failure);
        verify(connection, never()).rollback();
        assertThat(templateEvents.list)
                .extracting(ILoggingEvent::getFormattedMessage)
                .noneMatch(message -> message.contains(OVERRIDE_LINE));
        assertThat(managerEvents.list)
                .extracting(ILoggingEvent::getFormattedMessage)
                .noneMatch(message -> message.contains(STATEMENT_SECRET));
    }

    @Test
    @DisplayName("the base JdbcTransactionManager in the same case: the rollback failure replaces it")
    void theBaseManagerLetsTheRollbackFailureReplaceTheStatementsException() {
        DataAccessResourceFailureException failure = statementFailure();
        TransactionTemplate template = readOnlyTemplate(new JdbcTransactionManager(dataSource));

        Throwable thrown = catchThrowable(() -> template.execute(status -> {
            breakConnectionUnchecked();
            throw failure;
        }));

        assertThat(thrown)
                .as("without the subclass the caller loses the 08006 failure")
                .isInstanceOf(TransactionSystemException.class)
                .hasMessage("JDBC rollback failed")
                .hasRootCauseMessage(CONNECTION_IS_CLOSED);
        assertThat(templateEvents.list)
                .extracting(ILoggingEvent::getFormattedMessage)
                .anyMatch(message -> message.contains(OVERRIDE_LINE));
    }

    /**
     * The events of the manager's logger whose message contains the given text. That logger also carries
     * the DEBUG lines Spring's base classes write through their own logger, which is named after the
     * runtime class.
     *
     * @param text the text to look for
     * @return the matching events, in order
     */
    private List<ILoggingEvent> managerLines(String text) {
        return managerEvents.list.stream()
                .filter(event -> event.getFormattedMessage().contains(text))
                .toList();
    }

    /**
     * A read-only template, as {@code CustomerMaintenanceService.get} runs.
     *
     * @param transactionManager the manager the template drives
     * @return a new template
     */
    private static TransactionTemplate readOnlyTemplate(PlatformTransactionManager transactionManager) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setReadOnly(true);
        return template;
    }

    /**
     * {@link #breakConnection()} for use inside a transaction callback, which may not throw a checked
     * exception.
     */
    private void breakConnectionUnchecked() {
        try {
            breakConnection();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }
}
