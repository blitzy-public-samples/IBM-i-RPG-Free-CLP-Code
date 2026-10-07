package com.democorp.customermaster.generator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.democorp.customermaster.generator.CszSource.CszRow;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Specifies step 1 of {@link CustomerGeneratorRunner#execute(org.springframework.boot.ApplicationArguments)},
 * the strict options, for arguments whose value is missing.
 *
 * <p><b>Why a missing value must fail.</b> Spring Boot parses a bare {@code --seed} as an option with no
 * value and exposes it as the property value {@code ""}, so the bridge in {@code application-generator.yml}
 * would bind an empty seed (random), an empty start id (the automatic start) or an empty CSZ file (the
 * bundled sample), and the load would replace the table and exit 0 although the operator left the value
 * out. Each of the four flags and each fully qualified {@code customer-master.generator.*} property
 * given without a value therefore prints {@code Option --<name> requires a value} and exits 1 before
 * the CSZ file is read or the database is touched.
 *
 * <p><b>What stays accepted.</b> An explicitly empty value such as {@code --seed=} still selects its
 * default (an empty start id is the automatic start, an empty seed is random), a bare {@code --spring.*}
 * option is not judged, and an unknown option still prints {@code Unknown option --<name>}, ahead of
 * any missing value.
 *
 * <p>The runner is built through its package-private constructor with mocked collaborators and a
 * captured output stream, so the options are judged exactly as the runner receives them from
 * {@link DefaultApplicationArguments}; binding itself is not exercised here.
 */
class CustomerGeneratorRunnerOptionsTest {

    /** The bound options every run sees; the command lines below only decide what step 1 judges. */
    private static final GeneratorProperties PROPERTIES =
            new GeneratorProperties(5, "C000", GeneratorProperties.DEFAULT_CSZ_FILE, null);

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-01-05T08:30:00Z"), ZoneOffset.UTC);

    private static final List<CszRow> CSZ_ROWS = List.of(new CszRow(12345, "STANDARD", "SCHENECTADY", "NY"));

    private static final String PROFILE_OPTION = "--spring.profiles.active=" + CustomerGeneratorRunner.PROFILE;

    private CszSource cszSource;

    private CustomerLoader loader;

    private JdbcTemplate jdbcTemplate;

    private ByteArrayOutputStream captured;

    private CustomerGeneratorRunner runner;

    @BeforeEach
    void setUp() {
        cszSource = mock(CszSource.class);
        loader = mock(CustomerLoader.class);
        jdbcTemplate = mock(JdbcTemplate.class);
        captured = new ByteArrayOutputStream();
        runner = new CustomerGeneratorRunner(PROPERTIES, cszSource, loader, jdbcTemplate, CLOCK,
                new PrintStream(captured, true, StandardCharsets.UTF_8));
    }

    /**
     * Each generator option without a value, the other options of a valid load given with values.
     *
     * @return the option name as printed and the command line
     */
    static Stream<Arguments> valuelessOptions() {
        return Stream.of(
                Arguments.of("count", new String[] {PROFILE_OPTION, "--count", "--start-id=C000", "--seed=7"}),
                Arguments.of("start-id", new String[] {PROFILE_OPTION, "--count=5", "--start-id", "--seed=7"}),
                Arguments.of("csz-file",
                        new String[] {PROFILE_OPTION, "--count=5", "--start-id=C000", "--csz-file", "--seed=7"}),
                Arguments.of("seed", new String[] {PROFILE_OPTION, "--count=5", "--start-id=C000", "--seed"}),
                Arguments.of("customer-master.generator.count",
                        new String[] {PROFILE_OPTION, "--customer-master.generator.count", "--start-id=C000"}),
                Arguments.of("customer-master.generator.start-id",
                        new String[] {PROFILE_OPTION, "--count=5", "--customer-master.generator.start-id"}),
                Arguments.of("customer-master.generator.csz-file",
                        new String[] {PROFILE_OPTION, "--count=5", "--customer-master.generator.csz-file"}),
                Arguments.of("customer-master.generator.seed",
                        new String[] {PROFILE_OPTION, "--count=5", "--customer-master.generator.seed"}),
                Arguments.of("customer-master.generator.startId",
                        new String[] {PROFILE_OPTION, "--count=5", "--customer-master.generator.startId"}));
    }

    @ParameterizedTest(name = "--{0}")
    @MethodSource("valuelessOptions")
    @DisplayName("a generator option without a value fails before any CSZ read or database work")
    void valuelessGeneratorOptionFails(String name, String[] args) {
        int status = runner.execute(new DefaultApplicationArguments(args));

        assertThat(status).isEqualTo(CustomerGeneratorRunner.EXIT_FAILURE);
        assertThat(printedLines()).containsExactly("Option --" + name + " requires a value");
        verifyNoInteractions(cszSource, loader, jdbcTemplate);
    }

    @Test
    @DisplayName("several valueless options report the first in sorted order")
    void firstValuelessOptionInSortedOrder() {
        int status = runner.execute(new DefaultApplicationArguments("--seed", "--start-id", "--count"));

        assertThat(status).isEqualTo(CustomerGeneratorRunner.EXIT_FAILURE);
        assertThat(printedLines()).containsExactly("Option --count requires a value");
        verifyNoInteractions(cszSource, loader, jdbcTemplate);
    }

    @Test
    @DisplayName("an unknown option is reported ahead of a valueless one")
    void unknownOptionBeforeValueless() {
        int status = runner.execute(new DefaultApplicationArguments("--seed", "--cuont=5"));

        assertThat(status).isEqualTo(CustomerGeneratorRunner.EXIT_FAILURE);
        assertThat(printedLines()).containsExactly("Unknown option --cuont");
        verifyNoInteractions(cszSource, loader, jdbcTemplate);
    }

    @Test
    @DisplayName("a non-option argument is reported ahead of a valueless option")
    void nonOptionArgumentBeforeValueless() {
        int status = runner.execute(new DefaultApplicationArguments("--seed", "500"));

        assertThat(status).isEqualTo(CustomerGeneratorRunner.EXIT_FAILURE);
        assertThat(printedLines()).containsExactly("Unknown option 500");
        verifyNoInteractions(cszSource, loader, jdbcTemplate);
    }

    @Test
    @DisplayName("a mistyped flag still prints Unknown option --<name>")
    void mistypedFlagIsUnknown() {
        int status = runner.execute(new DefaultApplicationArguments(PROFILE_OPTION, "--cuont=5"));

        assertThat(status).isEqualTo(CustomerGeneratorRunner.EXIT_FAILURE);
        assertThat(printedLines()).containsExactly("Unknown option --cuont");
        verifyNoInteractions(cszSource, loader, jdbcTemplate);
    }

    /**
     * Command lines with one explicitly empty value, which selects that option's default.
     *
     * @return the empty option and the command line holding it
     */
    static Stream<Arguments> explicitlyEmptyOptions() {
        return Stream.of(
                Arguments.of("--seed=", new String[] {PROFILE_OPTION, "--seed=", "--count=5", "--start-id=C000"}),
                Arguments.of("--start-id=", new String[] {PROFILE_OPTION, "--count=5", "--start-id=", "--seed=7"}),
                Arguments.of("--csz-file=",
                        new String[] {PROFILE_OPTION, "--count=5", "--start-id=C000", "--csz-file="}),
                Arguments.of("--customer-master.generator.seed=",
                        new String[] {PROFILE_OPTION, "--count=5", "--customer-master.generator.seed="}));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("explicitlyEmptyOptions")
    @DisplayName("an explicitly empty value is accepted and the load proceeds")
    void explicitlyEmptyValueProceeds(String emptyOption, String[] args) {
        stubSuccessfulLoad();

        int status = runner.execute(new DefaultApplicationArguments(args));

        assertThat(status).isEqualTo(CustomerGeneratorRunner.EXIT_SUCCESS);
        assertThat(printedLines()).singleElement().asString()
                .matches("Loaded 5 customers C000\\.\\.C004 in \\d+\\.\\d s");
        verify(cszSource).load(GeneratorProperties.DEFAULT_CSZ_FILE);
        verify(loader).load(any(CustomerLoader.Plan.class));
    }

    @Test
    @DisplayName("a bare --spring.* option is not rejected")
    void bareSpringOptionAccepted() {
        stubSuccessfulLoad();

        int status = runner.execute(new DefaultApplicationArguments(
                PROFILE_OPTION, "--spring.main.lazy-initialization", "--count=5", "--start-id=C000", "--seed=7"));

        assertThat(status).isEqualTo(CustomerGeneratorRunner.EXIT_SUCCESS);
        assertThat(printedLines()).singleElement().asString().startsWith("Loaded 5 customers C000..C004 in ");
        verify(cszSource).load(GeneratorProperties.DEFAULT_CSZ_FILE);
        verify(loader).load(any(CustomerLoader.Plan.class));
        verify(jdbcTemplate).execute(CustomerGeneratorRunner.ANALYZE_SQL);
    }

    /** Lets the run reach the report line: the CSZ source yields one row and the loader commits five. */
    private void stubSuccessfulLoad() {
        when(cszSource.load(GeneratorProperties.DEFAULT_CSZ_FILE)).thenReturn(CSZ_ROWS);
        when(loader.load(any(CustomerLoader.Plan.class))).thenReturn(5L);
    }

    /**
     * Returns what the runner printed.
     *
     * @return the printed lines, in order
     */
    private List<String> printedLines() {
        return captured.toString(StandardCharsets.UTF_8).lines().toList();
    }
}
