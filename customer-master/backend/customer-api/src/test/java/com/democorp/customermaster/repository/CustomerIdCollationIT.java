package com.democorp.customermaster.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import com.democorp.customermaster.domain.CustomerId;
import com.democorp.customermaster.domain.State;
import com.democorp.customermaster.support.AbstractPostgresIT;
import com.democorp.customermaster.support.DatabaseCleaner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.RowMapper;

/**
 * Proves that the ICU collation {@code customer_sort} orders and compares customer ids as
 * {@link CustomerId#ORDER} does, and documents how {@code COLLATE "C"} differs.
 *
 * <p><b>Why.</b> The source orders ids in EBCDIC raw order, letters before digits, because
 * BASE36ADD's alphabet is {@code A..Z} then {@code 0..9} [BASE36/SRV_BASE36.RPGLE:15-16,37-39].
 * PostgreSQL's {@code "C"} collation puts digits first. The target keeps the source order through
 * migration V1, which creates {@code customer_sort} as the ICU root collation {@code und} with the
 * tailoring rule {@code &[before 1]\u03B1<0<1<2<3<4<5<6<7<8<9}: the ASCII digits {@code 0..9} sort
 * after every Latin letter. V2 and V3 declare it on {@code custmast.custid}, {@code name},
 * {@code city}, {@code state} and on {@code states.state} and {@code states.name}. So allocation
 * order equals sort order: {@code AAAA < AAAZ < AAA0 < ZZZZ < 0000 < 101A < 1010}.
 *
 * <p><b>Comparisons, not only ORDER BY.</b> The ICU reorder locale {@code und-u-kr-latn-digit} passes an
 * {@code ORDER BY} check on postgres:18.6, yet its {@code <} comparisons, B-tree order and row
 * comparisons put digits before letters. This class therefore also checks {@code <} over the column
 * collation, {@code min}/{@code max}, a range scan of the primary key and keyset pagination over
 * {@code custmast_search_keyset}: each fails under that locale.
 *
 * <p><b>Fixture.</b> Test databases hold no seed rows, so {@link #insertSampleIds()} inserts the seven
 * sample ids before each test, in an order that matches neither expected order. Expectations come from
 * {@link CustomerId#ORDER} and literal lists, never from {@link String#compareTo(String)}. The sequence
 * is irrelevant to collation and is never called. Statements run in autocommit, except the plan checks,
 * which run inside one {@code transactionTemplate} callback so their {@code SET LOCAL} settings end
 * with it.
 */
class CustomerIdCollationIT extends AbstractPostgresIT {

    /** The sample ids in insertion order, shuffled against both the customer_sort and the "C" order. */
    private static final List<String> SAMPLE_IDS =
            List.of("1010", "AAAZ", "0000", "ZZZZ", "AAAA", "101A", "AAA0");

    /** The sample ids under customer_sort: letters before digits, which is allocation order. */
    private static final List<String> CUSTOMER_SORT_ORDER =
            List.of("AAAA", "AAAZ", "AAA0", "ZZZZ", "0000", "101A", "1010");

    /** The ordinals of {@link #CUSTOMER_SORT_ORDER}, element for element. */
    private static final List<Integer> CUSTOMER_SORT_ORDINALS =
            List.of(0, 25, 26, 1_199_725, 1_247_714, 1_294_380, 1_294_406);

    /** The sample ids under {@code COLLATE "C"}: ASCII order, digits before letters. */
    private static final List<String> C_ORDER =
            List.of("0000", "1010", "101A", "AAA0", "AAAA", "AAAZ", "ZZZZ");

    /** BASE36ADD's digit alphabet in ascending digit value [BASE36/SRV_BASE36.RPGLE:38]. */
    private static final String BASE36_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";

    /** Orders text character by character by base-36 digit value; a shorter prefix sorts first. */
    private static final Comparator<String> BASE36_TEXT_ORDER = CustomerIdCollationIT::compareBase36;

    /** The phone number every fixture row carries. */
    private static final String PHONE = "(415) 555-0100";

    /** Rows per page of the keyset walk: small, so the walk crosses every tie group. */
    private static final int KEYSET_PAGE_SIZE = 2;

    /** The search's first page: no cursor yet. */
    private static final String KEYSET_FIRST_PAGE =
            "SELECT name, city, state, custid FROM custmast ORDER BY name, city, state, custid LIMIT ?";

    /** The search's next page: the keyset row comparison over the previous page's last row. */
    private static final String KEYSET_NEXT_PAGE =
            "SELECT name, city, state, custid FROM custmast WHERE (name, city, state, custid) > (?, ?, ?, ?)"
                    + " ORDER BY name, city, state, custid LIMIT ?";

    /** The search statement whose plan is checked: 12 rows per page plus the look-ahead row. */
    private static final String KEYSET_SEARCH =
            "SELECT custid FROM custmast WHERE (name, city, state, custid) > (?, ?, ?, ?)"
                    + " ORDER BY name, city, state, custid LIMIT 13";

    /** Reads the four sort-key columns of one search row. */
    private static final RowMapper<KeysetRow> KEYSET_ROW = CustomerIdCollationIT::keysetRow;

    /** Runs PMTSTATER's "By Code" list, as {@code StateService} calls it. */
    @Autowired
    private StateRepository stateRepository;

    /** Inserts the seven sample ids, shuffled, after the base class has emptied {@code custmast}. */
    @BeforeEach
    void insertSampleIds() {
        SAMPLE_IDS.forEach(this::insert);
    }

    /**
     * Checks that {@code ORDER BY custid} under the column collation is the {@link CustomerId#ORDER}
     * order and the literal allocation order (AAP 0.3.3, 0.8.3).
     */
    @Test
    void orderByCustidFollowsCustomerIdComparator() {
        List<String> actual = selectIds("SELECT custid FROM custmast ORDER BY custid");

        List<String> expected = SAMPLE_IDS.stream()
                .map(CustomerId::parse)
                .sorted(CustomerId.ORDER)
                .map(CustomerId::value)
                .toList();
        assertThat(actual).containsExactlyElementsOf(expected);
        assertThat(actual).containsExactlyElementsOf(CUSTOMER_SORT_ORDER);

        // Allocation order equals sort order: the ordinals of the sorted ids strictly increase.
        List<Integer> ordinals = actual.stream().map(CustomerId::parse).map(CustomerId::toOrdinal).toList();
        assertThat(ordinals).containsExactlyElementsOf(CUSTOMER_SORT_ORDINALS);
        assertThat(ordinals).isSorted().doesNotHaveDuplicates();
    }

    /** Documents the ASCII difference of AAP 0.3.3: {@code COLLATE "C"} puts digits before letters. */
    @Test
    void collateCYieldsDigitsBeforeLettersDocumentedDifference() {
        // The ASCII order the target deliberately does NOT use: digits sort first, so 1010 sorts before
        // 101A although it is allocated after it, and 0000 sorts before every letter-led id.
        List<String> cOrder = selectIds("SELECT custid FROM custmast ORDER BY custid COLLATE \"C\"");

        assertThat(cOrder).containsExactlyElementsOf(C_ORDER);
        assertThat(cOrder).isNotEqualTo(selectIds("SELECT custid FROM custmast ORDER BY custid"));
    }

    /**
     * Checks the State prompt's "By Code" order [5250_Subfile/PMTSTATER.SQLRPGLE:150-162]: all 58 codes
     * of V2 [5250_Subfile/States.sql], in base-36 order under {@code customer_sort}.
     */
    @Test
    void stateSearchByCodeFollowsCustomerSort() {
        List<State> states = stateRepository.search("%%", "code");

        assertThat(states).hasSize(DatabaseCleaner.STATE_COUNT);
        List<String> codes = states.stream().map(State::state).map(String::trim).toList();
        assertThat(codes).containsExactlyElementsOf(
                selectIds("SELECT state FROM states ORDER BY state COLLATE customer_sort"));
        assertThat(codes).isSortedAccordingTo(BASE36_TEXT_ORDER).doesNotHaveDuplicates();
        assertThat(codes.getFirst()).isEqualTo("AA");
    }

    /**
     * Checks {@code <} itself, which {@code WHERE}, {@code min}/{@code max}, B-tree lookups and keyset
     * pagination use. The reorder locale {@code und-u-kr-latn-digit} passes the {@code ORDER BY} test
     * above but fails this one: under it {@code 'ZZZZ' < '0000'} and {@code '101A' < '1010'} are false,
     * and {@code min}/{@code max} return {@code 0000}/{@code ZZZZ}. The self-join compares two
     * {@code custid} columns, so the column collation applies.
     */
    @Test
    void lessThanUnderColumnCollationAgreesWithCustomerIdComparator() {
        List<PairComparison> comparisons = jdbcTemplate.query(
                "SELECT a.custid, b.custid, a.custid < b.custid FROM custmast a CROSS JOIN custmast b",
                (rs, rowNum) -> new PairComparison(
                        rs.getString(1).trim(), rs.getString(2).trim(), rs.getBoolean(3)));

        assertThat(comparisons).hasSize(SAMPLE_IDS.size() * SAMPLE_IDS.size());
        List<String> mismatches = new ArrayList<>();
        for (PairComparison comparison : comparisons) {
            boolean byComparator = CustomerId.ORDER.compare(
                    CustomerId.parse(comparison.left()), CustomerId.parse(comparison.right())) < 0;
            boolean byLiteralList = CUSTOMER_SORT_ORDER.indexOf(comparison.left())
                    < CUSTOMER_SORT_ORDER.indexOf(comparison.right());
            if (comparison.databaseLess() != byComparator || comparison.databaseLess() != byLiteralList) {
                mismatches.add(comparison.left() + " < " + comparison.right() + ": database "
                        + comparison.databaseLess() + ", CustomerId.ORDER " + byComparator
                        + ", literal order " + byLiteralList);
            }
        }
        assertThat(mismatches).isEmpty();

        List<String> extremes = jdbcTemplate.queryForObject(
                "SELECT min(custid), max(custid) FROM custmast",
                (rs, rowNum) -> List.of(rs.getString(1).trim(), rs.getString(2).trim()));
        assertThat(extremes).containsExactly("AAAA", "1010");
    }

    /**
     * Checks that a B-tree range over the primary key returns the allocation-order ranges, read in index
     * order. With sequential and bitmap scans disabled the planner must walk {@code custmast_pkey} with
     * the range as its index condition, and needs no Sort node because the index order is the
     * {@code ORDER BY} order. The reorder locale {@code und-u-kr-latn-digit} passes the {@code ORDER BY}
     * test above but fails this one: its B-tree holds digits before letters, so
     * {@code custid > 'ZZZZ'} returns no rows instead of {@code 0000}, {@code 101A}, {@code 1010}.
     */
    @Test
    void primaryKeyRangeScanReturnsAllocationOrderRanges() {
        String aboveLetters = "SELECT custid FROM custmast WHERE custid > 'ZZZZ' ORDER BY custid";
        String belowDigits = "SELECT custid FROM custmast WHERE custid < '0000' ORDER BY custid";

        transactionTemplate.executeWithoutResult(status -> {
            jdbcTemplate.execute("SET LOCAL enable_seqscan = off");
            jdbcTemplate.execute("SET LOCAL enable_bitmapscan = off");

            assertPrimaryKeyRangeScan(explain(aboveLetters), "custid > 'ZZZZ'::bpchar");
            assertPrimaryKeyRangeScan(explain(belowDigits), "custid < '0000'::bpchar");
            assertThat(selectIds(aboveLetters)).containsExactly("0000", "101A", "1010");
            assertThat(selectIds(belowDigits)).containsExactly("AAAA", "AAAZ", "AAA0", "ZZZZ");
        });
    }

    /**
     * Checks the search's keyset pagination contract: {@code ORDER BY name, city, state, custid} and the
     * row comparison {@code (name, city, state, custid) > (...)} are one order, served by
     * {@code custmast_search_keyset} with the row comparison as its index condition, so walking the pages
     * returns every row exactly once. Besides the seven sample rows, the walk covers rows that tie on
     * name, city and state (custid decides, letters before digits), on name and city (state decides), on
     * name only (a digit-led city sorts last) and a digit-led name (sorts after letter-led names). The
     * reorder locale {@code und-u-kr-latn-digit} passes the {@code ORDER BY} test above but fails this
     * one: its row comparison and index order put digits first, so pages skip or repeat rows.
     */
    @Test
    void keysetRowComparisonOverSearchIndexServesEveryRowOnceInOrder() {
        insertKeysetRow("BBB0", "KEYSET TIE", "SPRINGFIELD", "CA");
        insertKeysetRow("EEEE", "9 LIVES", "SPRINGFIELD", "CA");
        insertKeysetRow("1BBB", "KEYSET TIE", "SPRINGFIELD", "CA");
        insertKeysetRow("CCCC", "KEYSET TIE", "1ST CITY", "CA");
        insertKeysetRow("BZZZ", "KEYSET TIE", "SPRINGFIELD", "CA");
        insertKeysetRow("DDDD", "KEYSET TIE", "SPRINGFIELD", "AA");
        insertKeysetRow("B000", "KEYSET TIE", "SPRINGFIELD", "CA");
        insertKeysetRow("BBBZ", "KEYSET TIE", "SPRINGFIELD", "CA");
        List<String> expected = List.of(
                "AAAA", "AAAZ", "AAA0", "ZZZZ", "0000", "101A", "1010",
                "DDDD", "BBBZ", "BBB0", "BZZZ", "B000", "1BBB", "CCCC", "EEEE");

        assertThat(selectIds("SELECT custid FROM custmast ORDER BY name, city, state, custid"))
                .containsExactlyElementsOf(expected);

        transactionTemplate.executeWithoutResult(status -> {
            jdbcTemplate.execute("SET LOCAL enable_seqscan = off");

            List<KeysetRow> served = new ArrayList<>();
            List<KeysetRow> page = jdbcTemplate.query(KEYSET_FIRST_PAGE, KEYSET_ROW, KEYSET_PAGE_SIZE);
            boolean planChecked = false;
            while (!page.isEmpty()) {
                served.addAll(page);
                assertThat(served).as("rows served by the walk so far")
                        .hasSizeLessThanOrEqualTo(expected.size());
                KeysetRow last = page.getLast();
                if (!planChecked) {
                    assertKeysetIndexScan(
                            explain(KEYSET_SEARCH, last.name(), last.city(), last.state(), last.custId()));
                    planChecked = true;
                }
                page = jdbcTemplate.query(KEYSET_NEXT_PAGE, KEYSET_ROW,
                        last.name(), last.city(), last.state(), last.custId(), KEYSET_PAGE_SIZE);
            }

            assertThat(planChecked).as("the walk served at least one page").isTrue();
            List<String> servedIds = served.stream().map(KeysetRow::custId).toList();
            assertThat(servedIds).doesNotHaveDuplicates().containsExactlyElementsOf(expected);
        });
    }

    /**
     * Writes one complete sample row with state {@code CA}.
     *
     * @param custId the 4-character id, also appended to the name
     */
    private void insert(String custId) {
        jdbcTemplate.update(
                "INSERT INTO custmast (custid, name, addr, city, state, zip, corpphone, acctmgr, acctphone,"
                        + " active, chgtime, chguser, row_version)"
                        + " VALUES (?, ?, ?, ?, 'CA', ?, ?, ?, ?, 'Y', ?, 'maint', 0)",
                custId, "COLLATION TEST " + custId, "1 MAIN ST", "SPRINGFIELD", "90210",
                PHONE, "SMITH, JANE", PHONE, OffsetDateTime.now(ZoneOffset.UTC));
    }

    /**
     * Writes one complete row whose sort keys the keyset test chooses, so rows can tie on name, city
     * and state.
     *
     * @param custId the 4-character id
     * @param name   the stored name
     * @param city   the stored city
     * @param state  a code present in {@code states}
     */
    private void insertKeysetRow(String custId, String name, String city, String state) {
        jdbcTemplate.update(
                "INSERT INTO custmast (custid, name, addr, city, state, zip, corpphone, acctmgr, acctphone,"
                        + " active, chgtime, chguser, row_version)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'Y', ?, 'maint', 0)",
                custId, name, "1 MAIN ST", city, state, "90210",
                PHONE, "SMITH, JANE", PHONE, OffsetDateTime.now(ZoneOffset.UTC));
    }

    /**
     * Runs a one-column query and trims each value ({@code char(n)} columns).
     *
     * @param sql  the query
     * @param args its bound parameters
     * @return the values in result order
     */
    private List<String> selectIds(String sql, Object... args) {
        return jdbcTemplate.queryForList(sql, String.class, args).stream().map(String::trim).toList();
    }

    /**
     * Returns the text plan of a query, one line per plan line.
     *
     * @param sql  the query
     * @param args its bound parameters
     * @return the plan lines joined with line breaks
     */
    private String explain(String sql, Object... args) {
        return String.join("\n", jdbcTemplate.queryForList("EXPLAIN " + sql, String.class, args));
    }

    /**
     * Asserts that a plan reads {@code custmast_pkey} in index order with the given range condition.
     *
     * @param plan      the text plan
     * @param condition the expected index condition, without its parentheses
     */
    private static void assertPrimaryKeyRangeScan(String plan, String condition) {
        assertThat(plan)
                .containsPattern("Index (Only )?Scan using custmast_pkey on custmast")
                .contains("Index Cond: (" + condition + ")")
                .doesNotContain("Sort");
    }

    /**
     * Asserts that a plan reads {@code custmast_search_keyset} in index order with the whole row
     * comparison as its index condition and no residual filter.
     *
     * @param plan the text plan
     */
    private static void assertKeysetIndexScan(String plan) {
        assertThat(plan)
                .containsPattern("Index (Only )?Scan using custmast_search_keyset on custmast")
                .contains("Index Cond: (ROW(name, city, state, custid) > ROW(")
                .doesNotContain("Filter:")
                .doesNotContain("Sort");
    }

    /**
     * Compares two strings character by character by base-36 digit value.
     *
     * @param left  text of base-36 digits
     * @param right text of base-36 digits
     * @return a negative number, zero or a positive number as {@code left} sorts before, equal to or
     *         after {@code right}
     * @throws IllegalArgumentException if either string holds a character outside the alphabet
     */
    private static int compareBase36(String left, String right) {
        int common = Math.min(left.length(), right.length());
        for (int position = 0; position < common; position++) {
            int difference = Integer.compare(
                    base36Digit(left.charAt(position)), base36Digit(right.charAt(position)));
            if (difference != 0) {
                return difference;
            }
        }
        return Integer.compare(left.length(), right.length());
    }

    /**
     * Returns a character's digit value in {@link #BASE36_ALPHABET}.
     *
     * @param character an uppercase letter or an ASCII digit
     * @return 0..35
     * @throws IllegalArgumentException if the character is not in the alphabet
     */
    private static int base36Digit(char character) {
        int digit = BASE36_ALPHABET.indexOf(character);
        if (digit < 0) {
            throw new IllegalArgumentException("not a base-36 digit: '" + character + "'");
        }
        return digit;
    }

    /**
     * Reads the sort-key columns of one search row.
     *
     * @param rs     the result set, positioned on a row
     * @param rowNum the row number, unused
     * @return the row's name, city, state and custid
     * @throws SQLException if a column cannot be read
     */
    private static KeysetRow keysetRow(ResultSet rs, int rowNum) throws SQLException {
        return new KeysetRow(rs.getString("name"), rs.getString("city"), rs.getString("state"),
                rs.getString("custid"));
    }

    /**
     * One database comparison {@code left < right} under the column collation.
     *
     * @param left         the left custid, trimmed
     * @param right        the right custid, trimmed
     * @param databaseLess the database's result
     */
    private record PairComparison(String left, String right, boolean databaseLess) {
    }

    /**
     * The search sort key of one row, as the keyset cursor carries it.
     *
     * @param name   the stored name
     * @param city   the stored city
     * @param state  the stored state code
     * @param custId the stored custid
     */
    private record KeysetRow(String name, String city, String state, String custId) {
    }
}
