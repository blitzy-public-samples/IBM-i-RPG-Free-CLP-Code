package com.democorp.customermaster.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.democorp.customermaster.support.AbstractPostgresIT;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * Specifies {@code GET /api/customers} over HTTP against PMTCUSTR's search subfile
 * [5250_Subfile/PMTCUSTR.SQLRPGLE], [5250_Subfile/PMTCUSTD.DSPF].
 * <ul>
 *   <li>{@code ProcessSearchCriteria} (lines 625-665): name and city match as trimmed prefixes; a blank
 *       State selects every state, and a State whose trimmed length is not 2 sends DEM0007 and runs no
 *       search; inactive customers are excluded unless F9 ({@code includeInactive}) is on.</li>
 *   <li>{@code ItemCur} (lines 208-223): {@code ORDER BY NAME, CITY, STATE}, with {@code custid} as the
 *       target's keyset tiebreaker.</li>
 *   <li>{@code SflFirstPage} (lines 532-545): an empty first page's DEM0002 travels as the page's
 *       {@code notice}.</li>
 *   <li>PMTCUSTD (lines 95-98): {@code SC_NAME} and {@code SC_CITY} are 13 wide, {@code SC_STATE} 2; a
 *       longer name or city is 400 APP0400.</li>
 *   <li>The 5250 screen uppercases keyed text (no {@code CHECK(LC)}), so lower-case filters and written
 *       fields meet as upper case.</li>
 * </ul>
 *
 * <p>Padding parity, {@code EXPLAIN} plans and the 9,999-row cap belong to
 * {@code repository/CustomerSearchRepositoryIT}. Every page member is present, {@code null} where it
 * does not apply; a rejected query is 400 {@code application/problem+json} with {@code errors[0]} on
 * the query parameter at fault.
 *
 * <p>Test databases hold no seed rows: after the base class empties {@code custmast},
 * {@link #createFixture()} adds five active customers and one inactive one through {@code POST} as the
 * maintenance user. Searches run as the inquiry user, because {@code INQUIRY} suffices. The base context
 * variant of {@link AbstractPostgresIT}: no mocked bean, import or property override.
 */
@DisplayName("GET /api/customers: PMTCUSTR's search filters, order, paging and notices over HTTP")
class CustomerSearchApiIT extends AbstractPostgresIT {

    /** The search endpoint, also the {@code instance} of its problems. */
    private static final String CUSTOMERS = "/api/customers";

    /** The members of a search page, in the order they are serialized. */
    private static final List<String> PAGE_MEMBERS = List.of("items", "nextCursor", "limitReached", "notice");

    /** The members of one list item, in the order they are serialized. */
    private static final List<String> ITEM_MEMBERS = List.of("custId", "name", "city", "state", "zip5", "active");

    private static final String DEM0002 = "DEM0002";

    /** DEM0002's catalog text [5250_Subfile/CRTMSGF.CLLE:14-15]. */
    private static final String DEM0002_TEXT = "No records match the selection criteria";

    private static final String DEM0007 = "DEM0007";

    /** DEM0007's catalog text, with the source typo "in invalid" corrected [5250_Subfile/CRTMSGF.CLLE:24-25]. */
    private static final String DEM0007_TEXT = "State selection field is invalid.";

    private static final String APP0400 = "APP0400";

    /** APP0400's catalog text up to its {@code {0}}. */
    private static final String APP0400_PREFIX = "Request is not valid: ";

    /** The {@code type} prefix of every problem; the catalog key follows it. */
    private static final String PROBLEM_TYPE_PREFIX = "urn:customer-master:problem:";

    private static final String CUST_ID_FORMAT = "[A-Z0-9]{4}";

    private String acmeTools;

    private String acmeParts;

    private String betaWorks;

    private String acmeOld;

    private String dupFirst;

    /** The second "dup co", identical to the first in name, city and state, so only its id orders it. */
    private String dupSecond;

    /**
     * Adds the fixture, in this order, after the base class has emptied {@code custmast}. Every text is
     * keyed in lower case, so the stored rows prove server-side uppercasing.
     */
    @BeforeEach
    void createFixture() {
        acmeTools = create("acme tools", "springfield", "IL", "62701", "Y");
        acmeParts = create("acme parts", "springfield", "CA", "06371-1234", "Y");
        betaWorks = create("beta works", "boston", "MA", "02101", "Y");
        acmeOld = create("acme old", "springfield", "IL", "62702", "N");
        dupFirst = create("dup co", "akron", "OH", "44301", "Y");
        dupSecond = create("dup co", "akron", "OH", "44301", "Y");
    }

    @Test
    @DisplayName("no parameters: the active rows in name, city, state, custid order, with the full page shape")
    void defaultSearchReturnsActiveRowsInSourceOrder() {
        JsonNode page = page(search());

        // Five active rows; ACME OLD is inactive and absent. The two DUP CO rows share name, city and
        // state, so custid orders them, and the first allocated sorts first.
        assertThat(rows(page)).containsExactly(
                acmeParts + "|ACME PARTS|SPRINGFIELD|CA|06371|Y",
                acmeTools + "|ACME TOOLS|SPRINGFIELD|IL|62701|Y",
                betaWorks + "|BETA WORKS|BOSTON|MA|02101|Y",
                dupFirst + "|DUP CO|AKRON|OH|44301|Y",
                dupSecond + "|DUP CO|AKRON|OH|44301|Y");
        assertThat(ids(page)).doesNotContain(acmeOld);
        assertThat(dupFirst).isNotEqualTo(dupSecond);
        for (JsonNode item : page.get("items")) {
            assertThat(fieldNames(item)).as("members of %s", item).containsExactlyElementsOf(ITEM_MEMBERS);
            for (JsonNode value : item) {
                assertThat(value.isTextual()).as("every item member is a string: %s", item).isTrue();
            }
        }
        assertBottomWithoutNotice(page);
    }

    @Test
    @DisplayName("name: a trimmed, uppercased prefix")
    void namePrefixUppercased() {
        JsonNode lowerCase = page(search("name", "acme"));
        JsonNode leadingBlank = page(search("name", " acme"));

        assertThat(ids(lowerCase)).containsExactly(acmeParts, acmeTools);
        assertThat(names(lowerCase)).containsExactly("ACME PARTS", "ACME TOOLS");
        assertBottomWithoutNotice(lowerCase);
        assertThat(ids(leadingBlank)).as("a leading blank is trimmed, as %trim(SC_NAME)")
                .containsExactly(acmeParts, acmeTools);
        assertBottomWithoutNotice(leadingBlank);
    }

    @Test
    @DisplayName("city: a trimmed, uppercased prefix")
    void cityPrefix() {
        JsonNode page = page(search("city", "spring"));

        assertThat(ids(page)).containsExactly(acmeParts, acmeTools);
        assertThat(names(page)).containsExactly("ACME PARTS", "ACME TOOLS");
        assertBottomWithoutNotice(page);
    }

    @Test
    @DisplayName("state: exact and uppercased, blank selects all; includeInactive (F9) adds the inactive rows")
    void stateExactAndInactiveToggle() {
        assertThat(ids(page(search("state", "IL")))).containsExactly(acmeTools);
        assertThat(ids(page(search("state", "il")))).as("the state filter is uppercased")
                .containsExactly(acmeTools);

        JsonNode withInactive = page(search("state", "IL", "includeInactive", "true"));
        assertThat(rows(withInactive)).containsExactly(
                acmeOld + "|ACME OLD|SPRINGFIELD|IL|62702|N",
                acmeTools + "|ACME TOOLS|SPRINGFIELD|IL|62701|Y");

        assertThat(ids(page(search("includeInactive", "true"))))
                .containsExactly(acmeOld, acmeParts, acmeTools, betaWorks, dupFirst, dupSecond);
        assertThat(ids(page(search("includeInactive", "false"))))
                .containsExactly(acmeParts, acmeTools, betaWorks, dupFirst, dupSecond);
        assertThat(ids(page(search("state", "  ")))).as("a blank state selects every state")
                .containsExactly(acmeParts, acmeTools, betaWorks, dupFirst, dupSecond);
    }

    @Test
    @DisplayName("an unknown 2-letter state is not an error: it matches nothing, with DEM0002")
    void unknownTwoLetterStateMatchesNothing() {
        assertEmptyWithDem0002(page(search("state", "XX")));
    }

    @Test
    @DisplayName("nothing matches: 200 with no items and the DEM0002 notice")
    void noMatchGivesDem0002() {
        assertEmptyWithDem0002(page(search("name", "ZZZ")));
    }

    @Test
    @DisplayName("nextCursor pages through the list without duplicates or gaps, filters resent each page")
    void cursorPaging() {
        List<String> served = new ArrayList<>();
        List<Integer> pageSizes = new ArrayList<>();
        String cursor = null;
        do {
            JsonNode page = cursor == null
                    ? page(search("size", "2"))
                    : page(search("size", "2", "cursor", cursor));
            assertThat(page.get("limitReached").booleanValue()).isFalse();
            assertThat(page.get("notice").isNull()).isTrue();
            served.addAll(ids(page));
            pageSizes.add(page.get("items").size());
            cursor = nextCursor(page);
            assertThat(pageSizes).as("pages served before the list ended").hasSizeLessThanOrEqualTo(3);
        } while (cursor != null);

        assertThat(pageSizes).containsExactly(2, 2, 1);
        assertThat(served).as("the default search's rows, in order, each once")
                .containsExactly(acmeParts, acmeTools, betaWorks, dupFirst, dupSecond);

        JsonNode first = page(search("name", "acme", "size", "1"));
        assertThat(ids(first)).containsExactly(acmeParts);
        String next = nextCursor(first);
        assertThat(next).as("a next page follows ACME PARTS").isNotNull();

        JsonNode second = page(search("name", "acme", "size", "1", "cursor", next));
        assertThat(ids(second)).as("the filter still applies after the cursor").containsExactly(acmeTools);
        assertBottomWithoutNotice(second);
    }

    @Test
    @DisplayName("a state that is neither blank nor 2 characters: 400 DEM0007 on field state, no search")
    void invalidStateFilterGivesDem0007() {
        for (String state : List.of("C", " C ", "ABC")) {
            ResponseEntity<String> response = search("state", state);

            assertThat(response.getStatusCode()).as("state=<%s>: %s", state, response.getBody())
                    .isEqualTo(HttpStatus.BAD_REQUEST);
            JsonNode problem = problem(response);
            assertThat(problem.path("type").asText()).isEqualTo(PROBLEM_TYPE_PREFIX + DEM0007);
            assertThat(problem.path("status").asInt()).isEqualTo(400);
            assertThat(problem.path("code").asText()).isEqualTo(DEM0007);
            assertThat(problem.path("detail").asText()).isEqualTo(DEM0007_TEXT);
            assertThat(problem.path("instance").asText()).isEqualTo(CUSTOMERS);
            assertThat(problem.path("errors")).hasSize(1);
            JsonNode error = problem.path("errors").get(0);
            assertThat(error.path("field").asText()).isEqualTo("state");
            assertThat(error.path("code").asText()).isEqualTo(DEM0007);
            assertThat(error.path("message").asText()).isEqualTo(DEM0007_TEXT);
        }
    }

    @Test
    @DisplayName("a cursor the service did not issue: 400 APP0400 on field cursor")
    void malformedCursorGivesApp0400() {
        String notBase64 = "not-a-cursor!";
        String notJson = base64Url("hello");
        String notTheCursorShape = base64Url("{\"name\":\"ACME PARTS\"}");

        for (String cursor : List.of(notBase64, notJson, notTheCursorShape)) {
            assertApp0400(search("cursor", cursor), "cursor", "cursor is not valid");
        }
    }

    @Test
    @DisplayName("size: 1 to 100, anything else 400 APP0400 on field size")
    void sizeBounds() {
        assertApp0400(search("size", "101"), "size", "size must be between 1 and 100");
        assertApp0400(search("size", "0"), "size", "size must be between 1 and 100");

        JsonNode largest = page(search("size", "100"));
        assertThat(ids(largest)).containsExactly(acmeParts, acmeTools, betaWorks, dupFirst, dupSecond);
        assertBottomWithoutNotice(largest);
    }

    @Test
    @DisplayName("name or city longer than the 13-character screen field: 400 APP0400 on that field")
    void filterLengthOverThirteen() {
        assertApp0400(search("name", "ABCDEFGHIJKLMN"), "name", "name must be at most 13 characters");
        assertApp0400(search("city", "ABCDEFGHIJKLMN"), "city", "city must be at most 13 characters");

        assertThat(search("name", "ABCDEFGHIJKLM").getStatusCode()).as("13 characters fit SC_NAME")
                .isEqualTo(HttpStatus.OK);
        assertThat(search("city", "ABCDEFGHIJKLM").getStatusCode()).as("13 characters fit SC_CITY")
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("includeInactive that is not a boolean: 400 APP0400 on field includeInactive")
    void typeMismatch() {
        assertApp0400(search("includeInactive", "maybe"), "includeInactive", "includeInactive has an invalid value");
    }

    /**
     * Adds one customer through {@code POST /api/customers} as the maintenance user, with fixed filler
     * values for the four fields the search does not show.
     *
     * @param name   the name as keyed
     * @param city   the city as keyed
     * @param state  a 2-letter code present in {@code states}
     * @param zip    the ZIP as keyed, at most 10 characters
     * @param active {@code Y} or {@code N}
     * @return the allocated customer id
     * @throws AssertionError if the add does not answer 201 with a 4-character id
     */
    private String create(String name, String city, String state, String zip, String active) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("name", name);
        fields.put("addr", "1 MAIN ST");
        fields.put("city", city);
        fields.put("state", state);
        fields.put("zip", zip);
        fields.put("corpPhone", "(555) 555-0100");
        fields.put("acctMgr", "PAT LEE");
        fields.put("acctPhone", "(555) 555-0101");
        fields.put("active", active);

        ResponseEntity<String> response = maintenance().post().uri(CUSTOMERS)
                .contentType(MediaType.APPLICATION_JSON)
                .body(toJson(fields))
                .retrieve()
                .toEntity(String.class);

        assertThat(response.getStatusCode()).as("add %s: %s", name, response.getBody())
                .isEqualTo(HttpStatus.CREATED);
        String custId = json(response).path("custId").asText();
        assertThat(custId).as("allocated id of %s", name).matches(CUST_ID_FORMAT);
        return custId;
    }

    /**
     * Searches as the inquiry user. Each value is passed as a URI variable, so it is percent-encoded in
     * full: a blank arrives as a blank and {@code !} as {@code !}.
     *
     * @param parameters query parameter names and values, alternating; none for the default search
     * @return the response, whatever its status
     * @throws IllegalArgumentException if a name lacks its value or appears twice
     */
    private ResponseEntity<String> search(String... parameters) {
        if (parameters.length % 2 != 0) {
            throw new IllegalArgumentException("parameters must be name and value pairs");
        }
        Map<String, String> variables = new LinkedHashMap<>();
        StringJoiner template = new StringJoiner("&", CUSTOMERS + "?", "").setEmptyValue(CUSTOMERS);
        for (int i = 0; i < parameters.length; i += 2) {
            String name = parameters[i];
            if (variables.put(name, parameters[i + 1]) != null) {
                throw new IllegalArgumentException("parameter " + name + " given twice");
            }
            template.add(name + "={" + name + "}");
        }
        return inquiry().get().uri(template.toString(), variables).retrieve().toEntity(String.class);
    }

    /**
     * Asserts a 200 JSON search page with exactly the four page members and returns it.
     *
     * @param response the search response
     * @return the parsed page
     */
    private JsonNode page(ResponseEntity<String> response) {
        assertThat(response.getStatusCode()).as("search answered: %s", response.getBody())
                .isEqualTo(HttpStatus.OK);
        MediaType contentType = response.getHeaders().getContentType();
        assertThat(contentType).isNotNull();
        assertThat(MediaType.APPLICATION_JSON.isCompatibleWith(contentType))
                .as("content type %s", contentType).isTrue();
        JsonNode page = json(response);
        assertThat(fieldNames(page)).containsExactlyElementsOf(PAGE_MEMBERS);
        assertThat(page.get("items").isArray()).isTrue();
        assertThat(page.get("limitReached").isBoolean()).isTrue();
        return page;
    }

    /**
     * Asserts the bottom of a list that carries no message: no next page, cap not reached, no notice.
     *
     * @param page a parsed search page
     */
    private static void assertBottomWithoutNotice(JsonNode page) {
        assertThat(page.get("nextCursor").isNull()).as("nextCursor of %s", page).isTrue();
        assertThat(page.get("limitReached").booleanValue()).isFalse();
        assertThat(page.get("notice").isNull()).as("notice of %s", page).isTrue();
    }

    /**
     * Asserts an empty first page that carries DEM0002.
     *
     * @param page a parsed search page
     */
    private static void assertEmptyWithDem0002(JsonNode page) {
        assertThat(page.get("items")).isEmpty();
        assertThat(page.get("nextCursor").isNull()).isTrue();
        assertThat(page.get("limitReached").booleanValue()).isFalse();
        JsonNode notice = page.get("notice");
        assertThat(fieldNames(notice)).containsExactly("code", "message");
        assertThat(notice.path("code").asText()).isEqualTo(DEM0002);
        assertThat(notice.path("message").asText()).isEqualTo(DEM0002_TEXT);
    }

    /**
     * Asserts a 400 APP0400 problem naming one query parameter.
     *
     * @param response the search response
     * @param field    the parameter {@code errors[0]} must name
     * @param reason   the {@code {0}} of "Request is not valid: {0}"
     */
    private void assertApp0400(ResponseEntity<String> response, String field, String reason) {
        assertThat(response.getStatusCode()).as("%s fault: %s", field, response.getBody())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        JsonNode problem = problem(response);
        assertThat(problem.path("type").asText()).isEqualTo(PROBLEM_TYPE_PREFIX + APP0400);
        assertThat(problem.path("status").asInt()).isEqualTo(400);
        assertThat(problem.path("code").asText()).isEqualTo(APP0400);
        assertThat(problem.path("detail").asText()).isEqualTo(APP0400_PREFIX + reason);
        assertThat(problem.path("instance").asText()).isEqualTo(CUSTOMERS);
        assertThat(problem.path("errors")).hasSize(1);
        JsonNode error = problem.path("errors").get(0);
        assertThat(error.path("field").asText()).isEqualTo(field);
        assertThat(error.path("code").asText()).isEqualTo(APP0400);
        assertThat(error.path("message").asText()).isEqualTo(APP0400_PREFIX + reason);
    }

    /**
     * Returns the {@code nextCursor} of a page.
     *
     * @param page a parsed search page
     * @return the cursor, or {@code null} at the bottom of the list
     */
    private static String nextCursor(JsonNode page) {
        JsonNode cursor = page.get("nextCursor");
        if (cursor.isNull()) {
            return null;
        }
        assertThat(cursor.isTextual()).as("nextCursor is a string: %s", cursor).isTrue();
        assertThat(cursor.textValue()).isNotBlank();
        return cursor.textValue();
    }

    private static List<String> ids(JsonNode page) {
        return column(page, "custId");
    }

    private static List<String> names(JsonNode page) {
        return column(page, "name");
    }

    private static List<String> column(JsonNode page, String member) {
        List<String> values = new ArrayList<>();
        page.get("items").forEach(item -> values.add(item.path(member).asText()));
        return values;
    }

    /** Returns every item as {@code custId|name|city|state|zip5|active}, in order. */
    private static List<String> rows(JsonNode page) {
        List<String> rows = new ArrayList<>();
        for (JsonNode item : page.get("items")) {
            StringJoiner row = new StringJoiner("|");
            ITEM_MEMBERS.forEach(member -> row.add(item.path(member).asText()));
            rows.add(row.toString());
        }
        return rows;
    }

    private static List<String> fieldNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    /** Encodes UTF-8 text as base64url without padding, the cursor's own encoding. */
    private static String base64Url(String text) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(text.getBytes(StandardCharsets.UTF_8));
    }

    private String toJson(Object body) {
        try {
            return objectMapper.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("request body could not be serialized", e);
        }
    }
}

