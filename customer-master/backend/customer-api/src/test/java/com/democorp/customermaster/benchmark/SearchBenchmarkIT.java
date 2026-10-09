package com.democorp.customermaster.benchmark;

import static org.assertj.core.api.Assertions.assertThat;

import com.democorp.customermaster.config.AppProperties;
import com.democorp.customermaster.domain.CustomerId;
import com.democorp.customermaster.domain.SearchCriteria;
import com.democorp.customermaster.generator.CszSource;
import com.democorp.customermaster.generator.CustomerDataGenerator;
import com.democorp.customermaster.generator.CustomerLoader;
import com.democorp.customermaster.generator.NameGenerator;
import com.democorp.customermaster.repository.CustomerSearchRepository;
import com.democorp.customermaster.support.AbstractPostgresIT;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.github.dockerjava.api.model.Info;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.OperatingSystemMXBean;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.Supplier;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;
import org.testcontainers.DockerClientFactory;

/**
 * Measures the customer search at 1,000,000 {@code custmast} rows through the running API and checks
 * the plans PostgreSQL chooses for it.
 *
 * <p><b>Why it exists.</b> The source readme claims that PMTCUSTR's static {@code ItemCur} cursor
 * ({@code NAME LIKE}, {@code CITY LIKE}, {@code STATE between}, {@code ACTIVE between},
 * {@code order by NAME, CITY, STATE optimize for 13 rows}) [5250_Subfile/PMTCUSTR.SQLRPGLE:208-223]
 * showed "no discernable performance hit" with 1 million records [5250_Subfile/README.md:42]. The
 * claim was never measured, and Custmast2.sql gives the cursor no composite index for its ORDER BY
 * [5250_Subfile/Custmast2.sql:26-31] (discrepancy D9). The target answers it with keyset pagination over
 * {@code custmast_search_keyset}, the {@code varchar_pattern_ops} indexes {@code custmast_name} and
 * {@code custmast_city}, and {@code custmast_state}; this class supplies the measured evidence.
 *
 * <p><b>Method.</b>
 * <ol>
 *   <li>Load {@value #ROWS} customers from {@value #START} with {@link CustomerLoader} (TRUNCATE, COPY
 *       and sequence restart in one transaction) over a {@link CustomerDataGenerator} seeded with
 *       {@value #SEED} and the bundled CSZ sample, then {@code ANALYZE custmast}, as the generator
 *       runner does after every load. A count above 385,245 takes the automatic {@code AAAA} start.</li>
 *   <li>Choose the name and city prefixes from the first suitable row at or after the middle id
 *       ({@link #chooseParameters(CustomerId)}). The seed fixes the data, so every run on the same Java
 *       runtime chooses the same values.</li>
 *   <li>Check the cursor chain from page 1 to page {@value #DEEP_PAGE} against an independent oracle:
 *       the first {@value #CHAIN_ROWS} active rows read by one plain
 *       {@code ORDER BY name, city, state, custid LIMIT}, with no keyset and no cursor. Page <i>k</i>
 *       must hold exactly rows 12(<i>k</i> - 1) + 1 to 12<i>k</i> of that order, in order, and its
 *       {@code nextCursor} must decode to the keys of its last row with {@code served} = 12<i>k</i>,
 *       so a repeated, skipped or reordered page fails the setup before any measurement.</li>
 *   <li>For each of eight operations, run {@value #WARMUP} warm-up calls (discarded; the first is
 *       checked for content), then {@value #MEASURED} measured calls, sequentially on one thread, as
 *       user {@code inq} (role {@code INQUIRY}) over HTTP Basic against the random-port server. A
 *       sample spans sending the request to reading the whole body, so it includes the security filter
 *       chain and its BCrypt check, the JSON rendering and the loopback transfer.</li>
 *   <li>Run {@code EXPLAIN (FORMAT JSON)} of the exact production statements: the page statement
 *       ({@link CustomerSearchRepository#buildQuery(SearchCriteria, int)}) for the no-filter first page,
 *       the page-{@value #DEEP_PAGE} keyset position and the three-letter name prefix, and the count
 *       statement ({@link CustomerSearchRepository#buildCountQuery(SearchCriteria, int)}) that the
 *       page-{@value #DEEP_PAGE} request runs before its page, bounded by the row cap. Each runs in a
 *       read-only transaction that first sets {@code plan_cache_mode = force_custom_plan}, as
 *       {@link CustomerSearchRepository#find(SearchCriteria, int)} and
 *       {@link CustomerSearchRepository#countThrough(SearchCriteria, int)} do, under the default
 *       planner settings.</li>
 *   <li>Print the Markdown report and write it to {@value #REPORT_FILE} under {@code target}, then
 *       assert every threshold with {@link SoftAssertions}, so a failing run still reports every
 *       measured number and lists every violation.</li>
 * </ol>
 *
 * <p><b>Thresholds.</b> p95 (nearest rank) at most {@value #SEARCH_P95_MS} ms for each search
 * operation and at most {@value #GET_P95_MS} ms for get-by-id. Latency depends on the host, so the
 * plan check is the hardware-independent evidence: none of the four plans, the deep page's page and
 * count statements among them, may contain a {@code Seq Scan} on {@code custmast}.
 *
 * <p><b>Running.</b> The class carries JUnit tag {@code benchmark}, which the default Failsafe run
 * excludes; {@code ./mvnw -B verify -Pbenchmark} runs this class and no other IT. The report feeds
 * {@code customer-master/docs/performance/search-benchmark.md}, which records only measured numbers.
 *
 * <p><b>Context and data.</b> The class uses the base context of {@link AbstractPostgresIT} unchanged
 * (no mock, property override or second container), so it adds no context variant.
 * {@code DatabaseCleaner} empties {@code custmast} before each test method, so the load and every
 * measurement sit in one test. The rows are generated lazily and streamed through COPY; none is held
 * in memory.
 */
@Tag("benchmark")
@DisplayName("Search at 1,000,000 customers meets its latency thresholds and uses no sequential scan")
class SearchBenchmarkIT extends AbstractPostgresIT {

    private static final long SEED = 42L;

    /** Rows loaded: the 1,000,000 of the source readme's claim. */
    private static final int ROWS = 1_000_000;

    /** First id; a count above 385,245 takes this automatic start in the generator. */
    private static final String START = "AAAA";

    /** The source's page size, SFLPAG 12 [5250_Subfile/PMTCUSTR.SQLRPGLE:134]. */
    private static final int PAGE_SIZE = 12;

    /** The look-ahead limit the search service passes for a 12-row page. */
    private static final int LIMIT = PAGE_SIZE + 1;

    private static final int DEEP_PAGE = 50;

    /** Rows of the list from the first row through the end of page {@value #DEEP_PAGE}. */
    private static final int CHAIN_ROWS = DEEP_PAGE * PAGE_SIZE;

    /**
     * The independent list order the cursor chain is checked against: the active rows in the
     * search's sort order, read by one plain {@code ORDER BY ... LIMIT} with no keyset and no cursor.
     * The four columns are {@code COLLATE customer_sort}, so this is the order the search serves.
     */
    private static final String CHAIN_ORDER_SQL = "SELECT custid, name, city, state FROM custmast"
            + " WHERE active = 'Y' ORDER BY name, city, state, custid LIMIT :rows";

    private static final List<String> CURSOR_PROPERTIES = List.of("name", "city", "state", "custid", "served");

    /** The base64url alphabet without padding, the only characters a search cursor may hold. */
    private static final String BASE64URL_NO_PADDING = "[A-Za-z0-9_-]+";

    private static final int WARMUP = 50;

    private static final int MEASURED = 200;

    /** p95 threshold of every search operation, in milliseconds. */
    private static final long SEARCH_P95_MS = 250;

    /** p95 threshold of get-by-id, in milliseconds. */
    private static final long GET_P95_MS = 50;

    private static final String CSZ = "classpath:generator/csz-sample.csv";

    private static final String STATE_FILTER = "CA";

    private static final String REPORT_FILE = "search-benchmark-results.md";

    private static final String SEARCH = "/api/customers";

    private static final String CUSTMAST = "custmast";

    private static final double NANOS_PER_MS = 1_000_000.0;

    private static final long MIB = 1024L * 1024L;

    @Autowired
    private CustomerLoader loader;

    @Autowired
    private CszSource cszSource;

    @Autowired
    private CustomerSearchRepository searchRepository;

    @Autowired
    private NamedParameterJdbcTemplate namedJdbc;

    /**
     * The search settings the service runs with; their row cap ({@code customer-master.search.max-rows},
     * 9,999) is the bound the service passes to the count through a cursor.
     */
    @Autowired
    private AppProperties appProperties;

    @Autowired
    private Clock clock;

    @Test
    @Timeout(value = 30, unit = TimeUnit.MINUTES)
    @DisplayName("1,000,000 rows: p95 within thresholds and no Seq Scan on custmast in the checked plans")
    void searchMeetsLatencyAndPlanThresholdsAtOneMillionRows() throws IOException {
        Instant runAt = Instant.now(clock);

        LoadStats loadStats = load();
        Filters filters = chooseParameters(loadStats.first());
        DeepPage deepPage = buildCursorChain();

        RestClient client = inquiry();
        List<Result> results = new ArrayList<>();
        for (Operation operation : operations(filters, deepPage, loadStats.first())) {
            results.add(measure(client, operation));
        }

        SearchCriteria deepPageCriteria = new SearchCriteria("", "", "", false, PAGE_SIZE, deepPage.keyset());
        List<PlanCheck> plans = List.of(
                explain("first-page", new SearchCriteria("", "", "", false, PAGE_SIZE, null)),
                explain("page-" + DEEP_PAGE, deepPageCriteria),
                explainCount("page-" + DEEP_PAGE + "-count", deepPageCriteria),
                explain("name-prefix-3", new SearchCriteria(filters.name3(), "", "", false, PAGE_SIZE, null)));

        // Report first, so a run that misses a threshold still prints and keeps every measured number.
        String report = report(runAt, loadStats, filters, deepPage, results, plans);
        System.out.println(report);
        Path reportPath = Path.of("target", REPORT_FILE);
        Files.createDirectories(reportPath.getParent());
        Files.writeString(reportPath, report, StandardCharsets.UTF_8);

        SoftAssertions softly = new SoftAssertions();
        for (Result result : results) {
            softly.assertThat(result.p95Ms())
                    .as("%s p95 (ms) against its threshold of %d ms", result.label(), result.thresholdMs())
                    .isLessThanOrEqualTo((double) result.thresholdMs());
        }
        for (PlanCheck plan : plans) {
            softly.assertThat(plan.violations())
                    .as("%s plan must not contain a Seq Scan on %s:%n%s", plan.label(), CUSTMAST,
                            String.join(System.lineSeparator(), plan.lines()))
                    .isEmpty();
        }
        softly.assertAll();
    }

    /**
     * Loads {@value #ROWS} generated customers from {@value #START}, analyzes the table and checks the
     * row count.
     *
     * <p>The load runs through the {@link CustomerLoader} bean outside any test transaction, so its own
     * transaction commits. {@code ANALYZE} follows the commit, as the generator runner runs it, so the
     * planner works from current statistics.
     *
     * @return the load figures for the report
     */
    private LoadStats load() {
        OffsetDateTime loadTime = OffsetDateTime.now(clock).truncatedTo(ChronoUnit.MICROS);
        List<CszSource.CszRow> csz = cszSource.load(CSZ);
        CustomerId start = CustomerId.parse(START);
        CustomerLoader.Plan plan = new CustomerLoader.Plan(start, ROWS,
                new CustomerDataGenerator(NameGenerator.seeded(SEED), csz, loadTime));

        long loadStart = System.nanoTime();
        long loaded = loader.load(plan);
        long loadNanos = System.nanoTime() - loadStart;

        long analyzeStart = System.nanoTime();
        jdbcTemplate.execute("ANALYZE " + CUSTMAST);
        long analyzeNanos = System.nanoTime() - analyzeStart;

        Long counted = jdbcTemplate.queryForObject("SELECT count(*) FROM " + CUSTMAST, Long.class);
        assertThat(loaded).as("rows the loader reports").isEqualTo(ROWS);
        assertThat(counted).as("rows in custmast after the load").isEqualTo(ROWS);

        String tableSize = jdbcTemplate.queryForObject(
                "SELECT pg_size_pretty(pg_total_relation_size('" + CUSTMAST + "'))", String.class);
        CustomerId last = CustomerId.fromOrdinal(start.toOrdinal() + ROWS - 1);
        return new LoadStats(start, last, csz.size(), loadNanos / NANOS_PER_MS, analyzeNanos / NANOS_PER_MS,
                tableSize);
    }

    /**
     * Chooses the name and city prefixes from the loaded rows and counts the active rows each filter
     * matches. None of these setup queries is timed.
     *
     * <p>The probe is the id in the middle of the loaded range. From the first row at or after it in
     * {@code custid} order ({@code customer_sort}) whose name starts with three letters A to Z, the
     * first three characters are the three-letter prefix and the first one the one-letter prefix; the
     * city prefix follows the same rule on {@code city}. Generated words use only A to Z, so a
     * one-letter prefix selects about 1/27 of the rows, a typical selectivity. The pattern match is
     * evaluated under {@code COLLATE "C"} so the bracket range means exactly the ASCII letters.
     *
     * @param first the first loaded id
     * @return the chosen filters and their match counts
     */
    private Filters chooseParameters(CustomerId first) {
        String probe = CustomerId.fromOrdinal(first.toOrdinal() + ROWS / 2).value();
        String name3 = firstThreeLetters("name", probe);
        String name1 = name3.substring(0, 1);
        String city3 = firstThreeLetters("city", probe);

        long active = count("active = 'Y'", Map.of());
        long name1Rows = count("active = 'Y' AND name LIKE :prefix", Map.of("prefix", name1 + "%"));
        long name3Rows = count("active = 'Y' AND name LIKE :prefix", Map.of("prefix", name3 + "%"));
        long city3Rows = count("active = 'Y' AND city LIKE :prefix", Map.of("prefix", city3 + "%"));
        long stateRows = count("active = 'Y' AND state = CAST(:state AS char(2))", Map.of("state", STATE_FILTER));
        long allRows = count("TRUE", Map.of());
        return new Filters(probe, name1, name3, city3, active, name1Rows, name3Rows, city3Rows, stateRows,
                allRows);
    }

    /**
     * Returns the first three characters of {@code column} in the first row at or after {@code probe}
     * whose value starts with three letters A to Z.
     *
     * @param column {@code name} or {@code city}, a fixed literal of this class
     * @param probe  the id to start from
     * @return three uppercase ASCII letters
     */
    private String firstThreeLetters(String column, String probe) {
        List<String> found = namedJdbc.queryForList(
                "SELECT left(" + column + ", 3) FROM " + CUSTMAST
                        + " WHERE custid >= CAST(:probe AS char(4))"
                        + " AND left(" + column + ", 3) COLLATE \"C\" ~ '^[A-Z]{3}$'"
                        + " ORDER BY custid LIMIT 1",
                Map.of("probe", probe), String.class);
        assertThat(found).as("a row at or after %s whose %s starts with three letters", probe, column)
                .hasSize(1);
        return found.get(0);
    }

    /**
     * Counts the {@code custmast} rows that satisfy a fixed predicate of this class.
     *
     * @param predicate the {@code WHERE} condition, with named parameters
     * @param params    its parameter values
     * @return the row count
     */
    private long count(String predicate, Map<String, ?> params) {
        Long rows = namedJdbc.queryForObject(
                "SELECT count(*) FROM " + CUSTMAST + " WHERE " + predicate, params, Long.class);
        assertThat(rows).as("count of rows where %s", predicate).isNotNull();
        return rows;
    }

    /**
     * Walks pages 1 to {@value #DEEP_PAGE} through the API following {@code nextCursor} and checks each
     * page and cursor against {@link #expectedChainRows()}, the oracle of the class description, before
     * anything is measured. The cursor of page {@value #DEEP_PAGE} - 1 requests the deep page, and its
     * decoded position is the deep-page plan's keyset.
     *
     * @return the deep-page cursor, its keyset, the boundary row it names and the ids page
     *         {@value #DEEP_PAGE} must hold
     */
    private DeepPage buildCursorChain() {
        List<KeyRow> expected = expectedChainRows();
        RestClient client = inquiry();
        String cursor = null;
        String cursorToDeepPage = null;
        SearchCriteria.Cursor keyset = null;
        for (int number = 1; number <= DEEP_PAGE; number++) {
            if (number == DEEP_PAGE) {
                cursorToDeepPage = cursor;
            }
            JsonNode page = searchPage(client, cursor, number, expected);
            cursor = page.path("nextCursor").textValue();
            SearchCriteria.Cursor position = decodeCursor(cursor, number, expected);
            if (number == DEEP_PAGE - 1) {
                keyset = position;
            }
        }

        int servedBefore = (DEEP_PAGE - 1) * PAGE_SIZE;
        KeyRow boundary = expected.get(servedBefore - 1);
        assertThat(keyset)
                .as("keyset of the page-%d cursor against row %d of the independent order", DEEP_PAGE, servedBefore)
                .isEqualTo(new SearchCriteria.Cursor(boundary.name(), boundary.city(), boundary.state(),
                        boundary.custId(), servedBefore));
        return new DeepPage(cursorToDeepPage, keyset, boundary, custIds(expected.subList(servedBefore, CHAIN_ROWS)));
    }

    /**
     * Reads the first {@value #CHAIN_ROWS} active rows in list order with {@link #CHAIN_ORDER_SQL},
     * independently of the search's statement, keyset and cursor.
     *
     * <p>{@code custid} and {@code state} are {@code char} columns, so they are trimmed, as the
     * search's JSON carries them.
     *
     * @return the rows of pages 1 to {@value #DEEP_PAGE}, in list order
     */
    private List<KeyRow> expectedChainRows() {
        List<KeyRow> rows = namedJdbc.query(CHAIN_ORDER_SQL, Map.of("rows", CHAIN_ROWS),
                (rs, rowNum) -> new KeyRow(rs.getString("custid").trim(), rs.getString("name"),
                        rs.getString("city"), rs.getString("state").trim()));
        assertThat(rows).as("active rows in list order for pages 1 to %d", DEEP_PAGE).hasSize(CHAIN_ROWS);
        return rows;
    }

    /**
     * Requests one unfiltered page of the cursor chain and asserts that it is page {@code number} of
     * the independent order: status 200, exactly its {@value #PAGE_SIZE} ids in order, a textual
     * {@code nextCursor} and {@code limitReached} false.
     *
     * @param client   the signed-in client
     * @param cursor   the cursor of the page, or {@code null} for the first page
     * @param number   the 1-based page number
     * @param expected the independent order of pages 1 to {@value #DEEP_PAGE}
     * @return the parsed page
     */
    private JsonNode searchPage(RestClient client, String cursor, int number, List<KeyRow> expected) {
        ResponseEntity<String> response = cursor == null
                ? client.get().uri(SEARCH + "?size={size}", PAGE_SIZE).retrieve().toEntity(String.class)
                : client.get().uri(SEARCH + "?size={size}&cursor={cursor}", PAGE_SIZE, cursor)
                        .retrieve().toEntity(String.class);
        assertThat(response.getStatusCode()).as("status of page %d: %s", number, response.getBody())
                .isEqualTo(HttpStatus.OK);
        JsonNode page = json(response);
        int from = (number - 1) * PAGE_SIZE;
        assertThat(custIds(page))
                .as("ids on page %d against rows %d to %d of the independent order", number, from + 1,
                        from + PAGE_SIZE)
                .containsExactlyElementsOf(custIds(expected.subList(from, from + PAGE_SIZE)));
        assertThat(page.path("nextCursor").isTextual()).as("nextCursor of page %d", number).isTrue();
        assertThat(page.path("limitReached").asBoolean(true)).as("limitReached on page %d", number).isFalse();
        return page;
    }

    /**
     * Decodes the {@code nextCursor} that page {@code number} issued: base64url without padding over a
     * JSON object with exactly {@link #CURSOR_PROPERTIES}, which must name row 12 &middot; {@code number}
     * of {@code expected} with {@code served} = 12 &middot; {@code number}.
     *
     * @return the decoded position, as the service passes it to the repository
     */
    private SearchCriteria.Cursor decodeCursor(String cursor, int number, List<KeyRow> expected) {
        assertThat(cursor).as("nextCursor of page %d is base64url without padding", number)
                .matches(BASE64URL_NO_PADDING);
        JsonNode payload;
        try {
            payload = objectMapper.readTree(Base64.getUrlDecoder().decode(cursor));
        } catch (IllegalArgumentException | IOException e) {
            throw new AssertionError("nextCursor of page " + number + " is not base64url JSON: " + cursor, e);
        }
        assertThat(payload.isObject()).as("nextCursor of page %d decodes to a JSON object: %s", number, payload)
                .isTrue();
        List<String> properties = new ArrayList<>();
        for (Map.Entry<String, JsonNode> property : payload.properties()) {
            properties.add(property.getKey());
        }
        assertThat(properties).as("properties of the nextCursor of page %d: %s", number, payload)
                .containsExactlyInAnyOrderElementsOf(CURSOR_PROPERTIES);
        for (String key : List.of("name", "city", "state", "custid")) {
            assertThat(payload.path(key).isTextual()).as("%s of the nextCursor of page %d: %s", key, number, payload)
                    .isTrue();
        }
        JsonNode servedNode = payload.path("served");
        assertThat(servedNode.isIntegralNumber() && servedNode.canConvertToInt())
                .as("served of the nextCursor of page %d is an int: %s", number, payload).isTrue();
        int served = number * PAGE_SIZE;
        assertThat(servedNode.intValue()).as("served of the nextCursor of page %d", number).isEqualTo(served);

        SearchCriteria.Cursor position = new SearchCriteria.Cursor(payload.path("name").textValue(),
                payload.path("city").textValue(), payload.path("state").textValue(),
                payload.path("custid").textValue(), servedNode.intValue());
        KeyRow last = expected.get(served - 1);
        assertThat(position)
                .as("nextCursor of page %d against row %d of the independent order", number, served)
                .isEqualTo(new SearchCriteria.Cursor(last.name(), last.city(), last.state(), last.custId(), served));
        return position;
    }

    private static List<String> custIds(JsonNode page) {
        List<String> ids = new ArrayList<>();
        for (JsonNode item : page.path("items")) {
            ids.add(item.path("custId").asText());
        }
        return ids;
    }

    private static List<String> custIds(List<KeyRow> rows) {
        List<String> ids = new ArrayList<>(rows.size());
        for (KeyRow row : rows) {
            ids.add(row.custId());
        }
        return ids;
    }

    /**
     * Defines the eight measured operations of the benchmark, each with its request, its threshold and
     * the content check applied to its first warm-up response.
     *
     * @param filters  the chosen filter values
     * @param deepPage the cursor to page {@value #DEEP_PAGE} and the ids that page must hold
     * @param first    the first loaded id, the base of the get-by-id draws
     * @return the operations in report order
     */
    private List<Operation> operations(Filters filters, DeepPage deepPage, CustomerId first) {
        String page = SEARCH + "?size={size}";
        Map<String, Object> size = Map.of("size", PAGE_SIZE);
        Random ids = new Random(SEED);
        return List.of(
                search("first-page", page, size, true, true, null, null),
                search("name-prefix-1", page + "&name={name}", with(size, "name", filters.name1()), false,
                        true, "name", filters.name1()),
                search("name-prefix-3", page + "&name={name}", with(size, "name", filters.name3()), false,
                        true, "name", filters.name3()),
                search("city-prefix-3", page + "&city={city}", with(size, "city", filters.city3()), false,
                        true, "city", filters.city3()),
                search("state-" + STATE_FILTER, page + "&state={state}", with(size, "state", STATE_FILTER),
                        false, true, "state", STATE_FILTER),
                search("include-inactive", page + "&includeInactive={inactive}", with(size, "inactive", "true"),
                        false, false, null, null),
                expectingIds(search("page-" + DEEP_PAGE, page + "&cursor={cursor}",
                        with(size, "cursor", deepPage.cursor()), true, true, null, null), deepPage.custIds()),
                new Operation("get-by-id", "GET " + SEARCH + "/{custId}, a seeded random id per call",
                        GET_P95_MS,
                        () -> new Call(SEARCH + "/{custId}", Map.of("custId",
                                CustomerId.fromOrdinal(first.toOrdinal() + ids.nextInt(ROWS)).value())),
                        (call, body) -> assertThat(body.path("custId").asText())
                                .as("get-by-id returns the requested customer")
                                .isEqualTo(call.variables().get("custId"))));
    }

    /**
     * Builds one search operation.
     *
     * @param label      the operation's name in the report
     * @param template   the URI template
     * @param variables  the template's values, encoded by the client
     * @param fullPage   whether the first warm-up response must hold exactly {@value #PAGE_SIZE} items
     *                   rather than 1 to {@value #PAGE_SIZE}
     * @param activeOnly whether every item must be active
     * @param field      the item member every item must match, or {@code null}
     * @param expected   the prefix of {@code field} ({@code name}, {@code city}) or its exact value
     *                   ({@code state}), or {@code null}
     * @return the operation with threshold {@value #SEARCH_P95_MS} ms
     */
    private Operation search(String label, String template, Map<String, Object> variables, boolean fullPage,
            boolean activeOnly, String field, String expected) {
        String request = "GET " + expand(template, variables);
        Call call = new Call(template, variables);
        return new Operation(label, request, SEARCH_P95_MS, () -> call, (sent, body) -> {
            JsonNode items = body.path("items");
            if (fullPage) {
                assertThat(items.size()).as("%s items", label).isEqualTo(PAGE_SIZE);
            } else {
                assertThat(items.size()).as("%s items", label).isBetween(1, PAGE_SIZE);
            }
            for (JsonNode item : items) {
                if (activeOnly) {
                    assertThat(item.path("active").asText()).as("%s active of %s", label, item).isEqualTo("Y");
                }
                if ("state".equals(field)) {
                    assertThat(item.path(field).asText()).as("%s state of %s", label, item).isEqualTo(expected);
                } else if (field != null) {
                    assertThat(item.path(field).asText()).as("%s %s of %s", label, field, item)
                            .startsWith(expected);
                }
            }
        });
    }

    /**
     * Extends an operation's content check: after its own checks, the items of the first warm-up
     * response must be exactly {@code custIds}, in order.
     *
     * @param operation the operation
     * @param custIds   the ids the response must hold, from the independent list order
     * @return the same operation with the extended check
     */
    private static Operation expectingIds(Operation operation, List<String> custIds) {
        return new Operation(operation.label(), operation.request(), operation.thresholdMs(), operation.calls(),
                operation.check().andThen((call, body) -> assertThat(custIds(body))
                        .as("%s ids against the independent list order", operation.label())
                        .containsExactlyElementsOf(custIds)));
    }

    private static Map<String, Object> with(Map<String, Object> base, String name, Object value) {
        Map<String, Object> variables = new LinkedHashMap<>(base);
        variables.put(name, value);
        return variables;
    }

    /**
     * Expands a URI template for the report only; the client encodes the values it sends.
     *
     * @param template  the template
     * @param variables its values
     * @return the request as text
     */
    private static String expand(String template, Map<String, Object> variables) {
        String text = template;
        for (Map.Entry<String, Object> variable : variables.entrySet()) {
            text = text.replace("{" + variable.getKey() + "}", String.valueOf(variable.getValue()));
        }
        return text;
    }

    /**
     * Runs one operation: {@value #WARMUP} warm-up calls, the first of which is checked for content,
     * then {@value #MEASURED} measured calls, sequentially on this thread.
     *
     * <p>A sample spans the call until its whole body is read. The status is checked after the clock
     * stops; any status other than 200 fails the test with the operation and the body.
     *
     * @param client    the signed-in client
     * @param operation the operation
     * @return the percentiles of the measured calls
     */
    private Result measure(RestClient client, Operation operation) {
        for (int i = 0; i < WARMUP; i++) {
            Call call = operation.calls().get();
            ResponseEntity<String> response = send(client, call);
            requireOk(operation, response);
            if (i == 0) {
                operation.check().accept(call, json(response));
            }
        }
        long[] samples = new long[MEASURED];
        for (int i = 0; i < MEASURED; i++) {
            Call call = operation.calls().get();
            long started = System.nanoTime();
            ResponseEntity<String> response = send(client, call);
            samples[i] = System.nanoTime() - started;
            requireOk(operation, response);
        }
        Arrays.sort(samples);
        return new Result(operation.label(), operation.request(), MEASURED,
                percentile(samples, 50) / NANOS_PER_MS,
                percentile(samples, 95) / NANOS_PER_MS,
                samples[samples.length - 1] / NANOS_PER_MS,
                operation.thresholdMs());
    }

    private static ResponseEntity<String> send(RestClient client, Call call) {
        return client.get().uri(call.template(), call.variables()).retrieve().toEntity(String.class);
    }

    private static void requireOk(Operation operation, ResponseEntity<String> response) {
        assertThat(response.getStatusCode())
                .as("%s (%s) answered %s: %s", operation.label(), operation.request(), response.getStatusCode(),
                        response.getBody())
                .isEqualTo(HttpStatus.OK);
    }

    /**
     * Returns the nearest-rank percentile of sorted samples: the value at 1-based rank
     * ceil(p / 100 &middot; n), computed in integers.
     *
     * @param sorted  the samples in ascending order, at least one
     * @param percent the percentile, 1 to 100
     * @return the sample at that rank
     */
    private static long percentile(long[] sorted, int percent) {
        int rank = (percent * sorted.length + 99) / 100;
        return sorted[Math.max(rank, 1) - 1];
    }

    /**
     * Explains the production page statement for {@code criteria} and checks it for a sequential scan
     * of {@code custmast}.
     *
     * <p>The statement and its parameters come from
     * {@link CustomerSearchRepository#buildQuery(SearchCriteria, int)} with the look-ahead limit the
     * service passes, never from hand-written SQL, and are explained as
     * {@link CustomerSearchRepository#find(SearchCriteria, int)} runs them
     * ({@link #explain(String, CustomerSearchRepository.SqlQuery)}).
     *
     * @param label    the plan's name in the report
     * @param criteria the normalized criteria the service would pass
     * @return the plan excerpt and its violations
     */
    private PlanCheck explain(String label, SearchCriteria criteria) {
        return explain(label, searchRepository.buildQuery(criteria, LIMIT));
    }

    /**
     * Explains the production count statement for {@code criteria} and checks it for a sequential scan
     * of {@code custmast}: the count of the matching rows at or before the cursor key that the service
     * runs before the page of every request that carries a cursor.
     *
     * <p>The statement and its parameters come from
     * {@link CustomerSearchRepository#buildCountQuery(SearchCriteria, int)} with the row cap the service
     * passes as its bound, never from hand-written SQL, and are explained as
     * {@link CustomerSearchRepository#countThrough(SearchCriteria, int)} runs them
     * ({@link #explain(String, CustomerSearchRepository.SqlQuery)}).
     *
     * @param label    the plan's name in the report
     * @param criteria the normalized criteria the service would pass, with the cursor's keyset
     * @return the plan excerpt and its violations
     */
    private PlanCheck explainCount(String label, SearchCriteria criteria) {
        return explain(label, searchRepository.buildCountQuery(criteria, appProperties.search().maxRows()));
    }

    /**
     * Explains one production statement and checks its plan for a sequential scan of {@code custmast}.
     *
     * <p>The {@code EXPLAIN} runs as the repository runs the statement: in a read-only transaction that
     * first issues {@code SET LOCAL plan_cache_mode = force_custom_plan}, with every other planner
     * setting at its default. Both templates share the application's datasource, so both statements
     * run on the transaction's connection, and the parameters reach the planner as bound values.
     *
     * @param label the plan's name in the report
     * @param query the statement and parameters the repository builds
     * @return the plan excerpt and its violations
     */
    private PlanCheck explain(String label, CustomerSearchRepository.SqlQuery query) {
        TransactionTemplate readOnly = new TransactionTemplate(transactionTemplate.getTransactionManager());
        readOnly.setReadOnly(true);
        String planJson = readOnly.execute(status -> {
            jdbcTemplate.execute("SET LOCAL plan_cache_mode = force_custom_plan");
            return namedJdbc.queryForObject("EXPLAIN (FORMAT JSON) " + query.sql(), query.params(), String.class);
        });
        assertThat(planJson).as("EXPLAIN output of %s", label).isNotBlank();
        JsonNode root;
        try {
            root = objectMapper.readTree(planJson);
        } catch (JsonProcessingException e) {
            throw new AssertionError("EXPLAIN (FORMAT JSON) of " + label + " is not JSON: " + planJson, e);
        }
        JsonNode plan = root.path(0).path("Plan");
        assertThat(plan.isObject()).as("plan root of %s in %s", label, planJson).isTrue();
        List<String> lines = new ArrayList<>();
        List<String> violations = new ArrayList<>();
        walk(plan, 0, lines, violations);
        return new PlanCheck(label, lines, violations);
    }

    /**
     * Visits a plan node and its children depth first, adding one excerpt line per node and a
     * violation for each {@code Seq Scan} on {@code custmast}.
     *
     * <p>A line reads {@code Node Type [using Index Name] [on Relation Name | on CTE Name]
     * [Index Cond: ...] [Filter: ...] [Sort Key: ...]}, prefixed with the subplan name when the node
     * is one, such as {@code [CTE candidates]} for the fenced candidate set of a prefix search.
     *
     * @param node       the plan node
     * @param depth      its depth, for indentation
     * @param lines      the excerpt lines so far
     * @param violations the violations so far
     */
    private static void walk(JsonNode node, int depth, List<String> lines, List<String> violations) {
        String nodeType = node.path("Node Type").asText();
        String relation = node.path("Relation Name").asText("");
        StringBuilder line = new StringBuilder("  ".repeat(depth));
        String subplan = node.path("Subplan Name").asText("");
        if (!subplan.isEmpty()) {
            line.append('[').append(subplan).append("] ");
        }
        line.append(nodeType);
        appendIfPresent(line, " using ", node.path("Index Name"));
        appendIfPresent(line, " on ", node.path("Relation Name"));
        appendIfPresent(line, " on ", node.path("CTE Name"));
        appendIfPresent(line, " Index Cond: ", node.path("Index Cond"));
        appendIfPresent(line, " Filter: ", node.path("Filter"));
        JsonNode sortKey = node.path("Sort Key");
        if (sortKey.isArray() && !sortKey.isEmpty()) {
            List<String> keys = new ArrayList<>();
            sortKey.forEach(key -> keys.add(key.asText()));
            line.append(" Sort Key: ").append(String.join(", ", keys));
        }
        lines.add(line.toString());
        if ("Seq Scan".equals(nodeType) && CUSTMAST.equals(relation)) {
            violations.add(line.toString().strip());
        }
        for (JsonNode child : node.path("Plans")) {
            walk(child, depth + 1, lines, violations);
        }
    }

    /**
     * Appends {@code label} and a node member's text when the member is present.
     *
     * @param line   the excerpt line
     * @param label  the text before the value
     * @param member the member, possibly missing
     */
    private static void appendIfPresent(StringBuilder line, String label, JsonNode member) {
        if (member.isValueNode() && !member.asText().isEmpty()) {
            line.append(label).append(member.asText());
        }
    }

    /**
     * Builds the Markdown report of the run: environment, data, filters, cursor chain, latency table
     * and plans.
     *
     * @param runAt    when the run started
     * @param load     the load figures
     * @param filters  the chosen filters
     * @param deepPage the checked cursor chain to page {@value #DEEP_PAGE}
     * @param results  the latency results
     * @param plans    the plan checks
     * @return the report text
     */
    private String report(Instant runAt, LoadStats load, Filters filters, DeepPage deepPage, List<Result> results,
            List<PlanCheck> plans) {
        StringBuilder md = new StringBuilder();
        String nl = System.lineSeparator();
        md.append("# Customer search benchmark: ").append(String.format(Locale.ROOT, "%,d", ROWS))
                .append(" customers").append(nl).append(nl);
        md.append("Run started ").append(runAt.truncatedTo(ChronoUnit.SECONDS)).append(" (UTC).").append(nl)
                .append(nl);

        md.append("## Environment").append(nl).append(nl);
        md.append("| Item | Value |").append(nl).append("|---|---|").append(nl);
        Runtime runtime = Runtime.getRuntime();
        row(md, "Java runtime", Runtime.version().toString());
        row(md, "Processors available to the JVM", Integer.toString(runtime.availableProcessors()));
        row(md, "JVM max heap", mib(runtime.maxMemory()));
        row(md, "Operating system", System.getProperty("os.name") + " " + System.getProperty("os.arch"));
        row(md, "Host total memory", hostMemory());
        row(md, "Docker server", dockerServer());
        row(md, "PostgreSQL", jdbcTemplate.queryForObject("SELECT version()", String.class));
        row(md, "shared_buffers", jdbcTemplate.queryForObject("SHOW shared_buffers", String.class));
        md.append(nl);

        md.append("## Data").append(nl).append(nl);
        md.append("| Item | Value |").append(nl).append("|---|---|").append(nl);
        row(md, "Seed", Long.toString(SEED));
        row(md, "Rows", String.format(Locale.ROOT, "%,d", ROWS));
        row(md, "Id range", load.first().value() + ".." + load.last().value());
        row(md, "CSZ rows retained", load.cszRows() + " from " + CSZ);
        row(md, "Load (TRUNCATE, COPY, sequence restart, commit)", ms(load.loadMs()) + " ms");
        row(md, "ANALYZE custmast", ms(load.analyzeMs()) + " ms");
        row(md, "Load rate", String.format(Locale.ROOT, "%,.0f rows/s", ROWS * 1000.0 / load.loadMs()));
        row(md, "custmast total size (table and indexes)", load.tableSize());
        md.append(nl);

        md.append("## Filters").append(nl).append(nl);
        md.append("Chosen from the first row at or after id ").append(filters.probe())
                .append(" whose value starts with three letters A to Z.").append(nl).append(nl);
        md.append("| Filter | Value | Matching active rows |").append(nl).append("|---|---|---|").append(nl);
        filterRow(md, "none (first page, deep page)", "", filters.activeRows());
        filterRow(md, "name prefix, 1 letter", filters.name1(), filters.name1Rows());
        filterRow(md, "name prefix, 3 letters", filters.name3(), filters.name3Rows());
        filterRow(md, "city prefix, 3 letters", filters.city3(), filters.city3Rows());
        filterRow(md, "state", STATE_FILTER, filters.stateRows());
        md.append("| include inactive | true | ").append(String.format(Locale.ROOT, "%,d", filters.allRows()))
                .append(" (all rows) |").append(nl).append(nl);

        md.append("## Cursor chain").append(nl).append(nl);
        md.append(String.format(Locale.ROOT, "Pages 1 to %d (%d rows each) matched, in order, rows 1 to %d of the"
                + " independent list `%s` with rows = %d. Each page's nextCursor carried the keys of its last row"
                + " and served = %d x page.", DEEP_PAGE, PAGE_SIZE, CHAIN_ROWS, CHAIN_ORDER_SQL, CHAIN_ROWS,
                PAGE_SIZE)).append(nl).append(nl);
        md.append("| Item | Value |").append(nl).append("|---|---|").append(nl);
        KeyRow boundary = deepPage.boundary();
        String boundaryRow = String.format(Locale.ROOT, "Page %d boundary row (row %d)", DEEP_PAGE - 1,
                deepPage.keyset().served());
        row(md, boundaryRow + ": custid", boundary.custId());
        row(md, boundaryRow + ": name", boundary.name());
        row(md, boundaryRow + ": city", boundary.city());
        row(md, boundaryRow + ": state", boundary.state());
        row(md, String.format(Locale.ROOT, "served carried by the page-%d cursor", DEEP_PAGE),
                Integer.toString(deepPage.keyset().served()));
        List<String> deepIds = deepPage.custIds();
        row(md, String.format(Locale.ROOT, "Page %d first custid (row %d)", DEEP_PAGE, CHAIN_ROWS - PAGE_SIZE + 1),
                deepIds.get(0));
        row(md, String.format(Locale.ROOT, "Page %d last custid (row %d)", DEEP_PAGE, CHAIN_ROWS),
                deepIds.get(deepIds.size() - 1));
        md.append(nl);

        md.append("## Latency").append(nl).append(nl);
        md.append(String.format(Locale.ROOT, "%d warm-up calls (discarded) and %d measured calls per operation,"
                + " sequential on one thread, HTTP Basic as user %s (role INQUIRY), page size %d."
                + " Percentiles are nearest-rank.", WARMUP, MEASURED, INQUIRY_USER, PAGE_SIZE)).append(nl)
                .append(nl);
        md.append("| Operation | Request | Calls | p50 ms | p95 ms | max ms | p95 threshold ms | Verdict |").append(nl)
                .append("|---|---|---:|---:|---:|---:|---:|---|").append(nl);
        for (Result result : results) {
            md.append("| ").append(cell(result.label()))
                    .append(" | ").append(cell(result.request()))
                    .append(" | ").append(result.calls())
                    .append(" | ").append(ms(result.p50Ms()))
                    .append(" | ").append(ms(result.p95Ms()))
                    .append(" | ").append(ms(result.maxMs()))
                    .append(" | ").append(result.thresholdMs())
                    .append(" | ").append(result.passed() ? "PASS" : "FAIL")
                    .append(" |").append(nl);
        }
        md.append(nl);

        md.append("## Plans").append(nl).append(nl);
        md.append("EXPLAIN (FORMAT JSON) of the production statements, plan_cache_mode = force_custom_plan:")
                .append(" the page statement, LIMIT ").append(LIMIT).append(", and, for page-").append(DEEP_PAGE)
                .append("-count, the count through the cursor key that the page-").append(DEEP_PAGE)
                .append(" request runs before its page, LIMIT ").append(appProperties.search().maxRows())
                .append(" (the row cap). PASS means no Seq Scan on custmast.").append(nl).append(nl);
        for (PlanCheck plan : plans) {
            md.append("### ").append(plan.label()).append(": ").append(plan.passed() ? "PASS" : "FAIL").append(nl)
                    .append(nl).append("```").append(nl);
            for (String line : plan.lines()) {
                md.append(line).append(nl);
            }
            md.append("```").append(nl).append(nl);
        }
        return md.toString();
    }

    private static void row(StringBuilder md, String item, String value) {
        md.append("| ").append(cell(item)).append(" | ").append(cell(value)).append(" |")
                .append(System.lineSeparator());
    }

    private static void filterRow(StringBuilder md, String name, String value, long rows) {
        md.append("| ").append(cell(name)).append(" | ").append(value.isEmpty() ? "-" : cell(value)).append(" | ")
                .append(String.format(Locale.ROOT, "%,d", rows)).append(" |").append(System.lineSeparator());
    }

    /**
     * Escapes a table cell: a pipe would end the cell, a line break the row.
     *
     * @param text the cell text, or {@code null}
     * @return the escaped text; {@code unknown} for {@code null}
     */
    private static String cell(String text) {
        if (text == null) {
            return "unknown";
        }
        return text.replace("|", "\\|").replace('\n', ' ').replace('\r', ' ');
    }

    private static String ms(double millis) {
        return String.format(Locale.ROOT, "%.1f", millis);
    }

    private static String mib(long bytes) {
        return String.format(Locale.ROOT, "%,d MiB", bytes / MIB);
    }

    /**
     * Returns the host's total physical memory as the JVM sees it.
     *
     * @return the size in MiB, or {@code unknown} when the platform bean does not expose it
     */
    private static String hostMemory() {
        try {
            OperatingSystemMXBean os = ManagementFactory.getOperatingSystemMXBean();
            if (os instanceof com.sun.management.OperatingSystemMXBean platform) {
                return mib(platform.getTotalMemorySize());
            }
            return "unknown";
        } catch (RuntimeException | LinkageError e) {
            return "unknown (" + e.getClass().getSimpleName() + ")";
        }
    }

    /**
     * Describes the Docker server that runs the database container.
     *
     * @return the server version, CPU count and total memory, or {@code unknown} when the daemon
     *         cannot be queried
     */
    private static String dockerServer() {
        try {
            Info info = DockerClientFactory.instance().getInfo();
            Long memory = info.getMemTotal();
            return "version " + info.getServerVersion() + ", NCPU " + info.getNCPU() + ", MemTotal "
                    + (memory == null ? "unknown" : mib(memory));
        } catch (RuntimeException e) {
            return "unknown (" + e.getClass().getSimpleName() + ")";
        }
    }

    /**
     * The figures of the load. {@code loadMs} spans {@link CustomerLoader#load(CustomerLoader.Plan)}
     * (TRUNCATE, COPY, sequence restart, commit); {@code tableSize} is
     * {@code pg_size_pretty(pg_total_relation_size('custmast'))}, indexes included.
     */
    private record LoadStats(CustomerId first, CustomerId last, int cszRows, double loadMs, double analyzeMs,
            String tableSize) {
    }

    /**
     * The chosen filter values and their match counts; every count but {@code allRows} counts active rows
     * only.
     */
    private record Filters(String probe, String name1, String name3, String city3, long activeRows, long name1Rows,
            long name3Rows, long city3Rows, long stateRows, long allRows) {
    }

    /**
     * The deep page of the checked cursor chain: {@code cursor} is page {@value #DEEP_PAGE} - 1's
     * {@code nextCursor}, {@code keyset} its decoded form used by the plan check, {@code boundary} the row
     * it names and {@code custIds} the ids page {@value #DEEP_PAGE} must hold, in order.
     */
    private record DeepPage(String cursor, SearchCriteria.Cursor keyset, KeyRow boundary, List<String> custIds) {
    }

    /**
     * The sort keys of one row of the independent list order, with {@code custId} and {@code state}
     * trimmed as the search's JSON carries them.
     */
    private record KeyRow(String custId, String name, String city, String state) {
    }

    /** One GET request: a URI template relative to the base URL and its values, which the client encodes. */
    private record Call(String template, Map<String, Object> variables) {
    }

    /**
     * One measured operation; {@code calls} supplies the request of each call, and {@code check} runs on
     * the first warm-up response only.
     */
    private record Operation(String label, String request, long thresholdMs, Supplier<Call> calls,
            BiConsumer<Call, JsonNode> check) {
    }

    /** The latency of one operation's measured calls; the percentiles are nearest-rank, in milliseconds. */
    private record Result(String label, String request, int calls, double p50Ms, double p95Ms, double maxMs,
            long thresholdMs) {

        boolean passed() {
            return p95Ms <= thresholdMs;
        }
    }

    /**
     * The plan check of one statement: {@code lines} holds one excerpt line per plan node, indented by
     * depth, and {@code violations} the lines of every {@code Seq Scan} on {@code custmast}.
     */
    private record PlanCheck(String label, List<String> lines, List<String> violations) {

        boolean passed() {
            return violations.isEmpty();
        }
    }
}
