package com.democorp.customermaster.generator;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.democorp.customermaster.generator.CszSource.CszRow;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.TransactionSystemException;

/**
 * Specifies how {@link CustomerGeneratorRunner} reports a failed {@link CustomerLoader#load(CustomerLoader.Plan)}.
 *
 * <p><b>Why the generic report claims nothing about the table.</b> {@code loader.load} is the Spring proxy, so
 * its exception may come from the {@code COMMIT} as well as from a statement. Spring's
 * {@code JdbcTransactionManager} reports a failed commit as a translated {@code DataAccessException}, for example
 * {@link DataAccessResourceFailureException} for a connection failure (SQLSTATE class {@code 08}), or as a
 * {@link TransactionSystemException} when the translator has no category. When the connection is lost before
 * the commit is acknowledged, PostgreSQL may have committed the new rows and the restarted sequence, so the
 * runner must neither say they are unchanged nor hide the exception: it logs at ERROR with the exception's
 * value-free copy, advises verifying {@code custmast} and {@code custmast_id_seq} before a retry, and prints
 * one line.
 *
 * <p><b>What the log may carry.</b> A failed {@code COPY} quotes the copied record in its {@code DETAIL} and
 * {@code CONTEXT}, and any message, cause or suppressed exception may hold CR or LF. Both ERROR lines, the
 * load failure's and the one for an unexpected failure outside the load steps, therefore carry their fixed
 * text and, of the exception graph, only types, stack frames and SQLSTATEs; the cases plant sentinels and
 * line breaks in every message of the graph and find none of them in the captured log.
 *
 * <p><b>Lock waits.</b> A lock wait ends the load before anything commits and prints LOADCUST2's
 * {@code Cannot allocate CUSTMAST} [5250_Subfile/LOADCUST2.CLLE:10-18].
 *
 * <p>Plain JUnit 5, Mockito and AssertJ over the runner's package-private constructor, with the outcome line
 * printed to a {@link ByteArrayOutputStream} and the log captured by {@link OutputCaptureExtension}: no Spring
 * application context, no database, no Docker.
 */
@ExtendWith(OutputCaptureExtension.class)
@DisplayName("CustomerGeneratorRunner: load failures report only the outcome that is known")
final class CustomerGeneratorRunnerLoadFailureTest {

    /** The advice the printed line ends with when the outcome of the load is not known. */
    private static final String PRINTED_ADVICE = "; verify custmast and custmast_id_seq before retrying";

    /** The verification advice the ERROR log line carries. */
    private static final String LOGGED_ADVICE =
            "verify custmast (row count, first and last custid) and custmast_id_seq against this run's count"
                    + " and start before retrying";

    /** The message PostgreSQL's driver gives when the connection drops while a statement is in flight. */
    private static final String CONNECTION_LOST = "An I/O error occurred while sending to the backend.";

    /** SQLSTATE {@code connection_failure}. */
    private static final String CONNECTION_FAILURE = "08006";

    /** SQLSTATE {@code not_null_violation}, which a {@code COPY} of a row missing a value raises. */
    private static final String NOT_NULL_VIOLATION = "23502";

    /** The message of the failure's top exception: a sentinel, then text after a CR, an LF and a CR LF. */
    private static final String TOP_MESSAGE = "COPY custmast; TOP-SENTINEL\rFORGED line after CR\n"
            + "FORGED line after LF\r\nFORGED line after CR LF";

    /** A PostgreSQL error as pgjdbc words it, with the copied record in its detail and line breaks. */
    private static final String CAUSE_MESSAGE = "ERROR: null value in column \"zip\" violates not-null constraint\r\n"
            + "  Detail: Failing row contains (C000, CAUSE-SENTINEL, SENTINELCITYZQ, NY).\n"
            + "  Where: COPY custmast, line 1\rFORGED cause line";

    /** {@link #CAUSE_MESSAGE} on one line, as the printed outcome line names the root cause. */
    private static final String CAUSE_LINE = "ERROR: null value in column \"zip\" violates not-null constraint"
            + " Detail: Failing row contains (C000, CAUSE-SENTINEL, SENTINELCITYZQ, NY)."
            + " Where: COPY custmast, line 1 FORGED cause line";

    /** The message of the failure's suppressed exception, with a CR LF and an LF. */
    private static final String SUPPRESSED_MESSAGE = "SUPPRESSED-SENTINEL\r\nFORGED suppressed line\nFORGED";

    /**
     * Text of {@link #TOP_MESSAGE}, {@link #CAUSE_MESSAGE} and {@link #SUPPRESSED_MESSAGE} that no log line may
     * carry; every line break in them is followed by {@code FORGED}, so a break that survived would too.
     */
    private static final List<String> SENTINELS = List.of(
            "TOP-SENTINEL", "CAUSE-SENTINEL", "SENTINELCITYZQ", "Failing row contains", "SUPPRESSED-SENTINEL",
            "FORGED");

    /** The generator options of every case: five rows from {@code C000}, seeded, bundled sample. */
    private static final GeneratorProperties OPTIONS =
            new GeneratorProperties(5, "C000", GeneratorProperties.DEFAULT_CSZ_FILE, 7L);

    /** The fixed load time. */
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-07T12:00:00Z"), ZoneOffset.UTC);

    /** The one retained city/state/ZIP row the mocked CSZ source returns. */
    private static final List<CszRow> CSZ_ROWS = List.of(new CszRow(10001, "STANDARD", "NEW YORK", "NY"));

    /** The command line of every case, matching {@link #OPTIONS}. */
    private static final String[] ARGS = {"--spring.profiles.active=generator", "--count=5", "--start-id=C000"};

    /** Receives the runner's outcome line. */
    private final ByteArrayOutputStream printed = new ByteArrayOutputStream();

    /** The mocked loader; each case stubs the exception its {@code load} throws. */
    private CustomerLoader loader;

    /** The mocked template the runner would use for {@code ANALYZE}, which no failed load may reach. */
    private JdbcTemplate jdbcTemplate;

    /** The runner under test, printing to {@link #printed}. */
    private CustomerGeneratorRunner runner;

    @BeforeEach
    void setUp() {
        // Plain mocks (no MockitoExtension), stubbed per test.
        CszSource cszSource = mock(CszSource.class);
        when(cszSource.load(anyString())).thenReturn(CSZ_ROWS);
        loader = mock(CustomerLoader.class);
        jdbcTemplate = mock(JdbcTemplate.class);
        runner = new CustomerGeneratorRunner(OPTIONS, cszSource, loader, jdbcTemplate, CLOCK,
                new PrintStream(printed, true, UTF_8));
    }

    @Test
    @DisplayName("a commit lost with its connection (DataAccessResourceFailureException) is outcome unknown")
    void lostCommitConnectionIsOutcomeNeutral(CapturedOutput output) {
        RuntimeException failure = new DataAccessResourceFailureException("JDBC commit; " + CONNECTION_LOST,
                new SQLException(CONNECTION_LOST, CONNECTION_FAILURE));
        when(loader.load(any())).thenThrow(failure);

        int status = runner.execute(new DefaultApplicationArguments(ARGS));

        assertOutcomeUnknownReported(status, output, DataAccessResourceFailureException.class);
    }

    @Test
    @DisplayName("an uncategorized commit failure (TransactionSystemException) is outcome unknown")
    void uncategorizedCommitFailureIsOutcomeNeutral(CapturedOutput output) {
        RuntimeException failure = new TransactionSystemException("Could not commit JDBC transaction",
                new SQLException(CONNECTION_LOST, CONNECTION_FAILURE));
        when(loader.load(any())).thenThrow(failure);

        int status = runner.execute(new DefaultApplicationArguments(ARGS));

        assertOutcomeUnknownReported(status, output, TransactionSystemException.class);
    }

    @Test
    @DisplayName("a lock wait still prints Cannot allocate CUSTMAST and logs no load failure")
    void lockWaitStillPrintsCannotAllocate(CapturedOutput output) {
        SQLException lockTimeout = new SQLException("ERROR: canceling statement due to lock timeout",
                CustomerGeneratorRunner.LOCK_NOT_AVAILABLE);
        when(loader.load(any())).thenThrow(new CannotAcquireLockException("LOCK TABLE custmast", lockTimeout));

        int status = runner.execute(new DefaultApplicationArguments(ARGS));

        assertThat(status).isEqualTo(CustomerGeneratorRunner.EXIT_FAILURE);
        assertThat(printedLines()).containsExactly(CustomerGeneratorRunner.LOCK_FAILURE_MESSAGE);
        assertThat(output.getAll())
                .contains("generator.load custmast lock not granted within " + CustomerLoader.LOCK_TIMEOUT)
                .doesNotContain("generator.load failed");
        verifyNoInteractions(jdbcTemplate);
    }

    @Test
    @DisplayName("a load failure with data and CR/LF in its message, cause and suppressed one is logged value-free")
    void loadFailureIsLoggedValueFree(CapturedOutput output) {
        when(loader.load(any())).thenThrow(withSentinelLinks(new DataIntegrityViolationException(TOP_MESSAGE)));

        int status = runner.execute(new DefaultApplicationArguments(ARGS));

        assertThat(status).isEqualTo(CustomerGeneratorRunner.EXIT_FAILURE);
        verifyNoInteractions(jdbcTemplate);
        assertThat(printedLines()).containsExactly("Load failed: " + CAUSE_LINE + PRINTED_ADVICE);
        String log = output.getAll();
        assertThat(errorLines(log, "generator.load failed"))
                .singleElement(InstanceOfAssertFactories.STRING)
                .contains("the outcome is unknown")
                .contains(LOGGED_ADVICE);
        assertValueFreeGraph(log, DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("an unexpected failure outside the load steps is logged value-free and still exits 1")
    void unexpectedFailureIsLoggedValueFree(CapturedOutput output) {
        GeneratorProperties failingOptions = mock(GeneratorProperties.class);
        when(failingOptions.count()).thenThrow(withSentinelLinks(new IllegalStateException(TOP_MESSAGE)));
        CustomerGeneratorRunner failingRunner = new CustomerGeneratorRunner(failingOptions, mock(CszSource.class),
                loader, jdbcTemplate, CLOCK, new PrintStream(printed, true, UTF_8));

        int status = failingRunner.execute(new DefaultApplicationArguments(ARGS));

        assertThat(status).isEqualTo(CustomerGeneratorRunner.EXIT_FAILURE);
        verifyNoInteractions(loader, jdbcTemplate);
        assertThat(printedLines()).containsExactly("Load failed: " + CAUSE_LINE);
        String log = output.getAll();
        assertThat(errorLines(log, "generator.failed unexpected error")).hasSize(1);
        assertValueFreeGraph(log, IllegalStateException.class);
    }

    /**
     * Gives a failure the cause and the suppressed exception of a failed {@code COPY}: an {@link SQLException}
     * whose message quotes the copied record, and a failure of the cleanup, each with sentinels and line breaks.
     *
     * @param top the top exception, created without a cause and with {@link #TOP_MESSAGE}
     * @return {@code top}
     */
    private static RuntimeException withSentinelLinks(RuntimeException top) {
        top.initCause(new SQLException(CAUSE_MESSAGE, NOT_NULL_VIOLATION));
        top.addSuppressed(new IllegalStateException(SUPPRESSED_MESSAGE));
        return top;
    }

    /**
     * Asserts that a captured log carries the value-free copy of a {@link #withSentinelLinks} failure: every
     * exception's type, the cause's SQLSTATE and the stack frames, and no text of any message, so no forged line.
     *
     * @param log the captured log
     * @param top the type of the failure's top exception
     */
    private static void assertValueFreeGraph(String log, Class<?> top) {
        assertThat(log)
                .contains(top.getName() + " [message withheld]")
                .contains("Caused by: ")
                .contains(redactedSqlException(NOT_NULL_VIOLATION))
                .contains("Suppressed: ")
                .contains("java.lang.IllegalStateException [message withheld]")
                .doesNotContain(SENTINELS);
        String testFrame = "\tat " + CustomerGeneratorRunnerLoadFailureTest.class.getName() + ".";
        assertThat(log.lines())
                .anyMatch(line -> line.startsWith(testFrame))
                .noneMatch(line -> line.strip().startsWith("FORGED"));
    }

    /**
     * Returns the value-free text of a plain {@link SQLException} with vendor code 0.
     *
     * @param sqlState its SQLSTATE
     * @return the text, for example {@code java.sql.SQLException [SQLSTATE 08006, vendor code 0; message withheld]}
     */
    private static String redactedSqlException(String sqlState) {
        return "java.sql.SQLException [SQLSTATE " + sqlState + ", vendor code 0; message withheld]";
    }

    /**
     * Returns the ERROR lines of a captured log that carry a message.
     *
     * @param log     the captured log
     * @param message the text the line must carry
     * @return the matching lines
     */
    private static List<String> errorLines(String log, String message) {
        return log.lines().filter(line -> line.contains(" ERROR ") && line.contains(message)).toList();
    }

    /**
     * Asserts the report of a failure whose outcome the runner cannot know: status 1, exactly one printed line
     * naming the root cause with the verification advice, an ERROR log line carrying the advice and the
     * exception's value-free copy, no ANALYZE, and no claim anywhere that the table or the sequence is unchanged.
     *
     * @param status   the status {@link CustomerGeneratorRunner#execute} returned
     * @param output   the captured log
     * @param reported the type of the exception the loader threw, whose value-free copy the log must carry
     */
    private void assertOutcomeUnknownReported(int status, CapturedOutput output, Class<?> reported) {
        assertThat(status).isEqualTo(CustomerGeneratorRunner.EXIT_FAILURE);
        verify(loader).load(any());
        verifyNoInteractions(jdbcTemplate);

        assertThat(printedLines()).containsExactly("Load failed: " + CONNECTION_LOST + PRINTED_ADVICE);

        String log = output.getAll();
        assertThat(errorLines(log, "generator.load failed"))
                .singleElement(InstanceOfAssertFactories.STRING)
                .contains("the outcome is unknown")
                .contains(LOGGED_ADVICE);
        assertThat(log)
                .contains(reported.getName() + " [message withheld]")
                .contains(redactedSqlException(CONNECTION_FAILURE))
                .doesNotContain(CONNECTION_LOST)
                .doesNotContainIgnoringCase("unchanged");
        assertThat(printed.toString(UTF_8)).doesNotContainIgnoringCase("unchanged");
    }

    /**
     * Returns what the runner printed, one element per line.
     *
     * @return the printed lines
     */
    private List<String> printedLines() {
        return printed.toString(UTF_8).lines().toList();
    }
}
