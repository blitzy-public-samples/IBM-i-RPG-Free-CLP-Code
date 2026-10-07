package com.democorp.customermaster.repository;

import com.democorp.customermaster.domain.CustomerId;
import com.democorp.customermaster.service.exception.CustomerIdExhaustedException;
import java.sql.SQLException;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.Objects;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.jdbc.core.namedparam.EmptySqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Allocates customer ids from the sequence {@code custmast_id_seq} and owns the allocation/load guard:
 * the one place where customer ids are handed out or reset.
 *
 * <p><b>What it replaces.</b>
 * <ul>
 *   <li>The CUSTNEXT data area. CRTDTAARA creates it with {@code VALUE('EEEE')}
 *       [5250_Subfile/CRTDTAARA.clle:1-9]. AddRecd reads it {@code in *LOCK}, advances it with
 *       BASE36ADD, writes it back with {@code out} and uses the advanced value as the new CUSTID
 *       [5250_Subfile/MTNCUSTR.SQLRPGLE:548-555]. The first interactive id is therefore {@code EEEF},
 *       the {@code START WITH 191957} of migration V4. Here {@link #next()} takes the table guard and
 *       calls {@code nextval}, which is atomic. No object lock is held beyond the add transaction, and a
 *       rolled-back add only leaves a gap, as the source also consumes the key before an insert that can
 *       fail [5250_Subfile/MTNCUSTR.SQLRPGLE:556-565].</li>
 *   <li>LOADCUSTR's own counter. LOADCUSTR truncates CUSTMAST and numbers its rows from {@code 1001}
 *       through a separate BASE36ADD chain that never touches CUSTNEXT
 *       [5250_Subfile/LOADCUSTR.SQLRPGLE:95,133-137]. With two counters, an interactive add fails as a
 *       duplicate once a load has issued the id CUSTNEXT hands out next. This is a corrected defect: the
 *       generator and the API share this one sequence. {@code CustomerLoader} replaces the table under
 *       {@link #lockForLoad()} and, in the same transaction, moves the sequence past the rows it wrote
 *       through {@link #restartAfterLoad(int)}.</li>
 *   <li>The {@code ALCOBJ ... WAIT(5)} guard of LOADCUST2 [5250_Subfile/LOADCUST2.CLLE:10-18]. It becomes
 *       the {@code ACCESS EXCLUSIVE} table lock of {@link #lockForLoad()}. The caller's
 *       {@code lock_timeout} provides the bounded wait.</li>
 *   <li>BASE36ADD's silent rollover from {@code 9999} to {@code AAAA}, which leaves the limit check to
 *       its caller [BASE36/SRV_BASE36.RPGLE:9-13]. The sequence is {@code NO CYCLE}: past
 *       {@code MAXVALUE 1679615} ({@code 9999}) {@code nextval} raises SQLSTATE {@code 2200H}, and
 *       {@link #next()} turns that into {@link CustomerIdExhaustedException} (HTTP 503 APP0503), so no
 *       id is reissued by wrap-around. Only a committed load resets the range, through
 *       {@link #restartAfterLoad(int)}, after it has replaced every earlier row.</li>
 * </ul>
 *
 * <p><b>The guard.</b> Every writer that allocates or resets ids first takes a table lock on
 * {@code custmast} in its own transaction. An add takes {@code ROW EXCLUSIVE} in {@link #next()}, and a
 * load takes {@code ACCESS EXCLUSIVE} in {@link #lockForLoad()} before its {@code TRUNCATE}. The two
 * modes conflict, so a load cannot replace the table while an add holds an allocated id it has not
 * inserted yet, and an add cannot allocate while a load is running. {@code ROW EXCLUSIVE} does not
 * conflict with itself, so adds run concurrently with each other and with updates.
 *
 * <p><b>Lock-order invariant.</b> The table lock comes first and the sequence second, in every writer.
 * Nothing touches {@code custmast_id_seq} without first holding a lock on {@code custmast}. No
 * production code outside this class calls {@code nextval} or {@code ALTER SEQUENCE}, and nothing calls
 * {@code setval}. With a single order, adds and loads cannot deadlock.
 *
 * <p><b>Transactions.</b> Every public method requires an open transaction
 * ({@link Propagation#MANDATORY}). A call without one fails with
 * {@link org.springframework.transaction.IllegalTransactionStateException} before any SQL runs, so a
 * guard can never be taken and released in autocommit. Callers set {@code SET LOCAL lock_timeout} before
 * calling: {@code CustomerMaintenanceService.add} from {@code customer-master.db.lock-timeout}, and
 * {@code CustomerLoader.load} with {@code '5s'}. This class never sets it.
 *
 * <p><b>Lock-wait timeouts.</b> A lock wait that exceeds the caller's timeout fails with SQLSTATE
 * {@code 55P03}. Every statement of this class reports it as
 * {@link org.springframework.dao.CannotAcquireLockException}, with the original data-access exception,
 * and so the {@link SQLException} carrying {@code 55P03}, as its cause. The
 * {@link NamedParameterJdbcTemplate} translates failures through the
 * {@link org.springframework.jdbc.core.JdbcTemplate} it wraps, whose default translator (the
 * {@code SQLException} subclass translator with its SQLSTATE-class fallback) leaves {@code 55P03}
 * uncategorized, so the type is fixed here. {@code CustomerMaintenanceService} maps it to 409 DEM1001,
 * and the generator to "Cannot allocate CUSTMAST".
 *
 * <p><b>SQL.</b> All names are unqualified. The schema comes from the JDBC {@code currentSchema} and
 * Hikari's {@code schema} setting ({@code DB_SCHEMA}), so no statement names a library or schema. The
 * only value placed into SQL text is the range-checked {@code int} of {@link #restartAfterLoad(int)},
 * because {@code RESTART WITH} takes a literal. No caller text ever reaches a statement.
 *
 * <p>Example, inside the add transaction:
 * <pre>{@code
 * jdbcTemplate.execute("SET LOCAL lock_timeout = '5s'");
 * CustomerId id = allocator.next(); // EEEF on a fresh database
 * customerRepository.save(newCustomer(id, fields));
 * }</pre>
 *
 * <p>The class is stateless apart from its thread-safe {@link NamedParameterJdbcTemplate}, so one
 * instance serves all threads. It and its public methods stay non-final, so Spring's proxy can apply
 * {@link Transactional}.
 */
@Component
public class CustomerIdAllocator {

    /** Name of the customer id sequence created by migration V4. */
    static final String SEQUENCE = "custmast_id_seq";

    /**
     * The next ordinal that a load ending at {@code 9999} leaves: 1,679,616, one past
     * {@link CustomerId#MAX_ORDINAL}. {@link #restartAfterLoad(int)} accepts it and leaves the sequence
     * exhausted, because {@code RESTART WITH 1679616} exceeds {@code MAXVALUE} and fails.
     */
    public static final int EXHAUSTED_NEXT_ORDINAL = CustomerId.CAPACITY;

    /** SQLSTATE {@code sequence_generator_limit_exceeded}: {@code nextval} past {@code MAXVALUE}. */
    static final String SEQUENCE_LIMIT_EXCEEDED = "2200H";

    /** SQLSTATE {@code lock_not_available}: the caller's {@code lock_timeout} expired. */
    static final String LOCK_NOT_AVAILABLE = "55P03";

    /** The allocation guard of an add: conflicts with a load, not with other adds or updates. */
    private static final String LOCK_FOR_ALLOCATION_SQL = "LOCK TABLE custmast IN ROW EXCLUSIVE MODE";

    /** The guard of a load: conflicts with every other access to {@code custmast}. */
    private static final String LOCK_FOR_LOAD_SQL = "LOCK TABLE custmast IN ACCESS EXCLUSIVE MODE";

    /** Draws the next ordinal; {@code nextval} returns {@code bigint}. */
    private static final String NEXTVAL_SQL = "SELECT nextval('" + SEQUENCE + "')";

    /** Transactional reset; the ordinal literal is appended by {@link #restartSql(int)} only. */
    private static final String RESTART_SQL_PREFIX = "ALTER SEQUENCE " + SEQUENCE + " RESTART WITH ";

    /** Operational log: allocations at DEBUG, restarts at INFO, exhaustion at WARN; never customer data. */
    private static final Logger log = LoggerFactory.getLogger(CustomerIdAllocator.class);

    /** Runs every statement on the connection bound to the caller's transaction. */
    private final NamedParameterJdbcTemplate namedJdbc;

    /**
     * Creates the allocator over the application's datasource.
     *
     * @param namedJdbc the named-parameter template Spring Boot configures on that datasource; its
     *                  connections take part in the caller's Spring-managed transaction and resolve
     *                  unqualified names to the {@code DB_SCHEMA} schema
     * @throws NullPointerException if {@code namedJdbc} is {@code null}
     */
    public CustomerIdAllocator(NamedParameterJdbcTemplate namedJdbc) {
        this.namedJdbc = Objects.requireNonNull(namedJdbc, "namedJdbc");
    }

    /**
     * Allocates the next customer id for an add: AddRecd's {@code in *LOCK Cust_Next},
     * {@code BASE36ADD} and {@code out Cust_Next} [5250_Subfile/MTNCUSTR.SQLRPGLE:550-555].
     *
     * <p>Runs {@code LOCK TABLE custmast IN ROW EXCLUSIVE MODE}, the allocation guard, and then
     * {@code SELECT nextval('custmast_id_seq')}, and converts the ordinal with
     * {@link CustomerId#fromOrdinal(int)}. The lock lasts until the caller's transaction ends, so the
     * caller inserts the row with the returned id in the same transaction. The guard waits while a load
     * holds {@code ACCESS EXCLUSIVE}, and does not wait for other adds or updates.
     *
     * <p>Between committed loads, ids strictly increase in allocation order, because V4 declares
     * {@code CACHE 1}, and none is handed out twice, whether or not the caller commits; a rollback
     * leaves a gap. A committed load replaces every row and restarts the sequence at its start + count
     * through {@link #restartAfterLoad(int)}, or leaves it exhausted when its last id is {@code 9999}.
     * That restart can move the sequence back, so an id issued before the load can be issued again; it
     * cannot collide, because no row from before the load remains.
     *
     * @return the allocated id; {@code EEEF} on a fresh database
     * @throws CustomerIdExhaustedException if the sequence has passed {@code 9999} (SQLSTATE
     *                                      {@code 2200H}), so no id is left; the database error is the
     *                                      cause, and the caller's transaction is aborted and must roll
     *                                      back
     * @throws CannotAcquireLockException   if the guard is not granted within the caller's
     *                                      {@code lock_timeout} (SQLSTATE {@code 55P03}), because a load
     *                                      holds the table; the original exception is the cause
     * @throws org.springframework.transaction.IllegalTransactionStateException if no transaction is
     *                                      open
     * @throws DataAccessException          for any other database error, propagated unchanged
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public CustomerId next() {
        execute(LOCK_FOR_ALLOCATION_SQL);
        long ordinal = drawNextval();
        CustomerId id = CustomerId.fromOrdinal(Math.toIntExact(ordinal));
        log.debug("Allocated customer id {} (ordinal {})", id, ordinal);
        return id;
    }

    /**
     * Takes the load guard: {@code LOCK TABLE custmast IN ACCESS EXCLUSIVE MODE}, which replaces
     * LOADCUST2's {@code ALCOBJ OBJ((CUSTMAST *FILE *EXCLRD)) WAIT(5)} [5250_Subfile/LOADCUST2.CLLE:10-18].
     *
     * <p>{@code CustomerLoader.load} calls this first in its transaction, after
     * {@code SET LOCAL lock_timeout '5s'} and before {@code TRUNCATE custmast}. The lock waits for every
     * add that holds the allocation guard, so no allocated id can be outstanding while the table is
     * replaced. Until the load commits or rolls back, it blocks every other access to {@code custmast}:
     * adds, updates, searches and reads.
     *
     * @throws CannotAcquireLockException if the lock is not granted within the caller's
     *         {@code lock_timeout} (SQLSTATE {@code 55P03}), because an add or another load holds the
     *         table; the original exception is the cause, and the generator reports "Cannot allocate
     *         CUSTMAST"
     * @throws org.springframework.transaction.IllegalTransactionStateException if no transaction is open
     * @throws DataAccessException for any other database error, propagated unchanged
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void lockForLoad() {
        execute(LOCK_FOR_LOAD_SQL);
        log.debug("Locked custmast in ACCESS EXCLUSIVE mode for a load");
    }

    /**
     * Moves the sequence so that the next add receives {@code nextOrdinal}, the ordinal after the last
     * row a load wrote: {@code start + count}. The restart ignores the sequence's current position, so
     * it can move the sequence back below ids issued before the load, which later adds then receive
     * again; they cannot collide, because the load has replaced every earlier row.
     *
     * <p><b>Caller duties.</b> The caller must already hold {@link #lockForLoad()} in the same
     * transaction, and must call this as the last statement before {@code COMMIT}, after the
     * {@code COPY}, so the value comes from the rows actually written.
     *
     * <p><b>Atomicity.</b> The reset is {@code ALTER SEQUENCE custmast_id_seq RESTART WITH n}, which
     * PostgreSQL applies transactionally and which blocks concurrent {@code nextval} until the
     * transaction ends. If anything fails before the commit, the rows and the sequence both return to
     * their pre-load state, so later adds cannot collide with the restored rows. {@code setval} is not
     * used, because it is never rolled back.
     *
     * <p><b>Exhausted branch.</b> A load whose last id is {@code 9999} passes
     * {@value #EXHAUSTED_NEXT_ORDINAL}, which {@code RESTART WITH} cannot take: it exceeds
     * {@code MAXVALUE 1679615} and fails with SQLSTATE {@code 22023}, rolling the valid load back. The
     * sequence is therefore restarted at {@link CustomerId#MAX_ORDINAL} and that last value is consumed
     * with one {@code nextval} in the same transaction. After {@code COMMIT} the next add's
     * {@code nextval} raises {@code 2200H}, which {@link #next()} turns into
     * {@link CustomerIdExhaustedException} (503 APP0503). After a rollback the previous next value
     * returns.
     *
     * @param nextOrdinal the ordinal the next add receives, from {@link CustomerId#MIN_ORDINAL} to
     *                    {@value #EXHAUSTED_NEXT_ORDINAL}; {@value #EXHAUSTED_NEXT_ORDINAL} leaves the
     *                    sequence exhausted
     * @throws IllegalArgumentException if {@code nextOrdinal} is outside that range; the generator's
     *                                  capacity check normally rejects such a load before any write
     * @throws org.springframework.transaction.IllegalTransactionStateException if no transaction is open
     * @throws IllegalStateException    if the exhausted branch does not consume the last value; the load
     *                                  must roll back
     * @throws CannotAcquireLockException if a statement waits longer than the caller's
     *                                  {@code lock_timeout} (SQLSTATE {@code 55P03}); the load must roll
     *                                  back
     * @throws DataAccessException      if a statement fails otherwise, propagated unchanged; the load
     *                                  must roll back
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void restartAfterLoad(int nextOrdinal) {
        if (nextOrdinal < CustomerId.MIN_ORDINAL || nextOrdinal > EXHAUSTED_NEXT_ORDINAL) {
            throw new IllegalArgumentException("next customer id ordinal must be " + CustomerId.MIN_ORDINAL
                    + ".." + EXHAUSTED_NEXT_ORDINAL + ", was " + nextOrdinal);
        }
        if (nextOrdinal < EXHAUSTED_NEXT_ORDINAL) {
            execute(restartSql(nextOrdinal));
            log.info("Customer id sequence {} restarted: the next add receives {}",
                    SEQUENCE, CustomerId.fromOrdinal(nextOrdinal));
            return;
        }
        // The load ended at 9999. RESTART WITH creates new sequence storage inside this transaction;
        // drawing its only remaining value leaves that storage at MAXVALUE with is_called = true. COMMIT
        // therefore leaves the sequence exhausted, so the next nextval raises 2200H, while ROLLBACK
        // discards the new storage together with the restart and the previous next value returns.
        execute(restartSql(CustomerId.MAX_ORDINAL));
        long consumed = drawNextval();
        if (consumed != CustomerId.MAX_ORDINAL) {
            throw new IllegalStateException("Expected " + NEXTVAL_SQL + " to consume the last ordinal "
                    + CustomerId.MAX_ORDINAL + " after the restart, but it returned " + consumed);
        }
        log.warn("Customer id sequence {} left exhausted: the load ended at 9999, so adds fail until"
                + " a new load starts lower", SEQUENCE);
    }

    /**
     * Runs one statement that returns no rows: a {@code LOCK TABLE} or an {@code ALTER SEQUENCE}.
     *
     * @param sql the statement, built only from this class's constants
     * @throws CannotAcquireLockException if the statement waits longer than the caller's
     *                                    {@code lock_timeout}
     * @throws DataAccessException        for any other database error, unchanged
     */
    private void execute(String sql) {
        try {
            namedJdbc.getJdbcOperations().execute(sql);
        } catch (DataAccessException e) {
            throw lockWaitOrSelf(e, sql);
        }
    }

    /**
     * Draws the next value of the sequence. Callers already hold a lock on {@code custmast}, so this
     * statement does not wait for a load.
     *
     * @return the value {@code nextval} returned, an {@code integer} sequence value widened to
     *         {@code long}
     * @throws CustomerIdExhaustedException if the sequence has passed {@code MAXVALUE} (SQLSTATE
     *                                      {@code 2200H})
     * @throws CannotAcquireLockException   if the statement waits longer than the caller's
     *                                      {@code lock_timeout}
     * @throws DataAccessException          for any other database error, unchanged
     * @throws IllegalStateException        if the database returns no value
     */
    private long drawNextval() {
        Long value;
        try {
            value = namedJdbc.queryForObject(NEXTVAL_SQL, EmptySqlParameterSource.INSTANCE, Long.class);
        } catch (DataAccessException e) {
            // Spring's fallback translator maps SQL class 22 to DataIntegrityViolationException, the
            // same type as unrelated data errors, so exhaustion is recognized by its SQLSTATE alone.
            if (hasSqlState(e, SEQUENCE_LIMIT_EXCEEDED)) {
                log.warn("Customer id sequence {} is exhausted: no id is left after 9999", SEQUENCE);
                throw new CustomerIdExhaustedException(e);
            }
            throw lockWaitOrSelf(e, NEXTVAL_SQL);
        }
        if (value == null) {
            throw new IllegalStateException(NEXTVAL_SQL + " returned no value");
        }
        return value;
    }

    /**
     * Gives a lock-wait timeout one stable type. Decision: a lock-wait failure is wrapped instead of
     * passed on as raised, because the {@link NamedParameterJdbcTemplate} translates through the
     * {@link org.springframework.jdbc.core.JdbcTemplate} it wraps, whose default translator leaves
     * SQLSTATE {@code 55P03} as an {@code UncategorizedSQLException}, while callers of the add
     * (409 DEM1001) and of the load ("Cannot allocate CUSTMAST") expect a
     * {@link CannotAcquireLockException}. The original exception stays the cause, so a consumer that
     * looks for {@code 55P03} in the cause chain still finds it; an exception that is already a
     * {@link PessimisticLockingFailureException} passes through unchanged.
     *
     * @param failure the exception the statement raised
     * @param sql     the statement, named in the message for server-side logs only
     * @return a {@link CannotAcquireLockException} wrapping {@code failure} when it carries
     *         {@code 55P03} and is not already a pessimistic-locking failure; otherwise {@code failure}
     */
    private static DataAccessException lockWaitOrSelf(DataAccessException failure, String sql) {
        if (failure instanceof PessimisticLockingFailureException
                || !hasSqlState(failure, LOCK_NOT_AVAILABLE)) {
            return failure;
        }
        return new CannotAcquireLockException("lock_timeout expired for [" + sql + "]", failure);
    }

    /**
     * Builds the restart statement. {@code RESTART WITH} takes a literal, not a bind parameter, so the
     * ordinal is formatted into the text; it is an {@code int} already range-checked by the caller, so
     * no other text can reach the statement.
     *
     * @param ordinal the restart value, from {@link CustomerId#MIN_ORDINAL} to
     *                {@link CustomerId#MAX_ORDINAL}
     * @return {@code ALTER SEQUENCE custmast_id_seq RESTART WITH <ordinal>}
     */
    private static String restartSql(int ordinal) {
        return RESTART_SQL_PREFIX + Integer.toString(ordinal);
    }

    /**
     * Reports whether any {@link SQLException} reachable from {@code failure} carries {@code sqlState}.
     * It follows {@link Throwable#getCause()} and {@link SQLException#getNextException()} and visits
     * each throwable once, so a cyclic chain cannot loop.
     *
     * @param failure  the exception to inspect; {@code null} yields {@code false}
     * @param sqlState the five-character SQLSTATE to look for
     * @return {@code true} if a reachable {@code SQLException} reports {@code sqlState}
     */
    private static boolean hasSqlState(Throwable failure, String sqlState) {
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        Deque<Throwable> pending = new ArrayDeque<>();
        if (failure != null) {
            pending.push(failure);
        }
        while (!pending.isEmpty()) {
            Throwable current = pending.pop();
            if (!visited.add(current)) {
                continue;
            }
            if (current instanceof SQLException sql) {
                if (sqlState.equals(sql.getSQLState())) {
                    return true;
                }
                SQLException nextException = sql.getNextException();
                if (nextException != null) {
                    pending.push(nextException);
                }
            }
            Throwable cause = current.getCause();
            if (cause != null) {
                pending.push(cause);
            }
        }
        return false;
    }
}
