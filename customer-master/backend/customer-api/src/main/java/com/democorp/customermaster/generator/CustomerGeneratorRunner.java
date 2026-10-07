package com.democorp.customermaster.generator;

import com.democorp.customermaster.domain.CustomerId;
import java.io.PrintStream;
import java.sql.SQLException;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * The command-line entry of the test-data generator: resolves the options, checks the id space, loads
 * {@code count} generated customers through {@link CustomerLoader}, analyzes the table and reports one
 * line with an exit status.
 *
 * <p><b>What it replaces.</b>
 * <ul>
 *   <li>LOADCUST2 [5250_Subfile/LOADCUST2.CLLE]: the {@code PARM(&NUM)} row count, the
 *       {@code ALCOBJ ((CUSTMAST *FILE *EXCLRD)) WAIT(5)} guard with its {@code MONMSG CPF1002} handler
 *       that sends {@code 'Cannot allocate CUSTMAST'} and returns, and the
 *       {@code SBMJOB CMD(CALL PGM(LOADCUSTR) PARM((&NUM)))} that runs the load in batch. Here the load
 *       runs synchronously in this process and its outcome is the process exit status.</li>
 *   <li>LOADCUST's identical guard [5250_Subfile/LOADCUST.CLLE:6-14].</li>
 *   <li>LOADCUSTR's fixed first id {@code varCUSTID = '1001'}, advanced by BASE36ADD per row
 *       [5250_Subfile/LOADCUSTR.SQLRPGLE:132-137], [BASE36/SRV_BASE36.RPGLE:29-55].</li>
 * </ul>
 *
 * <p><b>Invocation.</b> {@code java -jar app.jar --spring.profiles.active=generator [--count=N]
 * [--start-id=XXXX] [--csz-file=LOCATION] [--seed=L]}, or under Compose
 * {@code docker compose --profile tools run --rm generator --count=1000000}. Profile {@code generator}
 * ({@code application-generator.yml}) runs with no web server and no Flyway, and bridges the four flat
 * flags to {@link GeneratorProperties}: a flag wins over its {@code GENERATOR_*} variable, which wins
 * over the default. {@code CustomerMasterApplication.main} closes the non-web context once the runner
 * has returned and exits the JVM with {@link #getExitCode()}.
 *
 * <p><b>Steps of {@link #execute(ApplicationArguments)}</b>, each failure printing one line and
 * returning {@value #EXIT_FAILURE}:
 * <ol>
 *   <li><b>Strict options.</b> Only {@code --count}, {@code --start-id}, {@code --csz-file},
 *       {@code --seed}, {@code --customer-master.generator.*} and {@code --spring.*} are accepted, so a
 *       mistyped flag cannot silently fall back to its default. The first other option, in sorted
 *       order, prints {@code Unknown option --<name>}; failing that, the first non-option argument
 *       prints {@code Unknown option <arg>}. Spring Boot's own {@code --debug} and {@code --trace} are
 *       rejected too; {@code --logging.level...} likewise. Logging can still be tuned through
 *       {@code LOGGING_LEVEL_*} environment variables. Once every argument is accepted, the first
 *       flag or {@code --customer-master.generator.*} option given without a value, in sorted order,
 *       prints {@code Option --<name> requires a value}: a bare {@code --seed} would otherwise bind
 *       as empty and select a default. An explicitly empty value ({@code --start-id=},
 *       {@code --csz-file=}, {@code --seed=}) still selects the automatic start, the bundled sample or
 *       a random seed, and a bare {@code --spring.*} option is accepted.</li>
 *   <li><b>Start id.</b> {@link #resolveStart(String, int)}: the given {@code --start-id}, otherwise
 *       {@code 1001} as LOADCUSTR, or {@code AAAA} when {@code count} exceeds the
 *       {@value #AUTO_AAAA_THRESHOLD} ids that remain from {@code 1001}.</li>
 *   <li><b>Capacity, before any read or write.</b> {@link #fitsCapacity(CustomerId, int)}: the last id
 *       must not lie beyond {@code 9999}. The source has no such check: BASE36ADD rolls {@code 9999}
 *       over to {@code AAAA} [BASE36/SRV_BASE36.RPGLE:9-13].</li>
 *   <li><b>CSZ rows</b> from {@link CszSource#load(String)}; an unusable file prints the source's
 *       one-line message, an empty result prints {@code CSZ file <location> has no usable rows}.</li>
 *   <li><b>Generator.</b> {@link NameGenerator#seeded(long)} when {@code --seed} is given, otherwise
 *       {@link NameGenerator#random()}; every row is stamped with the load start from the injected
 *       {@link Clock}, truncated to microseconds, the precision of {@code chgtime timestamptz(6)}.</li>
 *   <li><b>Load.</b> {@link CustomerLoader#load(CustomerLoader.Plan)} replaces the table and restarts
 *       the id sequence in one transaction, which has committed when it returns. A lock not granted
 *       within the loader's 5-second {@code lock_timeout} prints {@code Cannot allocate CUSTMAST}; any
 *       other load failure prints {@code Load failed: <cause>; verify custmast and custmast_id_seq before
 *       retrying}.</li>
 *   <li><b>Statistics.</b> {@code ANALYZE custmast}, after the commit, as an auto-commit statement:
 *       this class is not transactional. A failure is logged at WARN and the run still succeeds,
 *       because the rows are committed.</li>
 *   <li><b>Report.</b> {@code Loaded <n> customers <first>..<last> in <s> s} and status
 *       {@value #EXIT_SUCCESS}, for example {@code Loaded 500 customers B000..B1EV in 0.4 s}. The
 *       elapsed time runs from just before step 4 to after step 7.</li>
 * </ol>
 *
 * <p><b>Failure contract.</b> {@link #execute(ApplicationArguments)} never throws. Every failure
 * before step 6 has written nothing. In step 6, a lock not granted and an invalid plan leave the
 * table, the sequence and the next interactive id as they were, and so does every failure the loader's
 * transaction rolled back, which restores the rows and the sequence together. A failure of the
 * {@code COMMIT} itself, such as a connection lost before the commit was acknowledged, leaves the
 * outcome unknown: the new rows and the restarted sequence may already be committed. Its exception
 * type does not set it apart from a rolled-back statement failure, so every other step-6 failure is
 * reported without a claim about the table, advising to verify {@code custmast} (row count, first and
 * last {@code custid}) and {@code custmast_id_seq} before retrying. Printed lines never carry a stack
 * trace; the details go to the log. Nothing printed or logged carries a credential.
 *
 * <p><b>Scope.</b> The runner touches the database only through {@link CustomerLoader} and the one
 * {@code ANALYZE}; id allocation and the sequence reset belong to {@code CustomerIdAllocator}, reached
 * through the loader, which keeps the single lock order of the allocation guard. The rows carry the
 * fixed identity {@code *SYSTEM*}, stamped by {@link CustomerDataGenerator}, so the generator needs no
 * user configuration; {@code SecurityConfig}, which is servlet-only, is absent from this context.
 *
 * <p><b>Registration.</b> The bean exists only under profile {@value #PROFILE}, and it is the only
 * registrar of {@link GeneratorProperties}, through {@link EnableConfigurationProperties}: the
 * application declares no properties scan, so the web and test contexts neither bind nor validate the
 * generator options. Next to it, {@link Import} brings in {@code GeneratorProperties.BlankCountAdvisor},
 * which binds an empty or whitespace-only count as the default 300, so that advisor too exists only
 * in this context. Tests may construct the runner directly and call
 * {@link #execute(ApplicationArguments)} with {@code new DefaultApplicationArguments(...)}.
 */
@Component
@Profile(CustomerGeneratorRunner.PROFILE)
@EnableConfigurationProperties(GeneratorProperties.class)
@Import(GeneratorProperties.BlankCountAdvisor.class)
public class CustomerGeneratorRunner implements ApplicationRunner, ExitCodeGenerator {

    /** The Spring profile that turns the application into the generator CLI. */
    public static final String PROFILE = "generator";

    /** Exit status of a successful load. */
    public static final int EXIT_SUCCESS = 0;

    /** Exit status of every failure: an option, capacity, CSZ, lock or load error. */
    public static final int EXIT_FAILURE = 1;

    /**
     * The number of ids from {@code 1001} (ordinal 1,294,371) through {@code 9999} (ordinal 1,679,615):
     * {@code CustomerId.CAPACITY - 1,294,371}. A larger count without {@code --start-id} starts at
     * {@code AAAA}.
     */
    public static final int AUTO_AAAA_THRESHOLD = 385_245;

    /** LOADCUSTR's first id, {@code varCUSTID = '1001'} [5250_Subfile/LOADCUSTR.SQLRPGLE:133]. */
    public static final CustomerId DEFAULT_START = CustomerId.parse("1001");

    /** The first id of a load larger than {@value #AUTO_AAAA_THRESHOLD} rows without {@code --start-id}. */
    public static final CustomerId LARGE_LOAD_START = CustomerId.parse("AAAA");

    /** The line LOADCUST and LOADCUST2 send when CUSTMAST cannot be allocated within 5 seconds. */
    public static final String LOCK_FAILURE_MESSAGE = "Cannot allocate CUSTMAST";

    /**
     * The four flat flags the option bridge in {@code application-generator.yml} maps to
     * {@code customer-master.generator.*}.
     */
    public static final Set<String> FLAGS = Set.of("count", "start-id", "csz-file", "seed");

    /** Refreshes the planner statistics after a load; an unqualified name, resolved by {@code currentSchema}. */
    static final String ANALYZE_SQL = "ANALYZE custmast";

    /** Option-name prefix of the fully qualified generator properties. */
    static final String GENERATOR_OPTION_PREFIX = GeneratorProperties.PREFIX + ".";

    /** Option-name prefix of Spring properties such as {@code spring.profiles.active}. */
    static final String SPRING_OPTION_PREFIX = "spring.";

    /** SQLSTATE {@code lock_not_available}, raised when {@code lock_timeout} expires. */
    static final String LOCK_NOT_AVAILABLE = "55P03";

    /** At most this many throwables of a cause graph are inspected, so a cyclic graph ends. */
    private static final int MAX_CAUSE_DEPTH = 64;

    /**
     * A line break ({@code \R}: CR, LF, CR LF, VT, FF, NEL, U+2028 or U+2029) with the blanks around it,
     * which {@link #oneLine(String)} folds into one space before it replaces the remaining controls.
     */
    private static final Pattern LINE_BREAK = Pattern.compile("\\s*\\R\\s*");

    /** U+2028, which some viewers render as a line break although it is no control character. */
    private static final char LINE_SEPARATOR = '\u2028';

    /** U+2029, which some viewers render as a line break although it is no control character. */
    private static final char PARAGRAPH_SEPARATOR = '\u2029';

    /** Nanoseconds per second, for the elapsed time of the report line. */
    private static final double NANOS_PER_SECOND = 1_000_000_000.0;

    private static final Logger LOG = LoggerFactory.getLogger(CustomerGeneratorRunner.class);

    private final GeneratorProperties properties;

    private final CszSource cszSource;

    private final CustomerLoader loader;

    private final JdbcTemplate jdbcTemplate;

    private final Clock clock;

    /** Receives the one outcome line; {@code System.out} in production. */
    private final PrintStream out;

    /** The status of the last {@link #run(ApplicationArguments)}, reported through {@link #getExitCode()}. */
    private volatile int exitCode = EXIT_SUCCESS;

    /**
     * Creates the runner, printing to {@code System.out}.
     *
     * @param properties   the bound and validated generator options
     * @param cszSource    the reader of the city/state/ZIP file, which already filters by state
     * @param loader       the transactional loader (the Spring proxy, so {@code load} runs in a transaction)
     * @param jdbcTemplate the application's template, used for {@code ANALYZE} only
     * @param clock        the application clock from {@code ClockConfig}, source of the load time
     * @throws NullPointerException if any argument is {@code null}
     */
    @Autowired
    public CustomerGeneratorRunner(GeneratorProperties properties, CszSource cszSource, CustomerLoader loader,
            JdbcTemplate jdbcTemplate, Clock clock) {
        this(properties, cszSource, loader, jdbcTemplate, clock, System.out);
    }

    /**
     * Creates the runner with an explicit output stream, for tests that capture the outcome line.
     *
     * @param properties   the generator options
     * @param cszSource    the reader of the city/state/ZIP file
     * @param loader       the transactional loader
     * @param jdbcTemplate the template used for {@code ANALYZE}
     * @param clock        the source of the load time
     * @param out          the stream that receives the outcome line
     * @throws NullPointerException if any argument is {@code null}
     */
    CustomerGeneratorRunner(GeneratorProperties properties, CszSource cszSource, CustomerLoader loader,
            JdbcTemplate jdbcTemplate, Clock clock, PrintStream out) {
        this.properties = Objects.requireNonNull(properties, "properties");
        this.cszSource = Objects.requireNonNull(cszSource, "cszSource");
        this.loader = Objects.requireNonNull(loader, "loader");
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.out = Objects.requireNonNull(out, "out");
    }

    /**
     * Runs the generator once, as Spring Boot calls every {@link ApplicationRunner} after the context
     * has started, and keeps the status for {@link #getExitCode()}.
     *
     * @param args the parsed command line
     */
    @Override
    public void run(ApplicationArguments args) {
        exitCode = execute(args);
    }

    /**
     * Returns the status of the last run, which {@code SpringApplication.exit} turns into the process
     * exit status.
     *
     * @return {@value #EXIT_SUCCESS} after a successful load (or before any run), otherwise
     *         {@value #EXIT_FAILURE}
     */
    @Override
    public int getExitCode() {
        return exitCode;
    }

    /**
     * Validates the options, loads the customers and prints exactly one outcome line.
     *
     * @param args the parsed command line; its option names, its non-option arguments and whether
     *             each generator option carries a value are checked, while the values themselves
     *             arrive already bound in {@link GeneratorProperties}
     * @return {@value #EXIT_SUCCESS} when the rows are committed, otherwise {@value #EXIT_FAILURE};
     *         never throws
     */
    public int execute(ApplicationArguments args) {
        try {
            return executeSteps(args);
        } catch (RuntimeException unexpected) {
            // Every expected failure is mapped inside the steps; this keeps the never-throws contract
            // for anything else, such as a null argument.
            LOG.error("generator.failed unexpected error", unexpected);
            return fail("Load failed: " + rootMessage(unexpected));
        }
    }

    /**
     * Resolves the first customer id of a load.
     *
     * @param startId the {@code --start-id} value; {@code null}, empty or blank selects the automatic
     *                start, anything else is trimmed and must be four characters of {@code [A-Z0-9]}
     * @param count   the number of rows to load
     * @return the given id; otherwise {@link #LARGE_LOAD_START} ({@code AAAA}) when {@code count}
     *         exceeds {@value #AUTO_AAAA_THRESHOLD}, else {@link #DEFAULT_START} ({@code 1001})
     * @throws IllegalArgumentException if a non-blank {@code startId} is not a valid customer id
     */
    public static CustomerId resolveStart(String startId, int count) {
        if (startId != null && !startId.isBlank()) {
            return CustomerId.parse(startId.trim());
        }
        return count > AUTO_AAAA_THRESHOLD ? LARGE_LOAD_START : DEFAULT_START;
    }

    /**
     * Whether {@code count} consecutive ids from {@code start} stay within the 4-character id space,
     * that is, whether the last id is at most {@code 9999}.
     *
     * <p>Examples: {@code 9999} with 1 fits; {@code 9999} with 2 does not; {@code 9990} with 10 fits;
     * {@code AAAA} with {@link CustomerId#CAPACITY} fits. A count below 1 is not judged here: the
     * options and {@link CustomerLoader.Plan} reject it.
     *
     * @param start the first id
     * @param count the number of rows
     * @return {@code start.toOrdinal() + count <= CustomerId.CAPACITY}, computed without overflow
     * @throws NullPointerException if {@code start} is {@code null}
     */
    public static boolean fitsCapacity(CustomerId start, int count) {
        Objects.requireNonNull(start, "start");
        return start.toOrdinal() + (long) count <= CustomerId.CAPACITY;
    }

    /**
     * The steps of {@link #execute(ApplicationArguments)}, with every expected failure mapped to its
     * line and status.
     *
     * @param args the parsed command line
     * @return the exit status
     */
    private int executeSteps(ApplicationArguments args) {
        Objects.requireNonNull(args, "args");

        // 1. Strict options, before any work: unknown options and arguments first, then valueless ones.
        Optional<String> unknown = firstUnknownOption(args);
        if (unknown.isPresent()) {
            return fail("Unknown option " + unknown.get());
        }
        Optional<String> valueless = firstValuelessOption(args);
        if (valueless.isPresent()) {
            return fail("Option " + valueless.get() + " requires a value");
        }

        // 2. Start id: explicit, else 1001, else AAAA for a load that cannot fit above 1001.
        final int count = properties.count();
        final CustomerId start;
        try {
            start = resolveStart(properties.hasStartId() ? properties.startId() : null, count);
        } catch (IllegalArgumentException invalid) {
            return fail(messageOf(invalid));
        }

        // 3. Capacity, before anything is read or written.
        if (!fitsCapacity(start, count)) {
            return fail("Cannot load " + count + " customers starting at " + start + ": only "
                    + (CustomerId.CAPACITY - start.toOrdinal()) + " ids remain through 9999");
        }

        // The location comes from the command line or the environment: the raw value opens the file,
        // and only its one-line form reaches the log and the outcome line.
        final String location = properties.cszLocation();
        final String shownLocation = oneLine(location);
        LOG.info("generator.options count={} start={} cszFile={} mode={}",
                count, start, shownLocation, properties.seed() != null ? "seeded" : "random");

        final long began = System.nanoTime();
        final long loaded;
        final CustomerId last;
        try {
            // 4. City/state/ZIP rows, already filtered to cities of at most 20 and known states.
            List<CszSource.CszRow> rows = cszSource.load(location);
            if (rows.isEmpty()) {
                return fail("CSZ file " + shownLocation + " has no usable rows");
            }

            // 5. The row generator, seeded when asked, stamping the load start.
            NameGenerator names = properties.seed() != null
                    ? NameGenerator.seeded(properties.seed())
                    : NameGenerator.random();
            OffsetDateTime loadTime = OffsetDateTime.now(clock).truncatedTo(ChronoUnit.MICROS);
            CustomerDataGenerator generator = new CustomerDataGenerator(names, rows, loadTime);

            // 6. Replace the table and restart the sequence in the loader's transaction.
            CustomerLoader.Plan plan = new CustomerLoader.Plan(start, count, generator);
            loaded = loader.load(plan);
            last = plan.last();
        } catch (RuntimeException failure) {
            return fail(describeLoadFailure(failure));
        }

        // 7. Statistics after the commit, outside any transaction.
        analyze();

        final double seconds = (System.nanoTime() - began) / NANOS_PER_SECOND;
        report(String.format(Locale.ROOT, "Loaded %d customers %s..%s in %.1f s", loaded, start, last, seconds));
        return EXIT_SUCCESS;
    }

    /**
     * Finds the first command-line argument the generator does not accept.
     *
     * @param args the parsed command line
     * @return {@code --<name>} for the first unknown option in sorted order, otherwise the first
     *         non-option argument as given, otherwise empty
     */
    static Optional<String> firstUnknownOption(ApplicationArguments args) {
        for (String name : new TreeSet<>(args.getOptionNames())) {
            if (!isAcceptedOption(name)) {
                return Optional.of("--" + oneLine(name));
            }
        }
        List<String> nonOptions = args.getNonOptionArgs();
        if (!nonOptions.isEmpty()) {
            return Optional.of(oneLine(nonOptions.get(0)));
        }
        return Optional.empty();
    }

    /**
     * Finds the first generator option given without a value, such as a bare {@code --seed}. Spring Boot
     * binds a bare option as an empty value, which would select the automatic start id, the bundled
     * sample or a random seed instead of the value the operator left out. An explicitly empty value
     * such as {@code --seed=} carries a value and still selects that default, and a bare
     * {@code --spring.*} option is not judged here.
     *
     * @param args the parsed command line
     * @return {@code --<name>} for the first flag or fully qualified generator property, in sorted
     *         order, that has no value, otherwise empty
     */
    static Optional<String> firstValuelessOption(ApplicationArguments args) {
        for (String name : new TreeSet<>(args.getOptionNames())) {
            if (isGeneratorOption(name)) {
                List<String> values = args.getOptionValues(name);
                if (values == null || values.isEmpty()) {
                    return Optional.of("--" + oneLine(name));
                }
            }
        }
        return Optional.empty();
    }

    /**
     * Whether an option name is one of the four flags, a fully qualified generator property or a Spring
     * property.
     *
     * @param name the option name without its leading {@code --}
     * @return {@code true} when the option is accepted
     */
    static boolean isAcceptedOption(String name) {
        return isGeneratorOption(name) || name.startsWith(SPRING_OPTION_PREFIX);
    }

    /**
     * Whether an option name sets a generator option: one of the four flags or a fully qualified
     * generator property.
     *
     * @param name the option name without its leading {@code --}
     * @return {@code true} for {@link #FLAGS} and names under {@value #GENERATOR_OPTION_PREFIX}
     */
    static boolean isGeneratorOption(String name) {
        return FLAGS.contains(name) || name.startsWith(GENERATOR_OPTION_PREFIX);
    }

    /**
     * Maps a failure of steps 4 to 6 to its outcome line. Only three failures are known to leave the
     * table, the sequence and the next id as they were, and they print their own message:
     * <ul>
     *   <li>a {@link CszSource.CszFileException}, raised before any write;</li>
     *   <li>a lock wait, which the server reports before any commit: the loader's
     *       {@code ACCESS EXCLUSIVE} lock was not granted, so nothing was truncated, or a later
     *       statement's wait expired and Spring rolled the transaction back; it prints
     *       {@value #LOCK_FAILURE_MESSAGE};</li>
     *   <li>an {@link IllegalArgumentException}, raised before the load or inside its transaction, which
     *       Spring then rolled back: a rollback that fails surfaces as its own exception instead.</li>
     * </ul>
     * Any other failure may come from the {@code COMMIT} itself, and its type does not tell: a connection
     * lost before the commit was acknowledged leaves the outcome unknown, so the new rows and the
     * restarted sequence may already be committed. It is logged at ERROR with the exception, claiming
     * nothing about the table, and both that log line and the printed
     * {@code Load failed: <cause>; verify custmast and custmast_id_seq before retrying} advise checking
     * the database before a retry.
     *
     * @param failure the exception the CSZ read, the generator setup or the load raised
     * @return the line to print
     */
    private String describeLoadFailure(RuntimeException failure) {
        if (failure instanceof CszSource.CszFileException) {
            return messageOf(failure);
        }
        if (isLockWait(failure)) {
            // The CPF1002 handler of LOADCUST and LOADCUST2: an add, an update or another load holds the table.
            LOG.warn("generator.load custmast lock not granted within {}: {}",
                    CustomerLoader.LOCK_TIMEOUT, rootMessage(failure));
            return LOCK_FAILURE_MESSAGE;
        }
        if (failure instanceof IllegalArgumentException) {
            return messageOf(failure);
        }
        // By type, a statement failure the loader rolled back looks the same as a failed COMMIT, whose
        // result is unknown when the acknowledgement was lost; so claim nothing about the table here.
        LOG.error("generator.load failed; if the COMMIT itself failed, for example on a connection lost before"
                + " the commit was acknowledged, the outcome is unknown and the new rows and the restarted id"
                + " sequence may already be committed; verify custmast (row count, first and last custid) and"
                + " custmast_id_seq against this run's count and start before retrying", failure);
        return "Load failed: " + rootMessage(failure) + "; verify custmast and custmast_id_seq before retrying";
    }

    /**
     * Whether a failure is an expired lock wait: its cause graph, including
     * {@link SQLException#getNextException()}, holds a {@link CannotAcquireLockException} (how
     * {@code CustomerIdAllocator} reports its table lock) or an {@link SQLException} with SQLSTATE
     * {@value #LOCK_NOT_AVAILABLE}. Each throwable is inspected once, and at most
     * {@value #MAX_CAUSE_DEPTH} of them.
     *
     * @param failure the failure
     * @return {@code true} if the failure is, or is caused by, a lock wait
     */
    static boolean isLockWait(Throwable failure) {
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        Deque<Throwable> pending = new ArrayDeque<>();
        if (failure != null) {
            pending.add(failure);
        }
        while (!pending.isEmpty() && visited.size() < MAX_CAUSE_DEPTH) {
            Throwable current = pending.poll();
            if (!visited.add(current)) {
                continue;
            }
            if (current instanceof CannotAcquireLockException) {
                return true;
            }
            if (current instanceof SQLException sql) {
                if (LOCK_NOT_AVAILABLE.equals(sql.getSQLState())) {
                    return true;
                }
                SQLException next = sql.getNextException();
                if (next != null) {
                    pending.add(next);
                }
            }
            Throwable cause = current.getCause();
            if (cause != null) {
                pending.add(cause);
            }
        }
        return false;
    }

    /**
     * Refreshes the planner statistics of {@code custmast}. The rows are already committed, so a failure
     * only costs plan quality until autovacuum analyzes the table; it is logged and the run succeeds.
     */
    private void analyze() {
        try {
            jdbcTemplate.execute(ANALYZE_SQL);
            LOG.debug("generator.analyze done");
        } catch (RuntimeException failure) {
            LOG.warn("generator.analyze failed; the load is committed, run ANALYZE custmast manually: {}",
                    rootMessage(failure));
        }
    }

    /**
     * Prints a failure line.
     *
     * @param line the one-line message
     * @return {@value #EXIT_FAILURE}
     */
    private int fail(String line) {
        report(line);
        return EXIT_FAILURE;
    }

    /**
     * Prints one outcome line and flushes it, so it is visible before the JVM exits. This is the printed
     * boundary: the line passes through {@link #oneLine(String)} here, so whatever option, location or
     * exception text it embeds, exactly one line reaches the stream and it carries no control character.
     * A line without control characters prints unchanged.
     *
     * @param line the outcome line
     */
    private void report(String line) {
        out.println(oneLine(line));
        out.flush();
    }

    /**
     * Returns a throwable's own message on one line.
     *
     * @param failure the throwable
     * @return its message through {@link #oneLine(String)}, or its root-cause message when it has none
     *         or nothing is left of it on one line
     */
    static String messageOf(Throwable failure) {
        String message = failure.getMessage();
        String line = message == null ? "" : oneLine(message);
        return line.isEmpty() ? rootMessage(failure) : line;
    }

    /**
     * Returns the message of the deepest cause on one line: the root cause names what actually failed,
     * for example the PostgreSQL error behind Spring's wrapper. When nothing is left of the root's
     * message on one line, the nearest throwable above it with such a message is used, and failing that
     * the root's simple class name.
     *
     * @param failure the throwable
     * @return a non-blank single-line message, free of control characters
     */
    static String rootMessage(Throwable failure) {
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        Deque<Throwable> chain = new ArrayDeque<>();
        for (Throwable current = failure;
                current != null && visited.size() < MAX_CAUSE_DEPTH && visited.add(current);
                current = current.getCause()) {
            chain.push(current);
        }
        if (chain.isEmpty()) {
            return "unknown error";
        }
        Throwable root = chain.peek();
        for (Throwable candidate : chain) {
            String message = candidate.getMessage();
            String line = message == null ? "" : oneLine(message);
            if (!line.isEmpty()) {
                return line;
            }
        }
        return root.getClass().getSimpleName();
    }

    /**
     * Puts text on one line for a log argument or a printed line, so that neither can be split, forged
     * or turned into a terminal control sequence, whatever a file name, option or exception message
     * holds. Every line break, with the blanks around it, becomes one space; every remaining ISO control
     * character ({@link Character#isISOControl(char)}: U+0000 to U+001F and U+007F to U+009F, so TAB,
     * ESC, NUL and the C1 controls such as CSI) and U+2028 or U+2029 becomes a space, the rule
     * {@code CszSource} applies to its messages; leading and trailing blanks are stripped. Other
     * characters are kept, so text without control characters changes only by that strip.
     *
     * @param text the text
     * @return the text on one line, free of control characters; empty when nothing else is left
     */
    static String oneLine(String text) {
        final String folded = LINE_BREAK.matcher(text).replaceAll(" ");
        final StringBuilder line = new StringBuilder(folded.length());
        for (int i = 0; i < folded.length(); i++) {
            final char c = folded.charAt(i);
            final boolean lineBreaking = Character.isISOControl(c)
                    || c == LINE_SEPARATOR
                    || c == PARAGRAPH_SEPARATOR;
            line.append(lineBreaking ? ' ' : c);
        }
        return line.toString().strip();
    }
}
