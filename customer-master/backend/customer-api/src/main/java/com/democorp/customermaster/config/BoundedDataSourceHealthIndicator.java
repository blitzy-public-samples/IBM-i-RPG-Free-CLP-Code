package com.democorp.customermaster.config;

import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.boot.actuate.health.AbstractHealthIndicator;
import org.springframework.boot.actuate.health.Health;
import org.springframework.util.Assert;

/**
 * The {@code db} health check, a member of the readiness probe: UP while a pooled connection to
 * PostgreSQL answers within a bounded time, DOWN otherwise.
 *
 * <p><b>Why not Spring Boot's check.</b> Boot's {@code DataSourceHealthIndicator} validates with
 * {@code Connection.isValid(0)}, which has no deadline. Hikari hands out a connection used within the
 * last 500 ms without checking it, so when PostgreSQL stops answering right after a probe (a paused
 * container, a network partition), the next probe waits on that connection without end and the
 * Compose healthcheck never sees 503.
 *
 * <p><b>The check.</b> One connection is borrowed, which Hikari bounds by
 * {@code spring.datasource.hikari.connection-timeout}, and validated with
 * {@code Connection.isValid(seconds)}, where the seconds are {@link #getValidationTimeoutSeconds()}.
 * pgjdbc applies them as the socket timeout of its validation round trip and answers {@code false}
 * once they expire. A connection that fails validation is evicted from the pool while it is still
 * held, so it is closed at once and never handed to a request. A borrow that times out or fails
 * throws, which {@link AbstractHealthIndicator} reports as DOWN with the exception as the
 * {@code error} detail, logging "DataSource health check failed" at WARN, as Boot's check does.
 *
 * <p><b>A saturated pool.</b> Requests waiting on locks (reads behind a generator load, writes up to
 * the lock timeout) can hold every pooled connection while PostgreSQL answers. A borrow that times
 * out that way, {@link ConnectionPoolSaturation#isSaturationTimeout(Throwable)}, is followed by the
 * direct probe {@link ConnectionPoolSaturation#answeringDatabase()}: when PostgreSQL answers it, the
 * check is UP; otherwise the borrow failure is reported as above. The probe bounds opening its
 * connection and validating it by {@link ConnectionPoolSaturation#getProbeTimeoutSeconds()} each, so
 * this path's worst case is the connection timeout plus twice that bound: 5 + 2 + 2 = 9 seconds with
 * {@code application.yml}, the same as the borrow-and-validate path.
 *
 * <p><b>Details.</b> The members of Boot's check: {@code database}, the product name from the
 * metadata of the connection validated (the pooled one, or the probe's), and {@code validationQuery}
 * with the value {@code "isValid()"}. Anonymous probe callers see neither, because the health
 * endpoint shows no details by default.
 */
public class BoundedDataSourceHealthIndicator extends AbstractHealthIndicator {

    /** The validation bound, in seconds, for a {@link DataSource} that is not a Hikari pool. */
    private static final int FALLBACK_VALIDATION_SECONDS = 2;

    /** The detail naming the database product, as in Boot's check. */
    private static final String DATABASE_DETAIL = "database";

    /** The detail naming the validation method, as in Boot's check. */
    private static final String VALIDATION_QUERY_DETAIL = "validationQuery";

    /** The {@value #VALIDATION_QUERY_DETAIL} value Boot reports for a validation by {@code isValid}. */
    private static final String IS_VALID = "isValid()";

    /** The application's connection pool. */
    private final DataSource dataSource;

    /** Tells a borrow that failed because every connection is busy, and probes PostgreSQL directly. */
    private final ConnectionPoolSaturation poolSaturation;

    /**
     * Creates the check over the application's {@link DataSource}. No connection is opened until the
     * first health evaluation.
     *
     * @param dataSource     the pool to borrow from; a {@link HikariDataSource} supplies the validation
     *                       bound and lets a connection that fails validation be evicted
     * @param poolSaturation the saturation checks over the same pool
     * @throws IllegalArgumentException if {@code dataSource} or {@code poolSaturation} is {@code null}
     */
    public BoundedDataSourceHealthIndicator(DataSource dataSource, ConnectionPoolSaturation poolSaturation) {
        super("DataSource health check failed");
        Assert.notNull(dataSource, "dataSource must not be null");
        Assert.notNull(poolSaturation, "poolSaturation must not be null");
        this.dataSource = dataSource;
        this.poolSaturation = poolSaturation;
    }

    /**
     * Returns the longest wait of the validation round trip. For a Hikari pool it is the pool's
     * {@code validation-timeout} (the bound Hikari applies to its own aliveness check), rounded up to
     * whole seconds and at least 1, because {@code Connection.isValid} takes seconds and reads 0 as no
     * limit. The pool's current value is read on every call. Any other {@link DataSource} gets 2
     * seconds.
     *
     * @return the validation bound in seconds, from 1 to {@link Integer#MAX_VALUE}
     */
    public int getValidationTimeoutSeconds() {
        if (dataSource instanceof HikariDataSource hikari) {
            return ConnectionPoolSaturation.validationTimeoutSeconds(hikari);
        }
        return FALLBACK_VALIDATION_SECONDS;
    }

    /**
     * Borrows one connection, records the database product and validates the connection within
     * {@link #getValidationTimeoutSeconds()}. A borrow that timed out only because every connection is
     * busy is UP when PostgreSQL answers the direct probe.
     *
     * @param builder the builder of this evaluation's result
     * @throws Exception if no connection is obtained within the pool's connection timeout, unless the
     *                   pool is saturated and PostgreSQL answers, or the connection fails before
     *                   validation; reported as DOWN
     */
    @Override
    protected void doHealthCheck(Health.Builder builder) throws Exception {
        int timeoutSeconds = getValidationTimeoutSeconds();
        Connection borrowed;
        try {
            borrowed = dataSource.getConnection();
        } catch (SQLException | RuntimeException borrowFailure) {
            Optional<String> database = ConnectionPoolSaturation.isSaturationTimeout(borrowFailure)
                    ? poolSaturation.answeringDatabase()
                    : Optional.empty();
            if (database.isEmpty()) {
                throw borrowFailure;
            }
            builder.up()
                    .withDetail(DATABASE_DETAIL, database.get())
                    .withDetail(VALIDATION_QUERY_DETAIL, IS_VALID);
            return;
        }
        try (Connection connection = borrowed) {
            builder.withDetail(DATABASE_DETAIL, connection.getMetaData().getDatabaseProductName())
                    .withDetail(VALIDATION_QUERY_DETAIL, IS_VALID);
            if (connection.isValid(timeoutSeconds)) {
                builder.up();
            } else {
                evict(connection);
                builder.down();
            }
        }
    }

    /**
     * Removes a connection that failed validation from a Hikari pool. It must be called while the
     * connection is still held: Hikari then closes it at once, and returning it afterwards neither
     * fails nor logs. Any other {@link DataSource} is left to its own checks.
     *
     * @param connection the held connection that failed validation
     */
    private void evict(Connection connection) {
        if (dataSource instanceof HikariDataSource hikari) {
            hikari.evictConnection(connection);
        }
    }
}
