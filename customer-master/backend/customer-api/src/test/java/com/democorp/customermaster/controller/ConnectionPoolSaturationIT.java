package com.democorp.customermaster.controller;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.democorp.customermaster.config.BrokenConnectionTransactionManager;
import com.democorp.customermaster.support.AbstractPostgresIT;
import com.democorp.customermaster.support.DatabaseCleaner;
import com.fasterxml.jackson.databind.JsonNode;
import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthComponent;
import org.springframework.boot.actuate.health.HealthEndpoint;
import org.springframework.boot.actuate.health.Status;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

/**
 * Proves that an add or update that cannot borrow a connection within the connection timeout, while
 * PostgreSQL answers, is 409 {@code DEM1001}, the answer of the lock wait it is, not 500 {@code DEM9999},
 * that a search or get in the same position waits for a connection instead of failing, and that readiness
 * stays UP, not DOWN, while requests waiting on locks hold every pooled connection.
 *
 * <ul>
 *   <li><b>Load lock, reads holding the pool.</b> A separate JDBC connection holds
 *       {@code LOCK TABLE custmast IN ACCESS EXCLUSIVE MODE}, as a generator load does. As many gets as
 *       the pool has connections wait behind it, so every pooled connection is held. A get and a search
 *       beyond the pool size then wait for a connection: the transaction manager logs, from each of
 *       their threads, the DEBUG line of a read-only transaction that borrows again after the connection
 *       timeout. An add then answers 409 {@code DEM1001} after the connection timeout, consuming no id;
 *       readiness answers 200 UP, its {@code db} check reporting the database and {@code isValid()} from
 *       the direct probe; and no read has answered, although each read beyond the pool size has by then
 *       outlasted the connection timeout at least twice. Once the lock is released, every read answers
 *       200 with the customer, and none is logged as an unhandled error: reads wait for a load until it
 *       commits, whether or not they hold a connection.</li>
 *   <li><b>Row lock, more writers than connections.</b> A separate JDBC connection holds the row lock
 *       of an {@code UPDATE}. More updates of that row than the pool has connections run at once: those
 *       holding a connection wait out the lock timeout, the others the connection timeout, which is
 *       shorter here, so both paths run; every update answers 409 {@code DEM1001}.</li>
 * </ul>
 *
 * <p><b>Context variant.</b> {@link TestPropertySource} lowers the pool to {@value #POOL_SIZE}
 * connections, the connection timeout to {@value #CONNECTION_TIMEOUT_MS} ms (with the validation timeout
 * below it, as Hikari requires) and the lock timeout to {@value #LOCK_TIMEOUT_SECONDS} seconds, so this
 * class forks a Spring context of its own beside the four the suite documents. Saturating the base
 * context's ten connections would take more concurrent requests and longer waits, and lowering its pool
 * would change every other test's concurrency; a lock timeout longer than the connection timeout is the
 * only way to make a writer time out on the pool while another writer still waits on the row.
 *
 * <p>The lock holders open their own connections with {@link DriverManager}, outside the pool, and are
 * closed in {@code finally}, which rolls back and releases their locks. Console output is captured by
 * {@link OutputCaptureExtension}. The load-lock test sets the transaction manager's logger to DEBUG and
 * attaches a list appender to it, and restores both before it returns.
 */
@ExtendWith(OutputCaptureExtension.class)
@TestPropertySource(properties = {
    "spring.datasource.hikari.maximum-pool-size=" + ConnectionPoolSaturationIT.POOL_SIZE,
    "spring.datasource.hikari.connection-timeout=" + ConnectionPoolSaturationIT.CONNECTION_TIMEOUT_MS,
    "spring.datasource.hikari.validation-timeout=500",
    "customer-master.db.lock-timeout=" + ConnectionPoolSaturationIT.LOCK_TIMEOUT_SECONDS + "s"
})
@DisplayName("Connection pool saturation: writes answer 409 DEM1001, reads wait, readiness stays UP")
class ConnectionPoolSaturationIT extends AbstractPostgresIT {

    /** Connections of this context's pool. */
    static final int POOL_SIZE = 3;

    /** Longest wait, in ms, of this context's pool for a free connection. */
    static final long CONNECTION_TIMEOUT_MS = 1000;

    /** This context's lock timeout of adds and updates, longer than the connection timeout. */
    static final int LOCK_TIMEOUT_SECONDS = 3;

    /** Reads sent during the load lock: two more than the pool has connections, a get and a search. */
    private static final int READERS = POOL_SIZE + 2;

    /** Updates sent during the row lock: twice the pool size. */
    private static final int WRITERS = POOL_SIZE * 2;

    /** The add route. */
    private static final String CUSTOMERS = "/api/customers";

    /** A search whose name prefix matches every customer this class adds. */
    private static final String SEARCH = CUSTOMERS + "?name=POOL";

    /** The logger of the transaction manager, which writes the DEBUG line of a new borrow. */
    private static final String MANAGER_LOGGER = BrokenConnectionTransactionManager.class.getName();

    /** The start of the manager's DEBUG line for a read-only transaction's new borrow. */
    private static final String BORROW_AGAIN_LINE = "Read-only transaction found every pooled connection busy";

    /** The start of the ERROR line of every 500 {@code DEM9999}. */
    private static final String UNHANDLED_ERROR = "Unhandled error";

    /** The readiness probe path. */
    private static final String READINESS = "/actuator/health/readiness";

    /** Time allowed beyond a configured timeout for the request, the probe and the host's scheduling. */
    private static final Duration MARGIN = Duration.ofSeconds(5);

    /** Hikari's own slack: a borrow may give up a few microseconds before its timeout. */
    private static final Duration BORROW_SLACK = Duration.ofMillis(50);

    /** Longest wait for the reads to hold every pooled connection. */
    private static final Duration SATURATION_DEADLINE = Duration.ofSeconds(15);

    /** Longest wait for any one response; no wait of this class exceeds it. */
    private static final Duration RESPONSE_DEADLINE = Duration.ofSeconds(30);

    /** The text of DEM1001. */
    private static final String DEM1001_TEXT = "Customer being updated by another user or job.";

    /** The WARN line of a write answered 409 because the pool stayed saturated. */
    private static final String SATURATED_WARNING = "Connection pool stayed saturated; write answered code=DEM1001";

    /** The nine data fields of a valid customer, without {@code name}. */
    private static final String FIELDS_WITHOUT_NAME = "\"addr\":\"1 ADV RD\",\"city\":\"DENVER\","
            + "\"state\":\"CO\",\"zip\":\"80202\",\"corpPhone\":\"(303) 555-0100\",\"acctMgr\":\"DOE, JANE\","
            + "\"acctPhone\":\"(303) 555-0101\",\"active\":\"Y\"";

    /** The health endpoint, evaluated with details. */
    @Autowired
    private HealthEndpoint healthEndpoint;

    @Test
    @DisplayName("load lock with every connection held by reads: add 409 DEM1001, readiness UP, reads beyond"
            + " the pool wait, every read 200")
    void addDuringALoadLockWhileReadsHoldThePoolIsALockWaitAndEveryReadWaits(CapturedOutput output)
            throws Exception {
        String custId = addCustomer("POOL SATURATION READ");
        String get = CUSTOMERS + "/" + custId;
        long rowsBefore = rowCount();
        Map<String, Object> sequenceBefore = sequenceState();
        HikariPoolMXBean pool = pool();
        ExecutorService readers = Executors.newFixedThreadPool(READERS);
        List<Future<ResponseEntity<String>>> holding = new ArrayList<>();
        List<Future<ResponseEntity<String>>> beyond = new ArrayList<>();
        List<String> beyondPaths = new ArrayList<>();
        LoggerContext loggerContext = (LoggerContext) LoggerFactory.getILoggerFactory();
        Logger managerLogger = loggerContext.getLogger(MANAGER_LOGGER);
        Level originalLevel = managerLogger.getLevel();
        ListAppender<ILoggingEvent> managerEvents = new ListAppender<>();
        managerEvents.setContext(loggerContext);
        managerEvents.start();
        // DEBUG is set explicitly, so the check does not depend on whatever configured the root logger.
        managerLogger.setLevel(Level.DEBUG);
        managerLogger.addAppender(managerEvents);
        try {
            try (Connection holder = lockHolder()) {
                try (Statement statement = holder.createStatement()) {
                    statement.execute("LOCK TABLE custmast IN ACCESS EXCLUSIVE MODE");
                }
                CyclicBarrier start = new CyclicBarrier(POOL_SIZE);
                for (int i = 0; i < POOL_SIZE; i++) {
                    holding.add(readers.submit(started(start, () -> read(get))));
                }
                awaitEveryConnectionWaitingOnTheLock(pool, holder);
                // Sent once the pool is saturated, so a get and a search are the reads without a connection.
                for (int i = 0; i < READERS - POOL_SIZE; i++) {
                    String path = i % 2 == 0 ? get : SEARCH;
                    beyondPaths.add(path);
                    beyond.add(readers.submit(() -> read(path)));
                }
                awaitBorrowingAgain(managerEvents, READERS - POOL_SIZE);

                long addStart = System.nanoTime();
                ResponseEntity<String> add = maintenanceXhr().post().uri(CUSTOMERS)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(customerJson("POOL SATURATION ADD"))
                        .retrieve()
                        .toEntity(String.class);
                Duration addElapsed = Duration.ofNanos(System.nanoTime() - addStart);

                assertLockWait(add, CUSTOMERS);
                assertThat(addElapsed)
                        .as("the add waits for a connection for the connection timeout, then answers")
                        .isGreaterThanOrEqualTo(Duration.ofMillis(CONNECTION_TIMEOUT_MS).minus(BORROW_SLACK))
                        .isLessThan(Duration.ofMillis(CONNECTION_TIMEOUT_MS).plus(MARGIN));

                long readinessStart = System.nanoTime();
                ResponseEntity<String> readiness = anonymous().get().uri(READINESS).retrieve()
                        .toEntity(String.class);
                Duration readinessElapsed = Duration.ofNanos(System.nanoTime() - readinessStart);

                assertThat(readiness.getStatusCode()).as("readiness: %s", readiness.getBody())
                        .isEqualTo(HttpStatus.OK);
                assertThat(json(readiness).path("status").asText()).isEqualTo("UP");
                assertThat(readinessElapsed).isLessThan(Duration.ofMillis(CONNECTION_TIMEOUT_MS).plus(MARGIN));
                HealthComponent db = healthEndpoint.healthForPath("readiness", "db");
                assertThat(db).isInstanceOf(Health.class);
                assertThat(db.getStatus()).isEqualTo(Status.UP);
                assertThat(((Health) db).getDetails())
                        .as("the db check reports Boot's members from the direct probe")
                        .containsOnly(Map.entry("database", "PostgreSQL"), Map.entry("validationQuery", "isValid()"));
                assertThat(pool.getActiveConnections())
                        .as("the reads still hold every pooled connection")
                        .isEqualTo(POOL_SIZE);
                assertThat(beyond)
                        .as("the reads beyond the pool size still wait for a connection while the lock is"
                                + " held, after the add and readiness each outlasted the connection timeout")
                        .noneMatch(Future::isDone);
                assertThat(holding).as("the reads holding a connection still wait on the lock")
                        .noneMatch(Future::isDone);

                holder.rollback();
            }

            for (Future<ResponseEntity<String>> read : holding) {
                ResponseEntity<String> answer = read.get(RESPONSE_DEADLINE.toMillis(), TimeUnit.MILLISECONDS);
                assertThat(answer.getStatusCode()).as("get holding a connection: %s", answer.getBody())
                        .isEqualTo(HttpStatus.OK);
                assertThat(json(answer).path("custId").asText()).isEqualTo(custId);
            }
            for (int i = 0; i < beyond.size(); i++) {
                String path = beyondPaths.get(i);
                ResponseEntity<String> answer = beyond.get(i).get(RESPONSE_DEADLINE.toMillis(), TimeUnit.MILLISECONDS);
                assertThat(answer.getStatusCode()).as("%s beyond the pool size: %s", path, answer.getBody())
                        .isEqualTo(HttpStatus.OK);
                if (path.equals(SEARCH)) {
                    assertThat(json(answer).path("items").findValuesAsText("custId"))
                            .as("the search finds the customer").contains(custId);
                } else {
                    assertThat(json(answer).path("custId").asText()).isEqualTo(custId);
                }
            }
        } finally {
            readers.shutdownNow();
            managerLogger.detachAppender(managerEvents);
            managerEvents.stop();
            managerLogger.setLevel(originalLevel);
        }

        assertThat(beyondPaths).as("a get and a search wait beyond the pool size").containsExactly(get, SEARCH);
        assertThat(rowCount()).as("the add inserted nothing").isEqualTo(rowsBefore);
        assertThat(sequenceState()).as("the add consumed no id").isEqualTo(sequenceBefore);
        assertThat(saturationWarnings(output, CUSTOMERS)).as("one WARN line for the add").isEqualTo(1);
        assertThat(unhandledErrors(output, get) + unhandledErrors(output, CUSTOMERS))
                .as("no read is answered 500 DEM9999").isZero();
    }

    @Test
    @DisplayName("row lock with more updates than connections: every update answers 409 DEM1001")
    void updatesOfALockedRowBeyondThePoolSizeAreAllLockWaits(CapturedOutput output) throws Exception {
        String custId = addCustomer("POOL SATURATION WRITE");
        String path = CUSTOMERS + "/" + custId;
        ExecutorService writers = Executors.newFixedThreadPool(WRITERS);
        try {
            List<ResponseEntity<String>> answers = new ArrayList<>();
            try (Connection holder = lockHolder()) {
                try (PreparedStatement update = holder.prepareStatement(
                        "UPDATE custmast SET name = name WHERE custid = ?")) {
                    update.setString(1, custId);
                    assertThat(update.executeUpdate()).isEqualTo(1);
                }
                CyclicBarrier start = new CyclicBarrier(WRITERS);
                List<Future<ResponseEntity<String>>> updates = new ArrayList<>();
                for (int i = 0; i < WRITERS; i++) {
                    String name = "POOL SATURATION EDIT " + i;
                    updates.add(writers.submit(started(start,
                            () -> maintenanceXhr().put().uri(path)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .body("{\"name\":\"" + name + "\"," + FIELDS_WITHOUT_NAME + ",\"version\":0}")
                                    .retrieve()
                                    .toEntity(String.class))));
                }
                for (Future<ResponseEntity<String>> update : updates) {
                    answers.add(update.get(RESPONSE_DEADLINE.toMillis(), TimeUnit.MILLISECONDS));
                }
                holder.rollback();
            }

            assertThat(answers).hasSize(WRITERS).allSatisfy(answer -> assertLockWait(answer, path));
        } finally {
            writers.shutdownNow();
        }

        assertThat(jdbcTemplate.queryForObject("SELECT row_version FROM custmast WHERE custid = ?",
                Long.class, custId)).as("no update was applied").isZero();
        assertThat(saturationWarnings(output, path))
                .as("writers beyond the pool size timed out on the pool, not on the row lock")
                .isBetween(1L, (long) (WRITERS - POOL_SIZE));
    }

    /**
     * Adds a valid customer through the API as the maintenance user.
     *
     * @param name the customer's name
     * @return the allocated id
     */
    private String addCustomer(String name) {
        ResponseEntity<String> response = maintenanceXhr().post().uri(CUSTOMERS)
                .contentType(MediaType.APPLICATION_JSON)
                .body(customerJson(name))
                .retrieve()
                .toEntity(String.class);
        assertThat(response.getStatusCode()).as("add: %s", response.getBody()).isEqualTo(HttpStatus.CREATED);
        String custId = json(response).path("custId").asText();
        assertThat(custId).isEqualTo(DatabaseCleaner.FIRST_INTERACTIVE_ID);
        return custId;
    }

    /**
     * Returns the JSON body of a valid customer.
     *
     * @param name the customer's name
     * @return the nine data fields
     */
    private static String customerJson(String name) {
        return "{\"name\":\"" + name + "\"," + FIELDS_WITHOUT_NAME + "}";
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
     * Returns the management view of the application's pool.
     *
     * @return the pool's MXBean
     * @throws SQLException if the data source is not a Hikari pool
     */
    private HikariPoolMXBean pool() throws SQLException {
        HikariPoolMXBean pool = dataSource.unwrap(HikariDataSource.class).getHikariPoolMXBean();
        assertThat(pool).as("the application's pool has started").isNotNull();
        assertThat(dataSource.unwrap(HikariDataSource.class).getMaximumPoolSize()).isEqualTo(POOL_SIZE);
        return pool;
    }

    /**
     * Waits until every pooled connection is active and at least that many sessions wait on the lock
     * the holder took on {@code custmast}.
     *
     * @param pool   the application's pool
     * @param holder the lock holder's connection
     * @throws Exception if the wait is interrupted or a query fails
     * @throws AssertionError if the pool is not saturated within {@link #SATURATION_DEADLINE}
     */
    private static void awaitEveryConnectionWaitingOnTheLock(HikariPoolMXBean pool, Connection holder)
            throws Exception {
        long deadline = System.nanoTime() + SATURATION_DEADLINE.toNanos();
        while (pool.getActiveConnections() < POOL_SIZE || waitingOnCustmast(holder) < POOL_SIZE) {
            if (System.nanoTime() - deadline >= 0) {
                throw new AssertionError("The reads did not hold every pooled connection within "
                        + SATURATION_DEADLINE + ": active=" + pool.getActiveConnections() + ", waiting on"
                        + " custmast=" + waitingOnCustmast(holder));
            }
            Thread.sleep(50);
        }
    }

    /**
     * Waits until the transaction manager has logged the DEBUG line of a new borrow from a number of
     * threads, each a read whose borrow timed out on the saturated pool and that borrows again.
     *
     * @param managerEvents the list appender on the manager's logger
     * @param expected      how many threads must have logged it
     * @throws InterruptedException if the wait is interrupted
     * @throws AssertionError if fewer threads have logged it within {@link #RESPONSE_DEADLINE}
     */
    private static void awaitBorrowingAgain(ListAppender<ILoggingEvent> managerEvents, int expected)
            throws InterruptedException {
        long deadline = System.nanoTime() + RESPONSE_DEADLINE.toNanos();
        while (borrowingAgainThreads(managerEvents) < expected) {
            if (System.nanoTime() - deadline >= 0) {
                throw new AssertionError(expected + " reads did not borrow again within " + RESPONSE_DEADLINE
                        + ": threads=" + borrowingAgainThreads(managerEvents));
            }
            Thread.sleep(50);
        }
    }

    /**
     * Counts the threads that logged the manager's DEBUG line of a new borrow.
     *
     * @param managerEvents the list appender on the manager's logger
     * @return the number of distinct threads
     */
    private static long borrowingAgainThreads(ListAppender<ILoggingEvent> managerEvents) {
        List<ILoggingEvent> events;
        // The appender adds under its own lock (AppenderBase.doAppend), so the copy is consistent.
        synchronized (managerEvents) {
            events = List.copyOf(managerEvents.list);
        }
        return events.stream()
                .filter(event -> event.getFormattedMessage().startsWith(BORROW_AGAIN_LINE))
                .map(ILoggingEvent::getThreadName)
                .distinct()
                .count();
    }

    /**
     * Sends a read as the inquiry user.
     *
     * @param path the path and query of the get or search
     * @return the response
     */
    private ResponseEntity<String> read(String path) {
        return inquiryXhr().get().uri(path).retrieve().toEntity(String.class);
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
     * Wraps a request so every worker sends it at the same moment.
     *
     * @param start   the barrier all workers meet at
     * @param request the request
     * @return the task
     */
    private static Callable<ResponseEntity<String>> started(CyclicBarrier start,
            Callable<ResponseEntity<String>> request) {
        return () -> {
            start.await(RESPONSE_DEADLINE.toMillis(), TimeUnit.MILLISECONDS);
            return request.call();
        };
    }

    /**
     * Asserts the 409 {@code DEM1001} problem of a lock wait: no data, no {@code errorId}.
     *
     * @param response the response
     * @param instance the request path
     */
    private void assertLockWait(ResponseEntity<String> response, String instance) {
        assertThat(response.getStatusCode()).as("status of %s", response.getBody()).isEqualTo(HttpStatus.CONFLICT);
        JsonNode problem = problem(response);
        assertThat(problem.path("code").asText()).isEqualTo("DEM1001");
        assertThat(problem.path("detail").asText()).isEqualTo(DEM1001_TEXT);
        assertThat(problem.path("instance").asText()).isEqualTo(instance);
        assertThat(problem.path("args").isArray()).isTrue();
        assertThat(problem.path("args")).isEmpty();
        assertThat(problem.has("errorId")).isFalse();
        assertThat(problem.has("errors")).isFalse();
        assertThat(problem.has("current")).isFalse();
    }

    /**
     * Counts the WARN lines of writes answered 409 because the pool stayed saturated, for one URI.
     *
     * @param output the captured console output
     * @param uri    the request URI the lines name
     * @return the number of lines
     */
    private static long saturationWarnings(CapturedOutput output, String uri) {
        return output.getAll().lines()
                .filter(line -> line.contains(SATURATED_WARNING) && line.endsWith("uri=" + uri))
                .count();
    }

    /**
     * Counts the ERROR lines of requests answered 500 {@code DEM9999}, for one URI, in the format of the
     * exception handler ({@code uri} last) and of the error controller ({@code uri} before
     * {@code sqlState}).
     *
     * @param output the captured console output
     * @param uri    the request URI the lines name, without the query
     * @return the number of lines
     */
    private static long unhandledErrors(CapturedOutput output, String uri) {
        return output.getAll().lines()
                .filter(line -> line.contains(UNHANDLED_ERROR)
                        && (line.endsWith(" uri=" + uri) || line.contains(" uri=" + uri + " ")))
                .count();
    }

    /**
     * Returns the number of {@code custmast} rows.
     *
     * @return the count
     */
    private long rowCount() {
        Long count = jdbcTemplate.queryForObject("SELECT count(*) FROM custmast", Long.class);
        return count == null ? 0 : count;
    }

    /**
     * Returns the state of {@code custmast_id_seq}.
     *
     * @return {@code last_value} and {@code is_called}
     */
    private Map<String, Object> sequenceState() {
        return jdbcTemplate.queryForMap("SELECT last_value, is_called FROM custmast_id_seq");
    }
}
