package com.democorp.customermaster.repository;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import com.democorp.customermaster.domain.Address;
import com.democorp.customermaster.domain.Customer;
import com.democorp.customermaster.domain.CustomerId;
import com.democorp.customermaster.generator.CszSource;
import com.democorp.customermaster.generator.CustomerDataGenerator;
import com.democorp.customermaster.generator.CustomerLoader;
import com.democorp.customermaster.generator.LoadCheckpoints;
import com.democorp.customermaster.generator.NameGenerator;
import com.democorp.customermaster.service.CustomerMaintenanceService;
import com.democorp.customermaster.service.exception.CustomerIdExhaustedException;
import com.democorp.customermaster.support.AbstractPostgresIT;
import com.democorp.customermaster.support.DatabaseCleaner;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Proves the customer id allocation protocol and its load guard under real concurrency on PostgreSQL:
 * parallel adds never share an id, an add holding an allocated id blocks a load, a running load blocks
 * allocation until it commits, and an exhausted sequence is reported as
 * {@link CustomerIdExhaustedException}.
 *
 * <p><b>Source behaviour.</b> AddRecd reads the CUSTNEXT data area {@code in *LOCK}, advances it with
 * BASE36ADD, writes it back with {@code out} and inserts the row under the advanced value
 * [5250_Subfile/MTNCUSTR.SQLRPGLE:548-566]; CRTDTAARA creates CUSTNEXT as {@code EEEE}
 * [5250_Subfile/CRTDTAARA.clle:1-9], so the first interactive id is {@code EEEF}. BASE36ADD rolls
 * {@code 9999} over to {@code AAAA} and leaves the limit to its caller [BASE36/SRV_BASE36.RPGLE:9-13].
 * LOADCUSTR truncates CUSTMAST and numbers its rows from {@code 1001} through its own BASE36ADD chain
 * [5250_Subfile/LOADCUSTR.SQLRPGLE:95,133-137], submitted by LOADCUST2 only after
 * {@code ALCOBJ ... WAIT(5)} [5250_Subfile/LOADCUST2.CLLE:10-19].
 *
 * <p><b>Target protocol under test</b> ({@link CustomerIdAllocator}, {@link CustomerLoader}):
 * <ul>
 *   <li>An add runs {@code LOCK TABLE custmast IN ROW EXCLUSIVE MODE}, then
 *       {@code nextval('custmast_id_seq')}, then its {@code INSERT}, in one transaction.</li>
 *   <li>A load runs {@code SET LOCAL lock_timeout = '5s'},
 *       {@code LOCK TABLE custmast IN ACCESS EXCLUSIVE MODE}, {@code TRUNCATE}, {@code COPY} and
 *       {@code ALTER SEQUENCE custmast_id_seq RESTART WITH start + count}, in one transaction.</li>
 *   <li>The two table-lock modes conflict, and every writer takes the table lock before touching the
 *       sequence. V4 declares {@code START WITH 191957} ({@code EEEF}), {@code MAXVALUE 1679615}
 *       ({@code 9999}), {@code CACHE 1} and {@code NO CYCLE}.</li>
 * </ul>
 *
 * <p><b>Proof of blocking.</b> No test infers a wait from elapsed time. A waiting session is observed in
 * {@code pg_stat_activity} ({@code wait_event_type = 'Lock'} on the exact {@code LOCK TABLE} statement
 * of the allocator) by {@link #awaitLockWait(String)}, and the holder is released as soon as the wait is
 * seen, well inside the loader's 5-second and the add's lock timeouts. Pauses inside a transaction use
 * {@link CountDownLatch}es with bounded waits; every latch is released and every worker thread ended in
 * {@link #releaseWorkersAndCheckNoLockSurvives()}, which then proves that no lock on {@code custmast}
 * and no open transaction outlives the test, so {@code DatabaseCleaner.clean()} of the next test never
 * blocks.
 *
 * <p><b>Context variant.</b> The one bean override is {@code @MockitoBean LoadCheckpoints}, by type and
 * with default settings; this class declares no other override, property source, import, profile or
 * {@code @SpringBootTest} attribute beyond {@link AbstractPostgresIT}. {@code generator/CustomerGeneratorIT}
 * declares the identical single override, so Spring's context cache serves both classes from one
 * context (variant 2 of the suite's context policy in {@link AbstractPostgresIT}). The mock replaces the
 * production {@code LoadCheckpoints.NoOp} bean; unstubbed calls do nothing, because Mockito does not run
 * interface default methods, and the mock is reset after each test, so no stub leaks into the next.
 *
 * <p><b>Fixtures.</b> Test databases hold no seed rows. {@link AbstractPostgresIT#cleanDatabase()} leaves
 * {@code custmast} empty and the next {@code nextval} at {@value DatabaseCleaner#SEQUENCE_START}
 * ({@value DatabaseCleaner#FIRST_INTERACTIVE_ID}) before each test. The allocator's methods require an
 * open transaction, so every direct call runs inside {@link #transactionTemplate}. Worker threads that
 * call {@link CustomerMaintenanceService#add(Customer)} bind their own {@link SecurityContext}, because
 * {@code CurrentUser} reads the change-stamp user from the calling thread.
 */
@DisplayName("Customer id allocation and the load guard under concurrency")
class CustomerIdAllocationIT extends AbstractPostgresIT {

    private static final int ADD_THREADS = 16;

    private static final int ADDS_PER_THREAD = 50;

    private static final int LOAD_COUNT = 50;

    private static final long LOAD_SEED = 7L;

    /** Interval between two {@code pg_stat_activity} probes of {@link #awaitLockWait(String)}. */
    private static final Duration LOCK_PROBE_INTERVAL = Duration.ofMillis(25);

    /** Longest wait for a lock waiter to appear: well inside the loader's 5-second lock timeout. */
    private static final Duration LOCK_PROBE_DEADLINE = Duration.ofSeconds(3);

    private static final long RELEASE_TIMEOUT_SECONDS = 10;

    private static final long PARALLEL_ADDS_TIMEOUT_SECONDS = 60;

    private static final String ID_FORMAT = "^[A-Z0-9]{4}$";

    /** {@code pg_stat_activity} match for the load guard of {@code CustomerIdAllocator.lockForLoad()}. */
    private static final String ACCESS_EXCLUSIVE = "%ACCESS EXCLUSIVE%";

    /** {@code pg_stat_activity} match for the allocation guard of {@code CustomerIdAllocator.next()}. */
    private static final String ROW_EXCLUSIVE = "%ROW EXCLUSIVE%";

    /**
     * Counts other sessions of this database that wait for a lock on the allocator's
     * {@code LOCK TABLE custmast ...} statement in the mode given as the parameter.
     */
    private static final String LOCK_WAITERS_SQL = "SELECT count(*) FROM pg_stat_activity"
            + " WHERE datname = current_database() AND pid <> pg_backend_pid()"
            + " AND wait_event_type = 'Lock'"
            + " AND query ILIKE '%LOCK TABLE%custmast%' AND query ILIKE ?";

    /** Describes the other sessions of this database, for the message of a failed lock probe. */
    private static final String SESSIONS_SQL = "SELECT pid || ' ' || coalesce(state, '-') || ' '"
            + " || coalesce(wait_event_type, '-') || ' [' || left(coalesce(query, ''), 80) || ']'"
            + " FROM pg_stat_activity WHERE datname = current_database() AND pid <> pg_backend_pid()"
            + " ORDER BY pid";

    /**
     * Counts locks on {@code custmast}, granted or awaited, held by any other client session. Background
     * workers are excluded: autovacuum may briefly analyze the table after a test's inserts, which is no
     * lock a test left behind.
     */
    private static final String OTHER_CUSTMAST_LOCKS_SQL = "SELECT count(*) FROM pg_locks l"
            + " JOIN pg_class c ON c.oid = l.relation"
            + " JOIN pg_stat_activity a ON a.pid = l.pid"
            + " WHERE c.relname = 'custmast' AND l.pid <> pg_backend_pid()"
            + " AND a.backend_type = 'client backend'";

    /** Counts other sessions of this database left inside a transaction, aborted ones included. */
    private static final String IDLE_IN_TRANSACTION_SQL = "SELECT count(*) FROM pg_stat_activity"
            + " WHERE datname = current_database() AND pid <> pg_backend_pid()"
            + " AND state LIKE 'idle in transaction%'";

    /** Inserts one row with the full V3 column list, as an add's {@code INSERT} would. */
    private static final String INSERT_SQL = "INSERT INTO custmast (custid, name, addr, city, state, zip,"
            + " corpphone, acctmgr, acctphone, active, chgtime, chguser, row_version)"
            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

    private static final String ADDR = "1 MAIN ST";

    private static final String CITY = "SPRINGFIELD";

    /** State of every fixture customer; present in {@code states}. */
    private static final String STATE = "CA";

    private static final String ZIP = "90210";

    private static final String CORP_PHONE = "(415) 555-0100";

    private static final String ACCT_MGR = "JANE DOE";

    private static final String ACCT_PHONE = "(415) 555-0101";

    private static final String ACTIVE = "Y";

    private static final String SYSTEM_USER = CustomerDataGenerator.SYSTEM_USER;

    /**
     * The suite's shared {@code LoadCheckpoints} override: replaces the no-op production bean, so a test
     * can pause a load at a step boundary while it holds its {@code ACCESS EXCLUSIVE} lock.
     */
    @MockitoBean
    private LoadCheckpoints loadCheckpoints;

    /** The allocator under test, through its transactional proxy. */
    @Autowired
    private CustomerIdAllocator allocator;

    /** The generator's loader, through its transactional proxy. */
    @Autowired
    private CustomerLoader customerLoader;

    @Autowired
    private CustomerMaintenanceService maintenanceService;

    @Autowired
    private Clock clock;

    /** Latches that park worker threads; all are counted down after each test. */
    private final List<CountDownLatch> releases = new ArrayList<>();

    /** The worker threads of the current test, or {@code null} before the test starts one. */
    private ExecutorService workers;

    /**
     * Sixteen threads add fifty customers each through the API's add path at the same time. The adds
     * share the {@code ROW EXCLUSIVE} guard, which does not conflict with itself, so none waits for
     * another; they queue only for the ten pooled connections. Every id is distinct, and because V4
     * declares {@code CACHE 1} and no add rolls back, the 800 ids are exactly the 800 ordinals from
     * {@code EEEF}, increasing within each thread in allocation order.
     */
    @Test
    @DisplayName("parallel adds receive distinct, consecutive ids from EEEF, increasing per thread")
    void parallelAddsYieldDistinctConsecutiveIds() throws Exception {
        ExecutorService pool = workers(ADD_THREADS);
        CountDownLatch start = release();
        List<Future<List<Customer>>> futures = new ArrayList<>(ADD_THREADS);
        for (int t = 0; t < ADD_THREADS; t++) {
            int thread = t;
            futures.add(pool.submit(() -> addSequentially(thread, start)));
        }
        start.countDown();

        List<List<Customer>> perThread = new ArrayList<>(ADD_THREADS);
        for (Future<List<Customer>> future : futures) {
            // Any failure of an add, a DuplicateKeyException or DbActionExecutionException included,
            // surfaces here as the ExecutionException's cause and fails the test.
            perThread.add(future.get(PARALLEL_ADDS_TIMEOUT_SECONDS, SECONDS));
        }

        List<Customer> added = perThread.stream().flatMap(List::stream).toList();
        int total = ADD_THREADS * ADDS_PER_THREAD;
        assertThat(added).hasSize(total);
        assertThat(added).allSatisfy(customer -> {
            assertThat(customer.custId().value()).matches(ID_FORMAT);
            assertThat(customer.rowVersion()).isZero();
            assertThat(customer.chgUser()).isEqualTo(MAINTENANCE_USER);
        });
        List<CustomerId> ids = added.stream().map(Customer::custId).toList();
        assertThat(ids).doesNotHaveDuplicates();

        Set<Integer> ordinals = ids.stream().map(CustomerId::toOrdinal).collect(Collectors.toSet());
        Set<Integer> expectedOrdinals = IntStream
                .range(DatabaseCleaner.SEQUENCE_START, DatabaseCleaner.SEQUENCE_START + total)
                .boxed()
                .collect(Collectors.toSet());
        assertThat(ordinals)
                .as("CACHE 1 and no rollback: exactly the %d ordinals from %s, with no gap", total,
                        DatabaseCleaner.FIRST_INTERACTIVE_ID)
                .isEqualTo(expectedOrdinals);
        assertThat(CustomerId.fromOrdinal(DatabaseCleaner.SEQUENCE_START).value())
                .isEqualTo(DatabaseCleaner.FIRST_INTERACTIVE_ID);

        for (int t = 0; t < ADD_THREADS; t++) {
            List<Integer> threadOrdinals = perThread.get(t).stream()
                    .map(customer -> customer.custId().toOrdinal())
                    .toList();
            for (int n = 1; n < threadOrdinals.size(); n++) {
                assertThat(threadOrdinals.get(n))
                        .as("thread %d, add %d: ids strictly increase in allocation order", t, n)
                        .isGreaterThan(threadOrdinals.get(n - 1));
            }
        }

        assertThat(count("SELECT count(*) FROM custmast")).isEqualTo(total);
        assertThat(jdbcTemplate.queryForList("SELECT custid FROM custmast", String.class))
                .containsExactlyInAnyOrderElementsOf(ids.stream().map(CustomerId::value).toList());
        assertThat(jdbcTemplate.queryForList("SELECT DISTINCT chguser FROM custmast", String.class))
                .containsExactly(MAINTENANCE_USER);
        assertThat(jdbcTemplate.queryForList("SELECT DISTINCT row_version FROM custmast", Long.class))
                .containsExactly(0L);
    }

    /**
     * An add allocates {@code EEEF} and pauses before its {@code INSERT}; a load whose range starts at
     * that id is then attempted. The load's {@code ACCESS EXCLUSIVE} guard waits for the add's
     * {@code ROW EXCLUSIVE} guard, so the add's {@code INSERT} and commit succeed, and only then does the
     * load replace the table: no {@code DuplicateKeyException}, no lock timeout, and the next id
     * continues after the loaded range.
     */
    @Test
    @DisplayName("a load waits for an add holding an allocated id, then replaces the table")
    void allocationThenLoad_loadWaitsForAllocatingTransaction() throws Exception {
        ExecutorService pool = workers(2);
        CountDownLatch releaseA = release();
        CompletableFuture<CustomerId> allocated = new CompletableFuture<>();

        Future<CustomerId> threadA = pool.submit(() -> transactionTemplate.execute(status -> {
            CustomerId id;
            try {
                id = allocator.next();
            } catch (RuntimeException e) {
                allocated.completeExceptionally(e);
                throw e;
            }
            allocated.complete(id);
            awaitRelease(releaseA, "thread A, between allocation and insert");
            insertRow(id, "ALLOCATED FIRST");
            return id;
        }));
        CustomerId aId = allocated.get(5, SECONDS);
        assertThat(aId.value()).isEqualTo(DatabaseCleaner.FIRST_INTERACTIVE_ID);

        Future<Long> threadB = pool.submit(() -> customerLoader.load(plan(aId, LOAD_COUNT)));
        awaitLockWait(ACCESS_EXCLUSIVE);
        assertThat(threadB.isDone())
                .as("the load must wait for the add's allocation guard")
                .isFalse();
        releaseA.countDown();

        assertThat(threadA.get(5, SECONDS))
                .as("the add's INSERT and commit succeed while the load waits")
                .isEqualTo(aId);
        assertThat(threadB.get(10, SECONDS))
                .as("the load proceeds once the add has committed")
                .isEqualTo((long) LOAD_COUNT);

        assertThat(count("SELECT count(*) FROM custmast")).isEqualTo(LOAD_COUNT);
        assertThat(jdbcTemplate.queryForObject("SELECT min(custid) FROM custmast", String.class))
                .isEqualTo(aId.value());
        assertThat(jdbcTemplate.queryForObject("SELECT max(custid) FROM custmast", String.class))
                .isEqualTo(CustomerId.fromOrdinal(aId.toOrdinal() + LOAD_COUNT - 1).value());
        assertThat(count("SELECT count(*) FROM custmast WHERE name = 'ALLOCATED FIRST'"))
                .as("the load replaced the table after the add committed")
                .isZero();
        assertThat(jdbcTemplate.queryForList("SELECT DISTINCT chguser FROM custmast", String.class))
                .containsExactly(SYSTEM_USER);
        CustomerId next = transactionTemplate.execute(status -> allocator.next());
        assertThat(next)
                .as("the next id continues after the loaded range")
                .isEqualTo(CustomerId.fromOrdinal(aId.toOrdinal() + LOAD_COUNT));
    }

    /**
     * A load pauses after its {@code COPY}, holding {@code ACCESS EXCLUSIVE} before it has restarted the
     * sequence. An allocation started meanwhile waits on its {@code ROW EXCLUSIVE} guard; once the load
     * commits, the allocation draws {@code start + count} from the restarted sequence, never an id from
     * before the restart, and its {@code INSERT} succeeds beside the loaded rows.
     */
    @Test
    @DisplayName("an allocation waits for a running load to commit, then continues after its range")
    void loadThenAllocation_allocationWaitsForLoadCommit() throws Exception {
        ExecutorService pool = workers(2);
        CountDownLatch inCopy = new CountDownLatch(1);
        CountDownLatch releaseB = release();
        doAnswer(invocation -> {
            inCopy.countDown();
            awaitRelease(releaseB, "thread B, after COPY");
            return null;
        }).when(loadCheckpoints).afterCopy();
        CustomerId loadStart = CustomerId.parse("B000");

        Future<Long> threadB = pool.submit(() -> customerLoader.load(plan(loadStart, LOAD_COUNT)));
        if (!inCopy.await(5, SECONDS)) {
            failWithOutcome(threadB, "the load did not reach afterCopy within 5 s");
        }

        Future<CustomerId> threadA = pool.submit(() -> transactionTemplate.execute(status -> {
            jdbcTemplate.execute("SET LOCAL lock_timeout = '10s'");
            CustomerId id = allocator.next();
            insertRow(id, "ALLOCATED AFTER LOAD");
            return id;
        }));
        awaitLockWait(ROW_EXCLUSIVE);
        assertThat(threadA.isDone())
                .as("the allocation must wait for the load's guard")
                .isFalse();
        releaseB.countDown();

        CustomerId expected = CustomerId.fromOrdinal(loadStart.toOrdinal() + LOAD_COUNT);
        assertThat(threadB.get(10, SECONDS)).isEqualTo((long) LOAD_COUNT);
        assertThat(threadA.get(10, SECONDS))
                .as("the allocation draws from the sequence the load restarted")
                .isEqualTo(expected);

        assertThat(count("SELECT count(*) FROM custmast")).isEqualTo(LOAD_COUNT + 1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT custid FROM custmast WHERE name = 'ALLOCATED AFTER LOAD'", String.class))
                .isEqualTo(expected.value());
        assertThat(jdbcTemplate.queryForObject("SELECT min(custid) FROM custmast", String.class))
                .isEqualTo(loadStart.value());

        verify(loadCheckpoints, times(1)).afterCopy();
        InOrder steps = inOrder(loadCheckpoints);
        steps.verify(loadCheckpoints).afterTruncate();
        steps.verify(loadCheckpoints).afterCopy();
        steps.verify(loadCheckpoints).afterSequenceRestart();
        verifyNoMoreInteractions(loadCheckpoints);
    }

    /**
     * Past {@code MAXVALUE 1679615} ({@code 9999}) the {@code NO CYCLE} sequence raises SQLSTATE
     * {@code 2200H}, which the allocator reports as {@link CustomerIdExhaustedException} (503 APP0503),
     * where BASE36ADD would roll over to {@code AAAA}. The test moves the sequence with {@code setval},
     * which only test code may call; no production code does.
     */
    @Test
    @DisplayName("an exhausted sequence raises CustomerIdExhaustedException and inserts nothing")
    void exhaustedSequenceRaisesCustomerIdExhaustedException() {
        assertThat(jdbcTemplate.queryForObject(
                "SELECT setval('custmast_id_seq', " + CustomerId.MAX_ORDINAL + ", true)", Long.class))
                .isEqualTo((long) CustomerId.MAX_ORDINAL);

        assertThatThrownBy(() -> transactionTemplate.execute(status -> allocator.next()))
                .isInstanceOf(CustomerIdExhaustedException.class);

        assertThat(count("SELECT count(*) FROM custmast")).isZero();
    }

    /**
     * Releases every parked worker, ends the worker threads, proves that no other client backend holds or
     * awaits a lock on {@code custmast} (background workers such as autovacuum are excluded), and
     * separately that no other session of this database is left idle in a transaction. Runs after every
     * test, failed or not, so no lock, transaction or connection survives into the next test.
     *
     * @throws InterruptedException if this thread is interrupted while waiting for the workers to end
     */
    @AfterEach
    void releaseWorkersAndCheckNoLockSurvives() throws InterruptedException {
        releases.forEach(CountDownLatch::countDown);
        if (workers != null) {
            workers.shutdownNow();
            assertThat(workers.awaitTermination(10, SECONDS))
                    .as("every worker thread ends within 10 s of its release")
                    .isTrue();
        }
        assertThat(count(OTHER_CUSTMAST_LOCKS_SQL))
                .as(() -> "no other session holds or awaits a lock on custmast; sessions: " + sessions())
                .isZero();
        assertThat(count(IDLE_IN_TRANSACTION_SQL))
                .as(() -> "no session is left idle in transaction; sessions: " + sessions())
                .isZero();
    }

    /**
     * Runs one worker of the parallel-add test: binds a {@code MAINTENANCE} principal to this thread,
     * waits for the common start, and adds {@value #ADDS_PER_THREAD} customers one after another.
     *
     * @param thread the worker's number, part of each customer name
     * @param start  the latch all workers start on
     * @return the added customers in the order this thread added them
     * @throws IllegalStateException if the start latch is not released in time or the wait is
     *                               interrupted
     */
    private List<Customer> addSequentially(int thread, CountDownLatch start) {
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                MAINTENANCE_USER, null, List.of(new SimpleGrantedAuthority("ROLE_MAINTENANCE"))));
        SecurityContextHolder.setContext(context);
        try {
            awaitRelease(start, "parallel add thread " + thread + ", common start");
            List<Customer> added = new ArrayList<>(ADDS_PER_THREAD);
            for (int n = 0; n < ADDS_PER_THREAD; n++) {
                added.add(maintenanceService.add(draft("PARALLEL T" + thread + " N" + n)));
            }
            return added;
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    /**
     * Waits until another session of this database waits for a lock on the allocator's
     * {@code LOCK TABLE custmast ...} statement in the given mode. This is the proof that a guard
     * blocks. It probes every 25 ms for at most 3 seconds, well inside every lock timeout in play, so the
     * caller releases the holder long before the waiter gives up.
     *
     * @param modeFragment {@link #ACCESS_EXCLUSIVE} or {@link #ROW_EXCLUSIVE}
     * @throws InterruptedException if this thread is interrupted between two probes
     * @throws AssertionError       if no such waiter appears before the deadline; the message lists the
     *                              database's other sessions
     */
    private void awaitLockWait(String modeFragment) throws InterruptedException {
        long deadline = System.nanoTime() + LOCK_PROBE_DEADLINE.toNanos();
        while (true) {
            Long waiters = jdbcTemplate.queryForObject(LOCK_WAITERS_SQL, Long.class, modeFragment);
            if (waiters != null && waiters >= 1) {
                return;
            }
            if (System.nanoTime() - deadline >= 0) {
                throw new AssertionError("No session waited for LOCK TABLE custmast IN " + modeFragment
                        + " within " + LOCK_PROBE_DEADLINE.toMillis() + " ms; sessions: " + sessions());
            }
            // The interval between two probes; the probe itself, not this pause, decides when to go on.
            TimeUnit.MILLISECONDS.sleep(LOCK_PROBE_INTERVAL.toMillis());
        }
    }

    /**
     * Inserts one customer with the full V3 column list on the calling thread's transaction-bound
     * connection, as the add would after its allocation.
     *
     * @param id   the id to insert
     * @param name the customer name, by which the test finds the row again
     */
    private void insertRow(CustomerId id, String name) {
        int inserted = jdbcTemplate.update(INSERT_SQL, id.value(), name, ADDR, CITY, STATE, ZIP, CORP_PHONE,
                ACCT_MGR, ACCT_PHONE, ACTIVE, OffsetDateTime.now(clock).truncatedTo(ChronoUnit.MICROS),
                MAINTENANCE_USER, 0L);
        assertThat(inserted).as("rows inserted for %s", id).isEqualTo(1);
    }

    /**
     * Builds a reproducible load plan over two city/state/ZIP rows whose states exist in {@code states}.
     *
     * @param start the id of the first loaded row
     * @param count the number of rows
     * @return the plan, stamped with the current time at microsecond precision
     */
    private CustomerLoader.Plan plan(CustomerId start, int count) {
        CustomerDataGenerator generator = new CustomerDataGenerator(
                NameGenerator.seeded(LOAD_SEED),
                List.of(new CszSource.CszRow(90210, "STANDARD", "BEVERLY HILLS", "CA"),
                        new CszSource.CszRow(10001, "STANDARD", "NEW YORK", "NY")),
                OffsetDateTime.now(clock).truncatedTo(ChronoUnit.MICROS));
        return new CustomerLoader.Plan(start, count, generator);
    }

    /**
     * Returns a new customer that passes all nine field rules.
     *
     * @param name the customer name
     * @return a draft with no id, stamp or version
     */
    private static Customer draft(String name) {
        return Customer.draft(name, new Address(ADDR, CITY, STATE, ZIP), CORP_PHONE, ACCT_MGR, ACCT_PHONE,
                ACTIVE);
    }

    /**
     * Creates this test's worker threads; {@link #releaseWorkersAndCheckNoLockSurvives()} ends them.
     *
     * @param threads the number of threads
     * @return the pool
     */
    private ExecutorService workers(int threads) {
        assertThat(workers).as("one worker pool per test").isNull();
        workers = Executors.newFixedThreadPool(threads);
        return workers;
    }

    /**
     * Creates a latch that parks a worker until the test releases it, registered so that
     * {@link #releaseWorkersAndCheckNoLockSurvives()} releases it whatever the outcome.
     *
     * @return a new latch with count 1
     */
    private CountDownLatch release() {
        CountDownLatch latch = new CountDownLatch(1);
        releases.add(latch);
        return latch;
    }

    /**
     * Parks the calling worker until {@code latch} is released, at most
     * {@value #RELEASE_TIMEOUT_SECONDS} seconds. Inside a transaction callback the exception it throws
     * rolls the transaction back.
     *
     * @param latch the release latch
     * @param where the pause point, for the message
     * @throws IllegalStateException if the latch is not released in time, or the wait is interrupted (the
     *                               interrupt flag is restored)
     */
    private static void awaitRelease(CountDownLatch latch, String where) {
        try {
            if (!latch.await(RELEASE_TIMEOUT_SECONDS, SECONDS)) {
                throw new IllegalStateException("Not released within " + RELEASE_TIMEOUT_SECONDS + " s: "
                        + where);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while parked: " + where, e);
        }
    }

    /**
     * Fails the test with the outcome of a worker that did not reach its pause point: its exception
     * when it has failed, otherwise the database's sessions.
     *
     * @param worker  the worker's future
     * @param message what did not happen
     * @throws Exception always: the worker's failure, or an {@link AssertionError}
     */
    private void failWithOutcome(Future<?> worker, String message) throws Exception {
        if (worker.isDone()) {
            worker.get();
            throw new AssertionError(message + "; the worker ended without pausing");
        }
        throw new AssertionError(message + "; sessions: " + sessions());
    }

    private long count(String sql) {
        Long value = jdbcTemplate.queryForObject(sql, Long.class);
        assertThat(value).as("result of %s", sql).isNotNull();
        return value;
    }

    private List<String> sessions() {
        return jdbcTemplate.queryForList(SESSIONS_SQL, String.class);
    }
}
