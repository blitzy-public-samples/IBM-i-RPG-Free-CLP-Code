package com.democorp.customermaster.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.democorp.customermaster.domain.State;
import com.democorp.customermaster.repository.StateRepository;
import com.democorp.customermaster.service.exception.InvalidSearchCriteriaException;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Specifies the request rules of {@link StateService#list(String, String)}, the State picker list behind
 * {@code GET /api/states}: PMTSTATER's "Name Contains" filter [5250_Subfile/PMTSTATER.SQLRPGLE:395-400]
 * and its F7 sort toggle.
 *
 * <p><b>What the target must do.</b> {@code sort} is {@code name} (the default, for an absent or empty
 * value) or {@code code}; any other value, a blank one included, is 400 APP0400 on {@code sort}. A
 * {@code nameContains} longer than 10 code points, or else one that contains U+0000 (which PostgreSQL
 * text cannot hold), is APP0400 on {@code nameContains}; both are checked before the sort. The filter is
 * trimmed and uppercased, its backslashes are doubled, {@code %} and {@code _} stay wildcards, and it is
 * wrapped as {@code "%" + filter + "%"}, or {@code "%%"} when blank. A rejected request never reaches
 * the repository.
 *
 * <p>{@link StateRepository} is a plain Mockito mock, so its {@code search} default method is stubbed
 * too and records the exact pattern and sort key the service passes.
 *
 * <p>Pure JUnit 5, AssertJ and Mockito: no Spring context, no database, no Docker.
 */
@DisplayName("StateService.list: State picker filter and sort rules")
final class StateServiceTest {

    /** The pattern PMTSTATER builds for a blank filter. */
    private static final String MATCH_ALL = "%%";

    private StateRepository stateRepository;

    private StateService stateService;

    @BeforeEach
    void setUp() {
        stateRepository = mock(StateRepository.class);
        stateService = new StateService(stateRepository);
    }

    @ParameterizedTest(name = "sort [{0}] means name")
    @NullAndEmptySource
    @DisplayName("an absent or empty sort lists by name")
    void absentOrEmptySortMeansName(String sort) {
        List<State> rows = List.of(new State("AL", "Alabama"), new State("AK", "Alaska"));
        when(stateRepository.search(MATCH_ALL, "name")).thenReturn(rows);

        assertThat(stateService.list(null, sort)).isEqualTo(rows);

        verify(stateRepository).search(MATCH_ALL, "name");
        verifyNoMoreInteractions(stateRepository);
    }

    @Test
    @DisplayName("sort name lists by name")
    void sortNameListsByName() {
        stateService.list("", "name");

        verify(stateRepository).search(MATCH_ALL, "name");
        verifyNoMoreInteractions(stateRepository);
    }

    @Test
    @DisplayName("sort code lists by code")
    void sortCodeListsByCode() {
        List<State> rows = List.of(new State("AA", "Armed Forces"), new State("AE", "Armed Forces"));
        when(stateRepository.search(MATCH_ALL, "code")).thenReturn(rows);

        assertThat(stateService.list("", "code")).isEqualTo(rows);

        verify(stateRepository).search(MATCH_ALL, "code");
        verifyNoMoreInteractions(stateRepository);
    }

    @ParameterizedTest(name = "sort [{0}] is rejected")
    @ValueSource(strings = {" ", "  ", "\t", "name ", " code", "NAME", "Code", "zip"})
    @DisplayName("any other sort, blank or padded or differently cased, is APP0400 on sort")
    void unknownSortIsRejected(String sort) {
        assertThatThrownBy(() -> stateService.list("car", sort))
                .isInstanceOfSatisfying(InvalidSearchCriteriaException.class, e -> {
                    assertThat(e.code()).isEqualTo(InvalidSearchCriteriaException.APP0400);
                    assertThat(e.field()).isEqualTo("sort");
                    assertThat(e.args()).containsExactly("sort must be name or code");
                });

        verifyNoInteractions(stateRepository);
    }

    @Test
    @DisplayName("a nameContains of 11 code points is APP0400 on nameContains")
    void overLongNameContainsIsRejected() {
        assertThatThrownBy(() -> stateService.list("abcdefghijk", "name"))
                .isInstanceOfSatisfying(InvalidSearchCriteriaException.class, e -> {
                    assertThat(e.code()).isEqualTo(InvalidSearchCriteriaException.APP0400);
                    assertThat(e.field()).isEqualTo("nameContains");
                    assertThat(e.args()).containsExactly("nameContains must be at most 10 characters");
                });

        verifyNoInteractions(stateRepository);
    }

    @Test
    @DisplayName("the length rule is checked before the sort rule")
    void lengthIsCheckedBeforeSort() {
        assertThatThrownBy(() -> stateService.list("abcdefghijk", "zip"))
                .isInstanceOfSatisfying(InvalidSearchCriteriaException.class,
                        e -> assertThat(e.field()).isEqualTo("nameContains"));

        verifyNoInteractions(stateRepository);
    }

    // The display name omits the value: U+0000 is not a legal XML character in the test report.
    @ParameterizedTest(name = "case {index}")
    @ValueSource(strings = {"A\u0000", "\u0000", " \u0000 ", "car\u0000olina"})
    @DisplayName("a nameContains holding U+0000 is APP0400 on nameContains and never queried")
    void nulInNameContainsIsRejected(String nameContains) {
        assertThatThrownBy(() -> stateService.list(nameContains, "name"))
                .isInstanceOfSatisfying(InvalidSearchCriteriaException.class, e -> {
                    assertThat(e.code()).isEqualTo(InvalidSearchCriteriaException.APP0400);
                    assertThat(e.field()).isEqualTo("nameContains");
                    assertThat(e.args()).containsExactly("nameContains must not contain U+0000");
                });

        verifyNoInteractions(stateRepository);
    }

    @Test
    @DisplayName("the length rule is checked before the U+0000 rule")
    void lengthIsCheckedBeforeNul() {
        assertThatThrownBy(() -> stateService.list("abcdefghij\u0000", "name"))
                .isInstanceOfSatisfying(InvalidSearchCriteriaException.class, e -> {
                    assertThat(e.field()).isEqualTo("nameContains");
                    assertThat(e.args()).containsExactly("nameContains must be at most 10 characters");
                });

        verifyNoInteractions(stateRepository);
    }

    @Test
    @DisplayName("the U+0000 rule is checked before the sort rule")
    void nulIsCheckedBeforeSort() {
        assertThatThrownBy(() -> stateService.list("A\u0000", " "))
                .isInstanceOfSatisfying(InvalidSearchCriteriaException.class, e -> {
                    assertThat(e.field()).isEqualTo("nameContains");
                    assertThat(e.args()).containsExactly("nameContains must not contain U+0000");
                });

        verifyNoInteractions(stateRepository);
    }

    @Test
    @DisplayName("10 code points, counted as code points, are accepted")
    void tenCodePointsAreAccepted() {
        // Nine ASCII letters and U+1D400 MATHEMATICAL BOLD CAPITAL A (two UTF-16 units).
        String filter = "abcdefghi\uD835\uDC00";

        stateService.list(filter, "name");

        verify(stateRepository).search("%ABCDEFGHI\uD835\uDC00%", "name");
        verifyNoMoreInteractions(stateRepository);
    }

    @Test
    @DisplayName("the filter is trimmed, uppercased and wrapped in %")
    void filterIsTrimmedUppercasedAndWrapped() {
        stateService.list(" car ", "name");

        verify(stateRepository).search("%CAR%", "name");
        verifyNoMoreInteractions(stateRepository);
    }

    @Test
    @DisplayName("% and _ stay wildcards")
    void wildcardsAreKept() {
        stateService.list("texas_", "name");
        stateService.list("n%a", "code");

        verify(stateRepository).search("%TEXAS_%", "name");
        verify(stateRepository).search("%N%A%", "code");
        verifyNoMoreInteractions(stateRepository);
    }

    @Test
    @DisplayName("every backslash is doubled so it stays literal")
    void backslashIsDoubled() {
        stateService.list("a\\b\\", "name");

        verify(stateRepository).search("%A\\\\B\\\\%", "name");
        verifyNoMoreInteractions(stateRepository);
    }

    @ParameterizedTest(name = "nameContains [{0}] matches every state")
    @NullAndEmptySource
    @ValueSource(strings = {" ", "   "})
    @DisplayName("a null, empty or blank filter matches every state")
    void blankFilterMatchesAll(String nameContains) {
        stateService.list(nameContains, "name");

        verify(stateRepository).search(MATCH_ALL, "name");
        verifyNoMoreInteractions(stateRepository);
    }
}
