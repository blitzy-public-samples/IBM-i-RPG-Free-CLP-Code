package com.democorp.customermaster.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.democorp.customermaster.config.AppProperties;
import com.democorp.customermaster.domain.CustomerId;
import com.democorp.customermaster.domain.CustomerSummary;
import com.democorp.customermaster.domain.SearchCriteria;
import com.democorp.customermaster.domain.SearchPage;
import com.democorp.customermaster.messages.MessageCatalog;
import com.democorp.customermaster.repository.CustomerSearchRepository;
import com.democorp.customermaster.service.exception.InvalidSearchCriteriaException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Specifies the filter rules of {@link CustomerSearchService#search}, PMTCUSTR's
 * {@code ProcessSearchCriteria} [5250_Subfile/PMTCUSTR.SQLRPGLE:625-665] behind
 * {@code GET /api/customers}.
 *
 * <p><b>What the target must do.</b> {@code name} and {@code city} hold at most 13 code points as
 * received, then must not contain U+0000, which PostgreSQL text cannot hold; each failure is APP0400 on
 * that field. A blank {@code state} selects every state; any trimmed length other than 2 is DEM0007 on
 * {@code state}, so a lone U+0000 stays DEM0007, and a 2-code-point state that contains U+0000 is
 * APP0400 on {@code state}. Inputs are checked in the order size, name, city, state, cursor, and the
 * first failure is reported. A rejected request never reaches the repository; an accepted one reaches it
 * with the trimmed, uppercased filters, {@code %} and {@code _} kept as typed.
 *
 * <p><b>The cap against the recount.</b> A cursor's {@code served} is the client's claim. With a cursor,
 * the rows served before the page are the larger of that claim and
 * {@link CustomerSearchRepository#countThrough(SearchCriteria, int)} over the same filters, counted up
 * to the cap: a lowered claim cannot lift the 9,999-row cap, a raised one is kept, and an honest one is
 * unchanged. When the rows served already reach the cap, the page is empty with {@code limitReached},
 * DEM0006 and no next cursor, and {@code find} is never called.
 *
 * <p>The service is built with the real {@link AppProperties} defaults (page 12, maximum 100, cap
 * 9,999), the packaged {@link MessageCatalog} and a plain {@link ObjectMapper}; only
 * {@link CustomerSearchRepository} is a Mockito mock, whose {@code find} answers an empty list and whose
 * {@code countThrough} answers 0 unless a test stubs them. Cursors are built as the service writes them:
 * base64url without padding over the JSON object {@code {name, city, state, custid, served}}.
 *
 * <p>Pure JUnit 5, AssertJ and Mockito: no Spring context, no database, no Docker.
 */
@DisplayName("CustomerSearchService.search: filter validation before the repository, and the row cap")
final class CustomerSearchServiceTest {

    /** The default page size, {@code customer-master.search.default-size}. */
    private static final int DEFAULT_SIZE = 12;

    /** The largest page size, {@code customer-master.search.max-size}. */
    private static final int MAX_SIZE = 100;

    /** The row cap, {@code customer-master.search.max-rows}: MAXSFLRECDS 9999. */
    private static final int MAX_ROWS = 9_999;

    /** The DEM0006 notice of the page that reaches the cap. */
    private static final SearchPage.Notice TOO_MANY =
            new SearchPage.Notice("DEM0006", "Too many records. Change the selection criteria.");

    /** The packaged catalog; immutable, so one instance serves every test. */
    private static final MessageCatalog CATALOG = new MessageCatalog();

    /** Writes and reads the test cursors' JSON. */
    private static final ObjectMapper JSON = new ObjectMapper();

    private CustomerSearchRepository repository;

    private CustomerSearchService service;

    @BeforeEach
    void setUp() {
        repository = mock(CustomerSearchRepository.class);
        AppProperties appProperties = new AppProperties(
                new AppProperties.Db(Duration.ofSeconds(5)),
                new AppProperties.Search(DEFAULT_SIZE, MAX_SIZE, MAX_ROWS));
        service = new CustomerSearchService(
                repository, appProperties, CATALOG, new ObjectMapper());
    }

    // Display names omit the values: U+0000 is not a legal XML character in the test report.
    @ParameterizedTest(name = "case {index}")
    @ValueSource(strings = {"A\u0000", "\u0000", " \u0000", "NIBH\u0000"})
    @DisplayName("a name holding U+0000 is APP0400 on name")
    void nulInNameIsRejected(String name) {
        assertRejected(() -> service.search(name, null, null, false, null, null),
                InvalidSearchCriteriaException.APP0400, "name", "name must not contain U+0000");
    }

    @ParameterizedTest(name = "case {index}")
    @ValueSource(strings = {"A\u0000", "\u0000", "\u0000BOSTON"})
    @DisplayName("a city holding U+0000 is APP0400 on city")
    void nulInCityIsRejected(String city) {
        assertRejected(() -> service.search(null, city, null, false, null, null),
                InvalidSearchCriteriaException.APP0400, "city", "city must not contain U+0000");
    }

    @ParameterizedTest(name = "case {index}")
    @ValueSource(strings = {"C\u0000", "\u0000C", " c\u0000 ", "\u0000\u0000"})
    @DisplayName("a 2-character state holding U+0000 is APP0400 on state")
    void nulInTwoCharacterStateIsRejected(String state) {
        assertRejected(() -> service.search(null, null, state, false, null, null),
                InvalidSearchCriteriaException.APP0400, "state", "state must not contain U+0000");
    }

    @ParameterizedTest(name = "case {index}")
    @ValueSource(strings = {"C", "\u0000", " \u0000 ", "CAL", "C\u0000A"})
    @DisplayName("a state whose trimmed length is not 2 is DEM0007, even when it holds U+0000")
    void stateOfOtherLengthIsDem0007(String state) {
        assertRejected(() -> service.search(null, null, state, false, null, null),
                InvalidSearchCriteriaException.DEM0007, "state", null);
    }

    @Test
    @DisplayName("a 14-code-point name is APP0400 on name")
    void overLongNameIsRejected() {
        assertRejected(() -> service.search("ABCDEFGHIJKLMN", null, null, false, null, null),
                InvalidSearchCriteriaException.APP0400, "name", "name must be at most 13 characters");
    }

    @Test
    @DisplayName("a 14-code-point city is APP0400 on city")
    void overLongCityIsRejected() {
        assertRejected(() -> service.search(null, "ABCDEFGHIJKLMN", null, false, null, null),
                InvalidSearchCriteriaException.APP0400, "city", "city must be at most 13 characters");
    }

    @Test
    @DisplayName("the width rule is checked before the U+0000 rule")
    void widthIsCheckedBeforeNul() {
        assertRejected(() -> service.search("ABCDEFGHIJKLM\u0000", null, null, false, null, null),
                InvalidSearchCriteriaException.APP0400, "name", "name must be at most 13 characters");
    }

    @Test
    @DisplayName("size is checked before name")
    void sizeIsCheckedBeforeName() {
        assertRejected(() -> service.search("A\u0000", null, null, false, 0, null),
                InvalidSearchCriteriaException.APP0400, "size", "size must be between 1 and 100");
    }

    @Test
    @DisplayName("name is checked before city")
    void nameIsCheckedBeforeCity() {
        assertRejected(() -> service.search("A\u0000", "B\u0000", null, false, null, null),
                InvalidSearchCriteriaException.APP0400, "name", "name must not contain U+0000");
    }

    @Test
    @DisplayName("city is checked before state")
    void cityIsCheckedBeforeState() {
        assertRejected(() -> service.search(null, "B\u0000", "C", false, null, null),
                InvalidSearchCriteriaException.APP0400, "city", "city must not contain U+0000");
    }

    @Test
    @DisplayName("state is checked before cursor")
    void stateIsCheckedBeforeCursor() {
        assertRejected(() -> service.search(null, null, "C\u0000", false, null, "not-a-cursor"),
                InvalidSearchCriteriaException.APP0400, "state", "state must not contain U+0000");
    }

    @Test
    @DisplayName("a valid search reaches the repository with the normalized filters")
    void validSearchReachesRepositoryNormalized() {
        SearchPage page = service.search(" nibh ", "a%b_\\", "ca", true, null, null);

        verify(repository).find(
                new SearchCriteria("NIBH", "A%B_\\", "CA", true, DEFAULT_SIZE, null), DEFAULT_SIZE + 1);
        verifyNoMoreInteractions(repository);
        assertThat(page.items()).isEmpty();
        assertThat(page.nextCursor()).isNull();
        assertThat(page.limitReached()).isFalse();
        assertThat(page.notice())
                .isEqualTo(new SearchPage.Notice("DEM0002", "No records match the selection criteria"));
    }

    @Test
    @DisplayName("blank filters select everything and an unknown 2-letter state still passes")
    void blankFiltersAndUnknownStatePass() {
        service.search("  ", null, " ", false, 5, null);
        service.search(null, "", "zz", false, null, "");

        verify(repository).find(new SearchCriteria("", "", "", false, 5, null), 6);
        verify(repository).find(
                new SearchCriteria("", "", "ZZ", false, DEFAULT_SIZE, null), DEFAULT_SIZE + 1);
        verifyNoMoreInteractions(repository);
    }

    /**
     * A forged {@code served} of 0 at a key through which 9,999 rows match: the recount reaches the cap,
     * so the list ends with an empty capped page before any page query.
     *
     * @throws JsonProcessingException if the test cursor cannot be written
     */
    @Test
    @DisplayName("served 0 at a key the recount puts at the cap: an empty capped page, no page query")
    void forgedLowServedAtCapKeyEndsTheList() throws JsonProcessingException {
        SearchCriteria.Cursor forged = new SearchCriteria.Cursor(
                "ZUUOYDEO RWGIXUBO EAFSSA", "DES MOINES", "IA", "14MD", 0);
        SearchCriteria through = new SearchCriteria("", "", "", true, MAX_SIZE, forged);
        when(repository.countThrough(through, MAX_ROWS)).thenReturn(MAX_ROWS);

        SearchPage page = service.search(null, null, null, true, MAX_SIZE, cursor(forged));

        verify(repository).countThrough(through, MAX_ROWS);
        verifyNoMoreInteractions(repository);
        assertThat(page.items()).isEmpty();
        assertThat(page.nextCursor()).isNull();
        assertThat(page.limitReached()).isTrue();
        assertThat(page.notice()).isEqualTo(TOO_MANY);
    }

    /**
     * A forged {@code served} of 0 at a key through which 9,950 rows match: the recount floors it, so a
     * page of 100 asks for the 49 rows left plus the look-ahead row and is cut at the cap.
     *
     * @throws JsonProcessingException if the test cursor cannot be written
     */
    @Test
    @DisplayName("served 0 where the recount finds 9,950 rows: the page is cut at the cap")
    void forgedLowServedIsFlooredByTheRecount() throws JsonProcessingException {
        SearchCriteria.Cursor forged = new SearchCriteria.Cursor("MIDDLE CO", "AUSTIN", "TX", "1ABC", 0);
        SearchCriteria through = new SearchCriteria("", "", "", true, MAX_SIZE, forged);
        when(repository.countThrough(through, MAX_ROWS)).thenReturn(9_950);
        // 9,999 - 9,950 = 49 rows remain, so the page asks for 49 plus the look-ahead row.
        SearchCriteria pageCriteria = new SearchCriteria("", "", "", true, 49, forged);
        List<CustomerSummary> rows = rows(50);
        when(repository.find(pageCriteria, 49 + 1)).thenReturn(rows);

        SearchPage page = service.search(null, null, null, true, MAX_SIZE, cursor(forged));

        verify(repository).countThrough(through, MAX_ROWS);
        verify(repository).find(pageCriteria, 49 + 1);
        verifyNoMoreInteractions(repository);
        assertThat(page.items()).containsExactlyElementsOf(rows.subList(0, 49));
        assertThat(page.nextCursor()).isNull();
        assertThat(page.limitReached()).isTrue();
        assertThat(page.notice()).isEqualTo(TOO_MANY);
    }

    /**
     * A {@code served} of 9,950 where the recount finds 3 rows: the larger value is kept, so the page
     * still stops at the cap, with the normalized filters reaching both repository calls.
     *
     * @throws JsonProcessingException if the test cursor cannot be written
     */
    @Test
    @DisplayName("a served above the recount is kept: it only lowers the caller's own allowance")
    void inflatedServedIsKept() throws JsonProcessingException {
        SearchCriteria.Cursor inflated =
                new SearchCriteria.Cursor("ACME", "BOSTON", "MA", "AAAB", 9_950);
        SearchCriteria through = new SearchCriteria("ACME", "", "MA", false, MAX_SIZE, inflated);
        when(repository.countThrough(through, MAX_ROWS)).thenReturn(3);
        SearchCriteria pageCriteria = new SearchCriteria("ACME", "", "MA", false, 49, inflated);
        List<CustomerSummary> rows = rows(50);
        when(repository.find(pageCriteria, 49 + 1)).thenReturn(rows);

        SearchPage page = service.search("acme", null, "ma", false, MAX_SIZE, cursor(inflated));

        verify(repository).countThrough(through, MAX_ROWS);
        verify(repository).find(pageCriteria, 49 + 1);
        verifyNoMoreInteractions(repository);
        assertThat(page.items()).containsExactlyElementsOf(rows.subList(0, 49));
        assertThat(page.nextCursor()).isNull();
        assertThat(page.limitReached()).isTrue();
        assertThat(page.notice()).isEqualTo(TOO_MANY);
    }

    /**
     * An honest cursor, whose {@code served} equals the recount: the page and its next cursor are what
     * they were without the recount.
     *
     * @throws JsonProcessingException if a test cursor cannot be written
     */
    @Test
    @DisplayName("a served equal to the recount is unchanged: the next cursor carries served + returned")
    void honestServedIsUnchanged() throws JsonProcessingException {
        SearchCriteria.Cursor honest = new SearchCriteria.Cursor("BETA", "CHICAGO", "IL", "AAAC", 24);
        // The page is not cut, so the count and the page share criteria: filters, size 12, cursor.
        SearchCriteria criteria = new SearchCriteria("", "", "", false, DEFAULT_SIZE, honest);
        when(repository.countThrough(criteria, MAX_ROWS)).thenReturn(24);
        List<CustomerSummary> rows = rows(DEFAULT_SIZE + 1);
        when(repository.find(criteria, DEFAULT_SIZE + 1)).thenReturn(rows);

        SearchPage page = service.search(null, null, null, false, null, cursor(honest));

        verify(repository).countThrough(criteria, MAX_ROWS);
        verify(repository).find(criteria, DEFAULT_SIZE + 1);
        verifyNoMoreInteractions(repository);
        assertThat(page.items()).containsExactlyElementsOf(rows.subList(0, DEFAULT_SIZE));
        assertThat(page.limitReached()).isFalse();
        assertThat(page.notice()).isNull();
        CustomerSummary last = rows.get(DEFAULT_SIZE - 1);
        assertThat(page.nextCursor()).isEqualTo(cursor(new SearchCriteria.Cursor(
                last.name(), last.city(), last.state(), last.custId().value(), 24 + DEFAULT_SIZE)));
    }

    /**
     * Encodes a cursor as the service writes one: base64url without padding over the UTF-8 JSON object
     * with exactly the properties {@code name}, {@code city}, {@code state}, {@code custid} and
     * {@code served}, in that order.
     *
     * @param position the keys and the served count
     * @return the cursor text
     * @throws JsonProcessingException if the JSON cannot be written, which a plain mapper never causes
     */
    private static String cursor(SearchCriteria.Cursor position) throws JsonProcessingException {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("name", position.name());
        payload.put("city", position.city());
        payload.put("state", position.state());
        payload.put("custid", position.custid());
        payload.put("served", position.served());
        return Base64.getUrlEncoder().withoutPadding().encodeToString(JSON.writeValueAsBytes(payload));
    }

    /**
     * Returns {@code count} distinct list rows, as the repository would answer them in list order.
     *
     * @param count the number of rows
     * @return the rows; row {@code i} has id {@code fromOrdinal(1000 + i)} and a name that sorts by
     *         {@code i}
     */
    private static List<CustomerSummary> rows(int count) {
        List<CustomerSummary> rows = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            rows.add(new CustomerSummary(CustomerId.fromOrdinal(1000 + i), String.format("ROW %03d", i),
                    "AUSTIN", "TX", "73301", "Y"));
        }
        return List.copyOf(rows);
    }

    /**
     * Asserts that {@code call} fails with the given code, field and reason, and that the repository
     * was never called.
     *
     * @param call   the search call
     * @param code   the expected catalog key
     * @param field  the expected field
     * @param reason the expected APP0400 reason, or {@code null} for DEM0007, which has no arguments
     */
    private void assertRejected(ThrowingCallable call, String code, String field, String reason) {
        assertThatThrownBy(call).isInstanceOfSatisfying(InvalidSearchCriteriaException.class, e -> {
            assertThat(e.code()).isEqualTo(code);
            assertThat(e.field()).isEqualTo(field);
            if (reason == null) {
                assertThat(e.args()).isEmpty();
            } else {
                assertThat(e.args()).containsExactly(reason);
            }
        });
        verifyNoInteractions(repository);
    }
}
