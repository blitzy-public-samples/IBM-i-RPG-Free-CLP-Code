package com.democorp.customermaster.generator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Specifies that {@link CustomerGeneratorRunner} neutralizes externally derived text in what it logs and
 * prints (CWE-117): the CSZ location comes from {@code --csz-file} or {@code GENERATOR_CSZ_FILE}, and
 * exception messages may embed it, so a line feed, a carriage return, an ISO control character such as ESC
 * or CSI, or U+2028/U+2029 must neither split a log record or the outcome line nor reach a terminal.
 *
 * <p>The runner is built through its package-private constructor with mocked collaborators and a
 * {@link PrintStream} over a byte buffer, so every outcome line is captured exactly; the log goes to the
 * console, which {@link OutputCaptureExtension} captures.
 */
@ExtendWith(OutputCaptureExtension.class)
class CustomerGeneratorRunnerOutputTest {

    private static final String ESC = "\u001B";

    /** Any C0 control, DEL, any C1 control, or a Unicode line or paragraph separator. */
    private static final Pattern CONTROL = Pattern.compile("[\\x00-\\x1F\\x7F-\\x9F\\u2028\\u2029]");

    /** A location carrying a line feed and an ANSI colour sequence, as a file name may. */
    private static final String HOSTILE_LOCATION = "dropped\n" + ESC + "[31mRED.csv";

    /** {@link #HOSTILE_LOCATION} on one line: the line feed folds to a space, ESC becomes a space. */
    private static final String SHOWN_LOCATION = "dropped  [31mRED.csv";

    /** A location that, printed raw, would add a forged INFO record after the {@code generator.options} line. */
    private static final String FORGING_LOCATION = "/nope\n2026-10-07T00:00:00Z  INFO FORGED line";

    private static final Instant LOAD_TIME = Instant.parse("2026-10-07T12:00:00Z");

    private CszSource cszSource;

    private CustomerLoader loader;

    private JdbcTemplate jdbcTemplate;

    private ByteArrayOutputStream printed;

    @BeforeEach
    void setUp() {
        cszSource = mock(CszSource.class);
        loader = mock(CustomerLoader.class);
        jdbcTemplate = mock(JdbcTemplate.class);
        printed = new ByteArrayOutputStream();
    }

    private CustomerGeneratorRunner runner(String cszFile) {
        GeneratorProperties properties = new GeneratorProperties(5, "C200", cszFile, null);
        return new CustomerGeneratorRunner(properties, cszSource, loader, jdbcTemplate,
                Clock.fixed(LOAD_TIME, ZoneOffset.UTC), new PrintStream(printed, true, StandardCharsets.UTF_8));
    }

    private static int executeWithoutArguments(CustomerGeneratorRunner runner) {
        return runner.execute(new DefaultApplicationArguments());
    }

    private String printedText() {
        return printed.toString(StandardCharsets.UTF_8);
    }

    /** The one printed line without its terminator, after asserting that exactly one line was printed. */
    private String onlyPrintedLine() {
        String text = printedText();
        assertThat(text).endsWith(System.lineSeparator());
        String line = text.substring(0, text.length() - System.lineSeparator().length());
        assertThat(line).doesNotContain("\n", "\r");
        assertThat(CONTROL.matcher(line).find()).as("control character in %s", line).isFalse();
        return line;
    }

    private static List<String> lines(CapturedOutput output) {
        return output.getAll().lines().toList();
    }

    /**
     * The message of the one captured log record that contains {@code marker}, from the marker to the end
     * of its line, so the assertion does not depend on the console pattern before the message.
     */
    private static String loggedMessage(CapturedOutput output, String marker) {
        List<String> messages = lines(output).stream()
                .filter(line -> line.contains(marker))
                .map(line -> line.substring(line.indexOf(marker)))
                .toList();
        assertThat(messages).as("records containing %s", marker).hasSize(1);
        String message = messages.get(0);
        assertThat(CONTROL.matcher(message).find()).as("control character in %s", message).isFalse();
        return message;
    }

    private static List<CszSource.CszRow> oneRow() {
        return List.of(new CszSource.CszRow(501, "UNIQUE", "TESTVILLE", "NY"));
    }

    @Nested
    @DisplayName("oneLine")
    class OneLine {

        static Stream<Arguments> controls() {
            return Stream.of(
                    Arguments.of("LF", "\n"),
                    Arguments.of("CR", "\r"),
                    Arguments.of("CR LF", "\r\n"),
                    Arguments.of("TAB", "\t"),
                    Arguments.of("NUL", "\u0000"),
                    Arguments.of("ESC", "\u001B"),
                    Arguments.of("DEL", "\u007F"),
                    Arguments.of("NEL", "\u0085"),
                    Arguments.of("CSI", "\u009B"),
                    Arguments.of("LINE SEPARATOR", "\u2028"),
                    Arguments.of("PARAGRAPH SEPARATOR", "\u2029"));
        }

        @ParameterizedTest(name = "{0} becomes one space")
        @MethodSource("controls")
        void replacesEachControlWithOneSpace(String name, String control) {
            assertThat(CustomerGeneratorRunner.oneLine("left" + control + "right")).isEqualTo("left right");
        }

        @Test
        void replacesEveryIsoControlCharacter() {
            for (char c = 0; c <= 0x9F; c++) {
                if (c >= 0x20 && c < 0x7F) {
                    continue;
                }
                String result = CustomerGeneratorRunner.oneLine("a" + c + "b");
                assertThat(result).as("U+%04X", (int) c).isEqualTo("a b");
            }
        }

        @Test
        void foldsALineBreakWithItsBlanksIntoOneSpaceAndStrips() {
            assertThat(CustomerGeneratorRunner.oneLine("  first line \r\n   second line \n"))
                    .isEqualTo("first line second line");
        }

        @Test
        void neutralizesATerminalSequenceAndAForgedRecord() {
            String result = CustomerGeneratorRunner.oneLine(
                    "x.csv\n2026-10-07T00:00:00Z  INFO FORGED " + ESC + "[2J" + ESC + "]0;title\u0007");

            assertThat(result).isEqualTo("x.csv 2026-10-07T00:00:00Z  INFO FORGED  [2J ]0;title");
            assertThat(CONTROL.matcher(result).find()).isFalse();
        }

        @Test
        void keepsTextWithoutControlCharacters() {
            assertThat(CustomerGeneratorRunner.oneLine("classpath:generator/csz-sample.csv"))
                    .isEqualTo("classpath:generator/csz-sample.csv");
            assertThat(CustomerGeneratorRunner.oneLine("ZÜRICH ß é \uD83D\uDE00 a  b"))
                    .isEqualTo("ZÜRICH ß é \uD83D\uDE00 a  b");
        }

        @Test
        void returnsEmptyWhenOnlyControlsAndBlanksRemain() {
            assertThat(CustomerGeneratorRunner.oneLine(" " + ESC + "\u0000\n\t ")).isEmpty();
        }
    }

    @Nested
    @DisplayName("messageOf and rootMessage")
    class Messages {

        @Test
        void keepAnOrdinaryMessage() {
            IllegalArgumentException failure =
                    new IllegalArgumentException("customer id must be 4 characters A-Z or 0-9, was 'b000'");

            assertThat(CustomerGeneratorRunner.messageOf(failure))
                    .isEqualTo("customer id must be 4 characters A-Z or 0-9, was 'b000'");
            assertThat(CustomerGeneratorRunner.rootMessage(failure))
                    .isEqualTo("customer id must be 4 characters A-Z or 0-9, was 'b000'");
        }

        @Test
        void putAMultilineMessageWithControlsOnOneLine() {
            IllegalStateException failure = new IllegalStateException("first\n" + ESC + "[31msecond");

            assertThat(CustomerGeneratorRunner.messageOf(failure)).isEqualTo("first  [31msecond");
            assertThat(CustomerGeneratorRunner.rootMessage(failure)).isEqualTo("first  [31msecond");
        }

        @Test
        void skipAMessageOfControlsOnly() {
            IllegalStateException failure = new IllegalStateException(ESC + "\u0000",
                    new IllegalArgumentException("the cause"));

            assertThat(CustomerGeneratorRunner.messageOf(failure)).isEqualTo("the cause");
            assertThat(CustomerGeneratorRunner.rootMessage(new RuntimeException(ESC + "\n")))
                    .isEqualTo("RuntimeException");
        }
    }

    @Nested
    @DisplayName("execute")
    class Execute {

        @Test
        void printsOneSanitizedLineForAFileWithNoUsableRowsAndReadsTheRawLocation(CapturedOutput output) {
            when(cszSource.load(HOSTILE_LOCATION)).thenReturn(List.of());

            int status = executeWithoutArguments(runner(HOSTILE_LOCATION));

            assertThat(status).isEqualTo(CustomerGeneratorRunner.EXIT_FAILURE);
            assertThat(onlyPrintedLine()).isEqualTo("CSZ file " + SHOWN_LOCATION + " has no usable rows");
            verify(cszSource).load(HOSTILE_LOCATION);
            verifyNoInteractions(loader, jdbcTemplate);
            assertThat(loggedMessage(output, "generator.options"))
                    .isEqualTo("generator.options count=5 start=C200 cszFile=" + SHOWN_LOCATION + " mode=random");
            assertThat(lines(output)).noneMatch(line -> line.startsWith(ESC + "[31mRED.csv"));
        }

        @Test
        void logsTheOptionsOnOneRecordWhenTheLocationCarriesAForgedRecord(CapturedOutput output) {
            when(cszSource.load(FORGING_LOCATION))
                    .thenThrow(new CszSource.CszFileException("CSZ file /nope 2026-10-07T00:00:00Z  INFO FORGED line"
                            + " not found"));

            int status = executeWithoutArguments(runner(FORGING_LOCATION));

            assertThat(status).isEqualTo(CustomerGeneratorRunner.EXIT_FAILURE);
            assertThat(onlyPrintedLine()).isEqualTo("CSZ file /nope 2026-10-07T00:00:00Z  INFO FORGED line not found");
            verify(cszSource).load(FORGING_LOCATION);
            assertThat(loggedMessage(output, "generator.options")).isEqualTo(
                    "generator.options count=5 start=C200 cszFile=/nope 2026-10-07T00:00:00Z  INFO FORGED line"
                            + " mode=random");
            assertThat(lines(output)).noneMatch(line -> line.startsWith("2026-10-07T00:00:00Z"));
        }

        @Test
        void printsAnUnsanitizedCszMessageOnOneLine() {
            when(cszSource.load(HOSTILE_LOCATION))
                    .thenThrow(new CszSource.CszFileException("CSZ file " + HOSTILE_LOCATION + " not found"));

            int status = executeWithoutArguments(runner(HOSTILE_LOCATION));

            assertThat(status).isEqualTo(CustomerGeneratorRunner.EXIT_FAILURE);
            assertThat(onlyPrintedLine()).isEqualTo("CSZ file " + SHOWN_LOCATION + " not found");
        }

        @Test
        void printsTheSuccessLineUnchanged() {
            when(cszSource.load(GeneratorProperties.DEFAULT_CSZ_FILE)).thenReturn(oneRow());
            when(loader.load(any(CustomerLoader.Plan.class))).thenReturn(5L);

            int status = executeWithoutArguments(runner(null));

            assertThat(status).isEqualTo(CustomerGeneratorRunner.EXIT_SUCCESS);
            assertThat(onlyPrintedLine()).matches("Loaded 5 customers C200\\.\\.C204 in \\d+\\.\\d s");
            verify(jdbcTemplate).execute(CustomerGeneratorRunner.ANALYZE_SQL);
        }

        @Test
        void logsAnAnalyzeFailureOnOneRecordAndStillSucceeds(CapturedOutput output) {
            when(cszSource.load(GeneratorProperties.DEFAULT_CSZ_FILE)).thenReturn(oneRow());
            when(loader.load(any(CustomerLoader.Plan.class))).thenReturn(5L);
            doThrow(new DataAccessResourceFailureException("analyze\nFORGED record " + ESC + "[0m"))
                    .when(jdbcTemplate).execute(CustomerGeneratorRunner.ANALYZE_SQL);

            int status = executeWithoutArguments(runner(null));

            assertThat(status).isEqualTo(CustomerGeneratorRunner.EXIT_SUCCESS);
            assertThat(onlyPrintedLine()).startsWith("Loaded 5 customers C200..C204 in ");
            assertThat(loggedMessage(output, "generator.analyze failed")).endsWith(": analyze FORGED record  [0m");
            assertThat(lines(output)).noneMatch(line -> line.startsWith("FORGED"));
        }

        @Test
        void printsTheLockMessageUnchangedAndLogsItsCauseOnOneRecord(CapturedOutput output) {
            when(cszSource.load(GeneratorProperties.DEFAULT_CSZ_FILE)).thenReturn(oneRow());
            when(loader.load(any(CustomerLoader.Plan.class)))
                    .thenThrow(new CannotAcquireLockException("lock wait\nFORGED record " + ESC + "[0m"));

            int status = executeWithoutArguments(runner(null));

            assertThat(status).isEqualTo(CustomerGeneratorRunner.EXIT_FAILURE);
            assertThat(onlyPrintedLine()).isEqualTo(CustomerGeneratorRunner.LOCK_FAILURE_MESSAGE);
            assertThat(loggedMessage(output, "lock not granted")).endsWith(": lock wait FORGED record  [0m");
            assertThat(lines(output)).noneMatch(line -> line.startsWith("FORGED"));
        }

        @Test
        void printsAnUnexpectedLoadFailureOnOneLine() {
            when(cszSource.load(GeneratorProperties.DEFAULT_CSZ_FILE)).thenReturn(oneRow());
            when(loader.load(any(CustomerLoader.Plan.class)))
                    .thenThrow(new IllegalStateException("connection lost\n" + ESC + "[2J"));

            int status = executeWithoutArguments(runner(null));

            assertThat(status).isEqualTo(CustomerGeneratorRunner.EXIT_FAILURE);
            assertThat(onlyPrintedLine()).startsWith("Load failed: connection lost  [2J");
        }
    }
}
