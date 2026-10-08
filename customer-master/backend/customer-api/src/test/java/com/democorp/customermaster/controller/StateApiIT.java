package com.democorp.customermaster.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.democorp.customermaster.support.AbstractPostgresIT;
import com.democorp.customermaster.support.DatabaseCleaner;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * Specifies {@code GET /api/states?nameContains=&sort=name|code}, the stateless list behind the State
 * picker, against the 58 rows of migration V2, end to end through security, controller, service,
 * repository SQL and the {@code customer_sort} collation.
 *
 * <p><b>Behavioural source.</b>
 * <ul>
 *   <li>PMTSTATER's {@code DataCur}: {@code select STATE, NAME from STATES where upper(NAME) like
 *       :DESCLike order by} NAME or STATE [5250_Subfile/PMTSTATER.SQLRPGLE:150-162], initially by
 *       name, toggled by F7;</li>
 *   <li>its {@code ProcessSearchCriteria}: {@code DESCLike = '%%'} for a blank "Name Contains" filter,
 *       else {@code '%' + %trim(SC_NAME) + '%'} [5250_Subfile/PMTSTATER.SQLRPGLE:395-400]; the field
 *       has no {@code CHECK(LC)}, so the typed filter is uppercase, while the stored names keep their
 *       mixed case;</li>
 *   <li>the filter field {@code SC_NAME 10A} [5250_Subfile/PMTSTATED.DSPF:88]: the API rejects a longer
 *       filter with 400 {@code APP0400};</li>
 *   <li>table STATES with {@code NAME CHAR(30)} and its 58 rows [5250_Subfile/States.sql:8-80]. Db2
 *       matches {@code LIKE} against the blank-padded 30-character value, so a typed {@code _} can match
 *       a pad blank; the target reproduces that by matching {@code rpad(upper(name), 30)}.</li>
 * </ul>
 *
 * <p><b>Expected values</b> are the rows of {@code 5250_Subfile/States.sql}, held in {@link #SOURCE_ROWS}
 * in source order; no expectation is derived from the implementation. Every name consists of letters
 * and spaces only, so {@link String#CASE_INSENSITIVE_ORDER} orders them as the ICU {@code customer_sort}
 * collation does, which compares spaces as characters that sort before letters: {@code Virgin Islands}
 * precedes {@code Virginia}, {@code Armed Forces} precedes {@code Armed Forces America}. The codes are two
 * upper-case letters, so their natural {@link String} order is the collation's.
 *
 * <p><b>Context.</b> The base context variant of {@link AbstractPostgresIT}: no mocked bean, no import,
 * no property override. The tests only read {@code states}, which {@link DatabaseCleaner} never empties,
 * and sign in as the {@code INQUIRY} user, the least role the endpoint requires. Query values are passed
 * as URI variables, so the client encodes them (a blank becomes {@code %20}).
 */
@DisplayName("GET /api/states: the State prompt list, filtered by name fragment and sorted by name or code")
class StateApiIT extends AbstractPostgresIT {

    private static final String STATES_PATH = "/api/states";

    private static final String NAME_CONTAINS = "nameContains";

    private static final String SORT = "sort";

    /** Catalog key of a request the API cannot evaluate: "Request is not valid: {0}". */
    private static final String APP0400 = "APP0400";

    private static final String APP0400_DETAIL_PREFIX = "Request is not valid: ";

    /** The {@code type} of an APP0400 problem, by the error model's {@code urn:customer-master:problem:<code>}. */
    private static final String APP0400_TYPE = "urn:customer-master:problem:" + APP0400;

    private static final String STATE_MEMBER = "state";

    private static final String NAME_MEMBER = "name";

    /** The 58 rows of STATES, in the order {@code 5250_Subfile/States.sql} inserts them (lines 23-80). */
    private static final List<StateRow> SOURCE_ROWS = List.of(
            row("AA", "Armed Forces America"),
            row("AE", "Armed Forces"),
            row("AK", "Alaska"),
            row("AL", "Alabama"),
            row("AS", "American Samoa"),
            row("AZ", "Arizona"),
            row("AR", "Arkansas"),
            row("CA", "California"),
            row("CO", "Colorado"),
            row("CT", "Connecticut"),
            row("DE", "Delaware"),
            row("DC", "District of Columbia"),
            row("FL", "Florida"),
            row("GA", "Georgia"),
            row("GU", "Guam"),
            row("HI", "Hawaii"),
            row("ID", "Idaho"),
            row("IL", "Illinois"),
            row("IN", "Indiana"),
            row("IA", "Iowa"),
            row("KS", "Kansas"),
            row("KY", "Kentucky"),
            row("LA", "Louisiana"),
            row("ME", "Maine"),
            row("MD", "Maryland"),
            row("MA", "Massachusetts"),
            row("MI", "Michigan"),
            row("MN", "Minnesota"),
            row("MP", "Northern Mariana Islands"),
            row("MS", "Mississippi"),
            row("MO", "Missouri"),
            row("MT", "Montana"),
            row("NE", "Nebraska"),
            row("NV", "Nevada"),
            row("NH", "New Hampshire"),
            row("NJ", "New Jersey"),
            row("NM", "New Mexico"),
            row("NY", "New York"),
            row("NC", "North Carolina"),
            row("ND", "North Dakota"),
            row("OH", "Ohio"),
            row("OK", "Oklahoma"),
            row("OR", "Oregon"),
            row("PA", "Pennsylvania"),
            row("PR", "Puerto Rico"),
            row("RI", "Rhode Island"),
            row("SC", "South Carolina"),
            row("SD", "South Dakota"),
            row("TN", "Tennessee"),
            row("TX", "Texas"),
            row("UT", "Utah"),
            row("VT", "Vermont"),
            row("VA", "Virginia"),
            row("WA", "Washington"),
            row("WV", "West Virginia"),
            row("WI", "Wisconsin"),
            row("VI", "Virgin Islands"),
            row("WY", "Wyoming"));

    /** The source rows in "By Name" order: PMTSTATER's initial sort, the endpoint's default. */
    private static final List<StateRow> BY_NAME = SOURCE_ROWS.stream()
            .sorted(Comparator.comparing(StateRow::name, String.CASE_INSENSITIVE_ORDER))
            .toList();

    /** The source rows in "By Code" order: PMTSTATER's F7 sort. */
    private static final List<StateRow> BY_CODE = SOURCE_ROWS.stream()
            .sorted(Comparator.comparing(StateRow::state))
            .toList();

    /** The two states whose name contains {@code CAR}, by name and, identically, by code. */
    private static final List<StateRow> CAROLINAS =
            List.of(row("NC", "North Carolina"), row("SC", "South Carolina"));

    @Test
    @DisplayName("no parameters: all 58 states sorted by name, each {state, name} in stored case")
    void loadsAll58SortedByName() {
        List<StateRow> rows = stateRows(listStates(Map.of()));

        assertThat(SOURCE_ROWS).as("rows of 5250_Subfile/States.sql").hasSize(DatabaseCleaner.STATE_COUNT);
        assertThat(rows).as("every V2 row, sorted by name").containsExactlyElementsOf(BY_NAME);
        assertThat(rows).hasSize(DatabaseCleaner.STATE_COUNT);
        assertThat(rows.get(0)).as("first by name").isEqualTo(row("AL", "Alabama"));
        assertThat(rows.get(rows.size() - 1)).as("last by name").isEqualTo(row("WY", "Wyoming"));

        List<String> names = rows.stream().map(StateRow::name).toList();
        assertThat(names).as("names in case-insensitive order")
                .isSortedAccordingTo(String.CASE_INSENSITIVE_ORDER);
        assertThat(names.indexOf("Virgin Islands")).as("a space sorts before a letter")
                .isLessThan(names.indexOf("Virginia"));
        assertThat(names.indexOf("Armed Forces")).as("a prefix sorts before its extension")
                .isLessThan(names.indexOf("Armed Forces America"));
        assertThat(names).as("names keep their stored mixed case")
                .contains("North Carolina", "District of Columbia")
                .doesNotContain("NORTH CAROLINA");
    }

    /**
     * A blank filter, empty or all blanks, means "not searching, take all" ({@code DESCLike = '%%'}), and
     * {@code sort=name} is the default order.
     */
    @Test
    @DisplayName("an empty or blank nameContains, and an explicit sort=name, list all 58 by name")
    void blankFilterReturnsAll() {
        List<StateRow> defaultRows = stateRows(listStates(Map.of()));
        assertThat(defaultRows).containsExactlyElementsOf(BY_NAME);

        assertThat(stateRows(listStates(Map.of(NAME_CONTAINS, ""))))
                .as("nameContains= (empty)")
                .containsExactlyElementsOf(defaultRows);
        assertThat(stateRows(listStates(Map.of(NAME_CONTAINS, "  "))))
                .as("nameContains=%20%20 (blanks)")
                .containsExactlyElementsOf(defaultRows);
        assertThat(stateRows(listStates(Map.of(SORT, "name"))))
                .as("sort=name")
                .containsExactlyElementsOf(defaultRows);
        assertThat(stateRows(listStates(params(NAME_CONTAINS, "", SORT, "name"))))
                .as("nameContains=&sort=name")
                .containsExactlyElementsOf(defaultRows);
    }

    /**
     * The filter is uppercased and compared with {@code upper(name)}, so its case does not matter, and it
     * is trimmed at both ends, as {@code %trim(SC_NAME)}.
     */
    @Test
    @DisplayName("nameContains=car, CAR, Car or ' car ' finds North and South Carolina")
    void nameContainsIsCaseInsensitive() {
        assertThat(stateRows(listStates(Map.of(NAME_CONTAINS, "car"))))
                .as("nameContains=car")
                .containsExactlyElementsOf(CAROLINAS);
        assertThat(stateRows(listStates(Map.of(NAME_CONTAINS, "CAR"))))
                .as("nameContains=CAR")
                .containsExactlyElementsOf(CAROLINAS);
        assertThat(stateRows(listStates(Map.of(NAME_CONTAINS, "Car"))))
                .as("nameContains=Car")
                .containsExactlyElementsOf(CAROLINAS);
        assertThat(stateRows(listStates(Map.of(NAME_CONTAINS, " car "))))
                .as("nameContains=%20car%20 (trimmed)")
                .containsExactlyElementsOf(CAROLINAS);
    }

    /**
     * A typed {@code _} is a {@code LIKE} wildcard that can match a pad blank of the 30-character name,
     * as on Db2's {@code CHAR(30)}: Texas is 5 characters, so {@code TEXAS_} matches only its padding.
     */
    @Test
    @DisplayName("nameContains=texas_ finds Texas: the _ matches a pad blank of NAME CHAR(30)")
    void underscoreMatchesPadBlank() {
        assertThat(stateRows(listStates(Map.of(NAME_CONTAINS, "texas_"))))
                .as("nameContains=texas_")
                .containsExactly(row("TX", "Texas"));
        assertThat(stateRows(listStates(Map.of(NAME_CONTAINS, "wyoming___"))))
                .as("nameContains=wyoming___ (three pad blanks, 10 characters)")
                .containsExactly(row("WY", "Wyoming"));
    }

    /**
     * {@code sort=code} orders by the state code, PMTSTATER's F7 "By Code"; a filter applies under either
     * order, and the two orders differ where names and codes disagree.
     */
    @Test
    @DisplayName("sort=code: all 58 by code, AA first and WY last; the filter applies under either order")
    void sortByCode() {
        List<StateRow> rows = stateRows(listStates(Map.of(SORT, "code")));

        assertThat(rows).as("every V2 row, sorted by code").containsExactlyElementsOf(BY_CODE);
        assertThat(rows).hasSize(DatabaseCleaner.STATE_COUNT);
        assertThat(rows.get(0).state()).as("first by code").isEqualTo("AA");
        assertThat(rows.get(rows.size() - 1).state()).as("last by code").isEqualTo("WY");
        List<String> codes = rows.stream().map(StateRow::state).toList();
        for (int i = 1; i < codes.size(); i++) {
            assertThat(codes.get(i - 1)).as("code %d precedes code %d", i - 1, i)
                    .isLessThan(codes.get(i));
        }

        assertThat(stateRows(listStates(params(NAME_CONTAINS, "car", SORT, "code"))))
                .as("nameContains=car&sort=code")
                .extracting(StateRow::state)
                .containsExactly("NC", "SC");

        // Name order and code order disagree here: "Virgin Islands" (VI) sorts before "Virginia" (VA).
        assertThat(stateRows(listStates(params(NAME_CONTAINS, "virgin", SORT, "code"))))
                .as("nameContains=virgin&sort=code")
                .extracting(StateRow::state)
                .containsExactly("VA", "VI", "WV");
        assertThat(stateRows(listStates(params(NAME_CONTAINS, "virgin", SORT, "name"))))
                .as("nameContains=virgin&sort=name")
                .extracting(StateRow::state)
                .containsExactly("VI", "VA", "WV");
    }

    @Test
    @DisplayName("sort=zip: 400 problem+json APP0400 on sort")
    void unknownSortRejected() {
        assertApp0400(listStates(Map.of(SORT, "zip")), SORT);
    }

    /**
     * A filter wider than PMTSTATED's 10-character {@code SC_NAME} is 400 {@code APP0400} on
     * {@code nameContains}; exactly 10 characters is accepted and here matches nothing.
     */
    @Test
    @DisplayName("nameContains of 11 characters: 400 APP0400 on nameContains; 10 characters is accepted")
    void filterLongerThanTenRejected() {
        assertApp0400(listStates(Map.of(NAME_CONTAINS, "ABCDEFGHIJK")), NAME_CONTAINS);

        assertThat(stateRows(listStates(Map.of(NAME_CONTAINS, "ABCDEFGHIJ"))))
                .as("a 10-character filter is valid and matches no state")
                .isEmpty();
    }

    /**
     * Sends {@code GET /api/states} as the {@code INQUIRY} user with the given query parameters, each
     * value passed as a URI variable so the client encodes it.
     *
     * @param query the query parameters, in the order they are to appear; may be empty
     * @return the response, whatever its status
     */
    private ResponseEntity<String> listStates(Map<String, String> query) {
        return inquiry().get()
                .uri(builder -> {
                    builder.path(STATES_PATH);
                    Map<String, String> variables = new LinkedHashMap<>();
                    int index = 0;
                    for (Map.Entry<String, String> parameter : query.entrySet()) {
                        String variable = "v" + index++;
                        builder.queryParam(parameter.getKey(), "{" + variable + "}");
                        variables.put(variable, parameter.getValue());
                    }
                    return builder.build(variables);
                })
                .retrieve()
                .toEntity(String.class);
    }

    /**
     * Asserts a 200 {@code application/json} array and maps it to rows, checking that each element has
     * exactly the members {@code state} and {@code name}, both strings.
     *
     * @param response the response of {@link #listStates(Map)}
     * @return the elements as rows, in response order
     */
    private List<StateRow> stateRows(ResponseEntity<String> response) {
        assertThat(response.getStatusCode()).as("status, body: %s", response.getBody()).isEqualTo(HttpStatus.OK);
        MediaType contentType = response.getHeaders().getContentType();
        assertThat(contentType).as("Content-Type").isNotNull();
        assertThat(MediaType.APPLICATION_JSON.isCompatibleWith(contentType))
                .as("Content-Type %s is application/json", contentType)
                .isTrue();

        JsonNode body = json(response);
        assertThat(body.isArray()).as("body is a JSON array: %s", body).isTrue();
        List<StateRow> rows = new ArrayList<>();
        for (JsonNode element : body) {
            assertThat(element.isObject()).as("element is an object: %s", element).isTrue();
            List<String> members = new ArrayList<>();
            element.fieldNames().forEachRemaining(members::add);
            assertThat(members).as("members of %s", element).containsExactlyInAnyOrder(STATE_MEMBER, NAME_MEMBER);
            assertThat(element.get(STATE_MEMBER).isTextual()).as("state of %s is a string", element).isTrue();
            assertThat(element.get(NAME_MEMBER).isTextual()).as("name of %s is a string", element).isTrue();
            rows.add(row(element.get(STATE_MEMBER).asText(), element.get(NAME_MEMBER).asText()));
        }
        return rows;
    }

    /**
     * Asserts a 400 {@code application/problem+json} {@code APP0400} whose single {@code errors} item
     * names the rejected parameter.
     *
     * @param response the response of {@link #listStates(Map)}
     * @param field    the query parameter the problem must name
     */
    private void assertApp0400(ResponseEntity<String> response, String field) {
        assertThat(response.getStatusCode()).as("status, body: %s", response.getBody())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        JsonNode problem = problem(response);
        assertThat(problem.path("type").asText()).isEqualTo(APP0400_TYPE);
        assertThat(problem.path("status").asInt()).isEqualTo(HttpStatus.BAD_REQUEST.value());
        assertThat(problem.path("code").asText()).isEqualTo(APP0400);
        assertThat(problem.path("instance").asText()).isEqualTo(STATES_PATH);
        assertThat(problem.path("detail").asText()).startsWith(APP0400_DETAIL_PREFIX);
        JsonNode errors = problem.path("errors");
        assertThat(errors.isArray()).as("errors is an array: %s", problem).isTrue();
        assertThat(errors).as("errors of %s", problem).hasSize(1);
        assertThat(errors.get(0).path("field").asText()).isEqualTo(field);
        assertThat(errors.get(0).path("code").asText()).isEqualTo(APP0400);
    }

    private static Map<String, String> params(String firstName, String firstValue, String secondName,
            String secondValue) {
        Map<String, String> query = new LinkedHashMap<>();
        query.put(firstName, firstValue);
        query.put(secondName, secondValue);
        return query;
    }

    private static StateRow row(String state, String name) {
        return new StateRow(state, name);
    }

    private record StateRow(String state, String name) {
    }
}
