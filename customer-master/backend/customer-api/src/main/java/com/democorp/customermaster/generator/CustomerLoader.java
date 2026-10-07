package com.democorp.customermaster.generator;

import com.democorp.customermaster.domain.CustomerId;
import com.democorp.customermaster.repository.CustomerIdAllocator;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;
import javax.sql.DataSource;
import org.postgresql.PGConnection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.stereotype.Component;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Replaces the whole contents of {@code custmast} with generated customers in one transaction, and moves
 * the shared customer id sequence past the rows written.
 *
 * <p><b>What it replaces.</b>
 * <ul>
 *   <li>LOADCUSTR's {@code truncate lennons1.custmast} followed by one
 *       {@code insert into lennons1.custmast values(:Fld)} per generated row, all under commitment control
 *       {@code *NONE} [5250_Subfile/LOADCUSTR.SQLRPGLE:90-98,131-207]. A failure part-way left the table
 *       truncated and partly loaded. Here the truncate, the rows and the sequence restart commit or roll
 *       back together.</li>
 *   <li>The {@code ALCOBJ OBJ((CUSTMAST *FILE *EXCLRD)) WAIT(5)} guard with its {@code CPF1002} handler
 *       "Cannot allocate CUSTMAST" in LOADCUST and LOADCUST2 [5250_Subfile/LOADCUST.CLLE:6-14],
 *       [5250_Subfile/LOADCUST2.CLLE:10-18]. It becomes {@code SET LOCAL lock_timeout = '5s'} plus
 *       {@link CustomerIdAllocator#lockForLoad()} ({@code LOCK TABLE custmast IN ACCESS EXCLUSIVE MODE}).
 *       The source checked the lock and then released it before submitting the job; here the lock is
 *       held for the whole load.</li>
 *   <li>LOADCUSTR's private BASE36ADD counter, which never updated CUSTNEXT, so later interactive adds
 *       could collide with loaded ids [5250_Subfile/LOADCUSTR.SQLRPGLE:133-137]. Here the load restarts
 *       the one sequence every writer shares, inside its own transaction.</li>
 * </ul>
 *
 * <p><b>Steps of {@link #load(Plan)}</b>, in this order and all on the one connection bound to the load
 * transaction:
 * <ol>
 *   <li>{@code SET LOCAL lock_timeout = '5s'} ({@link #LOCK_TIMEOUT}), the {@code WAIT(5)} of the
 *       source;</li>
 *   <li>{@link CustomerIdAllocator#lockForLoad()}: the load guard, taken before any sequence access
 *       (table lock first, sequence second, in every writer);</li>
 *   <li>{@code TRUNCATE custmast}, then {@link LoadCheckpoints#afterTruncate()};</li>
 *   <li>{@code COPY custmast (...) FROM STDIN} through {@link CustomerCopyWriter}, streaming the rows of
 *       {@link Plan#generator()} lazily, then a check that the server copied exactly
 *       {@link Plan#count()} rows, then {@link LoadCheckpoints#afterCopy()};</li>
 *   <li>{@link CustomerIdAllocator#restartAfterLoad(int)} with {@link Plan#nextOrdinal()}, the last
 *       statement before {@code COMMIT}, so its value comes from the rows actually written; then
 *       {@link LoadCheckpoints#afterSequenceRestart()};</li>
 *   <li>{@code COMMIT}, by Spring when the method returns.</li>
 * </ol>
 *
 * <p><b>Atomicity.</b> Any exception after the truncate (from the COPY, the row-count check, the
 * restart, a checkpoint or the commit) rolls the transaction back. PostgreSQL's {@code TRUNCATE} and
 * {@code ALTER SEQUENCE ... RESTART} are both transactional, so the previous rows and the previous next
 * id return together and a later add cannot collide with a restored row. The method itself never calls
 * {@code nextval}, {@code setval} or {@code ALTER SEQUENCE}: every sequence access goes through
 * {@link CustomerIdAllocator}. A load whose last id is {@code 9999} ({@code nextOrdinal()} =
 * {@value CustomerIdAllocator#EXHAUSTED_NEXT_ORDINAL}) leaves the sequence exhausted through the
 * allocator's exhausted branch, so the next add fails with 503 APP0503.
 *
 * <p><b>Concurrency.</b> The {@code ACCESS EXCLUSIVE} lock waits for every add that holds the allocation
 * guard ({@code ROW EXCLUSIVE}), so no allocated but uninserted id can be outstanding while the table is
 * replaced. Once granted, it blocks every search, read, add and update of {@code custmast} until the
 * load commits or rolls back. That wait is expected and documented in the README. When the lock is not
 * granted within 5 seconds, PostgreSQL raises SQLSTATE {@code 55P03}; the allocator reports it as
 * {@link org.springframework.dao.CannotAcquireLockException}, which propagates unchanged, and the
 * generator runner prints "Cannot allocate CUSTMAST" and exits with status 1.
 *
 * <p><b>Not done here.</b> No {@code SELECT ... FOR UPDATE}, and no {@code ANALYZE}: the runner analyzes
 * the table after the commit, outside any transaction. The capacity check and option handling also
 * belong to the runner; {@link Plan} repeats the capacity check defensively.
 *
 * <p><b>Profiles.</b> The bean carries no {@code @Profile} and does not depend on
 * {@code GeneratorProperties}, so integration tests use it under profile {@code test} as the generator
 * does under profile {@code generator}.
 *
 * <p>Example, as the generator runner uses it after its capacity check:
 * <pre>{@code
 * CustomerDataGenerator generator = new CustomerDataGenerator(
 *         NameGenerator.seeded(7), cszSource.load("classpath:generator/csz-sample.csv"),
 *         OffsetDateTime.now(clock).truncatedTo(ChronoUnit.MICROS));
 * CustomerLoader.Plan plan = new CustomerLoader.Plan(CustomerId.parse("1001"), 300, generator);
 * long rows = customerLoader.load(plan); // 300; the next add receives plan.nextOrdinal()
 * }</pre>
 *
 * <p>The bean holds no state beyond its collaborators, so concurrent calls are safe; the table lock
 * serializes them, and a second load waits at most 5 seconds for the first.
 */
@Component
public class CustomerLoader {

    /**
     * Lock wait of a load before it gives up, mirroring {@code WAIT(5)} of the source's {@code ALCOBJ}
     * [5250_Subfile/LOADCUST2.CLLE:10-11]. It is fixed, independent of
     * {@code customer-master.db.lock-timeout}, which governs adds and updates.
     */
    public static final String LOCK_TIMEOUT = "5s";

    /** Sets the lock wait for this transaction only; built from {@link #LOCK_TIMEOUT}, never caller text. */
    static final String SET_LOCK_TIMEOUT_SQL = "SET LOCAL lock_timeout = '" + LOCK_TIMEOUT + "'";

    /**
     * Empties the table inside the load transaction, replacing {@code truncate lennons1.custmast}
     * [5250_Subfile/LOADCUSTR.SQLRPGLE:95]. The table name is unqualified; the connection's
     * {@code currentSchema} ({@code DB_SCHEMA}) resolves it.
     */
    static final String TRUNCATE_SQL = "TRUNCATE custmast";

    /** Task name given to Spring's exception translator for a failed {@code COPY}; server-side logs only. */
    static final String COPY_TASK = "COPY custmast";

    /** Operational log: one INFO line per load and DEBUG step lines; never customer data. */
    private static final Logger LOG = LoggerFactory.getLogger(CustomerLoader.class);

    /** Runs the session statements on the connection bound to the load transaction. */
    private final JdbcTemplate jdbcTemplate;

    /** The datasource behind {@link #jdbcTemplate}, from which the bound connection is fetched for COPY. */
    private final DataSource dataSource;

    /** Owns the load guard and the sequence restart. */
    private final CustomerIdAllocator allocator;

    /** Streams the generated rows with {@code COPY ... FROM STDIN}. */
    private final CustomerCopyWriter copyWriter;

    /** Step-boundary callbacks; a no-op in production, a pause or failure in tests. */
    private final LoadCheckpoints checkpoints;

    /**
     * Creates the loader.
     *
     * @param jdbcTemplate the application's template, whose connections take part in the Spring-managed
     *                     transaction and resolve unqualified names to the {@code DB_SCHEMA} schema
     * @param dataSource   the same datasource the template and the transaction manager use, so
     *                     {@link DataSourceUtils#getConnection(DataSource)} returns the transaction's
     *                     connection
     * @param allocator    the owner of the load guard and the sequence restart
     * @param copyWriter   the {@code COPY} streamer
     * @param checkpoints  the step-boundary callbacks
     * @throws NullPointerException if any argument is {@code null}
     */
    public CustomerLoader(JdbcTemplate jdbcTemplate, DataSource dataSource, CustomerIdAllocator allocator,
            CustomerCopyWriter copyWriter, LoadCheckpoints checkpoints) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate");
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.allocator = Objects.requireNonNull(allocator, "allocator");
        this.copyWriter = Objects.requireNonNull(copyWriter, "copyWriter");
        this.checkpoints = Objects.requireNonNull(checkpoints, "checkpoints");
    }

    /**
     * Replaces every row of {@code custmast} with the plan's generated customers and restarts the id
     * sequence at {@link Plan#nextOrdinal()}, in one transaction.
     *
     * <p>The steps and their order are those of the class description. When the method returns, the
     * transaction commits: the new rows become visible, the table lock is released and the next add
     * receives {@link Plan#nextOrdinal()}, or 503 APP0503 when the load ended at {@code 9999}. When it
     * throws, the transaction rolls back and the table, the sequence and the next id are exactly as
     * they were before the call.
     *
     * @param plan the first id, the row count and the generator of the rows
     * @return the number of rows the server copied, always {@link Plan#count()}
     * @throws NullPointerException                if {@code plan} is {@code null}; nothing has run
     * @throws IllegalTransactionStateException    if the method runs without a Spring-managed
     *                                             transaction, for example on an instance that is not
     *                                             the Spring proxy; nothing has run
     * @throws org.springframework.dao.CannotAcquireLockException if {@code custmast} cannot be locked
     *                                             within {@value #LOCK_TIMEOUT}, because an add, an
     *                                             update or another load holds it; nothing has changed
     * @throws DataAccessException                 if a statement or the {@code COPY} fails, for example
     *                                             on a constraint violation; rolled back
     * @throws IllegalStateException               if the server copied a different number of rows than
     *                                             the plan holds, or the allocator's exhausted branch
     *                                             fails its own check; rolled back
     * @throws RuntimeException                    any exception a {@link LoadCheckpoints} method or the
     *                                             generator throws, unchanged; rolled back
     */
    @Transactional(rollbackFor = Exception.class)
    public long load(Plan plan) {
        Objects.requireNonNull(plan, "plan");
        // Guard against running outside a transaction (an unproxied instance): SET LOCAL and the table
        // lock would then end with each statement, and TRUNCATE would commit on its own.
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalTransactionStateException(
                    "CustomerLoader.load requires a Spring-managed transaction; call it through the bean");
        }

        // 1. The WAIT(5) of ALCOBJ: bound the lock wait of this transaction only.
        jdbcTemplate.execute(SET_LOCK_TIMEOUT_SQL);

        // 2. The load guard, before any sequence access. A CannotAcquireLockException propagates as is.
        allocator.lockForLoad();

        // 3. Empty the table inside the transaction.
        jdbcTemplate.execute(TRUNCATE_SQL);
        LOG.debug("generator.load truncated custmast");
        checkpoints.afterTruncate();

        // 4. Stream the generated rows and confirm the server took every one.
        long rows = copyRows(plan);
        if (rows != plan.count()) {
            throw new IllegalStateException("COPY custmast reported " + rows + " rows, but the load plan"
                    + " holds " + plan.count() + "; the load is rolled back");
        }
        LOG.debug("generator.load copied rows={}", rows);
        checkpoints.afterCopy();

        // 5. Last statement before COMMIT: the next add continues after the rows actually written.
        allocator.restartAfterLoad(plan.nextOrdinal());
        checkpoints.afterSequenceRestart();

        // 6. Spring commits when this method returns.
        LOG.info("generator.load start={} last={} rows={}", plan.start(), plan.last(), rows);
        return rows;
    }

    /**
     * Runs the {@code COPY} on the connection bound to the load transaction.
     *
     * <p>The connection is the one the transaction manager bound to this thread, so the rows commit or
     * roll back with the truncate and the restart. It is neither closed nor released here: the
     * transaction manager returns it to the pool when the transaction ends.
     *
     * @param plan the load plan
     * @return the row count the server reported
     * @throws DataAccessException if the connection cannot be unwrapped to {@link PGConnection} or the
     *                             {@code COPY} fails, translated by the template's exception translator,
     *                             or {@link UncategorizedSQLException} when it has no category
     */
    private long copyRows(Plan plan) {
        Connection connection = DataSourceUtils.getConnection(dataSource);
        try {
            PGConnection pgConnection = connection.unwrap(PGConnection.class);
            return copyWriter.copy(pgConnection, plan.generator().generate(plan.start(), plan.count()));
        } catch (SQLException e) {
            throw translate(e);
        }
    }

    /**
     * Turns a {@link SQLException} from the {@code COPY} into an unchecked {@link DataAccessException}
     * the same way {@link JdbcTemplate} does for its own statements.
     *
     * @param failure the exception the {@code COPY} raised
     * @return the translated exception, or an {@link UncategorizedSQLException} when the translator
     *         returns none
     */
    private DataAccessException translate(SQLException failure) {
        DataAccessException translated = jdbcTemplate.getExceptionTranslator()
                .translate(COPY_TASK, CustomerCopyWriter.COPY_SQL, failure);
        return translated != null
                ? translated
                : new UncategorizedSQLException(COPY_TASK, CustomerCopyWriter.COPY_SQL, failure);
    }

    /**
     * What one load writes: {@code count} generated rows with consecutive ids from {@code start}.
     *
     * <p>The runner checks capacity before any write and prints its own message; the compact constructor
     * repeats that check so that no plan can ask for an id beyond {@code 9999}.
     *
     * @param start     the id of the first row; {@code 1001} by default, as LOADCUSTR's
     *                  {@code varCUSTID = '1001'} [5250_Subfile/LOADCUSTR.SQLRPGLE:133]
     * @param count     the number of rows, LOADCUST2's {@code &NUM} [5250_Subfile/LOADCUST2.CLLE:5-6];
     *                  at least 1, and at most {@link CustomerId#CAPACITY} minus the start ordinal
     * @param generator the source of the rows; one load consumes it
     */
    public record Plan(CustomerId start, int count, CustomerDataGenerator generator) {

        /**
         * Validates the plan.
         *
         * @throws IllegalArgumentException if {@code start} or {@code generator} is {@code null},
         *                                  {@code count} is less than 1, or the last id would lie beyond
         *                                  {@code 9999} (start ordinal + count &gt;
         *                                  {@link CustomerId#CAPACITY})
         */
        public Plan {
            if (start == null) {
                throw new IllegalArgumentException("load plan start id must not be null");
            }
            if (generator == null) {
                throw new IllegalArgumentException("load plan generator must not be null");
            }
            if (count < 1) {
                throw new IllegalArgumentException("load plan count must be at least 1, was " + count);
            }
            if (start.toOrdinal() + (long) count > CustomerId.CAPACITY) {
                throw new IllegalArgumentException("cannot load " + count + " customers from "
                        + start.value() + ": only " + (CustomerId.CAPACITY - start.toOrdinal())
                        + " ids remain through 9999");
            }
        }

        /**
         * Returns the id of the last row.
         *
         * @return the id at ordinal {@code start.toOrdinal() + count - 1}; {@code 9999} at most
         */
        public CustomerId last() {
            return CustomerId.fromOrdinal(start.toOrdinal() + count - 1);
        }

        /**
         * Returns the ordinal the next add receives after this load commits.
         *
         * @return {@code start.toOrdinal() + count}, from 1 to {@link CustomerId#CAPACITY}; the value
         *         {@link CustomerId#CAPACITY} means the load ends at {@code 9999} and leaves no id
         */
        public int nextOrdinal() {
            return start.toOrdinal() + count;
        }
    }
}
