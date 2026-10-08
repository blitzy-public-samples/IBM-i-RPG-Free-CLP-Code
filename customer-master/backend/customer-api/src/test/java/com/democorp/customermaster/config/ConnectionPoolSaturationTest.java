package com.democorp.customermaster.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariDataSource;
import java.io.Closeable;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.CannotCreateTransactionException;

/**
 * Specifies {@link ConnectionPoolSaturation}: which failures are the borrow timeout of a saturated
 * HikariCP pool, and that the direct probe gives no answer, within its bound, whenever PostgreSQL
 * cannot be reached through the pool's configuration.
 *
 * <p>Plain JUnit 5 and AssertJ. The failures are built in the shapes HikariCP 6 throws: a
 * {@link SQLTransientConnectionException} whose cause and next exception are the last
 * connection-creation failure, or are absent when no connection attempt failed. The probe tests use an
 * unstarted {@link HikariDataSource}, so no pool starts, and a loopback port that refuses or a loopback
 * server that accepts and never answers; no database and no Docker are needed. A probe that PostgreSQL
 * answers is proved by {@code ConnectionPoolSaturationIT}.
 */
@DisplayName("ConnectionPoolSaturation")
final class ConnectionPoolSaturationTest {

    /** HikariCP's text for a borrow timeout. */
    private static final String TIMEOUT_MESSAGE = "HikariPool-1 - Connection is not available, request timed out"
            + " after 5000ms (total=10, active=10, idle=0, waiting=1)";

    /** Spring's text for a transaction that could not obtain its connection. */
    private static final String CANNOT_OPEN = "Could not open JDBC Connection for transaction";

    /** The password the probe tests configure; it must never reach the log. */
    private static final String SECRET = "probe-secret-pw";

    /** Time allowed beyond the probe's own bound for scheduling and the driver's own work. */
    private static final Duration PROBE_MARGIN = Duration.ofSeconds(2);

    @Nested
    @DisplayName("isSaturationTimeout")
    final class SaturationTimeout {

        @Test
        @DisplayName("a borrow timeout without cause and next exception is saturation")
        void bareBorrowTimeoutIsSaturation() {
            assertThat(ConnectionPoolSaturation.isSaturationTimeout(saturationTimeout())).isTrue();
        }

        @Test
        @DisplayName("the timeout wrapped by the transaction manager is saturation")
        void wrappedBorrowTimeoutIsSaturation() {
            assertThat(ConnectionPoolSaturation.isSaturationTimeout(
                    new CannotCreateTransactionException(CANNOT_OPEN, saturationTimeout()))).isTrue();
            assertThat(ConnectionPoolSaturation.isSaturationTimeout(new IllegalStateException("outer",
                    new CannotCreateTransactionException(CANNOT_OPEN, saturationTimeout())))).isTrue();
        }

        @Test
        @DisplayName("a timeout carrying the last connection failure as cause and next exception is not")
        void borrowTimeoutWithConnectionFailureIsNotSaturation() {
            SQLException refused = new SQLException("Connection refused", "08001");
            SQLTransientConnectionException timeout =
                    new SQLTransientConnectionException(TIMEOUT_MESSAGE, "08001", 0, refused);
            timeout.setNextException(refused);

            assertThat(ConnectionPoolSaturation.isSaturationTimeout(timeout)).isFalse();
            assertThat(ConnectionPoolSaturation.isSaturationTimeout(
                    new CannotCreateTransactionException(CANNOT_OPEN, timeout))).isFalse();
        }

        @Test
        @DisplayName("a timeout carrying only a cause, or only a next exception, is not")
        void borrowTimeoutWithEitherLinkIsNotSaturation() {
            SQLTransientConnectionException withCause = new SQLTransientConnectionException(TIMEOUT_MESSAGE,
                    new IllegalStateException("connection setup failed"));
            SQLTransientConnectionException withNext = new SQLTransientConnectionException(TIMEOUT_MESSAGE);
            withNext.setNextException(new SQLException("Connection refused", "08001"));

            assertThat(ConnectionPoolSaturation.isSaturationTimeout(withCause)).isFalse();
            assertThat(ConnectionPoolSaturation.isSaturationTimeout(withNext)).isFalse();
        }

        @Test
        @DisplayName("other failures, and null, are not")
        void otherFailuresAreNotSaturation() {
            assertThat(ConnectionPoolSaturation.isSaturationTimeout(null)).isFalse();
            assertThat(ConnectionPoolSaturation.isSaturationTimeout(new SQLException("I/O error", "08006")))
                    .isFalse();
            assertThat(ConnectionPoolSaturation.isSaturationTimeout(
                    new CannotCreateTransactionException(CANNOT_OPEN, new SQLException("I/O error", "08006"))))
                    .isFalse();
            assertThat(ConnectionPoolSaturation.isSaturationTimeout(
                    new CannotCreateTransactionException(CANNOT_OPEN))).isFalse();
        }

        @Test
        @DisplayName("a timeout reached through a next exception is found")
        void timeoutBehindANextExceptionIsFound() {
            SQLException batch = new SQLException("batch failed", "08006");
            batch.setNextException(saturationTimeout());

            assertThat(ConnectionPoolSaturation.isSaturationTimeout(batch)).isTrue();
        }

        @Test
        @DisplayName("cyclic cause and next-exception graphs end")
        void cyclicGraphsEnd() {
            RuntimeException first = new RuntimeException("first");
            RuntimeException second = new RuntimeException("second", first);
            first.initCause(second);
            SQLException selfNext = new SQLException("self", "08006");
            selfNext.setNextException(selfNext);
            RuntimeException cycleWithTimeout = new RuntimeException("cycle");
            SQLException holder = new SQLException("holder", "08006", cycleWithTimeout);
            cycleWithTimeout.initCause(holder);
            holder.setNextException(saturationTimeout());

            assertThat(ConnectionPoolSaturation.isSaturationTimeout(first)).isFalse();
            assertThat(ConnectionPoolSaturation.isSaturationTimeout(selfNext)).isFalse();
            assertThat(ConnectionPoolSaturation.isSaturationTimeout(cycleWithTimeout)).isTrue();
        }

        @Test
        @DisplayName("the walk is bounded: a timeout deeper than 32 throwables is not inspected")
        void walkIsBounded() {
            Throwable chain = saturationTimeout();
            for (int depth = 1; depth < 32; depth++) {
                chain = new RuntimeException("level " + depth, chain);
            }
            Throwable tooDeep = new RuntimeException("level 32", chain);

            assertThat(ConnectionPoolSaturation.isSaturationTimeout(chain)).isTrue();
            assertThat(ConnectionPoolSaturation.isSaturationTimeout(tooDeep)).isFalse();
        }

        private static SQLTransientConnectionException saturationTimeout() {
            return new SQLTransientConnectionException(TIMEOUT_MESSAGE);
        }
    }

    @Nested
    @DisplayName("withoutProbeTimeouts")
    final class WithoutProbeTimeouts {

        @Test
        @DisplayName("removes the three timeouts from the query and keeps every other parameter in order")
        void removesOnlyTheTimeouts() {
            assertThat(ConnectionPoolSaturation.withoutProbeTimeouts("jdbc:postgresql://db:5432/cm"
                    + "?currentSchema=customer_master&socketTimeout=45&loginTimeout=9&ApplicationName=api"
                    + "&connectTimeout=3&socketTimeoutX=1"))
                    .isEqualTo("jdbc:postgresql://db:5432/cm?currentSchema=customer_master&ApplicationName=api"
                            + "&socketTimeoutX=1");
        }

        @Test
        @DisplayName("drops the query separator when only timeouts were given, and keeps a URL without query")
        void handlesQueriesWithoutOtherParameters() {
            assertThat(ConnectionPoolSaturation.withoutProbeTimeouts(
                    "jdbc:postgresql://db/cm?socketTimeout=45&loginTimeout")).isEqualTo("jdbc:postgresql://db/cm");
            assertThat(ConnectionPoolSaturation.withoutProbeTimeouts("jdbc:postgresql://db/cm"))
                    .isEqualTo("jdbc:postgresql://db/cm");
        }
    }

    @Nested
    @DisplayName("the direct probe")
    @ExtendWith(OutputCaptureExtension.class)
    final class Probe {

        @Test
        @DisplayName("a DataSource that is not a Hikari pool is never probed")
        void nonHikariDataSourceGivesNoAnswer() {
            ConnectionPoolSaturation saturation = new ConnectionPoolSaturation(
                    new DriverManagerDataSource("jdbc:postgresql://127.0.0.1:1/none", "u", SECRET));

            assertThat(saturation.databaseAnswers()).isFalse();
            assertThat(saturation.answeringDatabase()).isEmpty();
            assertThat(saturation.getProbeTimeoutSeconds()).isZero();
        }

        @Test
        @DisplayName("a Hikari pool without a jdbcUrl gives no answer")
        void hikariWithoutJdbcUrlGivesNoAnswer() {
            try (HikariDataSource pool = new HikariDataSource()) {
                ConnectionPoolSaturation saturation = new ConnectionPoolSaturation(pool);

                assertThat(saturation.databaseAnswers()).isFalse();
                assertThat(pool.getHikariPoolMXBean()).as("the probe must not start the pool").isNull();
            }
        }

        @Test
        @DisplayName("the bound is the pool's validation-timeout rounded up to whole seconds, at least 1")
        void boundFollowsTheValidationTimeout() {
            try (HikariDataSource pool = new HikariDataSource()) {
                ConnectionPoolSaturation saturation = new ConnectionPoolSaturation(pool);

                pool.setValidationTimeout(2000);
                assertThat(saturation.getProbeTimeoutSeconds()).isEqualTo(2);
                pool.setValidationTimeout(1500);
                assertThat(saturation.getProbeTimeoutSeconds()).isEqualTo(2);
                pool.setValidationTimeout(250);
                assertThat(saturation.getProbeTimeoutSeconds()).isEqualTo(1);
            }
        }

        @Test
        @DisplayName("a refused connection gives no answer, and the log carries neither URL nor password")
        void refusedConnectionGivesNoAnswer(CapturedOutput output) {
            int port = closedLoopbackPort();
            try (HikariDataSource pool = unstartedPool(port)) {
                ConnectionPoolSaturation saturation = new ConnectionPoolSaturation(pool);

                assertThat(saturation.answeringDatabase()).isEmpty();
                assertThat(pool.getHikariPoolMXBean()).as("the probe must not use the pool").isNull();
            }
            assertThat(output.getAll()).doesNotContain(SECRET).doesNotContain(":" + port + "/");
        }

        @Test
        @DisplayName("a server that never answers gives no answer within the bound plus a margin")
        void silentServerGivesNoAnswerWithinTheBound(CapturedOutput output) throws Exception {
            try (SilentServer server = new SilentServer(); HikariDataSource pool = unstartedPool(server.port())) {
                ConnectionPoolSaturation saturation = new ConnectionPoolSaturation(pool);
                Duration bound = Duration.ofSeconds(saturation.getProbeTimeoutSeconds());

                long start = System.nanoTime();
                Optional<String> answer = saturation.answeringDatabase();
                Duration elapsed = Duration.ofNanos(System.nanoTime() - start);

                assertThat(answer).isEmpty();
                assertThat(elapsed).isLessThan(bound.plus(PROBE_MARGIN));
                assertThat(server.accepted()).isEqualTo(1);
            }
            assertThat(output.getAll()).doesNotContain(SECRET);
        }

        @Test
        @DisplayName("concurrent callers share the probe in flight: one probe connection for all of them")
        void concurrentCallersShareOneProbe() throws Exception {
            int callers = 4;
            ExecutorService executor = Executors.newFixedThreadPool(callers);
            try (SilentServer server = new SilentServer(); HikariDataSource pool = unstartedPool(server.port())) {
                ConnectionPoolSaturation saturation = new ConnectionPoolSaturation(pool);
                CyclicBarrier start = new CyclicBarrier(callers);
                List<Future<Boolean>> answers = new ArrayList<>();
                for (int i = 0; i < callers; i++) {
                    answers.add(executor.submit(() -> {
                        start.await();
                        return saturation.databaseAnswers();
                    }));
                }

                for (Future<Boolean> answer : answers) {
                    assertThat(answer.get(30, TimeUnit.SECONDS)).isFalse();
                }
                assertThat(server.accepted()).as("probe connections opened").isEqualTo(1);
            } finally {
                executor.shutdownNow();
            }
        }

        /**
         * Returns an unstarted Hikari pool configured for a loopback port, with a 2-second validation
         * bound and the {@link #SECRET} password.
         *
         * @param port the loopback port
         * @return the pool; no connection is opened by configuring it
         */
        private static HikariDataSource unstartedPool(int port) {
            HikariDataSource pool = new HikariDataSource();
            pool.setJdbcUrl("jdbc:postgresql://127.0.0.1:" + port + "/customermaster?sslmode=disable"
                    + "&socketTimeout=45&currentSchema=customer_master");
            pool.setUsername("customermaster");
            pool.setPassword(SECRET);
            pool.setValidationTimeout(2000);
            pool.addDataSourceProperty("socketTimeout", "45");
            return pool;
        }

        /**
         * Returns a loopback port with no listener: the port of a just-closed ephemeral server socket, so
         * a connection to it is refused.
         *
         * @return the closed port
         */
        private static int closedLoopbackPort() {
            try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
                return socket.getLocalPort();
            } catch (IOException e) {
                throw new IllegalStateException("No ephemeral loopback port could be bound", e);
            }
        }
    }

    /**
     * A loopback server that accepts every connection and never sends a byte, as a database server
     * that stopped answering behaves. It counts the connections it accepted and closes them all on
     * {@link #close()}.
     */
    private static final class SilentServer implements AutoCloseable {

        /** The listening loopback socket. */
        private final ServerSocket server;

        /** Every accepted socket, closed by {@link #close()}. */
        private final Set<Socket> sockets = ConcurrentHashMap.newKeySet();

        /** The number of connections accepted. */
        private final AtomicInteger accepted = new AtomicInteger();

        /**
         * Starts the server on an ephemeral loopback port, accepting on a daemon thread.
         *
         * @throws IOException if no loopback port can be bound
         */
        SilentServer() throws IOException {
            server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
            Thread acceptor = new Thread(this::acceptConnections, "silent-server-accept");
            acceptor.setDaemon(true);
            acceptor.start();
        }

        /**
         * Returns the port the probe connects to.
         *
         * @return the listening loopback port
         */
        int port() {
            return server.getLocalPort();
        }

        /**
         * Returns how many connections were accepted so far.
         *
         * @return the count
         */
        int accepted() {
            return accepted.get();
        }

        /** Accepts connections until the server socket closes, holding each one open and silent. */
        private void acceptConnections() {
            while (!server.isClosed()) {
                try {
                    Socket socket = server.accept();
                    sockets.add(socket);
                    accepted.incrementAndGet();
                } catch (IOException e) {
                    // The server socket was closed by close(): no further connection is accepted.
                    return;
                }
            }
        }

        /** Stops accepting and closes every accepted socket. */
        @Override
        public void close() {
            closeQuietly(server);
            sockets.forEach(SilentServer::closeQuietly);
        }

        /**
         * Closes a socket that may already be closed or reset.
         *
         * @param closeable the socket
         */
        private static void closeQuietly(Closeable closeable) {
            try {
                closeable.close();
            } catch (IOException e) {
                // Already closed or reset by the peer: nothing is left to release.
            }
        }
    }
}
