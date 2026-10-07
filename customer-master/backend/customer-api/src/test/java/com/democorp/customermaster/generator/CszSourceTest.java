package com.democorp.customermaster.generator;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.democorp.customermaster.generator.CszSource.CszFileException;
import com.democorp.customermaster.generator.CszSource.CszRow;
import com.democorp.customermaster.service.StateService;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Specifies how {@link CszSource} treats U+0000 (NUL) in the city column of a CSZ file.
 *
 * <p><b>Why it is rejected.</b> PostgreSQL text cannot store U+0000, so a kept city carrying it would fail the
 * {@code COPY} into {@code custmast.city} inside the loader's transaction, after {@code TRUNCATE}. Strict UTF-8
 * decoding accepts byte {@code 0x00} and trimming keeps it, so {@code CszSource} itself must reject it: a
 * {@link CszFileException} with the usual one-line {@code CSZ file <location> line <N>:} message naming the
 * record's start line and the city column used ({@code city} or {@code primary_city}), raised before
 * {@link CustomerLoader} is ever called. The character is never removed or replaced.
 *
 * <p><b>What stays as it was.</b> Only a city in a row the 20-character rule and the STATES rule keep is checked,
 * so rows those rules drop stay dropped. Blank cities, non-ASCII cities, other control characters such as a tab,
 * and every other column are unchanged, and the bundled sample still loads its 200 rows.
 *
 * <p>Plain JUnit 5, Mockito and AssertJ: a real {@link CszSource} over a mocked {@link StateService}, CSV files
 * written with real {@code 0x00} bytes into a {@link TempDir}, and the runner through its package-private
 * constructor with a mocked loader and template. No Spring application context, no database, no Docker.
 */
@DisplayName("CszSource: a kept city containing U+0000 fails the load before any write")
final class CszSourceTest {

    /** U+0000, written by UTF-8 as the single byte {@code 0x00}. */
    private static final String NUL = "\u0000";

    /** The 50 states, DC and PR: every code of the bundled sample; anything else is unknown here. */
    private static final Set<String> KNOWN_STATES = Set.of(
            "AK", "AL", "AR", "AZ", "CA", "CO", "CT", "DC", "DE", "FL", "GA", "HI", "IA", "ID", "IL", "IN", "KS",
            "KY", "LA", "MA", "MD", "ME", "MI", "MN", "MO", "MS", "MT", "NC", "ND", "NE", "NH", "NJ", "NM", "NV",
            "NY", "OH", "OK", "OR", "PA", "PR", "RI", "SC", "SD", "TN", "TX", "UT", "VA", "VT", "WA", "WI", "WV",
            "WY");

    /** Any C0 control, DEL, any C1 control, or a Unicode line or paragraph separator. */
    private static final Pattern CONTROL = Pattern.compile("[\\x00-\\x1F\\x7F-\\x9F\\u2028\\u2029]");

    /** What every NUL message says after the quoted city. */
    private static final String NUL_REASON =
            " contains U+0000 (NUL), which custmast.city cannot store; remove the character from the file";

    /** The fixed load time of the runner case. */
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-07T12:00:00Z"), ZoneOffset.UTC);

    /** Receives the files each case writes. */
    @TempDir
    Path dir;

    /** The reader under test, over a mocked STATES cache. */
    private CszSource source;

    @BeforeEach
    void setUp() {
        // StateService matches case-insensitively; the mock does too.
        StateService stateService = mock(StateService.class);
        when(stateService.exists(anyString()))
                .thenAnswer(call -> KNOWN_STATES.contains(call.<String>getArgument(0).toUpperCase(Locale.ROOT)));
        source = new CszSource(stateService);
    }

    @Test
    @DisplayName("a city with NUL fails with the location, the record's line and the city column, on one line")
    void nulInCityFailsNamingLineAndColumn() throws IOException {
        Path file = csv("nul-city.csv", "zip,city,state", "501,AB" + NUL + "CD,NY");
        assertThat(Files.readAllBytes(file)).as("the file holds a real zero byte").contains((byte) 0);

        CszFileException failure = loadFailure(file);

        assertThat(failure.getMessage())
                .isEqualTo(nulMessage(file, 2, "city", "AB CD"))
                .contains(file.toString(), "line 2", "city", "U+0000");
        assertOneLine(failure.getMessage());
    }

    @Test
    @DisplayName("a primary_city with NUL fails naming primary_city")
    void nulInPrimaryCityNamesPrimaryCity() throws IOException {
        Path file = csv("nul-primary-city.csv",
                "zip,type,primary_city,state", "00501,UNIQUE,Holts" + NUL + "ville,NY");

        CszFileException failure = loadFailure(file);

        assertThat(failure.getMessage()).isEqualTo(nulMessage(file, 2, "primary_city", "Holts ville"));
        assertOneLine(failure.getMessage());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("nulPositions")
    @DisplayName("NUL anywhere in a kept city fails, at the line on which its record starts")
    void nulAnywhereFailsAtTheRecordStartLine(String label, List<String> lines, long line, String shown)
            throws IOException {
        Path file = csv("nul-position.csv", lines.toArray(String[]::new));

        CszFileException failure = loadFailure(file);

        assertThat(failure.getMessage()).isEqualTo(nulMessage(file, line, "city", shown));
        assertOneLine(failure.getMessage());
    }

    /**
     * NUL positions and record layouts: label, file lines, expected start line, city as the message shows it.
     *
     * @return the cases
     */
    static Stream<Arguments> nulPositions() {
        return Stream.of(
                Arguments.of("NUL first", List.of("zip,city,state", "501," + NUL + "ABC,NY"), 2L, " ABC"),
                Arguments.of("NUL last", List.of("zip,city,state", "501,ABC" + NUL + ",NY"), 2L, "ABC "),
                Arguments.of("NUL alone between blanks",
                        List.of("zip,city,state", "501,  " + NUL + "  ,NY"), 2L, " "),
                Arguments.of("NUL in a 20-code-point city",
                        List.of("zip,city,state", "501,ABCDEFGHIJKLMNOPQRS" + NUL + ",NY"), 2L,
                        "ABCDEFGHIJKLMNOPQRS "),
                Arguments.of("after a valid row and a multi-line quoted record",
                        List.of("zip,city,state", "10001,NEW YORK,NY", "94105,\"SAN", "FRANCISCO\",CA",
                                "501,AB" + NUL + "CD,NY"), 5L, "AB CD"),
                Arguments.of("inside a multi-line quoted record",
                        List.of("zip,city,state", "10001,NEW YORK,NY", "94105,\"SAN" + NUL, "FRANCISCO\",CA"),
                        3L, "SAN  FRANCISCO"));
    }

    @Test
    @DisplayName("the runner prints only the CSZ message, exits 1 and never calls the loader or the template")
    void runnerRejectsNulCityBeforeAnyWrite() throws IOException {
        Path file = csv("nul-city.csv", "zip,city,state", "501,AB" + NUL + "CD,NY");
        CustomerLoader loader = mock(CustomerLoader.class);
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        ByteArrayOutputStream printed = new ByteArrayOutputStream();
        GeneratorProperties options = new GeneratorProperties(1, "C000", file.toString(), 7L);
        CustomerGeneratorRunner runner = new CustomerGeneratorRunner(options, source, loader, jdbcTemplate, CLOCK,
                new PrintStream(printed, true, UTF_8));

        int status = runner.execute(new DefaultApplicationArguments("--spring.profiles.active=generator",
                "--count=1", "--start-id=C000", "--csz-file=" + file, "--seed=7"));

        assertThat(status).isEqualTo(CustomerGeneratorRunner.EXIT_FAILURE);
        assertThat(printed.toString(UTF_8).lines().toList())
                .containsExactly(loadFailure(file).getMessage())
                .containsExactly(nulMessage(file, 2, "city", "AB CD"));
        verifyNoInteractions(loader, jdbcTemplate);
    }

    @Test
    @DisplayName("NUL in a row dropped for its state or its city width is dropped with the row, without failing")
    void nulInDroppedRowsIsDroppedWithoutFailing() throws IOException {
        Path file = csv("nul-dropped.csv", "zip,city,state",
                "501,AB" + NUL + "CD,ZZ",
                "502,ABCDEFGHIJKLMNOPQRST" + NUL + ",NY",
                "10001,New York,NY");

        assertThat(source.load(file.toString())).containsExactly(new CszRow(10001, "", "NEW YORK", "NY"));
    }

    @Test
    @DisplayName("NUL in a column the reader does not use is ignored, as the column is")
    void nulInUnusedColumnIsIgnored() throws IOException {
        Path file = csv("nul-unused.csv", "zip,city,primary_city,state,county",
                "10001,New York,Man" + NUL + "hattan,NY,New " + NUL + "York County");

        assertThat(source.load(file.toString())).containsExactly(new CszRow(10001, "", "NEW YORK", "NY"));
    }

    @Test
    @DisplayName("blank, 20-character, non-ASCII and tab-bearing cities are kept as before; long and unknown dropped")
    void otherRowRulesAreUnchanged() throws IOException {
        Path file = csv("unchanged.csv", "zip,type,city,state",
                "501,UNIQUE,,NY",
                "502,STANDARD,   ,ny",
                "503,STANDARD,ABCDEFGHIJKLMNOPQRST,CA",
                "504,STANDARD,ABCDEFGHIJKLMNOPQRSTU,CA",
                "505,STANDARD,Albany,ZZ",
                "624,STANDARD,Peñuelas,PR",
                "506,STANDARD,San\tJuan,PR");

        assertThat(source.load(file.toString())).containsExactly(
                new CszRow(501, "UNIQUE", "", "NY"),
                new CszRow(502, "STANDARD", "", "ny"),
                new CszRow(503, "STANDARD", "ABCDEFGHIJKLMNOPQRST", "CA"),
                new CszRow(624, "STANDARD", "PEÑUELAS", "PR"),
                new CszRow(506, "STANDARD", "SAN\tJUAN", "PR"));
    }

    @Test
    @DisplayName("the bundled sample still loads its 200 rows")
    void bundledSampleStillLoads() {
        List<CszRow> rows = source.load(GeneratorProperties.DEFAULT_CSZ_FILE);

        assertThat(rows).hasSize(200);
        assertThat(rows).noneMatch(row -> row.city().contains(NUL));
    }

    /**
     * Writes a UTF-8 CSV file, one element per line, each ending in {@code \n}; U+0000 becomes byte {@code 0x00}.
     *
     * @param name  the file name inside {@link #dir}
     * @param lines the lines without terminators
     * @return the written file
     * @throws IOException if the file cannot be written
     */
    private Path csv(String name, String... lines) throws IOException {
        Path file = dir.resolve(name);
        Files.write(file, (String.join("\n", lines) + "\n").getBytes(UTF_8));
        return file;
    }

    /**
     * Loads {@code file} and returns the {@link CszFileException} the load must raise.
     *
     * @param file the file
     * @return the exception
     */
    private CszFileException loadFailure(Path file) {
        CszFileException failure = catchThrowableOfType(CszFileException.class, () -> source.load(file.toString()));
        assertThat(failure).as("CszFileException from %s", file).isNotNull();
        return failure;
    }

    /**
     * The expected NUL message.
     *
     * @param file   the file
     * @param line   the record's 1-based start line
     * @param column the city column used
     * @param shown  the stripped city as the message shows it, each control character as a space
     * @return the message
     */
    private static String nulMessage(Path file, long line, String column, String shown) {
        return "CSZ file " + file + " line " + line + ": " + column + " \"" + shown + "\"" + NUL_REASON;
    }

    /**
     * Asserts that a message is one line with no control character.
     *
     * @param message the message
     */
    private static void assertOneLine(String message) {
        assertThat(CONTROL.matcher(message).find()).as("control character in %s", message).isFalse();
    }
}
