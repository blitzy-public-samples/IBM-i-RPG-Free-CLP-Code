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
 * the strict options, for arguments whose value is missing or empty and for fully qualified generator
 * options.
 *
 * <p><b>Why a missing value must fail.</b> Spring Boot parses a bare {@code --seed} as an option with no
 * value and exposes it as the property value {@code ""}, so the bridge in {@code application-generator.yml}
 * would bind an empty seed (random), an empty start id (the automatic start) or an empty CSZ file (the
 * bundled sample), and the load would replace the table and exit 0 although the operator left the value
 * out. Each of the four flags and each accepted fully qualified generator property given without a
 * value therefore prints {@code Option --<name> requires a value} and exits 1 before the CSZ file is
 * read or the database is touched.
 *
 * <p><b>Why an empty value must fail.</b> An explicitly empty or blank value, such as {@code --count=}
 * from a script whose {@code $N} is unset, is a present property that shadows the option's
 * {@code GENERATOR_*} variable in the bridge and then binds as the default, so the load would run with
 * that default (300 rows, the automatic start id, the bundled sample or a random seed), replace the
 * table and exit 0. It is rejected exactly like the bare flag, also when only one value of a repeated
 * option is empty.
 *
 * <p><b>Why a mistyped qualified name must fail.</b> A name under {@code customer-master.generator.}
 * that is not one of {@link CustomerGeneratorRunner#QUALIFIED_OPTIONS}, such as
 * {@code customer-master.generator.cuont}, sets no option, so the load would run with the defaults and
 * replace the table. It prints {@code Unknown option --<name>} and exits 1 before any work, with or
 * without a value.
 *
 * <p><b>Usage guidance.</b> Every unknown option or argument, {@code --help} included since the strict
 * options accept no help flag, is followed by exactly one {@link CustomerGeneratorRunner#USAGE} line
 * naming the four flags, their {@code GENERATOR_*} variables, ranges, defaults and an example; a
 * valueless or empty-valued option prints its one line only.
 *
 * <p><b>What stays accepted.</b> Each accepted qualified property with a non-blank value proceeds to
 * the load, a bare {@code --spring.*} option is not judged, and an unknown option still prints
 * {@code Unknown option --<name>} and the usage line, ahead of any missing or empty value.
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
                        new String[] {PROFILE_OPTION, "--count=5", "--customer-master.generator.startId"}),
                Arguments.of("customer-master.generator.start_id",
                        new String[] {PROFILE_OPTION, "--count=5", "--customer-master.generator.start_id"}),
                Arguments.of("customer-master.generator.cszFile",
                        new String[] {PROFILE_OPTION, "--count=5", "--customer-master.generator.cszFile"}),
                Arguments.of("customer-master.generator.csz_file",
                        new String[] {PROFILE_OPTION, "--count=5", "--customer-master.generator.csz_file"}));
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
        assertThat(printedLines()).containsExactly("Unknown option --cuont", CustomerGeneratorRunner.USAGE);
        verifyNoInteractions(cszSource, loader, jdbcTemplate);
    }

    @Test
    @DisplayName("a non-option argument is reported ahead of a valueless option")
    void nonOptionArgumentBeforeValueless() {
        int status = runner.execute(new DefaultApplicationArguments("--seed", "500"));

        assertThat(status).isEqualTo(CustomerGeneratorRunner.EXIT_FAILURE);
        assertThat(printedLines()).containsExactly("Unknown option 500", CustomerGeneratorRunner.USAGE);
        verifyNoInteractions(cszSource, loader, jdbcTemplate);
    }

    @Test
    @DisplayName("a mistyped flag still prints Unknown option --<name>")
    void mistypedFlagIsUnknown() {
        int status = runner.execute(new DefaultApplicationArguments(PROFILE_OPTION, "--cuont=5"));

        assertThat(status).isEqualTo(CustomerGeneratorRunner.EXIT_FAILURE);
        assertThat(printedLines()).containsExactly("Unknown option --cuont", CustomerGeneratorRunner.USAGE);
        verifyNoInteractions(cszSource, loader, jdbcTemplate);
    }

    @Test
    @DisplayName("--help is not an option: it prints Unknown option --help and the usage line, and does nothing")
    void helpIsUnknownAndPrintsTheUsageLine() {
        int status = runner.execute(new DefaultApplicationArguments(PROFILE_OPTION, "--help"));

        assertThat(status).isEqualTo(CustomerGeneratorRunner.EXIT_FAILURE);
        assertThat(printedLines()).containsExactly("Unknown option --help", CustomerGeneratorRunner.USAGE);
        verifyNoInteractions(cszSource, loader, jdbcTemplate);
    }

    @Test
    @DisplayName("the usage line names the four flags, their variables, ranges, defaults and an example")
    void usageLineNamesTheOptions() {
        assertThat(CustomerGeneratorRunner.USAGE)
                .startsWith("Usage: ")
                .isEqualTo(CustomerGeneratorRunner.oneLine(CustomerGeneratorRunner.USAGE))
                .doesNotContain("\n", "\r")
                .contains("[--count=N]", "[--start-id=XXXX]", "[--csz-file=LOCATION]", "[--seed=L]")
                .contains("GENERATOR_COUNT", "GENERATOR_START_ID", "GENERATOR_CSZ_FILE", "GENERATOR_SEED",
                        "the flag wins")
                .contains("1.." + GeneratorProperties.MAX_COUNT, "(1..1679616, default 300)")
                .contains("default 1001, or AAAA above 385245 rows")
                .contains("default " + GeneratorProperties.DEFAULT_CSZ_FILE, "classpath:generator/csz-sample.csv",
                        "/data/csz.csv")
                .contains("(default random)")
                .contains("--count=1000000");
        assertThat(CustomerGeneratorRunner.FLAGS)
                .allSatisfy(flag -> assertThat(CustomerGeneratorRunner.USAGE).contains("[--" + flag + "="));
    }

    /**
     * Names under {@code customer-master.generator.} that are not one of the accepted qualified
     * properties, each given with a value. None of them would set an option, so each would otherwise
     * load with the defaults and replace the table.
     *
     * @return the option name as printed and the command line
     */
    static Stream<Arguments> unknownQualifiedOptions() {
        return Stream.of(
                Arguments.of("customer-master.generator.cuont",
                        new String[] {PROFILE_OPTION, "--customer-master.generator.cuont=5"}),
                Arguments.of("customer-master.generator.bogus",
                        new String[] {PROFILE_OPTION, "--customer-master.generator.bogus=1", "--count=5"}),
                Arguments.of("customer-master.generator.Count",
                        new String[] {PROFILE_OPTION, "--customer-master.generator.Count=5"}),
                Arguments.of("customer-master.generator.count.x",
                        new String[] {PROFILE_OPTION, "--customer-master.generator.count.x=5"}),
                Arguments.of("customer-master.generator.count[0]",
                        new String[] {PROFILE_OPTION, "--customer-master.generator.count[0]=5"}),
                Arguments.of("customer-master.generator.start.id",
                        new String[] {PROFILE_OPTION, "--count=5", "--customer-master.generator.start.id=C000"}),
                Arguments.of("customer-master.generator.",
                        new String[] {PROFILE_OPTION, "--customer-master.generator.=5"}));
    }

    @ParameterizedTest(name = "--{0}")
    @MethodSource("unknownQualifiedOptions")
    @DisplayName("a mistyped fully qualified generator option prints Unknown option --<name> before any work")
    void mistypedQualifiedOptionIsUnknown(String name, String[] args) {
        int status = runner.execute(new DefaultApplicationArguments(args));

        assertThat(status).isEqualTo(CustomerGeneratorRunner.EXIT_FAILURE);
        assertThat(printedLines()).containsExactly("Unknown option --" + name, CustomerGeneratorRunner.USAGE);
        verifyNoInteractions(cszSource, loader, jdbcTemplate);
    }

    @Test
    @DisplayName("a mistyped fully qualified option without a value is unknown, not merely valueless")
    void bareMistypedQualifiedOptionIsUnknown() {
        int status = runner.execute(new DefaultApplicationArguments(
                PROFILE_OPTION, "--count=5", "--customer-master.generator.cuont"));

        assertThat(status).isEqualTo(CustomerGeneratorRunner.EXIT_FAILURE);
        assertThat(printedLines()).containsExactly(
                "Unknown option --customer-master.generator.cuont", CustomerGeneratorRunner.USAGE);
        verifyNoInteractions(cszSource, loader, jdbcTemplate);
    }

    /**
     * Each accepted fully qualified generator property given with a value.
     *
     * @return the option as given and the command line holding it
     */
    static Stream<Arguments> acceptedQualifiedOptions() {
        return Stream.of(
                "--customer-master.generator.count=5",
                "--customer-master.generator.start-id=C000",
                "--customer-master.generator.startId=C000",
                "--customer-master.generator.start_id=C000",
                "--customer-master.generator.csz-file=" + GeneratorProperties.DEFAULT_CSZ_FILE,
                "--customer-master.generator.cszFile=" + GeneratorProperties.DEFAULT_CSZ_FILE,
                "--customer-master.generator.csz_file=" + GeneratorProperties.DEFAULT_CSZ_FILE,
                "--customer-master.generator.seed=7")
                .map(option -> Arguments.of(option, new String[] {PROFILE_OPTION, option}));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("acceptedQualifiedOptions")
    @DisplayName("an accepted fully qualified generator option with a value proceeds to the load")
    void acceptedQualifiedOptionProceeds(String option, String[] args) {
        stubSuccessfulLoad();

        int status = runner.execute(new DefaultApplicationArguments(args));

        assertThat(status).isEqualTo(CustomerGeneratorRunner.EXIT_SUCCESS);
        assertThat(printedLines()).singleElement().asString()
                .matches("Loaded 5 customers C000\\.\\.C004 in \\d+\\.\\d s");
        verify(cszSource).load(GeneratorProperties.DEFAULT_CSZ_FILE);
        verify(loader).load(any(CustomerLoader.Plan.class));
        verify(jdbcTemplate).execute(CustomerGeneratorRunner.ANALYZE_SQL);
    }

    @Test
    @DisplayName("the accepted fully qualified options are exactly the four properties and their bound spellings")
    void acceptedQualifiedOptionsAreTheGeneratorProperties() {
        assertThat(CustomerGeneratorRunner.QUALIFIED_OPTIONS).containsExactlyInAnyOrder(
                "customer-master.generator.count",
                "customer-master.generator.start-id",
                "customer-master.generator.startId",
                "customer-master.generator.start_id",
                "customer-master.generator.csz-file",
                "customer-master.generator.cszFile",
                "customer-master.generator.csz_file",
                "customer-master.generator.seed");
    }

    /**
     * Each generator option given an empty or blank value, the other options of a valid load given with
     * values: the four flags, whitespace-only values, every accepted fully qualified property, and a
     * repeated flag of which one value is empty.
     *
     * @return the empty-valued option as given, the option name as printed and the command line
     */
    static Stream<Arguments> emptyValuedOptions() {
        return Stream.of(
                Arguments.of("--count=", "count",
                        new String[] {PROFILE_OPTION, "--count=", "--start-id=C000", "--seed=7"}),
                Arguments.of("--start-id=", "start-id",
                        new String[] {PROFILE_OPTION, "--count=5", "--start-id=", "--seed=7"}),
                Arguments.of("--csz-file=", "csz-file",
                        new String[] {PROFILE_OPTION, "--count=5", "--start-id=C000", "--csz-file=", "--seed=7"}),
                Arguments.of("--seed=", "seed",
                        new String[] {PROFILE_OPTION, "--count=5", "--start-id=C000", "--seed="}),
                Arguments.of("--seed=<blank>", "seed",
                        new String[] {PROFILE_OPTION, "--count=5", "--start-id=C000", "--seed= "}),
                Arguments.of("--count=<tab>", "count",
                        new String[] {PROFILE_OPTION, "--count=\t", "--start-id=C000", "--seed=7"}),
                Arguments.of("--csz-file=<blanks>", "csz-file",
                        new String[] {PROFILE_OPTION, "--count=5", "--start-id=C000", "--csz-file=   "}),
                Arguments.of("--customer-master.generator.count=", "customer-master.generator.count",
                        new String[] {PROFILE_OPTION, "--customer-master.generator.count=", "--start-id=C000"}),
                Arguments.of("--customer-master.generator.start-id=", "customer-master.generator.start-id",
                        new String[] {PROFILE_OPTION, "--count=5", "--customer-master.generator.start-id="}),
                Arguments.of("--customer-master.generator.startId=", "customer-master.generator.startId",
                        new String[] {PROFILE_OPTION, "--count=5", "--customer-master.generator.startId="}),
                Arguments.of("--customer-master.generator.start_id=", "customer-master.generator.start_id",
                        new String[] {PROFILE_OPTION, "--count=5", "--customer-master.generator.start_id="}),
                Arguments.of("--customer-master.generator.csz-file=", "customer-master.generator.csz-file",
                        new String[] {PROFILE_OPTION, "--count=5", "--customer-master.generator.csz-file="}),
                Arguments.of("--customer-master.generator.cszFile=", "customer-master.generator.cszFile",
                        new String[] {PROFILE_OPTION, "--count=5", "--customer-master.generator.cszFile="}),
                Arguments.of("--customer-master.generator.csz_file=", "customer-master.generator.csz_file",
                        new String[] {PROFILE_OPTION, "--count=5", "--customer-master.generator.csz_file="}),
                Arguments.of("--customer-master.generator.seed=", "customer-master.generator.seed",
                        new String[] {PROFILE_OPTION, "--count=5", "--customer-master.generator.seed="}),
                Arguments.of("--csz-file=x --csz-file=", "csz-file",
                        new String[] {PROFILE_OPTION, "--count=5", "--csz-file=x", "--csz-file="}),
                Arguments.of("--seed= --seed=7", "seed",
                        new String[] {PROFILE_OPTION, "--count=5", "--seed=", "--seed=7"}));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("emptyValuedOptions")
    @DisplayName("a generator option with an empty or blank value fails like a bare one, before any CSZ read or"
            + " database work")
    void emptyValuedGeneratorOptionFails(String given, String name, String[] args) {
        int status = runner.execute(new DefaultApplicationArguments(args));

        assertThat(status).as("exit status of %s", given).isEqualTo(CustomerGeneratorRunner.EXIT_FAILURE);
        assertThat(printedLines()).as("printed lines of %s", given)
                .containsExactly("Option --" + name + " requires a value");
        verifyNoInteractions(cszSource, loader, jdbcTemplate);
    }

    @Test
    @DisplayName("empty-valued and valueless options together report the first in sorted order")
    void firstEmptyOrValuelessOptionInSortedOrder() {
        int status = runner.execute(new DefaultApplicationArguments("--seed", "--start-id=", "--count=5",
                "--csz-file="));

        assertThat(status).isEqualTo(CustomerGeneratorRunner.EXIT_FAILURE);
        assertThat(printedLines()).containsExactly("Option --csz-file requires a value");
        verifyNoInteractions(cszSource, loader, jdbcTemplate);
    }

    @Test
    @DisplayName("an unknown option is reported ahead of an empty-valued one")
    void unknownOptionBeforeEmptyValued() {
        int status = runner.execute(new DefaultApplicationArguments("--count=", "--cuont=5"));

        assertThat(status).isEqualTo(CustomerGeneratorRunner.EXIT_FAILURE);
        assertThat(printedLines()).containsExactly("Unknown option --cuont", CustomerGeneratorRunner.USAGE);
        verifyNoInteractions(cszSource, loader, jdbcTemplate);
    }

    @Test
    @DisplayName("an empty --spring.* value is not judged")
    void emptySpringOptionAccepted() {
        stubSuccessfulLoad();

        int status = runner.execute(new DefaultApplicationArguments(
                PROFILE_OPTION, "--spring.main.banner-mode=", "--count=5", "--start-id=C000", "--seed=7"));

        assertThat(status).isEqualTo(CustomerGeneratorRunner.EXIT_SUCCESS);
        assertThat(printedLines()).singleElement().asString().startsWith("Loaded 5 customers C000..C004 in ");
        verify(cszSource).load(GeneratorProperties.DEFAULT_CSZ_FILE);
        verify(loader).load(any(CustomerLoader.Plan.class));
        verify(jdbcTemplate).execute(CustomerGeneratorRunner.ANALYZE_SQL);
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
