package com.democorp.customermaster.generator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.democorp.customermaster.domain.CustomerId;
import com.democorp.customermaster.support.AbstractPostgresIT;
import com.democorp.customermaster.support.DatabaseCleaner;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.InOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Proves the generator's load protocol end to end against PostgreSQL: the runner, the loader, the
 * allocator's sequence restart and the CSZ reader, together with the interactive add that must continue
 * after a load.
 *
 * <p><b>Source.</b> LOADCUSTR truncates CUSTMAST and numbers rows from {@code '1001'} through BASE36ADD
 * [5250_Subfile/LOADCUSTR.SQLRPGLE:95-137]. LOADCUST and LOADCUST2 send
 * {@code 'Cannot allocate CUSTMAST'} when {@code ALCOBJ OBJ((CUSTMAST *FILE *EXCLRD)) WAIT(5)} fails
 * [5250_Subfile/LOADCUST.CLLE:6-14], [5250_Subfile/LOADCUST2.CLLE:10-18]. CRTDTAARA's {@code EEEE} and
 * AddRecd's increment-before-use make {@code EEEF} the first interactive id
 * [5250_Subfile/CRTDTAARA.clle:1-9], where every test starts
 * ({@link DatabaseCleaner#FIRST_INTERACTIVE_ID}). BASE36ADD rolls {@code 9999} over to {@code AAAA}
 * and leaves the limit to its caller [BASE36/SRV_BASE36.RPGLE:9-13].
 *
 * <p><b>Load protocol.</b> One transaction holds the truncate, the {@code COPY} and the sequence
 * restart; a failure at any {@link LoadCheckpoints} boundary rolls all three back, including the
 * exhausted branch of a load ending at {@code 9999}. {@link Snapshot} compares the rows (count and an
 * md5 over every row's id, name, address, city and ZIP) and the next id, read from the sequence
 * relation without calling {@code nextval}, before and after a failed load.
 *
 * <p><b>Context variant.</b> The one override, {@code @MockitoBean LoadCheckpoints loadCheckpoints}, is
 * declared identically in {@code repository.CustomerIdAllocationIT}, so both classes share one cached
 * Spring context (variant 2 of the policy in {@link AbstractPostgresIT}). The class adds no property
 * source, import, nested configuration or other bean override. Unstubbed, the mock does nothing, as the
 * production {@link LoadCheckpoints.NoOp}; Mockito resets it after every test.
 *
 * <p><b>Runner.</b> {@link CustomerGeneratorRunner} is a bean only under profile {@code generator}, so
 * {@link #runGenerator(int, String, String, Long)} builds it by hand from the context's collaborators,
 * with no command-line arguments: the options arrive as a {@link GeneratorProperties} record, as the
 * option bridge binds them. The runner prints its one outcome line to {@code System.out}, which
 * {@link OutputCaptureExtension} captures; it is constructed inside each test, after the capture is in
 * place. Assertions on what a run printed read only the output written after a mark taken just before
 * that run, because startup logging and an earlier successful run in the same test also print lines.
 *
 * <p><b>Determinism.</b> Every run is seeded. No test synchronises with {@code Thread.sleep}; the
 * allocation-guard test measures the loader's fixed 5-second lock wait, and its lock-holding connection
 * is rolled back and closed in {@code finally}, so the next test's {@code DatabaseCleaner} is never
 * blocked.
 */
@ExtendWith(OutputCaptureExtension.class)
@DisplayName("Generator load: ids, rollback at every checkpoint, exhausted branch, capacity, lock guard, CSZ")
class CustomerGeneratorIT extends AbstractPostgresIT {

    /** The bundled 200-row city/state/ZIP sample, the generator's default. */
    private static final String SAMPLE_CSZ = GeneratorProperties.DEFAULT_CSZ_FILE;

    /** The 14-row test file: header {@code primary_city}, 12 rows kept. */
    private static final String TEST_CSZ = "classpath:generator/test-csz.csv";

    /** Ordinal of {@code 1001}, LOADCUSTR's first id. */
    private static final int LOADCUSTR_START_ORDINAL = 1_294_371;

    private static final String PRELOAD_START = "B000";

    /** Number of {@link #preload()} rows: {@code B000}..{@code B004}. */
    private static final int PRELOAD_COUNT = 5;

    /** The id the next add receives after {@link #preload()}. */
    private static final String PRELOAD_NEXT_ID = "B005";

    private static final long PRELOAD_SEED = 11L;

    /** The exception a stubbed checkpoint throws; its message reaches the outcome line. */
    private static final String CHECKPOINT_FAILURE = "checkpoint failure";

    /** Matches the success line of any run, {@code Loaded <n> customers <first>..<last> in <s> s}. */
    private static final Pattern LOADED_LINE = Pattern.compile("Loaded \\d+ customers");

    /**
     * Lower bound of the lock wait: the loader's fixed {@code lock_timeout} of
     * {@value CustomerLoader#LOCK_TIMEOUT}, with half a second of slack for timer granularity. A run that
     * gave up after the test profile's 1-second add/update timeout would fall well below it.
     */
    private static final Duration LOCK_WAIT_FLOOR = Duration.ofMillis(4_500);

    /** Upper bound of the lock wait, far below anything but a hung run. */
    private static final Duration LOCK_WAIT_CEILING = Duration.ofSeconds(60);

    /** The states the test file keeps: its {@code ZZ} row and its 21-character WA city are dropped. */
    private static final Set<String> TEST_CSZ_STATES = Set.of("NY", "CA", "TX", "DC", "PR", "FL", "IL");

    /**
     * Every {@code city|state|zip} a load from {@link #TEST_CSZ} may write: the 12 kept rows, cities
     * uppercased, ZIPs as the last five digits of the zero-padded number.
     */
    private static final Set<String> TEST_CSZ_PLACES = Set.of(
            "TESTVILLE|NY|00501",
            "TEST HARBOR|NY|10013",
            "TESTHAVEN|CA|90210",
            "TEST BAY|CA|94105",
            "TESTMONT|TX|73301",
            "TEST JUNCTION|TX|77001",
            "TESTINGTON CROSSROAD|DC|20001",
            "TEST CAPITOL|DC|20090",
            "TEST MESA|PR|00601",
            "TESTBRIDGE|PR|00936",
            "TEST BAYOU|FL|33101",
            "TESTLAKE CITY|IL|60601");

    /** One checksum over every row, empty for an empty table; order by id makes it deterministic. */
    private static final String CHECKSUM_SQL = "SELECT coalesce(md5(string_agg("
            + "custid || '|' || name || '|' || addr || '|' || city || '|' || zip, ',' ORDER BY custid)), '')"
            + " FROM custmast";

    /** The sequence position, read from the sequence relation; {@code nextval} would move it. */
    private static final String SEQUENCE_SQL = "SELECT last_value, is_called FROM custmast_id_seq";

    private static final String ADD_BODY = "{"
            + "\"name\":\"GENERATOR IT CUSTOMER\","
            + "\"addr\":\"1 LOAD STREET\","
            + "\"city\":\"TESTVILLE\","
            + "\"state\":\"CA\","
            + "\"zip\":\"90210\","
            + "\"corpPhone\":\"(415) 555-0100\","
            + "\"acctMgr\":\"PAT TESTER\","
            + "\"acctPhone\":\"(415) 555-0101\","
            + "\"active\":\"Y\""
            + "}";

    /**
     * Replaces the production no-op checkpoints, so a test can fail a load at an exact step boundary and
     * verify which boundaries a run reached. Declared exactly so in {@code CustomerIdAllocationIT}.
     */
    @MockitoBean
    LoadCheckpoints loadCheckpoints;

    @Autowired
    CszSource cszSource;

    /** The transactional loader proxy, so {@code load} runs in its own transaction. */
    @Autowired
    CustomerLoader customerLoader;

    @Autowired
    Clock clock;

    @Test
    @DisplayName("1,000 rows from 1001 as LOADCUSTR, stamped *SYSTEM*, analyzed; the next add continues after them")
    void loadsFromLoadcustrStartAndTheNextAddContinuesAfterTheLastId(CapturedOutput output) {
        CustomerId last = CustomerId.fromOrdinal(LOADCUSTR_START_ORDINAL + 999);
        int mark = output.getOut().length();

        assertThat(runGenerator(1000, "", SAMPLE_CSZ, 42L)).isEqualTo(CustomerGeneratorRunner.EXIT_SUCCESS);

        assertThat(outputSince(output, mark)).contains("Loaded 1000 customers 1001.." + last + " in ");
        assertThat(rowCount()).isEqualTo(1000);
        List<String> expectedIds = IntStream.range(0, 1000)
                .mapToObj(i -> CustomerId.fromOrdinal(LOADCUSTR_START_ORDINAL + i).value())
                .toList();
        assertThat(jdbcTemplate.queryForList("SELECT custid FROM custmast ORDER BY custid", String.class))
                .as("consecutive base-36 ids 1001..%s, in allocation order", last)
                .containsExactlyElementsOf(expectedIds);
        assertThat(queryLong("SELECT count(*) FROM custmast WHERE chguser <> '*SYSTEM*' OR row_version <> 0"))
                .as("rows not stamped *SYSTEM* with version 0")
                .isZero();
        assertThat(queryLong("SELECT count(DISTINCT chgtime) FROM custmast"))
                .as("every row carries the one load time")
                .isEqualTo(1);
        assertThat(queryLong("SELECT count(*) FROM custmast WHERE active = 'N'"))
                .as("ACTIVE 'N' exactly on every seventh row, as LOADCUSTR")
                .isEqualTo(1000 / 7);
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT reltuples FROM pg_class WHERE oid = 'custmast'::regclass", Double.class))
                .as("ANALYZE ran after the commit")
                .isEqualTo(1000.0);

        assertThat(nextOrdinal()).isEqualTo(LOADCUSTR_START_ORDINAL + 1000);
        assertAdded(addCustomer(), CustomerId.fromOrdinal(LOADCUSTR_START_ORDINAL + 1000).value());
        assertThat(rowCount()).isEqualTo(1001);
    }

    @Test
    @DisplayName("A load from the test CSZ file writes only its kept rows and violates no state key")
    void loadsOnlyTheRowsTheTestCszFileKeeps(CapturedOutput output) {
        int mark = output.getOut().length();

        assertThat(runGenerator(1000, "", TEST_CSZ, 42L)).isEqualTo(CustomerGeneratorRunner.EXIT_SUCCESS);

        assertThat(outputSince(output, mark)).contains("Loaded 1000 customers 1001..");
        assertThat(rowCount()).isEqualTo(1000);
        assertThat(queryLong("SELECT count(*) FROM custmast c LEFT JOIN states s ON s.state = c.state"
                + " WHERE s.state IS NULL"))
                .as("rows whose state is not in STATES")
                .isZero();
        assertThat(jdbcTemplate.queryForList("SELECT DISTINCT state FROM custmast", String.class))
                .isSubsetOf(TEST_CSZ_STATES)
                .doesNotContain("ZZ", "WA");
        assertThat(jdbcTemplate.queryForList(
                        "SELECT DISTINCT city || '|' || state || '|' || zip FROM custmast", String.class))
                .as("city, state and ZIP come only from the kept rows of the test file")
                .isSubsetOf(TEST_CSZ_PLACES);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(FailingCheckpoint.class)
    @DisplayName("A failure at a checkpoint rolls back rows and sequence; the next add gets the pre-load id")
    void aFailureAtACheckpointRestoresThePreLoadRowsAndNextId(FailingCheckpoint checkpoint,
            CapturedOutput output) {
        preload();
        Snapshot before = snapshot();
        checkpoint.failOn(loadCheckpoints);
        int mark = output.getOut().length();

        assertThat(runGenerator(30, "C000", SAMPLE_CSZ, 3L)).isEqualTo(CustomerGeneratorRunner.EXIT_FAILURE);

        String printed = outputSince(output, mark);
        assertThat(printed).doesNotContainPattern(LOADED_LINE);
        assertThat(printed).contains("Load failed: " + CHECKPOINT_FAILURE);
        checkpoint.verifyReached(loadCheckpoints);
        assertThat(snapshot()).as("rows, count and next id after the rolled-back load").isEqualTo(before);

        assertAdded(addCustomer(), PRELOAD_NEXT_ID);
        assertThat(rowCount()).isEqualTo(PRELOAD_COUNT + 1);
    }

    @ParameterizedTest(name = "--start-id={0} --count={1}")
    @CsvSource({"9999, 1", "9990, 10"})
    @DisplayName("A load ending at 9999 commits, leaves the sequence exhausted, and the next add answers 503")
    void aLoadEndingAt9999LeavesTheSequenceExhausted(String startId, int count, CapturedOutput output) {
        int startOrdinal = CustomerId.parse(startId).toOrdinal();
        int mark = output.getOut().length();

        assertThat(runGenerator(count, startId, SAMPLE_CSZ, 5L)).isEqualTo(CustomerGeneratorRunner.EXIT_SUCCESS);

        assertThat(outputSince(output, mark))
                .contains("Loaded " + count + " customers " + startId + "..9999 in ");
        List<String> expectedIds = IntStream.range(0, count)
                .mapToObj(i -> CustomerId.fromOrdinal(startOrdinal + i).value())
                .toList();
        assertThat(expectedIds).last().isEqualTo("9999");
        assertThat(jdbcTemplate.queryForList("SELECT custid FROM custmast ORDER BY custid", String.class))
                .containsExactlyElementsOf(expectedIds);
        assertThat(sequencePosition()).as("last_value and is_called")
                .isEqualTo(new SequencePosition(CustomerId.MAX_ORDINAL, true));
        assertThat(nextOrdinal()).isEqualTo(CustomerId.CAPACITY);

        ResponseEntity<String> add = addCustomer();
        assertThat(add.getStatusCode()).as("add after a load ending at 9999: %s", add.getBody())
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(problem(add).path("code").asText()).isEqualTo("APP0503");
        assertThat(rowCount()).as("the refused add inserted nothing").isEqualTo(count);
        assertThat(sequencePosition()).isEqualTo(new SequencePosition(CustomerId.MAX_ORDINAL, true));
    }

    @Test
    @DisplayName("A failure after the exhausted restart rolls it back; the next add gets exactly the pre-load id")
    void aFailureAfterTheExhaustedRestartRestoresThePreLoadNextId(CapturedOutput output) {
        preload();
        Snapshot before = snapshot();
        doThrow(new IllegalStateException(CHECKPOINT_FAILURE)).when(loadCheckpoints).afterSequenceRestart();
        int mark = output.getOut().length();

        assertThat(runGenerator(10, "9990", SAMPLE_CSZ, 5L)).isEqualTo(CustomerGeneratorRunner.EXIT_FAILURE);

        String printed = outputSince(output, mark);
        assertThat(printed).doesNotContainPattern(LOADED_LINE);
        assertThat(printed).contains("Load failed: " + CHECKPOINT_FAILURE);
        // Reaching afterSequenceRestart proves the exhausted restart and its nextval had run.
        verify(loadCheckpoints).afterSequenceRestart();
        assertThat(snapshot()).as("rows, count and next id after the rolled-back load").isEqualTo(before);

        assertAdded(addCustomer(), PRELOAD_NEXT_ID);
        assertThat(rowCount()).isEqualTo(PRELOAD_COUNT + 1);
    }

    @Test
    @DisplayName("Two rows from 9999 fail the capacity check before any write")
    void aLoadBeyond9999FailsTheCapacityCheckBeforeAnyWrite(CapturedOutput output) {
        preload();
        Snapshot before = snapshot();
        int mark = output.getOut().length();

        assertThat(runGenerator(2, "9999", SAMPLE_CSZ, 5L)).isEqualTo(CustomerGeneratorRunner.EXIT_FAILURE);

        String printed = outputSince(output, mark);
        assertThat(printed).doesNotContainPattern(LOADED_LINE);
        assertThat(printed).contains("Cannot load 2 customers starting at 9999: only 1 ids remain through 9999");
        verifyNoInteractions(loadCheckpoints);
        assertThat(snapshot()).isEqualTo(before);
    }

    @Test
    @DisplayName("Start id: given, else 1001, else AAAA above 385,245 rows; capacity ends at 9999")
    void resolvesTheStartIdAndJudgesCapacity() {
        assertThat(CustomerGeneratorRunner.resolveStart("", 385_246)).isEqualTo(CustomerId.parse("AAAA"));
        assertThat(CustomerGeneratorRunner.resolveStart("", 385_245)).isEqualTo(CustomerId.parse("1001"));
        assertThat(CustomerGeneratorRunner.resolveStart("B000", 10)).isEqualTo(CustomerId.parse("B000"));
        assertThat(CustomerGeneratorRunner.resolveStart("B000", 385_246))
                .as("an explicit start id wins over the automatic rule")
                .isEqualTo(CustomerId.parse("B000"));

        assertThat(CustomerGeneratorRunner.fitsCapacity(CustomerId.parse("9999"), 1)).isTrue();
        assertThat(CustomerGeneratorRunner.fitsCapacity(CustomerId.parse("9999"), 2)).isFalse();
        assertThat(CustomerGeneratorRunner.fitsCapacity(CustomerId.parse("9990"), 10)).isTrue();
        assertThat(CustomerGeneratorRunner.fitsCapacity(CustomerId.parse("1001"), 385_245)).isTrue();
        assertThat(CustomerGeneratorRunner.fitsCapacity(CustomerId.parse("1001"), 385_246)).isFalse();
        assertThat(CustomerGeneratorRunner.fitsCapacity(CustomerId.parse("AAAA"), CustomerId.CAPACITY)).isTrue();
    }

    @Test
    @DisplayName("An add's ROW EXCLUSIVE guard blocks a load for 5 seconds, then 'Cannot allocate CUSTMAST'")
    void aHeldAllocationGuardMakesTheLoadReportCannotAllocate(CapturedOutput output) throws SQLException {
        int mark = output.getOut().length();
        Connection holder = dataSource.getConnection();
        try {
            holder.setAutoCommit(false);
            try (Statement statement = holder.createStatement()) {
                // The mode an add takes before nextval; it conflicts with the loader's ACCESS EXCLUSIVE.
                statement.execute("LOCK TABLE custmast IN ROW EXCLUSIVE MODE");
            }

            long began = System.nanoTime();
            int status = runGenerator(10, "", SAMPLE_CSZ, 1L);
            Duration elapsed = Duration.ofNanos(System.nanoTime() - began);

            assertThat(status).isEqualTo(CustomerGeneratorRunner.EXIT_FAILURE);
            String printed = outputSince(output, mark);
            assertThat(printed).contains(CustomerGeneratorRunner.LOCK_FAILURE_MESSAGE);
            assertThat(printed).doesNotContainPattern(LOADED_LINE);
            assertThat(elapsed)
                    .as("the loader waits its own %s lock_timeout, not the 1-second add timeout",
                            CustomerLoader.LOCK_TIMEOUT)
                    .isGreaterThanOrEqualTo(LOCK_WAIT_FLOOR)
                    .isLessThan(LOCK_WAIT_CEILING);
            verifyNoInteractions(loadCheckpoints);
        } finally {
            try {
                holder.rollback();
            } finally {
                holder.close();
            }
        }

        assertThat(rowCount()).as("the refused load changed nothing").isZero();
        assertThat(nextOrdinal()).isEqualTo(DatabaseCleaner.SEQUENCE_START);
    }

    @Test
    @DisplayName("The test CSZ file: primary_city header accepted, 12 rows kept in file order")
    void readsTheTestCszFileWithItsPrimaryCityHeader() {
        List<CszSource.CszRow> rows = cszSource.load(TEST_CSZ);

        assertThat("TESTINGTON CROSSROAD").hasSize(CszSource.CITY_MAX_LENGTH);
        assertThat(rows).containsExactly(
                new CszSource.CszRow(501, "UNIQUE", "TESTVILLE", "NY"),
                new CszSource.CszRow(10013, "STANDARD", "TEST HARBOR", "NY"),
                new CszSource.CszRow(90210, "STANDARD", "TESTHAVEN", "CA"),
                new CszSource.CszRow(94105, "PO BOX", "TEST BAY", "CA"),
                new CszSource.CszRow(73301, "UNIQUE", "TESTMONT", "TX"),
                new CszSource.CszRow(77001, "STANDARD", "TEST JUNCTION", "TX"),
                new CszSource.CszRow(20001, "STANDARD", "TESTINGTON CROSSROAD", "DC"),
                new CszSource.CszRow(20090, "PO BOX", "TEST CAPITOL", "DC"),
                new CszSource.CszRow(601, "STANDARD", "TEST MESA", "PR"),
                new CszSource.CszRow(936, "PO BOX", "TESTBRIDGE", "PR"),
                new CszSource.CszRow(33101, "STANDARD", "TEST BAYOU", "FL"),
                new CszSource.CszRow(60601, "STANDARD", "TESTLAKE CITY", "IL"));
        assertThat(rows).extracting(CszSource.CszRow::state).doesNotContain("ZZ");
        assertThat(rows).extracting(CszSource.CszRow::city)
                .doesNotContain("TESTINGTON CROSSROADS", "TEST NOWHERE");
    }

    /**
     * Runs the generator once, as the {@code generator} profile would with the given bound options and
     * no further command-line arguments.
     *
     * @param count   the row count
     * @param startId the first id, or {@code ""} for the automatic start
     * @param cszFile the CSZ location
     * @param seed    the seed of the random data
     * @return the exit status the runner reports
     */
    int runGenerator(int count, String startId, String cszFile, Long seed) {
        return new CustomerGeneratorRunner(new GeneratorProperties(count, startId, cszFile, seed),
                cszSource, customerLoader, jdbcTemplate, clock)
                .execute(new DefaultApplicationArguments());
    }

    /**
     * Loads {@value #PRELOAD_COUNT} rows {@code B000}..{@code B004}, so a later failed load has rows and
     * a next id ({@value #PRELOAD_NEXT_ID}) to restore, then forgets the checkpoint calls it made.
     */
    void preload() {
        assertThat(runGenerator(PRELOAD_COUNT, PRELOAD_START, SAMPLE_CSZ, PRELOAD_SEED))
                .as("preload of %d rows from %s", PRELOAD_COUNT, PRELOAD_START)
                .isEqualTo(CustomerGeneratorRunner.EXIT_SUCCESS);
        clearInvocations(loadCheckpoints);
        assertThat(nextOrdinal()).isEqualTo(CustomerId.parse(PRELOAD_START).toOrdinal() + PRELOAD_COUNT);
        assertThat(CustomerId.fromOrdinal(Math.toIntExact(nextOrdinal())).value()).isEqualTo(PRELOAD_NEXT_ID);
    }

    /**
     * Reads the sequence's position without moving it.
     *
     * @return {@code last_value} and {@code is_called} of {@code custmast_id_seq}
     */
    SequencePosition sequencePosition() {
        return Objects.requireNonNull(jdbcTemplate.queryForObject(SEQUENCE_SQL,
                (resultSet, rowNumber) -> new SequencePosition(
                        resultSet.getLong("last_value"), resultSet.getBoolean("is_called"))));
    }

    /**
     * Returns the ordinal the next {@code nextval} would return, without calling it.
     *
     * @return {@code last_value + 1} once a value has been drawn since the last restart, otherwise
     *         {@code last_value}; {@link CustomerId#CAPACITY} means the sequence is exhausted
     */
    long nextOrdinal() {
        return sequencePosition().next();
    }

    /**
     * Captures what a failed load must leave unchanged.
     *
     * @return the row count, the checksum over every row and the next ordinal
     */
    Snapshot snapshot() {
        String checksum = Objects.requireNonNull(jdbcTemplate.queryForObject(CHECKSUM_SQL, String.class));
        return new Snapshot(rowCount(), checksum, nextOrdinal());
    }

    long rowCount() {
        return queryLong("SELECT count(*) FROM custmast");
    }

    long queryLong(String sql) {
        return Objects.requireNonNull(jdbcTemplate.queryForObject(sql, Long.class));
    }

    /**
     * Adds one valid customer through the API as {@value AbstractPostgresIT#MAINTENANCE_USER}.
     *
     * @return the response, whatever its status
     */
    ResponseEntity<String> addCustomer() {
        return maintenance().post().uri("/api/customers")
                .contentType(MediaType.APPLICATION_JSON)
                .body(ADD_BODY)
                .retrieve()
                .toEntity(String.class);
    }

    /**
     * Asserts a successful add with the expected id.
     *
     * @param response   the add's response
     * @param expectedId the id the sequence must have allocated
     */
    void assertAdded(ResponseEntity<String> response, String expectedId) {
        assertThat(response.getStatusCode()).as("add: %s", response.getBody()).isEqualTo(HttpStatus.CREATED);
        assertThat(json(response).path("custId").asText()).isEqualTo(expectedId);
        assertThat(response.getHeaders().getFirst(HttpHeaders.LOCATION)).endsWith("/api/customers/" + expectedId);
    }

    static String outputSince(CapturedOutput output, int mark) {
        return output.getOut().substring(mark);
    }

    /**
     * What a failed load must leave unchanged.
     *
     * @param count       the number of rows
     * @param checksum    md5 over every row's id, name, address, city and ZIP in id order; empty for no rows
     * @param nextOrdinal the ordinal the next add receives
     */
    record Snapshot(long count, String checksum, long nextOrdinal) {
    }

    /**
     * The state of {@code custmast_id_seq}.
     *
     * @param lastValue the sequence's {@code last_value}
     * @param isCalled  whether {@code last_value} has been handed out since the last restart
     */
    record SequencePosition(long lastValue, boolean isCalled) {

        long next() {
            return isCalled ? lastValue + 1 : lastValue;
        }
    }

    /** A step boundary of the load transaction at which a test makes the load fail. */
    enum FailingCheckpoint {

        /** After {@code TRUNCATE}, before the {@code COPY}. */
        AFTER_TRUNCATE {
            @Override
            void failOn(LoadCheckpoints checkpoints) {
                doThrow(new IllegalStateException(CHECKPOINT_FAILURE)).when(checkpoints).afterTruncate();
            }

            @Override
            void verifyReached(LoadCheckpoints checkpoints) {
                verify(checkpoints).afterTruncate();
                verify(checkpoints, never()).afterCopy();
                verify(checkpoints, never()).afterSequenceRestart();
            }
        },

        /** After the {@code COPY}, before the sequence restart. */
        AFTER_COPY {
            @Override
            void failOn(LoadCheckpoints checkpoints) {
                doThrow(new IllegalStateException(CHECKPOINT_FAILURE)).when(checkpoints).afterCopy();
            }

            @Override
            void verifyReached(LoadCheckpoints checkpoints) {
                InOrder order = inOrder(checkpoints);
                order.verify(checkpoints).afterTruncate();
                order.verify(checkpoints).afterCopy();
                verify(checkpoints, never()).afterSequenceRestart();
            }
        },

        /** After the sequence restart, the last step before {@code COMMIT}. */
        AFTER_SEQUENCE_RESTART {
            @Override
            void failOn(LoadCheckpoints checkpoints) {
                doThrow(new IllegalStateException(CHECKPOINT_FAILURE)).when(checkpoints).afterSequenceRestart();
            }

            @Override
            void verifyReached(LoadCheckpoints checkpoints) {
                InOrder order = inOrder(checkpoints);
                order.verify(checkpoints).afterTruncate();
                order.verify(checkpoints).afterCopy();
                order.verify(checkpoints).afterSequenceRestart();
            }
        };

        /**
         * Stubs the checkpoint to throw an {@link IllegalStateException} carrying {@code "checkpoint failure"}.
         *
         * @param checkpoints the mock
         */
        abstract void failOn(LoadCheckpoints checkpoints);

        /**
         * Verifies the load reached this checkpoint, after every earlier one, and no later one.
         *
         * @param checkpoints the mock
         */
        abstract void verifyReached(LoadCheckpoints checkpoints);
    }
}
