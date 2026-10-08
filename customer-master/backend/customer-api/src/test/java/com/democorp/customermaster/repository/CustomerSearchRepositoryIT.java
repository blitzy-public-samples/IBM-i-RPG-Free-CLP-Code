package com.democorp.customermaster.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.function.Predicate;
import java.util.stream.IntStream;

import com.democorp.customermaster.config.AppProperties;
import com.democorp.customermaster.domain.CustomerId;
import com.democorp.customermaster.domain.CustomerSummary;
import com.democorp.customermaster.domain.SearchCriteria;
import com.democorp.customermaster.domain.SearchPage;
import com.democorp.customermaster.generator.CszSource;
import com.democorp.customermaster.generator.CustomerDataGenerator;
import com.democorp.customermaster.generator.CustomerLoader;
import com.democorp.customermaster.generator.NameGenerator;
import com.democorp.customermaster.repository.CustomerSearchRepository.SqlQuery;
import com.democorp.customermaster.service.CustomerSearchService;
import com.democorp.customermaster.support.AbstractPostgresIT;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * Re-verifies the customer search list query of PMTCUSTR against PostgreSQL 18.6: the filters, the
 * blank-padded {@code LIKE} parity with Db2, literal matching of backslashes and apostrophes, the sort,
 * the plans the patterns produce, keyset pagination and the 9,999-row cap.
 *
 * <p><b>Source behaviour.</b> PMTCUSTR's cursor {@code ItemCur} selects
 * {@code where NAME LIKE :wkName and CITY LIKE :wkCity and STATE between ... and ACTIVE between ...
 * order by NAME, CITY, STATE optimize for 13 rows} [5250_Subfile/PMTCUSTR.SQLRPGLE:208-223], with
 * {@code ProcessSearchCriteria} building {@code wkName = %trim(SC_NAME) + '%'} into a
 * {@code varchar(13)}, so a full 13-character entry loses its {@code %}; a blank State entry selects
 * every state, and inactive rows appear only with F9 [5250_Subfile/PMTCUSTR.SQLRPGLE:625-665]. Db2
 * matches {@code LIKE} against the whole blank-padded {@code NAME CHAR(40)} / {@code CITY CHAR(20)}
 * value [5250_Subfile/Custmast2.sql:11,13] and has no default escape character. The target stores
 * {@code varchar} without padding, so {@link CustomerSearchRepository} matches
 * {@code rpad(name, 40) LIKE :namePattern} (and {@code rpad(city, 20)}), narrows the rows with the
 * index prefix {@code name LIKE :namePrefix}, drops {@code rpad} only for a pure literal prefix that
 * does not end in a blank, and doubles every backslash.
 *
 * <p><b>What is proved.</b>
 * <ul>
 *   <li><b>Filters</b> through {@link CustomerSearchRepository#find(SearchCriteria, int)}: name and city
 *       prefixes, the exact state, inactive rows excluded unless included, and user {@code %} and
 *       {@code _} acting as wildcards.</li>
 *   <li><b>Padding parity</b> (a preserved source defect): a full 13-character filter finds nothing; a
 *       13-character filter with an inner {@code %} matches only at the end of the 40-character padded
 *       value; a {@code _} matches a pad blank; a lead that ends in blanks is completed only by padding.
 *       Each case gives a different answer without {@code rpad}, so these tests discriminate.</li>
 *   <li><b>Literals:</b> the seed names {@code URNA \NUNC\ COMPANY} and {@code NIBH L'LOR COMPANY}
 *       match literally, and a control row proves {@code \} does not act as an escape.</li>
 *   <li><b>Sort:</b> {@code name, city, state, custid} under {@code customer_sort}, letters before
 *       digits.</li>
 *   <li><b>Plans</b> of the exact statement {@code find} runs ({@link CustomerSearchRepository#buildQuery
 *       (SearchCriteria, int)}): a three-letter prefix is served by {@code custmast_name} with no
 *       {@code rpad}; {@code AB_} and {@code AB}, ten blanks, {@code %} use {@code custmast_name} on the
 *       lead {@code AB} with the {@code rpad} check as a filter.</li>
 *   <li><b>Pagination</b> through {@link CustomerSearchService}: 30 pages of the default 12 rows with
 *       no duplicate and no gap, across rows that tie on name, city and state.</li>
 *   <li><b>Cap:</b> the page that reaches 9,999 rows is cut there, carries {@code limitReached} and
 *       DEM0006, and has no next cursor.</li>
 * </ul>
 *
 * <p><b>Fixtures.</b> Test databases hold no seed rows and the base class empties {@code custmast}
 * before every test, so each test writes its own rows: small fixtures with plain SQL and the explicit V3
 * column list, and the 20,000- and 10,500-row sets through {@link CustomerLoader} (one {@code COPY} in
 * its own transaction; the base context's {@code LoadCheckpoints} bean does nothing). The loader
 * restarts {@code custmast_id_seq}; the next {@code DatabaseCleaner.clean()} resets it. Filters passed
 * to the repository are already normalized (uppercase, trimmed, {@code ""} for none), as
 * {@code CustomerSearchService} would pass them.
 *
 * <p><b>Context.</b> The base context of {@link AbstractPostgresIT}, with no {@code @MockitoBean},
 * {@code @Import} or property override, so no context variant is added. The only session settings, the
 * {@code SET LOCAL} statements of the plan checks, run inside one {@code transactionTemplate} callback
 * and end with it; no transaction, lock or connection outlives a test.
 */
class CustomerSearchRepositoryIT extends AbstractPostgresIT {

    /** Every column of V3, in the order the fixture inserts bind them; {@code row_version} is 0. */
    private static final String INSERT_SQL =
            "INSERT INTO custmast (custid, name, addr, city, state, zip, corpphone, acctmgr, acctphone,"
                    + " active, chgtime, chguser, row_version)"
                    + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0)";

    /** The list order the search must reproduce, read independently of the repository. */
    private static final String EXPECTED_ORDER_SQL =
            "SELECT custid, name, city, state FROM custmast ORDER BY name, city, state, custid";

    /** Street of every fixture row; the search never reads it. */
    private static final String ADDR = "1 MAIN ST";

    /** ZIP of every fixture row; the list shows its first five characters. */
    private static final String ZIP = "90210";

    /** Corporate and account manager phone of every fixture row. */
    private static final String PHONE = "(415) 555-0100";

    /** Account manager of every fixture row. */
    private static final String MANAGER = "JANE DOE";

    /** Change user of every fixture row: a test user, never {@code *SYSTEM*}. */
    private static final String CHG_USER = "maint";

    /** Active flag of a customer the list shows by default. */
    private static final String ACTIVE = "Y";

    /** Active flag of a customer the list shows only with F9. */
    private static final String INACTIVE = "N";

    /** Page size of the small fixture criteria; the repository ignores it and honours the limit. */
    private static final int FIXTURE_SIZE = 100;

    /** Limit of the small fixture queries: larger than any fixture, so every match is returned. */
    private static final int FIXTURE_LIMIT = FIXTURE_SIZE + 1;

    /** Limit of the plan checks: one 12-row page plus the look-ahead row, as the service asks. */
    private static final int PAGE_LIMIT = 13;

    /** Default page size of the service: the 12-row subfile page (SFLPAG). */
    private static final int DEFAULT_PAGE_SIZE = 12;

    /** Largest page size the service accepts; used to reach the cap in few pages. */
    private static final int MAX_PAGE_SIZE = 100;

    /** Guard of the cap walk: 100 pages of at most 100 rows reach 9,999; one more means a failure. */
    private static final int MAX_CAP_PAGES = 101;

    /** First id of a generated load, LOADCUSTR's {@code varCUSTID = '1001'}. */
    private static final String FIRST_GENERATED_ID = "1001";

    /** Seed of every generated load, so each test sees the same rows. */
    private static final long GENERATOR_SEED = 42L;

    /** City/state/ZIP rows of the generated loads; every state exists in V2. */
    private static final List<CszSource.CszRow> CSZ_ROWS = List.of(
            new CszSource.CszRow(90210, "STANDARD", "BEVERLY HILLS", "CA"),
            new CszSource.CszRow(10001, "STANDARD", "NEW YORK", "NY"),
            new CszSource.CszRow(73301, "STANDARD", "AUSTIN", "TX"),
            new CszSource.CszRow(60601, "STANDARD", "CHICAGO", "IL"),
            new CszSource.CszRow(84101, "STANDARD", "SALT LAKE CITY", "UT"),
            new CszSource.CszRow(2108, "STANDARD", "BOSTON", "MA"));

    /** The index {@code custmast_name (name varchar_pattern_ops)} of V3. */
    private static final String NAME_INDEX = "custmast_name";

    /** The padded match the repository issues when padding can contribute. */
    private static final String RPAD = "rpad";

    @Autowired
    private CustomerSearchRepository searchRepository;

    @Autowired
    private CustomerSearchService searchService;

    @Autowired
    private CustomerLoader customerLoader;

    @Autowired
    private NamedParameterJdbcTemplate namedParameterJdbcTemplate;

    @Autowired
    private AppProperties appProperties;

    @Autowired
    private Clock clock;

    // ---------------------------------------------------------------------------------------------
    // Filters
    // ---------------------------------------------------------------------------------------------

    /**
     * Name prefix {@code AC}: the active matches by default; with F9 the inactive {@code ACME HOLDINGS}
     * joins them, flagged {@code N}. Every list column comes back as stored, {@code zip5} cut to five.
     */
    @Test
    void namePrefixListsActiveMatchesAndAddsInactiveOnesWhenIncluded() {
        insertFilterFixture();

        List<CustomerSummary> activeOnly = find(criteria("AC", "", "", false));
        assertThat(names(activeOnly)).containsExactly("ACME TOOLS", "ACORN LLC");
        CustomerSummary acorn = activeOnly.get(1);
        assertThat(acorn.custId().value()).isEqualTo("AAAC");
        assertThat(acorn.city()).isEqualTo("SACRAMENTO");
        assertThat(acorn.state()).isEqualTo("CA");
        assertThat(acorn.zip5()).isEqualTo(ZIP);
        assertThat(acorn.active()).isEqualTo(ACTIVE);

        List<CustomerSummary> withInactive = find(criteria("AC", "", "", true));
        assertThat(names(withInactive)).containsExactly("ACME HOLDINGS", "ACME TOOLS", "ACORN LLC");
        assertThat(withInactive.getFirst().active()).isEqualTo(INACTIVE);
    }

    /** City prefix {@code SPRING}: both Springfield and Springville, in name order. */
    @Test
    void cityPrefixMatchesStartOfCity() {
        insertFilterFixture();

        assertThat(names(find(criteria("", "SPRING", "", false))))
                .containsExactly("ACME TOOLS", "BETA INC");
    }

    /** The State filter is exact; a code no row carries matches nothing. */
    @Test
    void stateFilterIsExact() {
        insertFilterFixture();

        assertThat(names(find(criteria("", "", "CA", false)))).containsExactly("ACORN LLC");
        assertThat(find(criteria("", "", "NY", false))).isEmpty();
    }

    /** With no filter, only active rows are listed unless F9 includes the inactive ones. */
    @Test
    void inactiveRowsAreExcludedUnlessIncluded() {
        insertFilterFixture();

        List<CustomerSummary> activeOnly = find(criteria("", "", "", false));
        assertThat(activeOnly).hasSize(3).allSatisfy(row -> assertThat(row.active()).isEqualTo(ACTIVE));

        List<CustomerSummary> all = find(criteria("", "", "", true));
        assertThat(names(all)).containsExactly("ACME HOLDINGS", "ACME TOOLS", "ACORN LLC", "BETA INC");
        assertThat(all).filteredOn(row -> INACTIVE.equals(row.active()))
                .extracting(row -> row.custId().value()).containsExactly("AAAE");
    }

    /** A typed {@code %} matches any run of characters and a typed {@code _} any one character. */
    @Test
    void userPercentAndUnderscoreActAsWildcards() {
        insertFilterFixture();

        assertThat(names(find(criteria("%TOOLS", "", "", false)))).containsExactly("ACME TOOLS");
        assertThat(names(find(criteria("AC_E", "", "", false)))).containsExactly("ACME TOOLS");
        assertThat(names(find(criteria("AC_E", "", "", true))))
                .containsExactly("ACME HOLDINGS", "ACME TOOLS");
    }

    // ---------------------------------------------------------------------------------------------
    // Padding parity with Db2's blank-padded CHAR (preserved source defect)
    // ---------------------------------------------------------------------------------------------

    /**
     * A full 13-character entry loses the appended {@code %} to the {@code varchar(13)} cut, so its
     * pattern has no wildcard and, compared with the 40-character padded value, finds nothing, not even
     * the identical stored name. One character shorter, the {@code %} survives and the name is found.
     */
    @Test
    void fullThirteenCharacterFilterLosesItsWildcardAndFindsNothing() {
        String thirteen = "ABCDEFGHIJKLM";
        assertThat(thirteen).hasSize(CustomerSearchRepository.FILTER_WIDTH);
        insert("AAAB", thirteen, "SPRINGFIELD", "IL", ACTIVE);

        SearchCriteria full = criteria(thirteen, "", "", false);
        assertThat(searchRepository.buildQuery(full, FIXTURE_LIMIT).params())
                .as("the cut drops the appended percent sign").containsEntry("namePattern", thirteen);
        assertThat(find(full)).isEmpty();

        assertThat(names(find(criteria("ABCDEFGHIJKL", "", "", false)))).containsExactly(thirteen);
    }

    /**
     * The 13-character filter {@code A%ENDSWITHXYZ} keeps no trailing {@code %}, so the padded value
     * must end in those 11 characters: a 40-character name does, a 20-character name does not, because
     * its 20 pad blanks follow them. Without the padded match both would be found.
     */
    @Test
    void innerPercentMatchesOnlyAtEndOfPaddedValue() {
        String filter = "A%ENDSWITHXYZ";
        String fullWidth = "A" + "B".repeat(28) + "ENDSWITHXYZ";
        String halfWidth = "A" + "C".repeat(8) + "ENDSWITHXYZ";
        assertThat(filter).hasSize(CustomerSearchRepository.FILTER_WIDTH);
        assertThat(fullWidth).hasSize(CustomerSearchRepository.NAME_WIDTH);
        assertThat(halfWidth).hasSize(20);
        insert("AAAB", fullWidth, "SPRINGFIELD", "IL", ACTIVE);
        insert("AAAC", halfWidth, "SPRINGFIELD", "IL", ACTIVE);

        assertThat(names(find(criteria(filter, "", "", false)))).containsExactly(fullWidth);
    }

    /**
     * A typed {@code _} can match a pad blank: name filter {@code AB_} finds the stored name {@code AB}
     * and city filter {@code X_} the stored city {@code X}, as on the padded Db2 columns.
     */
    @Test
    void underscoreMatchesPadBlank() {
        insert("AAAB", "AB", "SPRINGFIELD", "IL", ACTIVE);
        insert("AAAC", "CITY PAD", "X", "CA", ACTIVE);

        assertThat(names(find(criteria("AB_", "", "", false)))).containsExactly("AB");
        assertThat(find(criteria("", "X_", "", false))).extracting(CustomerSummary::city)
                .containsExactly("X");
    }

    /**
     * The 13-character filter {@code AB}, ten blanks, {@code %} keeps that pattern after the cut. Only
     * padding can supply the ten blanks, so it finds {@code AB} and not {@code ABX}, for the name and
     * for the city alike.
     */
    @Test
    void leadEndingInBlanksIsCompletedOnlyByPadding() {
        String filter = "AB" + " ".repeat(10) + "%";
        assertThat(filter).hasSize(CustomerSearchRepository.FILTER_WIDTH);
        insert("AAAB", "AB", "SPRINGFIELD", "IL", ACTIVE);
        insert("AAAC", "ABX", "SPRINGFIELD", "IL", ACTIVE);
        insert("AAAD", "CITY AB", "AB", "CA", ACTIVE);
        insert("AAAE", "CITY ABX", "ABX", "CA", ACTIVE);

        assertThat(names(find(criteria(filter, "", "", false)))).containsExactly("AB");
        assertThat(find(criteria("", filter, "", false))).extracting(CustomerSummary::city)
                .containsExactly("AB");
    }

    // ---------------------------------------------------------------------------------------------
    // Literal backslash and apostrophe (seed names of ids 6 and 3)
    // ---------------------------------------------------------------------------------------------

    /**
     * The seed names {@code URNA \NUNC\ COMPANY} and {@code NIBH L'LOR COMPANY} match literally. The
     * control {@code URNA NUNC COMPANY} would match {@code URNA \NUNC} if PostgreSQL's default
     * {@code LIKE} escape were left active, so its absence proves the backslash is literal, as on Db2.
     */
    @Test
    void backslashAndApostropheMatchLiterally() {
        String backslashName = "URNA \\NUNC\\ COMPANY";
        insert("AAAG", backslashName, "AUBURN", "ME", ACTIVE);
        insert("AAAD", "NIBH L'LOR COMPANY", "AUBURN", "ME", ACTIVE);
        insert("AAAH", "URNA NUNC COMPANY", "AUBURN", "ME", ACTIVE);

        assertThat(names(find(criteria("URNA \\NUNC", "", "", false)))).containsExactly(backslashName);
        assertThat(names(find(criteria("NIBH L'LOR", "", "", false))))
                .containsExactly("NIBH L'LOR COMPANY");
    }

    // ---------------------------------------------------------------------------------------------
    // Sort
    // ---------------------------------------------------------------------------------------------

    /**
     * Rows inserted out of order come back by name, city, state and then custid, with letters before
     * digits under {@code customer_sort}: {@code 101A} before {@code 1010}, and {@code ALPHAB} before
     * {@code ALPHA2}.
     */
    @Test
    void resultsSortByNameCityStateThenCustid() {
        insert("1010", "ALPHA", "BOSTON", "MA", ACTIVE);
        insert("AAAI", "ALPHA2", "AUSTIN", "TX", ACTIVE);
        insert("AAAG", "ALPHA", "BOSTON", "CA", ACTIVE);
        insert("AAAH", "ALPHAB", "AUSTIN", "TX", ACTIVE);
        insert("101A", "ALPHA", "BOSTON", "MA", ACTIVE);
        insert("AAAF", "ALPHA", "AUSTIN", "TX", ACTIVE);

        List<CustomerSummary> rows = find(criteria("", "", "", false));

        assertThat(rows).extracting(row -> row.custId().value())
                .containsExactly("AAAF", "AAAG", "101A", "1010", "AAAH", "AAAI");
        assertThat(rows).extracting(CustomerSummary::name)
                .containsExactly("ALPHA", "ALPHA", "ALPHA", "ALPHA", "ALPHAB", "ALPHA2");
        assertThat(rows).extracting(CustomerSummary::city)
                .containsExactly("AUSTIN", "BOSTON", "BOSTON", "BOSTON", "AUSTIN", "AUSTIN");
        assertThat(rows).extracting(CustomerSummary::state)
                .containsExactly("TX", "CA", "MA", "MA", "TX", "TX");
        assertThat(names(find(criteria("ALPHA", "", "", false))))
                .as("the prefix filter keeps the same order")
                .containsExactly("ALPHA", "ALPHA", "ALPHA", "ALPHA", "ALPHAB", "ALPHA2");
    }

    // ---------------------------------------------------------------------------------------------
    // Plans of the exact statement find() runs
    // ---------------------------------------------------------------------------------------------

    /**
     * A three-letter prefix is a pure literal prefix: the statement carries no {@code rpad}, and
     * {@code custmast_name} serves the candidate rows over 20,000 analyzed rows, with no sequential
     * scan of {@code custmast}.
     */
    @Test
    void threeLetterPrefixIsServedByNameIndexWithoutPaddedMatch() {
        loadGenerated(20_000);
        jdbcTemplate.execute("ANALYZE custmast");
        SearchCriteria prefix = criteria("ABE", "", "", false);

        assertThat(searchRepository.buildQuery(prefix, PAGE_LIMIT).sql())
                .as("statement of a pure prefix").doesNotContain(RPAD);
        ExplainedPlan plan = explain(prefix);

        assertThat(plan.nodes()).as("%s", plan.json()).anyMatch(PlanNode::usesNameIndex);
        assertThat(plan.nodes()).as("%s", plan.json()).noneMatch(PlanNode::isSeqScanOnCustmast);
        assertThat(plan.nodes()).as("%s", plan.json()).noneMatch(node -> node.mentions(RPAD));
    }

    /**
     * {@code AB_} and {@code AB}, ten blanks, {@code %} both need the padded match. Each plan reads
     * {@code custmast_name} with an index condition on the lead {@code AB} and applies the {@code rpad}
     * check as a filter, so padding parity costs no scan of rows outside the lead.
     */
    @Test
    void paddedPatternsUseNameIndexOnLeadWithPaddedMatchAsFilter() {
        loadGenerated(20_000);
        jdbcTemplate.execute("ANALYZE custmast");
        Predicate<PlanNode> nameIndexOnLead =
                node -> node.usesNameIndex() && node.indexCond() != null && node.indexCond().contains("'AB'");
        Predicate<PlanNode> paddedFilter = node -> node.filter() != null && node.filter().contains(RPAD);

        for (String filter : List.of("AB_", "AB" + " ".repeat(10) + "%")) {
            ExplainedPlan plan = explain(criteria(filter, "", "", false));

            assertThat(plan.nodes()).as("plan of [%s]: %s", filter, plan.json()).anyMatch(nameIndexOnLead);
            assertThat(plan.nodes()).as("plan of [%s]: %s", filter, plan.json()).anyMatch(paddedFilter);
            assertThat(plan.nodes()).as("plan of [%s]: %s", filter, plan.json())
                    .noneMatch(PlanNode::isSeqScanOnCustmast);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Pagination and the 9,999-row cap, through the service
    // ---------------------------------------------------------------------------------------------

    /**
     * Walks 30 pages of the default 12 rows from the service's opaque cursors over 400 active rows
     * whose names repeat, so rows tie on name, city and state and only {@code custid} orders them. Every
     * page is full and has a next cursor; the 360 ids served have no duplicate and equal the first 360
     * of the independent list order, so no row is skipped. At least one page boundary falls inside a
     * tie group, which proves the {@code custid} tiebreak of the keyset.
     */
    @Test
    void cursorPagesServeEveryRowOnceInListOrder() {
        insertPaginationFixture(400);
        List<KeyRow> expected = expectedKeyRows();
        assertThat(expected).hasSize(400);
        int pages = 30;
        assertThat(IntStream.range(1, pages)
                .anyMatch(page -> expected.get(page * DEFAULT_PAGE_SIZE - 1)
                        .tiesWith(expected.get(page * DEFAULT_PAGE_SIZE))))
                .as("a page boundary falls inside a name, city and state tie").isTrue();

        List<String> served = new ArrayList<>();
        String cursor = null;
        for (int page = 1; page <= pages; page++) {
            SearchPage result = searchService.search("", "", "", false, null, cursor);
            assertThat(result.items()).as("rows of page %d", page).hasSize(DEFAULT_PAGE_SIZE);
            assertThat(result.nextCursor()).as("next cursor of page %d", page).isNotNull();
            assertThat(result.limitReached()).as("limitReached on page %d", page).isFalse();
            assertThat(result.notice()).as("notice on page %d", page).isNull();
            result.items().forEach(row -> served.add(row.custId().value()));
            cursor = result.nextCursor();
        }

        List<String> expectedIds = expected.stream().map(KeyRow::custId).toList();
        assertThat(served).hasSize(pages * DEFAULT_PAGE_SIZE).doesNotHaveDuplicates()
                .containsExactlyElementsOf(expectedIds.subList(0, pages * DEFAULT_PAGE_SIZE));
    }

    /**
     * Over 10,500 rows with inactive ones included, pages of 100 run until the cap: 99 full pages with
     * next cursors, then a page cut to 99 rows that brings the rows served to exactly 9,999, carries
     * {@code limitReached} and DEM0006, and has no next cursor although more rows match. The ids served
     * are the first 9,999 of the list order, inactive rows among them, with no duplicate.
     */
    @Test
    void searchStopsAtRowCapWithDem0006() {
        int maxRows = appProperties.search().maxRows();
        assertThat(maxRows).isEqualTo(9_999);
        int loaded = 10_500;
        loadGenerated(loaded);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM custmast", Integer.class))
                .isEqualTo(loaded);
        List<String> expectedIds = expectedKeyRows().stream().map(KeyRow::custId).limit(maxRows).toList();

        List<String> served = new ArrayList<>();
        int inactiveServed = 0;
        SearchPage capped = null;
        String cursor = null;
        int pages = 0;
        while (capped == null && pages < MAX_CAP_PAGES) {
            SearchPage result = searchService.search("", "", "", true, MAX_PAGE_SIZE, cursor);
            pages++;
            for (CustomerSummary row : result.items()) {
                served.add(row.custId().value());
                inactiveServed += INACTIVE.equals(row.active()) ? 1 : 0;
            }
            if (result.limitReached()) {
                capped = result;
            } else {
                assertThat(result.items()).as("rows of page %d", pages).hasSize(MAX_PAGE_SIZE);
                assertThat(result.nextCursor()).as("next cursor of page %d", pages).isNotNull();
                assertThat(result.notice()).as("notice on page %d", pages).isNull();
                cursor = result.nextCursor();
            }
        }

        assertThat(capped).as("a page reached the cap within %d pages", pages).isNotNull();
        assertThat(pages).isEqualTo(100);
        assertThat(capped.items()).hasSize(maxRows - 99 * MAX_PAGE_SIZE);
        assertThat(capped.nextCursor()).isNull();
        assertThat(capped.notice()).isNotNull();
        assertThat(capped.notice().code()).isEqualTo("DEM0006");
        assertThat(capped.notice().message()).isEqualTo("Too many records. Change the selection criteria.");
        assertThat(served).hasSize(maxRows).doesNotHaveDuplicates().containsExactlyElementsOf(expectedIds);
        assertThat(inactiveServed).as("inactive rows served with includeInactive").isPositive();
    }

    // ---------------------------------------------------------------------------------------------
    // Fixtures and helpers
    // ---------------------------------------------------------------------------------------------

    /**
     * Writes the four-row filter fixture: three active customers in Illinois, California and Utah, and
     * the inactive {@code ACME HOLDINGS} in Texas.
     */
    private void insertFilterFixture() {
        insert("AAAB", "ACME TOOLS", "SPRINGFIELD", "IL", ACTIVE);
        insert("AAAC", "ACORN LLC", "SACRAMENTO", "CA", ACTIVE);
        insert("AAAD", "BETA INC", "SPRINGVILLE", "UT", ACTIVE);
        insert("AAAE", "ACME HOLDINGS", "AUSTIN", "TX", INACTIVE);
    }

    /**
     * Writes {@code count} active rows in one batch, in a shuffled order so that neither insertion nor
     * id order matches the list order. Row {@code i} has id {@code fromOrdinal(1000 + i)}, one of 37
     * names ({@code i % 37}) and one of three city/state pairs ({@code i % 3}), so the 111 name, city
     * and state combinations each hold three or four rows that only {@code custid} orders.
     *
     * @param count the number of rows
     */
    private void insertPaginationFixture(int count) {
        List<String> cities = List.of("AUSTIN", "BOSTON", "CHICAGO");
        List<String> states = List.of("CA", "NY", "TX");
        OffsetDateTime chgTime = now();
        List<Object[]> rows = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            int nameIndex = i % 37;
            String name = "CUST " + (char) ('A' + nameIndex % 26) + nameIndex;
            rows.add(new Object[] {CustomerId.fromOrdinal(1000 + i).value(), name, ADDR, cities.get(i % 3),
                    states.get(i % 3), ZIP, PHONE, MANAGER, PHONE, ACTIVE, chgTime, CHG_USER});
        }
        Collections.shuffle(rows, new Random(7L));
        jdbcTemplate.batchUpdate(INSERT_SQL, rows);
    }

    /**
     * Writes one complete row with the V3 column list; the columns the search does not read take the
     * fixture defaults.
     *
     * @param custId a 4-character base-36 id
     * @param name   the stored name
     * @param city   the stored city
     * @param state  a code present in {@code states}
     * @param active {@code Y} or {@code N}
     */
    private void insert(String custId, String name, String city, String state, String active) {
        jdbcTemplate.update(INSERT_SQL, custId, name, ADDR, city, state, ZIP, PHONE, MANAGER, PHONE, active,
                now(), CHG_USER);
    }

    /**
     * Replaces the table with {@code count} generated customers from id {@code 1001}, through the
     * generator's loader: one {@code COPY} in the loader's own transaction, so no outer transaction is
     * open here.
     *
     * @param count the number of rows
     */
    private void loadGenerated(int count) {
        CustomerDataGenerator generator =
                new CustomerDataGenerator(NameGenerator.seeded(GENERATOR_SEED), CSZ_ROWS, now());
        long rows = customerLoader.load(
                new CustomerLoader.Plan(CustomerId.parse(FIRST_GENERATED_ID), count, generator));
        assertThat(rows).as("rows loaded").isEqualTo(count);
    }

    /**
     * Returns the current time at the microsecond precision of {@code chgtime timestamptz(6)}.
     *
     * @return the application clock's time, in UTC
     */
    private OffsetDateTime now() {
        return OffsetDateTime.now(clock).truncatedTo(ChronoUnit.MICROS);
    }

    /**
     * Returns criteria for one repository call of the small fixtures: normalized filters, no cursor.
     *
     * @param name            the name filter, {@code ""} for none
     * @param city            the city filter, {@code ""} for none
     * @param state           the state filter, {@code ""} for none
     * @param includeInactive {@code true} to include inactive rows (F9)
     * @return the criteria
     */
    private static SearchCriteria criteria(String name, String city, String state, boolean includeInactive) {
        return new SearchCriteria(name, city, state, includeInactive, FIXTURE_SIZE, null);
    }

    /**
     * Runs the repository query with a limit above every small fixture, so all matches return.
     *
     * @param criteria the criteria
     * @return the rows in list order
     */
    private List<CustomerSummary> find(SearchCriteria criteria) {
        return searchRepository.find(criteria, FIXTURE_LIMIT);
    }

    /**
     * Maps result rows to their names, keeping the order.
     *
     * @param rows the rows
     * @return the names
     */
    private static List<String> names(List<CustomerSummary> rows) {
        return rows.stream().map(CustomerSummary::name).toList();
    }

    /**
     * Reads every row's sort keys in the list order, independently of the repository.
     *
     * @return the rows ordered by name, city, state and custid
     */
    private List<KeyRow> expectedKeyRows() {
        return jdbcTemplate.query(EXPECTED_ORDER_SQL,
                (rs, rowNum) -> new KeyRow(rs.getString("custid").trim(), rs.getString("name"),
                        rs.getString("city"), rs.getString("state").trim()));
    }

    /**
     * Explains the exact statement {@code find} would run for {@code criteria} with a 13-row limit, in
     * one transaction whose {@code SET LOCAL} settings end with it: sequential scans are disabled so the
     * planner shows which index it can use, and custom plans are forced as {@code find} forces them. The
     * statement's bind parameters are passed to {@code EXPLAIN} as they are to the query.
     *
     * @param criteria the criteria
     * @return the JSON plan and its nodes, depth first
     * @throws AssertionError if the output is not a JSON plan
     */
    private ExplainedPlan explain(SearchCriteria criteria) {
        String json = transactionTemplate.execute(status -> {
            jdbcTemplate.execute("SET LOCAL enable_seqscan = off");
            jdbcTemplate.execute("SET LOCAL plan_cache_mode = force_custom_plan");
            SqlQuery query = searchRepository.buildQuery(criteria, PAGE_LIMIT);
            return namedParameterJdbcTemplate.queryForObject(
                    "EXPLAIN (FORMAT JSON) " + query.sql(), query.params(), String.class);
        });
        assertThat(json).as("EXPLAIN output").isNotBlank();
        JsonNode root;
        try {
            root = objectMapper.readTree(json);
        } catch (JsonProcessingException e) {
            throw new AssertionError("EXPLAIN output is not JSON: " + json, e);
        }
        List<PlanNode> nodes = new ArrayList<>();
        collect(root.path(0).path("Plan"), nodes);
        assertThat(nodes).as("plan nodes of %s", json).isNotEmpty();
        return new ExplainedPlan(json, nodes);
    }

    /**
     * Adds a plan node and, recursively, its {@code Plans} children, including the materialized
     * candidate set's subplan.
     *
     * @param node the JSON plan node, possibly missing
     * @param into the list to add to
     */
    private static void collect(JsonNode node, List<PlanNode> into) {
        if (node.isMissingNode() || node.isNull()) {
            return;
        }
        into.add(new PlanNode(text(node, "Node Type"), text(node, "Relation Name"), text(node, "Index Name"),
                text(node, "Index Cond"), text(node, "Recheck Cond"), text(node, "Filter")));
        for (JsonNode child : node.path("Plans")) {
            collect(child, into);
        }
    }

    /**
     * Returns a text member of a plan node.
     *
     * @param node  the plan node
     * @param field the member name
     * @return its text, or {@code null} when the node has no such member
     */
    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    /**
     * The members of one {@code EXPLAIN (FORMAT JSON)} node the plan checks read.
     *
     * @param nodeType     {@code Node Type}, for example {@code Bitmap Index Scan}
     * @param relationName {@code Relation Name}, or {@code null}
     * @param indexName    {@code Index Name}, or {@code null}
     * @param indexCond    {@code Index Cond}, or {@code null}
     * @param recheckCond  {@code Recheck Cond}, or {@code null}
     * @param filter       {@code Filter}, or {@code null}
     */
    private record PlanNode(String nodeType, String relationName, String indexName, String indexCond,
            String recheckCond, String filter) {

        /** Whether the node reads {@code custmast_name} (index, index-only or bitmap index scan). */
        boolean usesNameIndex() {
            return NAME_INDEX.equals(indexName);
        }

        /** Whether the node is a sequential scan of {@code custmast}. */
        boolean isSeqScanOnCustmast() {
            return "Seq Scan".equals(nodeType) && "custmast".equals(relationName);
        }

        /** Whether the node's filter, index condition or recheck condition contains {@code fragment}. */
        boolean mentions(String fragment) {
            return contains(filter, fragment) || contains(indexCond, fragment) || contains(recheckCond, fragment);
        }

        private static boolean contains(String text, String fragment) {
            return text != null && text.contains(fragment);
        }
    }

    /**
     * An explained plan: the raw JSON, shown when an assertion fails, and its nodes.
     *
     * @param json  the {@code EXPLAIN (FORMAT JSON)} output
     * @param nodes every node, depth first
     */
    private record ExplainedPlan(String json, List<PlanNode> nodes) {
    }

    /**
     * The sort keys of one stored row.
     *
     * @param custId the id
     * @param name   the name
     * @param city   the city
     * @param state  the state
     */
    private record KeyRow(String custId, String name, String city, String state) {

        /** Whether this row ties with {@code other} on name, city and state, so only custid orders them. */
        boolean tiesWith(KeyRow other) {
            return name.equals(other.name) && city.equals(other.city) && state.equals(other.state);
        }
    }
}
