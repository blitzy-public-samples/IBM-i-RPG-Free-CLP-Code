package com.democorp.customermaster.controller;

import static org.assertj.core.api.Assertions.assertThat;

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
 * Proves that requests waiting on locks, by holding every pooled connection, no longer turn an add or
 * an update into 500 {@code DEM9999} or the readiness probe into DOWN: an add or update that cannot
 * borrow a connection within the connection timeout while PostgreSQL answers is 409 {@code DEM1001},
 * the answer of the lock wait it is, and readiness stays UP.
 *
 * <ul>
 *   <li><b>Load lock, reads holding the pool.</b> A separate JDBC connection holds
 *       {@code LOCK TABLE custmast IN ACCESS EXCLUSIVE MODE}, as a generator load does. More reads than
 *       the pool has connections wait behind it, so every pooled connection is held. An add then
 *       answers 409 {@code DEM1001} after the connection timeout, consuming no id; readiness answers
 *       200 UP, its {@code db} check reporting the database and {@code isValid()} from the direct
 *       probe; once the lock is released, the reads holding connections answer 200. The reads beyond
 *       the pool size get no connection and answer 500 {@code DEM9999} after the connection timeout:
 *       reads wait for a load without a bound, and a read that cannot even borrow a connection is not
 *       a write, so that answer is unchanged.</li>
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
 * {@link OutputCaptureExtension}.
 */
@ExtendWith(OutputCaptureExtension.class)
@TestPropertySource(properties = {
    "spring.datasource.hikari.maximum-pool-size=" + ConnectionPoolSaturationIT.POOL_SIZE,
    "spring.datasource.hikari.connection-timeout=" + ConnectionPoolSaturationIT.CONNECTION_TIMEOUT_MS,
    "spring.datasource.hikari.validation-timeout=500",
    "customer-master.db.lock-timeout=" + ConnectionPoolSaturationIT.LOCK_TIMEOUT_SECONDS + "s"
})
@DisplayName("Connection pool saturation: writes answer 409 DEM1001 and readiness stays UP")
class ConnectionPoolSaturationIT extends AbstractPostgresIT {

    /** Connections of this context's pool. */
    static final int POOL_SIZE = 3;

    /** Longest wait, in ms, of this context's pool for a free connection. */
    static final long CONNECTION_TIMEOUT_MS = 1000;

    /** This context's lock timeout of adds and updates, longer than the connection timeout. */
    static final int LOCK_TIMEOUT_SECONDS = 3;

    /** Reads sent during the load lock: two more than the pool has connections. */
    private static final int READERS = POOL_SIZE + 2;

    /** Updates sent during the row lock: twice the pool size. */
    private static final int WRITERS = POOL_SIZE * 2;

    /** The add route. */
    private static final String CUSTOMERS = "/api/customers";

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
    @DisplayName("load lock with every connection held by reads: add 409 DEM1001, readiness UP, reads 200")
    void addDuringALoadLockWhileReadsHoldThePoolIsALockWait(CapturedOutput output) throws Exception {
        String custId = addCustomer("POOL SATURATION READ");
        long rowsBefore = rowCount();
        Map<String, Object> sequenceBefore = sequenceState();
        HikariPoolMXBean pool = pool();
        ExecutorService readers = Executors.newFixedThreadPool(READERS);
        List<Future<ResponseEntity<String>>> reads = new ArrayList<>();
        try {
            try (Connection holder = lockHolder()) {
                try (Statement statement = holder.createStatement()) {
                    statement.execute("LOCK TABLE custmast IN ACCESS EXCLUSIVE MODE");
                }
                CyclicBarrier start = new CyclicBarrier(READERS);
                for (int i = 0; i < READERS; i++) {
                    reads.add(readers.submit(started(start,
                            () -> inquiryXhr().get().uri(CUSTOMERS + "/" + custId).retrieve()
                                    .toEntity(String.class))));
                }
                awaitEveryConnectionWaitingOnTheLock(pool, holder);

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
                // The reads beyond the pool size answer while the lock is held, so none of them can
                // receive a connection freed by the release below.
                awaitAnswered(reads, READERS - POOL_SIZE);

                holder.rollback();
            }

            List<ResponseEntity<String>> answers = new ArrayList<>();
            for (Future<ResponseEntity<String>> read : reads) {
                answers.add(read.get(RESPONSE_DEADLINE.toMillis(), TimeUnit.MILLISECONDS));
            }
            List<ResponseEntity<String>> found = answers.stream()
                    .filter(answer -> answer.getStatusCode().value() == HttpStatus.OK.value())
                    .toList();
            List<ResponseEntity<String>> notServed = answers.stream()
                    .filter(answer -> answer.getStatusCode().value() != HttpStatus.OK.value())
                    .toList();
            assertThat(found).as("the reads that held a connection answer once the lock is released")
                    .hasSize(POOL_SIZE)
                    .allSatisfy(answer -> assertThat(json(answer).path("custId").asText()).isEqualTo(custId));
            assertThat(notServed)
                    .as("the reads beyond the pool size get no connection: the documented cost of reads"
                            + " waiting for a load, which this change leaves as it was")
                    .hasSize(READERS - POOL_SIZE)
                    .allSatisfy(answer -> {
                        assertThat(answer.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
                        assertThat(problem(answer).path("code").asText()).isEqualTo("DEM9999");
                    });
        } finally {
            readers.shutdownNow();
        }

        assertThat(rowCount()).as("the add inserted nothing").isEqualTo(rowsBefore);
        assertThat(sequenceState()).as("the add consumed no id").isEqualTo(sequenceBefore);
        assertThat(saturationWarnings(output, CUSTOMERS)).as("one WARN line for the add").isEqualTo(1);
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
     * Waits until a number of the requests have answered.
     *
     * @param requests the requests in flight
     * @param expected how many must have answered
     * @throws InterruptedException if the wait is interrupted
     * @throws AssertionError if fewer have answered within {@link #RESPONSE_DEADLINE}
     */
    private static void awaitAnswered(List<Future<ResponseEntity<String>>> requests, int expected)
            throws InterruptedException {
        long deadline = System.nanoTime() + RESPONSE_DEADLINE.toNanos();
        while (requests.stream().filter(Future::isDone).count() < expected) {
            if (System.nanoTime() - deadline >= 0) {
                throw new AssertionError(expected + " requests did not answer within " + RESPONSE_DEADLINE);
            }
            Thread.sleep(50);
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
