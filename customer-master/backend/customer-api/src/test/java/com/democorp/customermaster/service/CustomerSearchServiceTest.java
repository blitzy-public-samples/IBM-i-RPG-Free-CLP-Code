package com.democorp.customermaster.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import com.democorp.customermaster.config.AppProperties;
import com.democorp.customermaster.domain.SearchCriteria;
import com.democorp.customermaster.domain.SearchPage;
import com.democorp.customermaster.messages.MessageCatalog;
import com.democorp.customermaster.repository.CustomerSearchRepository;
import com.democorp.customermaster.service.exception.InvalidSearchCriteriaException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
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
 * <p>The service is built with the real {@link AppProperties} defaults (page 12, maximum 100, cap
 * 9,999), the packaged {@link MessageCatalog} and a plain {@link ObjectMapper}; only
 * {@link CustomerSearchRepository} is a Mockito mock, whose {@code find} answers an empty list.
 *
 * <p>Pure JUnit 5, AssertJ and Mockito: no Spring context, no database, no Docker.
 */
@DisplayName("CustomerSearchService.search: filter validation before the repository")
final class CustomerSearchServiceTest {

    /** The default page size, {@code customer-master.search.default-size}. */
    private static final int DEFAULT_SIZE = 12;

    /** The packaged catalog; immutable, so one instance serves every test. */
    private static final MessageCatalog CATALOG = new MessageCatalog();

    private CustomerSearchRepository repository;

    private CustomerSearchService service;

    @BeforeEach
    void setUp() {
        repository = mock(CustomerSearchRepository.class);
        AppProperties appProperties = new AppProperties(
                new AppProperties.Db(Duration.ofSeconds(5)),
                new AppProperties.Search(DEFAULT_SIZE, 100, 9999));
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
