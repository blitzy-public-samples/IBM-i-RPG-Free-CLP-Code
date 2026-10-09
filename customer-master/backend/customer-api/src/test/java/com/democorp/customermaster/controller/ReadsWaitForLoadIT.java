package com.democorp.customermaster.controller;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.democorp.customermaster.repository.LockWaitingReads;
import com.democorp.customermaster.support.AbstractPostgresIT;
import com.democorp.customermaster.support.DatabaseCleaner;
import com.fasterxml.jackson.databind.JsonNode;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

/**
 * Proves that a search and a get queued behind a healthy load's table lock wait until the load commits,
 * even when the wait is longer than the pgjdbc socket bound, and then answer 200 with the committed data:
 * the socket bound only ends a read on a database that stops answering.
 *
 * <p><b>The load.</b> A separate JDBC connection takes {@code LOCK TABLE custmast IN ACCESS EXCLUSIVE
 * MODE}, as a generator load does, renames the customer, as a load replaces the rows, and holds the lock
 * for {@link #LOCK_HOLD}, longer than this context's socket bound of {@value #SOCKET_TIMEOUT_SECONDS}
 * seconds. {@code GET /api/customers/{custId}} and {@code GET /api/customers?name=...} wait behind it
 * meanwhile. PostgreSQL answers each of their waits after the test profile's 1-second lock timeout,
 * {@link LockWaitingReads} re-checks, and no wait for an answer reaches the socket bound. Once the
 * holder commits, both answer 200 with the new name; neither answers 500, and no {@code Unhandled error}
 * line is logged. The DEBUG re-check lines show the reads were answered during the hold and carry no
 * customer data or SQL.
 *
 * <p>That a stalled database still fails within the socket bound, through the same read path, is
 * proved by {@code config.DatabaseConnectionBoundsIT}.
 *
 * <p><b>Context variant.</b> {@link TestPropertySource} lowers
 * {@code spring.datasource.hikari.data-source-properties.socketTimeout} from 45 to
 * {@value #SOCKET_TIMEOUT_SECONDS} seconds, so this class forks a Spring context of its own beside the
 * four the suite documents. The bound is a property of every pooled connection: a lock held longer than
 * 45 seconds would make the test needlessly long, and lowering the bound of the base context would change
 * the 45-second bound {@code DatabaseConnectionBoundsIT} checks on its connections and shorten every
 * other test's database waits.
 *
 * <p>The lock holder opens its own connection with {@link DriverManager}, outside the pool, and is closed
 * in {@code finally}, which rolls back and releases its lock if the test fails before the commit. The
 * logger level and the list appender of {@link LockWaitingReads} are global state and are restored in
 * {@code finally}. Console output is captured by {@link OutputCaptureExtension}.
 */
@ExtendWith(OutputCaptureExtension.class)
@TestPropertySource(properties =
        "spring.datasource.hikari.data-source-properties.socketTimeout=" + ReadsWaitForLoadIT.SOCKET_TIMEOUT_SECONDS)
@DisplayName("Reads behind a load wait past the socket bound and answer 200 once it commits")
class ReadsWaitForLoadIT extends AbstractPostgresIT {

    /** The socket bound of this context's pooled connections, in seconds. */
    static final int SOCKET_TIMEOUT_SECONDS = 3;

    /** The socket bound of this context's pooled connections. */
    private static final Duration SOCKET_TIMEOUT = Duration.ofSeconds(SOCKET_TIMEOUT_SECONDS);

    /** How long the holder keeps the lock once both reads wait: well past the socket bound. */
    private static final Duration LOCK_HOLD = Duration.ofSeconds(8);

    /** Longest wait for both reads to queue behind the lock. */
    private static final Duration WAITING_DEADLINE = Duration.ofSeconds(15);

    /** Longest wait for a response once the holder has committed. */
    private static final Duration RESPONSE_DEADLINE = Duration.ofSeconds(30);

    /** The add and search route. */
    private static final String CUSTOMERS = "/api/customers";

    /** The customer's name before the load. */
    private static final String NAME_BEFORE = "LOAD WAIT BEFORE";

    /** The customer's name the load commits. */
    private static final String NAME_AFTER = "LOAD WAIT AFTER";

    /** The search filter, a prefix of both names. */
    private static final String NAME_FILTER = "LOAD WAIT";

    /** The nine data fields of a valid customer, without {@code name}. */
    private static final String FIELDS_WITHOUT_NAME = "\"addr\":\"1 ADV RD\",\"city\":\"DENVER\","
            + "\"state\":\"CO\",\"zip\":\"80202\",\"corpPhone\":\"(303) 555-0100\",\"acctMgr\":\"DOE, JANE\","
            + "\"acctPhone\":\"(303) 555-0101\",\"active\":\"Y\"";

    @Test
    @DisplayName("get and search wait through a lock held longer than the socket bound, then answer 200")
    void getAndSearchWaitForTheLoadToCommitPastTheSocketBound(CapturedOutput output) throws Exception {
        String custId = addCustomer();
        try (Connection pooled = dataSource.getConnection()) {
            assertThat(pooled.getNetworkTimeout())
                    .as("this context's socket bound must reach pgjdbc")
                    .isEqualTo((int) SOCKET_TIMEOUT.toMillis());
        }
        LoggerContext loggerContext = (LoggerContext) LoggerFactory.getILoggerFactory();
        Logger readsLogger = loggerContext.getLogger(LockWaitingReads.class);
        Level originalLevel = readsLogger.getLevel();
        ListAppender<ILoggingEvent> rechecks = new ListAppender<>();
        rechecks.setContext(loggerContext);
        rechecks.start();
        readsLogger.setLevel(Level.DEBUG);
        readsLogger.addAppender(rechecks);
        ExecutorService readers = Executors.newFixedThreadPool(2);
        try {
            Future<Timed> get;
            Future<Timed> search;
            try (Connection holder = lockHolder()) {
                try (Statement statement = holder.createStatement()) {
                    statement.execute("LOCK TABLE custmast IN ACCESS EXCLUSIVE MODE");
                }
                try (PreparedStatement rename = holder.prepareStatement(
                        "UPDATE custmast SET name = ? WHERE custid = ?")) {
                    rename.setString(1, NAME_AFTER);
                    rename.setString(2, custId);
                    assertThat(rename.executeUpdate()).isEqualTo(1);
                }
                get = readers.submit(timed(() -> inquiryXhr().get().uri(CUSTOMERS + "/{custId}", custId)
                        .retrieve()
                        .toEntity(String.class)));
                search = readers.submit(timed(() -> inquiryXhr().get().uri(CUSTOMERS + "?name={name}", NAME_FILTER)
                        .retrieve()
                        .toEntity(String.class)));
                awaitWaitingOnCustmast(holder, 2);

                Thread.sleep(LOCK_HOLD.toMillis());

                assertThat(get.isDone()).as("the get still waits for the load").isFalse();
                assertThat(search.isDone()).as("the search still waits for the load").isFalse();
                holder.commit();
            }

            Timed found = get.get(RESPONSE_DEADLINE.toMillis(), TimeUnit.MILLISECONDS);
            Timed listed = search.get(RESPONSE_DEADLINE.toMillis(), TimeUnit.MILLISECONDS);

            assertThat(found.response().getStatusCode()).as("get: %s", found.response().getBody())
                    .isEqualTo(HttpStatus.OK);
            JsonNode customer = json(found.response());
            assertThat(customer.path("custId").asText()).isEqualTo(custId);
            assertThat(customer.path("name").asText()).as("the get reads what the load committed")
                    .isEqualTo(NAME_AFTER);
            assertThat(listed.response().getStatusCode()).as("search: %s", listed.response().getBody())
                    .isEqualTo(HttpStatus.OK);
            JsonNode items = json(listed.response()).path("items");
            assertThat(items).as("the search reads what the load committed").hasSize(1);
            assertThat(items.get(0).path("custId").asText()).isEqualTo(custId);
            assertThat(items.get(0).path("name").asText()).isEqualTo(NAME_AFTER);
            for (Timed answer : new Timed[] {found, listed}) {
                assertThat(answer.elapsed())
                        .as("each read waited for the whole hold, longer than the socket bound")
                        .isGreaterThan(SOCKET_TIMEOUT)
                        .isGreaterThanOrEqualTo(LOCK_HOLD);
            }
        } finally {
            readers.shutdownNow();
            readsLogger.detachAppender(rechecks);
            rechecks.stop();
            readsLogger.setLevel(originalLevel);
        }

        assertThat(output.getAll()).as("no read became a 500").doesNotContain("Unhandled error");
        assertThat(rechecks.list)
                .as("PostgreSQL answered the reads' lock waits during the hold, and each answer was re-checked")
                .hasSizeGreaterThanOrEqualTo(2)
                .extracting(ILoggingEvent::getFormattedMessage)
                .allSatisfy(message -> assertThat(message)
                        .startsWith("customer.read table lock still held after lock_timeout=1000ms")
                        .doesNotContain(NAME_BEFORE)
                        .doesNotContain(NAME_AFTER)
                        .doesNotContain("custmast"));
    }

    /**
     * Adds a valid customer named {@value #NAME_BEFORE} through the API as the maintenance user.
     *
     * @return the allocated id
     */
    private String addCustomer() {
        ResponseEntity<String> response = maintenanceXhr().post().uri(CUSTOMERS)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"name\":\"" + NAME_BEFORE + "\"," + FIELDS_WITHOUT_NAME + "}")
                .retrieve()
                .toEntity(String.class);
        assertThat(response.getStatusCode()).as("add: %s", response.getBody()).isEqualTo(HttpStatus.CREATED);
        String custId = json(response).path("custId").asText();
        assertThat(custId).isEqualTo(DatabaseCleaner.FIRST_INTERACTIVE_ID);
        return custId;
    }

    /**
     * Opens a connection to the test database outside the application's pool, in a transaction, with
     * the application's schema on its search path.
     *
     * @return the open connection; closing it rolls back and releases its locks
     * @throws SQLException if the connection cannot be opened
     */
    private Connection lockHolder() throws SQLException {
        Connection connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword());
        try {
            connection.setSchema(dataSource.unwrap(HikariDataSource.class).getSchema());
            connection.setAutoCommit(false);
            return connection;
        } catch (SQLException | RuntimeException e) {
            connection.close();
            throw e;
        }
    }

    /**
     * Waits until the given number of sessions wait for a lock on {@code custmast}. A read re-checking
     * after an expired wait leaves the queue for a moment, so the count is polled.
     *
     * @param holder   the lock holder's connection, which runs the query
     * @param expected how many sessions must wait
     * @throws Exception if the wait is interrupted or a query fails
     * @throws AssertionError if fewer sessions wait within {@link #WAITING_DEADLINE}
     */
    private static void awaitWaitingOnCustmast(Connection holder, int expected) throws Exception {
        long deadline = System.nanoTime() + WAITING_DEADLINE.toNanos();
        while (waitingOnCustmast(holder) < expected) {
            if (System.nanoTime() - deadline >= 0) {
                throw new AssertionError(expected + " reads did not wait on custmast within " + WAITING_DEADLINE
                        + ": waiting=" + waitingOnCustmast(holder));
            }
            Thread.sleep(20);
        }
    }

    /**
     * Counts the sessions waiting for a lock on {@code custmast}.
     *
     * @param holder the lock holder's connection, which runs the query
     * @return the number of ungranted locks on the table
     * @throws SQLException if the query fails
     */
    private static int waitingOnCustmast(Connection holder) throws SQLException {
        try (Statement statement = holder.createStatement();
                ResultSet result = statement.executeQuery(
                        "SELECT count(*) FROM pg_locks WHERE relation = 'custmast'::regclass AND NOT granted")) {
            result.next();
            return result.getInt(1);
        }
    }

    /**
     * Wraps a request so its answer carries the time it took.
     *
     * @param request the request
     * @return the task
     */
    private static Callable<Timed> timed(Callable<ResponseEntity<String>> request) {
        return () -> {
            long start = System.nanoTime();
            ResponseEntity<String> response = request.call();
            return new Timed(response, Duration.ofNanos(System.nanoTime() - start));
        };
    }

    /**
     * One answer and the time from sending the request to receiving it.
     *
     * @param response the answer
     * @param elapsed  the time it took
     */
    private record Timed(ResponseEntity<String> response, Duration elapsed) {
    }
}
