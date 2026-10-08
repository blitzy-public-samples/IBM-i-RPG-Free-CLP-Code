package com.democorp.customermaster.generator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import com.democorp.customermaster.CustomerMasterApplication;
import com.democorp.customermaster.config.AppProperties;
import com.democorp.customermaster.domain.CustomerId;
import com.democorp.customermaster.security.SecurityConfig;
import com.democorp.customermaster.security.UsersProperties;
import com.democorp.customermaster.support.AbstractPostgresIT;
import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.TypeExcludeFilter;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.type.ClassMetadata;
import org.springframework.core.type.classreading.MetadataReader;
import org.springframework.core.type.classreading.MetadataReaderFactory;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Runs the packaged {@code customer-api} jar as the test-data generator CLI, a separate JVM started with
 * {@code java -jar app.jar --spring.profiles.active=generator}, against the shared Testcontainers
 * database, exactly as the Compose {@code generator} service runs it.
 *
 * <p><b>What it replaces.</b> LOADCUST2 [5250_Subfile/LOADCUST2.CLLE] took the row count as
 * {@code PARM(&NUM)} and submitted {@code CALL PGM(LOADCUSTR) PARM((&NUM))} to batch; LOADCUSTR
 * [5250_Subfile/LOADCUSTR.SQLRPGLE] truncated CUSTMAST, numbered the rows from {@code 1001} and loaded
 * them from the CSZ table. The target is one synchronous process whose outcome is its exit status and
 * one printed line, {@code Loaded <n> customers <first>..<last> in <s> s}.
 *
 * <p><b>What is proved here, and only here, end to end through the real jar.</b>
 * <ul>
 *   <li>The option bridge of {@code application-generator.yml}: the flat flags {@code --count},
 *       {@code --start-id}, {@code --csz-file} and {@code --seed} reach {@code GeneratorProperties};
 *       {@code GENERATOR_COUNT} applies without a flag, and a flag wins over its variable.</li>
 *   <li>Determinism: the same {@code --seed} loads the same names, compared by
 *       {@code md5(string_agg(name, ',' ORDER BY custid))}.</li>
 *   <li>Strictness: a mistyped flag exits 1 with {@code Unknown option --<name>} and leaves the table
 *       and the id sequence untouched.</li>
 *   <li>The properties registration rule: no run has a {@code CM_*} variable or a
 *       {@code customer-master.security.*} property. No inherited JVM option variable
 *       ({@code JDK_JAVA_OPTIONS}, {@code JAVA_TOOL_OPTIONS}, {@code _JAVA_OPTIONS}) reaches a child, so
 *       no {@code -D} system property exists in it, and a run whose invoking environment carries such
 *       variables, with {@code -D} count and security properties, still loads exactly its
 *       {@code GENERATOR_COUNT}. A run that adds user settings {@code UsersProperties} validation would
 *       reject still loads, because {@code SecurityConfig}, the only registrar of
 *       {@code UsersProperties}, is servlet-only and absent from the generator context. No run logs a
 *       web server, a {@code SecurityFilterChain}, a {@code customer-master.security.users} binding or
 *       the JVM's {@code Picked up ...} echo of an option variable.</li>
 *   <li>The same rule inside one context: an {@link ApplicationContextRunner} over the generator
 *       profile's configuration holds no {@code UsersProperties}, {@code SecurityConfig} or
 *       {@code SecurityFilterChain}, and holds {@code AppProperties} and {@code GeneratorProperties}.</li>
 * </ul>
 *
 * <p><b>Database.</b> The Spring test context inherited from {@link AbstractPostgresIT} has already
 * migrated V1 to V4 into {@value #SCHEMA} and empties {@code custmast} and restarts
 * {@code custmast_id_seq} before every test. The child receives the container's address through
 * {@code DB_HOST}, {@code DB_PORT}, {@code DB_NAME}, {@code DB_USER}, {@code DB_PASSWORD} and
 * {@code DB_SCHEMA}, the variables {@code application.yml} reads. The generator profile disables
 * Flyway, so the child runs no migration. Each successful load truncates and reloads {@code custmast}
 * and restarts {@code custmast_id_seq} in the same transaction; a rejected run, such as
 * {@code --cuont=5}, changes neither.
 *
 * <p><b>Process handling.</b> The child's stdout and stderr go to one file per run in a
 * {@link TempDir}, never to an unread pipe on which the child could block. Every run is bounded by
 * {@value #PROCESS_TIMEOUT_SECONDS} seconds; a child that overruns is killed with its descendants and
 * the test fails with its log, and a child still alive for any other reason is killed in
 * {@code finally}, so no JVM outlives its test. Every assertion about a run carries the child's whole
 * log in its message.
 *
 * <p>The jar is {@code target/app.jar}, passed by Failsafe as system property {@code app.jar.path};
 * Failsafe's {@code integration-test} phase runs after {@code package}, so {@code ./mvnw verify}
 * builds it first.
 */
@DisplayName("Generator CLI: the packaged jar run as a separate process")
class GeneratorProcessIT extends AbstractPostgresIT {

    /** Longest a single generator run may take before it is killed and the test fails. */
    private static final long PROCESS_TIMEOUT_SECONDS = 180;

    /** Longest wait for a killed child to be reaped. */
    private static final long DESTROY_WAIT_SECONDS = 10;

    /** The schema the test context migrated and the child must use ({@code DB_SCHEMA}). */
    private static final String SCHEMA = "customer_master";

    /** The bundled test CSV as a class-path resource, the form {@link CszSource#load(String)} reads. */
    private static final String TEST_CSZ_LOCATION = "classpath:generator/test-csz.csv";

    /** The same CSV as a resource path, resolved to an absolute file for {@code --csz-file}. */
    private static final String TEST_CSZ_RESOURCE = "/generator/test-csz.csv";

    /** Rows of {@code test-csz.csv} that {@link CszSource} keeps: 14 minus state ZZ and a 21-character city. */
    private static final int TEST_CSZ_RETAINED_ROWS = 12;

    /** Checksum of the stored names in id order; empty for an empty table. */
    private static final String CHECKSUM_SQL =
            "SELECT coalesce(md5(string_agg(name, ',' ORDER BY custid)), '') FROM custmast";

    /** The state of the id sequence a run with a rejected option must leave as it was. */
    private static final String SEQUENCE_STATE_SQL = "SELECT last_value, is_called FROM custmast_id_seq";

    /** One row inserted directly, so an untouched table is distinguishable from an emptied one. */
    private static final String PRELOAD_SQL = "INSERT INTO custmast (custid, name, addr, city, state, zip,"
            + " corpphone, acctmgr, acctphone, active, chgtime, chguser, row_version) VALUES ('B000', 'KEEP ME',"
            + " '1 MAIN ST', 'TESTVILLE', 'NY', '00501', '(100) 001-0001', 'A B', '(100) 001-0001', 'Y', now(),"
            + " '*SYSTEM*', 0)";

    /**
     * Inherited environment variables removed before a run, by name prefix. The plan names {@code CM_},
     * {@code CUSTOMER_MASTER_SECURITY_}, {@code SPRING_FLYWAY_}, {@code GENERATOR_} and
     * {@code SPRING_DATASOURCE_}; {@code CUSTOMER_MASTER_} covers {@code CUSTOMER_MASTER_SECURITY_} and
     * also removes every other relaxed-binding form of a {@code customer-master.*} property, such as
     * {@code CUSTOMER_MASTER_GENERATOR_COUNT}, which outranks the option bridge of
     * {@code application-generator.yml} and would override both {@code GENERATOR_COUNT} and
     * {@code --count}. {@code SPRING_CONFIG_} is removed because {@code SPRING_CONFIG_IMPORT},
     * {@code SPRING_CONFIG_LOCATION} and {@code SPRING_CONFIG_ADDITIONAL_LOCATION} can load a file that
     * carries {@code customer-master.security.*} properties. {@code DB_} is removed as well, so a stray
     * variable such as {@code DB_LOCK_TIMEOUT} in the developer's shell cannot change a run; every
     * {@code DB_*} the child needs is set again by {@link #run(Map, Map, String...)}.
     */
    private static final List<String> SCRUBBED_PREFIXES = List.of(
            "CM_", "CUSTOMER_MASTER_", "SPRING_CONFIG_", "SPRING_FLYWAY_", "GENERATOR_", "SPRING_DATASOURCE_",
            "DB_");

    /**
     * Inherited environment variables removed before a run, by exact name. {@code SPRING_APPLICATION_JSON}
     * is removed besides the planned {@code SPRING_PROFILES_ACTIVE}, because it could carry a
     * {@code customer-master.security.*} property into the child, which every run must be free of. The
     * JVM option variables {@code JDK_JAVA_OPTIONS} (read by the {@code java} launcher),
     * {@code JAVA_TOOL_OPTIONS} (read by every JVM) and {@code _JAVA_OPTIONS} (read by HotSpot) are
     * removed because each one adds its options to the child's command line: a {@code -D} among them
     * becomes a system property, which outranks the option bridge and could set a
     * {@code customer-master.security.*} property, and the JVM echoes the variable as
     * {@code Picked up ...}, which {@link #FORBIDDEN_LOG_TEXT} rejects.
     */
    private static final Set<String> SCRUBBED_NAMES = Set.of("SPRING_PROFILES_ACTIVE", "SPRING_APPLICATION_JSON",
            "JDK_JAVA_OPTIONS", "JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS");

    /**
     * Text no generator log may contain: a servlet container or reactive server start, a security filter
     * chain, or a user binding or validation message ({@code customer-master.security.users[0].role}).
     * {@code Picked up } is the line the JVM prints when it reads {@code JDK_JAVA_OPTIONS},
     * {@code JAVA_TOOL_OPTIONS} or {@code _JAVA_OPTIONS}, so its absence shows that no JVM option variable
     * reached the run.
     */
    private static final List<String> FORBIDDEN_LOG_TEXT = List.of(
            "Tomcat", "Netty started", "SecurityFilterChain", "customer-master.security.users", "Picked up ");

    /** Matched case-insensitively: Spring Boot writes both "web server" and "Web server". */
    private static final String FORBIDDEN_LOG_TEXT_ANY_CASE = "web server";

    /**
     * Spring Boot's startup line, logged after the context refresh and before the runner. Its presence
     * shows that the child's log was captured, so the absence checks above are not vacuous.
     */
    private static final String STARTED_LOG_TEXT = "Started CustomerMasterApplication";

    /**
     * Any successful outcome line. Other startup lines also begin with "Loaded", such as the message
     * catalog's {@code Loaded 22 message texts ...}, so a rejected run is checked against this shape.
     */
    private static final Pattern LOADED_REPORT = Pattern.compile("(?m)^Loaded \\d+ customers ");

    /** One file per run under {@link #logDir}. */
    private final AtomicInteger runs = new AtomicInteger();

    /** Receives the child's combined stdout and stderr, one file per run. */
    @TempDir
    Path logDir;

    /** The reader the generator itself uses, to learn which cities of the test CSV a load may draw. */
    @Autowired
    private CszSource cszSource;

    /** The outcome of one child process: its exit status and its whole combined output. */
    private record RunResult(int exitCode, String output) {

        /**
         * Describes the run for an assertion message.
         *
         * @return the exit status followed by the child's whole log
         */
        String describe() {
            return "exit status " + exitCode + "; generator output:" + System.lineSeparator() + output;
        }
    }

    @Test
    @DisplayName("--count, --start-id, --seed and --csz-file load those rows, and the same seed loads the same names")
    void flagsLoadTheRequestedRowsAndTheSameSeedRepeatsThem() {
        String[] args = {"--count=500", "--start-id=B000", "--seed=7", "--csz-file=" + testCszPath()};
        CustomerId start = CustomerId.parse("B000");

        RunResult first = run(Map.of(), args);

        assertSucceeded(first);
        assertLoadedReport(first, 500, start);
        assertThat(rowCount()).as("rows after the first run; %s", first.describe()).isEqualTo(500);
        assertThat(jdbcTemplate.queryForObject("SELECT custid FROM custmast ORDER BY custid LIMIT 1", String.class))
                .as("first id by ORDER BY custid; %s", first.describe())
                .isEqualTo("B000");
        assertThat(storedIdsInOrdinalOrder())
                .as("500 successive ordinals from B000; %s", first.describe())
                .containsExactlyElementsOf(successiveIds(start, 500));
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM custmast WHERE city NOT LIKE 'TEST%'",
                Long.class))
                .as("rows whose city is not from the test CSV; %s", first.describe())
                .isZero();

        Set<String> csvCityStates = cszSource.load(TEST_CSZ_LOCATION).stream()
                .map(row -> row.city() + "|" + row.state())
                .collect(Collectors.toSet());
        assertThat(csvCityStates).as("retained rows of %s", TEST_CSZ_LOCATION).hasSize(TEST_CSZ_RETAINED_ROWS);
        assertThat(jdbcTemplate.queryForList("SELECT DISTINCT city || '|' || state FROM custmast", String.class))
                .as("stored city and state pairs, each one row of the test CSV; %s", first.describe())
                .isNotEmpty()
                .isSubsetOf(csvCityStates);

        String checksum = checksum();
        RunResult second = run(Map.of(), args);

        assertSucceeded(second);
        assertLoadedReport(second, 500, start);
        assertThat(rowCount()).as("rows after the second run; %s", second.describe()).isEqualTo(500);
        assertThat(checksum())
                .as("name checksum of a second run with --seed=7; %s", second.describe())
                .isEqualTo(checksum);
    }

    @Test
    @DisplayName("GENERATOR_COUNT sets the count when no --count flag is given")
    void generatorCountVariableAppliesWithoutAFlag() {
        RunResult result = run(Map.of("GENERATOR_COUNT", "40"));

        assertSucceeded(result);
        assertLoadedReport(result, 40, CustomerGeneratorRunner.DEFAULT_START);
        assertThat(rowCount()).as("rows loaded with GENERATOR_COUNT=40; %s", result.describe()).isEqualTo(40);
    }

    @Test
    @DisplayName("--count wins over GENERATOR_COUNT")
    void countFlagWinsOverTheGeneratorCountVariable() {
        RunResult result = run(Map.of("GENERATOR_COUNT", "40"), "--count=60");

        assertSucceeded(result);
        assertLoadedReport(result, 60, CustomerGeneratorRunner.DEFAULT_START);
        assertThat(rowCount())
                .as("rows loaded with GENERATOR_COUNT=40 and --count=60; %s", result.describe())
                .isEqualTo(60);
    }

    @Test
    @DisplayName("A mistyped flag exits 1 with 'Unknown option' and leaves the table and the sequence untouched")
    void unknownOptionExitsOneAndChangesNothing() {
        jdbcTemplate.update(PRELOAD_SQL);
        long countBefore = rowCount();
        String checksumBefore = checksum();
        Map<String, Object> sequenceBefore = jdbcTemplate.queryForMap(SEQUENCE_STATE_SQL);

        RunResult result = run(Map.of(), "--cuont=5");

        assertThat(result.exitCode()).as("exit status of --cuont=5; %s", result.describe())
                .isEqualTo(CustomerGeneratorRunner.EXIT_FAILURE);
        assertThat(result.output()).as("output of --cuont=5; %s", result.describe())
                .contains("Unknown option --cuont")
                .doesNotContainPattern(LOADED_REPORT);
        assertGeneratorLog(result);
        assertThat(rowCount()).as("row count after --cuont=5; %s", result.describe()).isEqualTo(countBefore);
        assertThat(checksum()).as("name checksum after --cuont=5; %s", result.describe()).isEqualTo(checksumBefore);
        assertThat(jdbcTemplate.queryForMap(SEQUENCE_STATE_SQL))
                .as("custmast_id_seq after --cuont=5; %s", result.describe())
                .isEqualTo(sequenceBefore);
    }

    @Test
    @DisplayName("User settings UsersProperties would reject are never bound in the generator context")
    void securityUserSettingsAreNeverBoundByTheGenerator() {
        RunResult result = run(
                Map.of("CUSTOMER_MASTER_SECURITY_USERS_0_ROLE", "BOGUS",
                        "CUSTOMER_MASTER_SECURITY_USERS_0_PASSWORD", ""),
                "--count=25", "--seed=3");

        assertSucceeded(result);
        assertLoadedReport(result, 25, CustomerGeneratorRunner.DEFAULT_START);
        assertThat(rowCount())
                .as("rows loaded with invalid customer-master.security.users settings; %s", result.describe())
                .isEqualTo(25);
    }

    /**
     * The inherited map stands for the shell that runs the build: {@code JDK_JAVA_OPTIONS},
     * {@code JAVA_TOOL_OPTIONS} and {@code _JAVA_OPTIONS} carrying {@code -D} count and security
     * properties, and {@code CUSTOMER_MASTER_GENERATOR_COUNT}. The scrub removes them all, so the child
     * loads exactly the {@code GENERATOR_COUNT=40} rows and prints no {@code Picked up} line.
     */
    @Test
    @DisplayName("Inherited JVM option and property variables never reach the child")
    void inheritedJvmOptionAndPropertyVariablesNeverReachTheChild() {
        RunResult result = run(
                Map.of("JDK_JAVA_OPTIONS", "-Dcustomer-master.generator.count=41",
                        "JAVA_TOOL_OPTIONS", "-Dcustomer-master.generator.count=42",
                        "_JAVA_OPTIONS", "-Dcustomer-master.security.users[0].role=BOGUS",
                        "CUSTOMER_MASTER_GENERATOR_COUNT", "44"),
                Map.of("GENERATOR_COUNT", "40"));

        assertSucceeded(result);
        assertLoadedReport(result, 40, CustomerGeneratorRunner.DEFAULT_START);
        assertThat(rowCount())
                .as("rows loaded with GENERATOR_COUNT=40 under inherited count and security options; %s",
                        result.describe())
                .isEqualTo(40);
    }

    @Test
    @DisplayName("The generator profile's context holds no security configuration and binds its own properties")
    void generatorContextHoldsNoSecurityConfiguration() {
        new ApplicationContextRunner()
                .withInitializer(context -> context.getEnvironment().setActiveProfiles(CustomerGeneratorRunner.PROFILE))
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withPropertyValues(
                        "spring.datasource.url=" + POSTGRES.getJdbcUrl(),
                        "spring.datasource.username=" + POSTGRES.getUsername(),
                        "spring.datasource.password=" + POSTGRES.getPassword())
                .withBean("testTypeExcludeFilter", TypeExcludeFilter.class, TestClassExcludeFilter::new)
                .withUserConfiguration(CustomerMasterApplication.class)
                .run(context -> assertThat(context)
                        .hasNotFailed()
                        .doesNotHaveBean(UsersProperties.class)
                        .doesNotHaveBean(SecurityConfig.class)
                        .doesNotHaveBean(SecurityFilterChain.class)
                        .hasSingleBean(AppProperties.class)
                        .hasSingleBean(GeneratorProperties.class)
                        .hasSingleBean(CustomerGeneratorRunner.class));
    }

    /**
     * Starts the generator jar as a separate JVM with the environment of this test JVM, and waits for it
     * to exit; see {@link #run(Map, Map, String...)}.
     *
     * @param extraEnv variables added last, for example {@code GENERATOR_COUNT}
     * @param args     the generator options appended after the profile option
     * @return the exit status and the whole combined output
     * @throws AssertionError as {@link #run(Map, Map, String...)} does
     */
    private RunResult run(Map<String, String> extraEnv, String... args) {
        return run(Map.of(), extraEnv, args);
    }

    /**
     * Starts the generator jar as a separate JVM and waits for it to exit.
     *
     * <p>The command is {@code <java.home>/bin/java -jar <app.jar> --spring.profiles.active=generator}
     * followed by {@code args}; no {@code -D} system property is passed, and because no JVM option
     * variable survives the scrub, none reaches the child either. The environment is the inherited one,
     * that of this test JVM plus {@code inheritedEnv}, without the scrubbed variables
     * ({@link #SCRUBBED_PREFIXES}, {@link #SCRUBBED_NAMES}), plus the {@code DB_*} address of
     * {@link #POSTGRES} and schema {@value #SCHEMA}, then {@code extraEnv}, which may override anything
     * before it.
     *
     * @param inheritedEnv variables added before the scrub, standing for the shell that runs the build;
     *                     a scrubbed one never reaches the child
     * @param extraEnv     variables added last, for example {@code GENERATOR_COUNT}
     * @param args         the generator options appended after the profile option
     * @return the exit status and the whole combined output
     * @throws AssertionError if the jar is missing, the process cannot start, the wait is interrupted,
     *                        or the process does not exit within {@value #PROCESS_TIMEOUT_SECONDS}
     *                        seconds (it is then killed and the message carries its log)
     */
    private RunResult run(Map<String, String> inheritedEnv, Map<String, String> extraEnv, String... args) {
        List<String> command = new ArrayList<>();
        command.add(javaExecutable().toString());
        command.add("-jar");
        command.add(appJar().toString());
        command.add("--spring.profiles.active=" + CustomerGeneratorRunner.PROFILE);
        command.addAll(List.of(args));

        Path log = logDir.resolve("generator-run-" + runs.incrementAndGet() + ".log");
        ProcessBuilder builder = new ProcessBuilder(command)
                .directory(logDir.toFile())
                .redirectErrorStream(true)
                .redirectOutput(log.toFile());

        Map<String, String> environment = builder.environment();
        environment.putAll(inheritedEnv);
        environment.keySet().removeIf(GeneratorProcessIT::isScrubbed);
        environment.put("DB_HOST", POSTGRES.getHost());
        environment.put("DB_PORT", String.valueOf(POSTGRES.getMappedPort(5432)));
        environment.put("DB_NAME", POSTGRES.getDatabaseName());
        environment.put("DB_USER", POSTGRES.getUsername());
        environment.put("DB_PASSWORD", POSTGRES.getPassword());
        environment.put("DB_SCHEMA", SCHEMA);
        environment.putAll(extraEnv);

        Process process;
        try {
            process = builder.start();
        } catch (IOException e) {
            throw new AssertionError("Cannot start the generator process " + command, e);
        }
        try {
            // The child never reads stdin; closing it gives the child EOF instead of an open pipe.
            process.getOutputStream().close();
            if (!process.waitFor(PROCESS_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                destroy(process);
                return fail("Generator process %s did not exit within %d s and was killed; its output:%n%s",
                        command, PROCESS_TIMEOUT_SECONDS, readLog(log));
            }
            return new RunResult(process.exitValue(), readLog(log));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while waiting for the generator process " + command
                    + "; its output so far:" + System.lineSeparator() + readLog(log), e);
        } catch (IOException e) {
            throw new AssertionError("Cannot close the stdin of the generator process " + command, e);
        } finally {
            if (process.isAlive()) {
                destroy(process);
            }
        }
    }

    /**
     * Tells whether an inherited environment variable is removed before a run.
     *
     * @param name the variable name
     * @return {@code true} for a name in {@link #SCRUBBED_NAMES} or starting with a
     *         {@link #SCRUBBED_PREFIXES} entry
     */
    private static boolean isScrubbed(String name) {
        return SCRUBBED_NAMES.contains(name) || SCRUBBED_PREFIXES.stream().anyMatch(name::startsWith);
    }

    /**
     * Kills a child and its descendants and waits briefly for it to be reaped.
     *
     * @param process the child to kill
     */
    private static void destroy(Process process) {
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
        try {
            process.waitFor(DESTROY_WAIT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Reads a run's log; bytes that are not UTF-8 become replacement characters rather than an error.
     *
     * @param log the file the child wrote
     * @return its content, or a note when it cannot be read
     */
    private static String readLog(Path log) {
        try {
            return new String(Files.readAllBytes(log), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "<cannot read " + log + ": " + e + ">";
        }
    }

    /**
     * Returns the repackaged jar Failsafe names in {@code app.jar.path}, or {@code target/app.jar}
     * relative to the module when the property is absent (a run from an IDE).
     *
     * @return the jar, which exists
     * @throws AssertionError if no jar is there
     */
    private static Path appJar() {
        String configured = System.getProperty("app.jar.path");
        Path jar = (configured == null || configured.isBlank() ? Path.of("target", "app.jar") : Path.of(configured))
                .toAbsolutePath();
        assertThat(jar).as("app.jar not found at %s — run ./mvnw verify so package precedes integration-test", jar)
                .isRegularFile();
        return jar;
    }

    /**
     * Returns the {@code java} launcher of the JVM running this test.
     *
     * @return {@code <java.home>/bin/java}, or {@code java.exe} on Windows
     */
    private static Path javaExecutable() {
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");
        return Path.of(System.getProperty("java.home"), "bin", windows ? "java.exe" : "java");
    }

    /**
     * Returns {@code test-csz.csv} as an absolute file, for {@code --csz-file}.
     *
     * @return {@code target/test-classes/generator/test-csz.csv}, absolute
     */
    private static Path testCszPath() {
        URL resource = GeneratorProcessIT.class.getResource(TEST_CSZ_RESOURCE);
        assertThat(resource).as("test resource %s", TEST_CSZ_RESOURCE).isNotNull();
        try {
            return Path.of(resource.toURI()).toAbsolutePath();
        } catch (URISyntaxException e) {
            throw new AssertionError("Test resource " + TEST_CSZ_RESOURCE + " has no file path: " + resource, e);
        }
    }

    /**
     * Asserts that a run exited 0 and that its log shows a generator context, see
     * {@link #assertGeneratorLog(RunResult)}.
     *
     * @param result the run
     */
    private static void assertSucceeded(RunResult result) {
        assertThat(result.exitCode()).as("exit status; %s", result.describe())
                .isEqualTo(CustomerGeneratorRunner.EXIT_SUCCESS);
        assertGeneratorLog(result);
    }

    /**
     * Asserts that a run's log comes from a started generator context: Spring Boot's startup line is
     * present, and nothing shows a web server start, a {@code SecurityFilterChain}, a
     * {@code customer-master.security.users} binding or validation message, or a JVM option variable
     * the JVM picked up.
     *
     * @param result the run
     */
    private static void assertGeneratorLog(RunResult result) {
        assertThat(result.output()).as("generator log; %s", result.describe())
                .contains(STARTED_LOG_TEXT)
                .doesNotContain(FORBIDDEN_LOG_TEXT);
        assertThat(result.output().toLowerCase(Locale.ROOT)).as("generator log; %s", result.describe())
                .doesNotContain(FORBIDDEN_LOG_TEXT_ANY_CASE);
    }

    /**
     * Asserts the outcome line {@code Loaded <count> customers <start>..<last> in <s> s}, with the last
     * id {@code count - 1} ordinals after {@code start}.
     *
     * @param result the run
     * @param count  the rows the run loaded
     * @param start  the first id of the run
     */
    private static void assertLoadedReport(RunResult result, int count, CustomerId start) {
        CustomerId last = CustomerId.fromOrdinal(start.toOrdinal() + count - 1);
        Pattern report = Pattern.compile("(?m)^Loaded " + count + " customers " + Pattern.quote(start.value())
                + "\\.\\." + Pattern.quote(last.value()) + " in \\d+\\.\\d s$");
        assertThat(result.output()).as("outcome line %s; %s", report, result.describe()).containsPattern(report);
    }

    /**
     * Returns {@code count} successive ids from {@code start}, in ordinal order.
     *
     * @param start the first id
     * @param count how many ids
     * @return their values
     */
    private static List<String> successiveIds(CustomerId start, int count) {
        List<String> ids = new ArrayList<>(count);
        CustomerId id = start;
        for (int i = 0; i < count; i++) {
            ids.add(id.value());
            if (i < count - 1) {
                id = id.successor();
            }
        }
        return ids;
    }

    /**
     * Returns the stored ids sorted by ordinal, independent of the database collation.
     *
     * @return the values of every stored {@code custid}
     */
    private List<String> storedIdsInOrdinalOrder() {
        return jdbcTemplate.queryForList("SELECT custid FROM custmast", String.class).stream()
                .map(CustomerId::parse)
                .sorted(Comparator.comparingInt(CustomerId::toOrdinal))
                .map(CustomerId::value)
                .toList();
    }

    /**
     * Counts the stored customers.
     *
     * @return {@code count(*)} of {@code custmast}
     */
    private long rowCount() {
        Long count = jdbcTemplate.queryForObject("SELECT count(*) FROM custmast", Long.class);
        return count == null ? 0 : count;
    }

    /**
     * Returns the name checksum {@value #CHECKSUM_SQL}.
     *
     * @return the md5 of the names in id order, or {@code ""} for an empty table
     */
    private String checksum() {
        return jdbcTemplate.queryForObject(CHECKSUM_SQL, String.class);
    }

    /**
     * Keeps test classes out of the component scan of {@link CustomerMasterApplication} in the
     * {@link ApplicationContextRunner} context.
     *
     * <p>{@code @SpringBootTest} registers Spring Boot's {@code TestTypeExcludeFilter}, which the
     * {@code TypeExcludeFilter} of {@code @SpringBootApplication}'s scan consults; a plain
     * {@link ApplicationContextRunner} has none, so the scan from {@code com.democorp.customermaster}
     * would also register nested test configurations of other integration tests on the test class
     * path, such as {@code RedactedErrorLogIT.FilterConfig}. Registered as a {@link TypeExcludeFilter}
     * bean, this filter excludes every class annotated or meta-annotated with {@link TestComponent}
     * ({@code @TestConfiguration} included) and every nested class whose outermost enclosing class
     * name ends with {@code Test}, {@code Tests} or {@code IT}. Equality is by class, as
     * {@link TypeExcludeFilter} requires of its implementations.
     */
    private static final class TestClassExcludeFilter extends TypeExcludeFilter {

        /** Simple-name suffixes of test classes, as Surefire and Failsafe select them. */
        private static final List<String> TEST_CLASS_SUFFIXES = List.of("Test", "Tests", "IT");

        @Override
        public boolean match(MetadataReader metadataReader, MetadataReaderFactory metadataReaderFactory)
                throws IOException {
            if (metadataReader.getAnnotationMetadata().isAnnotated(TestComponent.class.getName())) {
                return true;
            }
            ClassMetadata metadata = metadataReader.getClassMetadata();
            if (!metadata.hasEnclosingClass()) {
                return false;
            }
            String outermost = metadata.getEnclosingClassName();
            ClassMetadata enclosing = metadataReaderFactory.getMetadataReader(outermost).getClassMetadata();
            while (enclosing.hasEnclosingClass()) {
                outermost = enclosing.getEnclosingClassName();
                enclosing = metadataReaderFactory.getMetadataReader(outermost).getClassMetadata();
            }
            String simpleName = outermost.substring(outermost.lastIndexOf('.') + 1);
            return TEST_CLASS_SUFFIXES.stream().anyMatch(simpleName::endsWith);
        }

        @Override
        public boolean equals(Object other) {
            return other != null && other.getClass() == getClass();
        }

        @Override
        public int hashCode() {
            return getClass().hashCode();
        }
    }
}
