package com.democorp.customermaster.support;

import java.sql.SQLException;
import java.sql.Statement;
import java.util.Objects;

import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Resets the shared test database between integration tests: {@code AbstractPostgresIT} calls
 * {@code new DatabaseCleaner(jdbcTemplate).clean()} before every test method of every {@code *IT}.
 *
 * <p><b>Invariant after {@link #clean()}.</b>
 * <ul>
 *   <li>{@code custmast} is empty.</li>
 *   <li>{@code states} keeps its {@value #STATE_COUNT} rows from migration V2; it is never touched.</li>
 *   <li>The next {@code nextval('custmast_id_seq')} returns {@value #SEQUENCE_START}, the ordinal of
 *       {@value #FIRST_INTERACTIVE_ID} in BASE36ADD's alphabet ({@code A..Z} = 0..25, {@code 0..9} =
 *       26..35): 4·36³ + 4·36² + 4·36 + 5. That is the START value of migration V4, which replaces
 *       CUSTNEXT's initial {@code EEEE}, incremented before use.</li>
 * </ul>
 * Test databases receive {@code classpath:db/migration} (V1 to V4) only, never the V5 seed, so no seed
 * rows exist in tests: every test inserts the customers it needs.
 *
 * <p><b>Statements.</b> All run on one connection: {@code SET lock_timeout = '10s'},
 * {@code TRUNCATE custmast}, {@code ALTER SEQUENCE custmast_id_seq RESTART}, and in {@code finally}
 * {@code RESET lock_timeout}, so the pooled connection returns with default settings. The sequence is
 * not owned by a column, so {@code TRUNCATE ... RESTART IDENTITY} would not reset it; the explicit
 * {@code ALTER SEQUENCE} restarts it at its START value. Names are unqualified: the datasource puts
 * the {@code DB_SCHEMA} schema on the search path.
 *
 * <p><b>Sequence ownership.</b> Production code touches {@code custmast_id_seq} only inside
 * {@code CustomerIdAllocator}. Test code may reset it here directly. Do not route this reset through
 * the allocator: its methods require an open transaction ({@code Propagation.MANDATORY}) and take a
 * table lock.
 *
 * <p><b>Context policy binding all ITs</b> (the same text as in {@code AbstractPostgresIT}). One
 * PostgreSQL container serves every cached Spring context. Each distinct {@code @MockitoBean} or
 * {@code @Import} set forks a context with its own Hikari pool on that container. The suite keeps to
 * four variants:
 * <ol>
 *   <li>the base;</li>
 *   <li>{@code @MockitoBean LoadCheckpoints}, declared identically in {@code CustomerGeneratorIT} and
 *       {@code CustomerIdAllocationIT};</li>
 *   <li>{@code @MockitoBean AddressValidationClient} in {@code CustomerMaintenanceApiIT};</li>
 *   <li>{@code ErrorModelIT}, with {@code @MockitoBean CustomerSearchService} and
 *       {@code CustomerMaintenanceService} plus one nested {@code @ConditionalOnWebApplication}
 *       {@code @TestConfiguration}.</li>
 * </ol>
 * Tests must release every lock and close every extra connection in {@code finally}; otherwise the
 * {@code TRUNCATE} here blocks, and fails after 10 seconds.
 *
 * <p>This is a plain class with no Spring stereotype: test classes are on the classpath when
 * {@code GeneratorProcessIT} runs the application's component scan without a
 * {@code TestTypeExcludeFilter}, so a bean here would leak into that context.
 */
public final class DatabaseCleaner {

    /** The value the next {@code nextval('custmast_id_seq')} returns after {@link #clean()}. */
    public static final int SEQUENCE_START = 191_957;

    /** The customer id {@link #SEQUENCE_START} encodes: the first id an add receives in every test. */
    public static final String FIRST_INTERACTIVE_ID = "EEEF";

    /** The number of {@code states} rows, loaded by migration V2 and kept by {@link #clean()}. */
    public static final int STATE_COUNT = 58;

    /** SQLSTATE {@code lock_not_available}, raised when {@code lock_timeout} expires. */
    private static final String LOCK_NOT_AVAILABLE = "55P03";

    private final JdbcTemplate jdbcTemplate;

    /**
     * Creates a cleaner over the test context's datasource.
     *
     * @param jdbcTemplate the test context's template, whose connections resolve unqualified names to
     *                     the test schema
     * @throws NullPointerException if {@code jdbcTemplate} is {@code null}
     */
    public DatabaseCleaner(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate");
    }

    /**
     * Empties {@code custmast} and restarts {@code custmast_id_seq} at {@value #SEQUENCE_START}
     * ({@value #FIRST_INTERACTIVE_ID}), leaving {@code states} and everything else unchanged.
     *
     * @throws IllegalStateException if {@code custmast} cannot be locked within 10 seconds (SQLSTATE
     *                               55P03) because a previous test left a transaction, lock or extra
     *                               connection open; the original exception is the cause
     * @throws org.springframework.dao.DataAccessException if any other statement fails
     */
    public void clean() {
        jdbcTemplate.execute((ConnectionCallback<Void>) connection -> {
            try (Statement statement = connection.createStatement()) {
                Exception failure = null;
                try {
                    statement.execute("SET lock_timeout = '10s'");
                    statement.execute("TRUNCATE custmast");
                    statement.execute("ALTER SEQUENCE custmast_id_seq RESTART");
                } catch (SQLException e) {
                    if (LOCK_NOT_AVAILABLE.equals(e.getSQLState())) {
                        IllegalStateException locked = new IllegalStateException(
                                "custmast could not be locked within 10s because a previous test left a"
                                        + " transaction, lock or extra connection open; tests must release"
                                        + " their locks and close extra connections in finally",
                                e);
                        failure = locked;
                        throw locked;
                    }
                    failure = e;
                    throw e;
                } finally {
                    resetLockTimeout(statement, failure);
                }
            }
            return null;
        });
    }

    /**
     * Restores the session default {@code lock_timeout}, so the pooled connection goes back to Hikari
     * unchanged.
     *
     * @param statement the statement the reset ran on
     * @param failure   the exception the reset is already failing with, or {@code null}; a failed
     *                  {@code RESET} is added to it as suppressed, so it cannot hide that cause (as it
     *                  would inside a transaction the failure aborted)
     * @throws SQLException if the {@code RESET} fails and no earlier failure is in flight
     */
    private static void resetLockTimeout(Statement statement, Exception failure) throws SQLException {
        try {
            statement.execute("RESET lock_timeout");
        } catch (SQLException resetFailure) {
            if (failure == null) {
                throw resetFailure;
            }
            failure.addSuppressed(resetFailure);
        }
    }
}
