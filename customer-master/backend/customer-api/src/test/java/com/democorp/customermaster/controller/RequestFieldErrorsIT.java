package com.democorp.customermaster.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.democorp.customermaster.support.AbstractPostgresIT;
import com.democorp.customermaster.support.DatabaseCleaner;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * Specifies the {@code errors[]} member of 400 APP0400 and the rejection of text PostgreSQL cannot store.
 *
 * <p><b>Field errors.</b> When a request fails bean or method validation, or binds a value of the wrong
 * type, on a JSON property or a query or path parameter, the problem keeps its {@code detail}
 * "Request is not valid: {0}" and {@code args}, and adds one {@code {field, code, message}} item per
 * property or parameter at fault, all with code APP0400 and the reason of that field as message. The
 * first item is the field the {@code detail} names, so the shared presenter highlights it and moves the
 * focus there (error model: "the first entry receives focus"). Failures that name no field (malformed
 * JSON, an unknown property, a media-type failure) carry no {@code errors} member at all.
 *
 * <p><b>Filter widths.</b> The search filters {@code name} and {@code city} (13 characters) and the
 * state list's {@code nameContains} (10 characters) carry no Bean Validation {@code @Size}, which
 * would count UTF-16 units. {@code CustomerSearchService} and {@code StateService} count code points
 * and reject a longer entry with the same APP0400 shape, reason "{field} must be at most {n}
 * characters", so an entry of supplementary characters at the width is accepted and one more is not.
 *
 * <p><b>U+0000.</b> PostgreSQL text columns cannot hold NUL. Before {@code @StorableText}, a review
 * echoed such a value with 200 and an add failed at the {@code INSERT} as 500 DEM9999 after consuming
 * the allocated id. Each of the nine text fields of review, add and update is now rejected with 400
 * APP0400 on that field before the service runs, so no id is consumed: the next valid add still gets
 * {@link DatabaseCleaner#FIRST_INTERACTIVE_ID}.
 *
 * <p><b>Unchanged business rules.</b> A blank or missing field still reaches {@code CustomerValidator}
 * (422 DEM0502), and an add without {@code active} still stores {@code Y}.
 *
 * <p>The base context variant: no mocked beans. Bodies are serialized with the application's mapper, which
 * writes a NUL as the JSON escape {@code \u005Cu0000}.
 */
@DisplayName("APP0400 field errors and text that PostgreSQL cannot store")
class RequestFieldErrorsIT extends AbstractPostgresIT {

    /** The collection path. */
    private static final String CUSTOMERS = "/api/customers";

    /** The review path. */
    private static final String REVIEW = "/api/customers/review";

    /** The nine text fields of every customer request body, in JSON order. */
    private static final List<String> TEXT_FIELDS = List.of("name", "addr", "city", "state", "zip",
            "corpPhone", "acctMgr", "acctPhone", "active");

    /** A one-character value made of U+0000, within every column size, {@code active}'s 1 included. */
    private static final String NUL = "\0";

    /** The phrase of a {@code @StorableText} violation. */
    private static final String NOT_STORABLE = "contains a character that cannot be stored";

    /** U+1F600, one code point held in two UTF-16 units; the client encodes it as UTF-8 in the query. */
    private static final String EMOJI = new String(Character.toChars(0x1F600));

    /** The reason {@code CustomerSearchService} gives for a {@code name} filter over 13 code points. */
    private static final String SEARCH_FILTER_TOO_LONG = "name must be at most 13 characters";

    /** The reason {@code StateService} gives for a {@code nameContains} over 10 code points. */
    private static final String STATE_FILTER_TOO_LONG = "nameContains must be at most 10 characters";

    // ---------------------------------------------------------------------------------------------
    // errors[] for identifiable fields
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("a 41-character name on add: errors[0] is name with the detail's reason")
    void tooLongNameOnAddIsAFieldError() {
        Map<String, Object> body = validFields();
        body.put("name", "N".repeat(41));

        ResponseEntity<String> response = post(CUSTOMERS, body);

        JsonNode problem = assertInvalid(response, "name is too long");
        assertErrors(problem, List.of("name"));
        assertThat(problem.path("errors").get(0).path("code").asText()).isEqualTo("APP0400");
        assertThat(problem.path("errors").get(0).path("message").asText())
                .isEqualTo("Request is not valid: name is too long");
        assertThat(customerCount()).isZero();
    }

    @Test
    @DisplayName("a 41-character name on review: the same field error")
    void tooLongNameOnReviewIsAFieldError() {
        Map<String, Object> body = reviewBody();
        body.put("name", "N".repeat(41));

        JsonNode problem = assertInvalid(post(REVIEW, body), "name is too long");

        assertErrors(problem, List.of("name"));
    }

    @Test
    @DisplayName("several too-long fields: one item each, in property order, the detail's field first")
    void severalTooLongFieldsAreOneItemEach() {
        Map<String, Object> body = validFields();
        body.put("name", "N".repeat(41));
        body.put("city", "C".repeat(21));
        body.put("addr", "A".repeat(41));

        JsonNode problem = assertInvalid(post(CUSTOMERS, body), "addr is too long");

        assertErrors(problem, List.of("addr", "city", "name"));
        assertThat(problem.path("errors").get(1).path("message").asText())
                .isEqualTo("Request is not valid: city is too long");
        assertThat(problem.path("errors").get(2).path("message").asText())
                .isEqualTo("Request is not valid: name is too long");
    }

    @Test
    @DisplayName("a value both too long and with NUL: one item on that field, phrased as the detail")
    void oneItemPerFieldWithTwoViolations() {
        Map<String, Object> body = validFields();
        body.put("name", "N".repeat(40) + NUL);

        JsonNode problem = assertInvalid(post(CUSTOMERS, body), "name is too long");

        assertErrors(problem, List.of("name"));
        assertThat(problem.path("errors").get(0).path("message").asText())
                .isEqualTo("Request is not valid: name is too long");
    }

    @Test
    @DisplayName("PUT without version: errors[0] is version")
    void missingVersionIsAFieldError() {
        String custId = addValidCustomer();
        Map<String, Object> body = validFields();

        JsonNode problem = assertInvalid(put(custId, body), "version is required");

        assertErrors(problem, List.of("version"));
        assertThat(problem.path("errors").get(0).path("message").asText())
                .isEqualTo("Request is not valid: version is required");
    }

    @Test
    @DisplayName("PUT with a version of the wrong type: errors[0] is version")
    void wronglyTypedVersionIsAFieldError() {
        String custId = addValidCustomer();
        Map<String, Object> body = validFields();
        body.put("version", "abc");

        JsonNode problem = assertInvalid(put(custId, body), "version has an invalid value");

        assertErrors(problem, List.of("version"));
    }

    @Test
    @DisplayName("PUT with a bad path id and a too-long name: custId first, as the detail, then name")
    void pathAndBodyFailuresAreBothFieldErrors() {
        Map<String, Object> body = validFields();
        body.put("name", "N".repeat(41));
        body.put("version", 0);

        JsonNode problem = assertInvalid(put("abc", body), "custId has an invalid format");

        assertErrors(problem, List.of("custId", "name"));
    }

    @Test
    @DisplayName("GET with a bad path id: errors[0] is custId")
    void badPathIdIsAFieldError() {
        ResponseEntity<String> response = inquiryXhr().get().uri(CUSTOMERS + "/abc")
                .retrieve().toEntity(String.class);

        JsonNode problem = assertInvalid(response, "custId has an invalid format");

        assertErrors(problem, List.of("custId"));
        assertThat(problem.path("errors").get(0).path("message").asText())
                .isEqualTo("Request is not valid: custId has an invalid format");
    }

    @Test
    @DisplayName("search with size=abc: errors[0] is size")
    void wronglyTypedQueryParameterIsAFieldError() {
        ResponseEntity<String> response = inquiryXhr().get().uri(CUSTOMERS + "?size=abc")
                .retrieve().toEntity(String.class);

        JsonNode problem = assertInvalid(response, "size has an invalid value");

        assertErrors(problem, List.of("size"));
    }

    @Test
    @DisplayName("search with a 14-character name filter: errors[0] is name; 13 of U+1F600 pass")
    void tooLongSearchFilterIsAFieldError() {
        ResponseEntity<String> response = inquiryXhr().get().uri(CUSTOMERS + "?name=ABCDEFGHIJKLMN")
                .retrieve().toEntity(String.class);

        JsonNode problem = assertInvalid(response, SEARCH_FILTER_TOO_LONG);

        assertErrors(problem, List.of("name"));

        ResponseEntity<String> atWidth = inquiryXhr().get()
                .uri(CUSTOMERS + "?name={name}", EMOJI.repeat(13))
                .retrieve().toEntity(String.class);
        assertThat(atWidth.getStatusCode()).as(atWidth.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(json(atWidth).path("items").isArray()).as(atWidth.getBody()).isTrue();
        assertThat(json(atWidth).path("items")).isEmpty();
        assertThat(json(atWidth).path("notice").path("code").asText()).isEqualTo("DEM0002");

        ResponseEntity<String> overWidth = inquiryXhr().get()
                .uri(CUSTOMERS + "?name={name}", EMOJI.repeat(14))
                .retrieve().toEntity(String.class);
        assertErrors(assertInvalid(overWidth, SEARCH_FILTER_TOO_LONG), List.of("name"));
    }

    @Test
    @DisplayName("state list, 11-character nameContains: errors[0] is nameContains; 10 of U+1F600 pass")
    void tooLongStateFilterIsAFieldError() {
        ResponseEntity<String> response = inquiryXhr().get().uri("/api/states?nameContains=ABCDEFGHIJK")
                .retrieve().toEntity(String.class);

        JsonNode problem = assertInvalid(response, STATE_FILTER_TOO_LONG);

        assertErrors(problem, List.of("nameContains"));

        ResponseEntity<String> atWidth = inquiryXhr().get()
                .uri("/api/states?nameContains={nameContains}", EMOJI.repeat(10))
                .retrieve().toEntity(String.class);
        assertThat(atWidth.getStatusCode()).as(atWidth.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(json(atWidth).isArray()).as(atWidth.getBody()).isTrue();
        assertThat(json(atWidth)).isEmpty();

        ResponseEntity<String> overWidth = inquiryXhr().get()
                .uri("/api/states?nameContains={nameContains}", EMOJI.repeat(11))
                .retrieve().toEntity(String.class);
        assertErrors(assertInvalid(overWidth, STATE_FILTER_TOO_LONG), List.of("nameContains"));
    }

    // ---------------------------------------------------------------------------------------------
    // No invented fields
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("malformed JSON: APP0400 with no errors member")
    void malformedJsonHasNoErrors() {
        ResponseEntity<String> response = maintenanceXhr().post().uri(CUSTOMERS)
                .contentType(MediaType.APPLICATION_JSON).body("{\"name\":").retrieve().toEntity(String.class);

        JsonNode problem = assertInvalid(response, "malformed request body");

        assertThat(problem.has("errors")).isFalse();
    }

    @Test
    @DisplayName("an unknown property: APP0400 naming it, with no errors member")
    void unknownPropertyHasNoErrors() {
        Map<String, Object> body = validFields();
        body.put("custId", "EEEF");

        JsonNode problem = assertInvalid(post(CUSTOMERS, body), "unknown property custId");

        assertThat(problem.has("errors")).isFalse();
        assertThat(customerCount()).isZero();
    }

    @Test
    @DisplayName("415 keeps its status and its Accept header, with no errors member")
    void unsupportedMediaTypeKeepsHeadersAndHasNoErrors() {
        ResponseEntity<String> response = maintenanceXhr().post().uri(CUSTOMERS)
                .contentType(MediaType.TEXT_PLAIN).body("name").retrieve().toEntity(String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
        JsonNode problem = problem(response);
        assertThat(problem.path("code").asText()).isEqualTo("APP0400");
        assertThat(problem.path("detail").asText()).isEqualTo("Request is not valid: unsupported media type");
        assertThat(problem.has("errors")).isFalse();
        assertThat(response.getHeaders().getFirst(HttpHeaders.ACCEPT)).contains("application/json");
    }

    // ---------------------------------------------------------------------------------------------
    // U+0000
    // ---------------------------------------------------------------------------------------------

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("textFields")
    @DisplayName("review with NUL in one text field: 400 APP0400 on that field, no 200 echo")
    void nulOnReviewIsRejected(String field) {
        Map<String, Object> body = reviewBody();
        body.put(field, NUL);

        JsonNode problem = assertInvalid(post(REVIEW, body), field + " " + NOT_STORABLE);

        assertErrors(problem, List.of(field));
        assertThat(problem.path("errors").get(0).path("message").asText())
                .isEqualTo("Request is not valid: " + field + " " + NOT_STORABLE);
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("textFields")
    @DisplayName("add with NUL in one text field: 400 APP0400 on that field, nothing stored")
    void nulOnAddIsRejected(String field) {
        Map<String, Object> body = validFields();
        body.put(field, NUL);

        JsonNode problem = assertInvalid(post(CUSTOMERS, body), field + " " + NOT_STORABLE);

        assertErrors(problem, List.of(field));
        assertThat(customerCount()).isZero();
    }

    @Test
    @DisplayName("add with NUL inside name, then in addr: no id is consumed, the next add gets EEEF")
    void nulOnAddConsumesNoId() {
        Map<String, Object> withNulName = validFields();
        withNulName.put("name", "AB" + NUL + "C");
        assertErrors(assertInvalid(post(CUSTOMERS, withNulName), "name " + NOT_STORABLE), List.of("name"));

        Map<String, Object> withNulAddr = validFields();
        withNulAddr.put("addr", "1 MAIN" + NUL + " ST");
        assertErrors(assertInvalid(post(CUSTOMERS, withNulAddr), "addr " + NOT_STORABLE), List.of("addr"));

        assertThat(customerCount()).isZero();
        assertThat(addValidCustomer()).isEqualTo(DatabaseCleaner.FIRST_INTERACTIVE_ID);
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("textFields")
    @DisplayName("PUT with NUL in one text field: 400 APP0400 on that field, the row unchanged")
    void nulOnUpdateIsRejected(String field) {
        String custId = addValidCustomer();
        Map<String, Object> body = validFields();
        body.put(field, NUL);
        body.put("version", 0);

        JsonNode problem = assertInvalid(put(custId, body), field + " " + NOT_STORABLE);

        assertErrors(problem, List.of(field));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT row_version FROM custmast WHERE custid = ?", Long.class, custId)).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT name FROM custmast WHERE custid = ?", String.class, custId)).isEqualTo("ACME INC");
    }

    static Stream<String> textFields() {
        return TEXT_FIELDS.stream();
    }

    // ---------------------------------------------------------------------------------------------
    // Business rules unchanged
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("a blank name still reaches the field rules: 422 DEM0502 on name")
    void blankNameIsStillABusinessRuleFailure() {
        Map<String, Object> body = validFields();
        body.put("name", "");

        ResponseEntity<String> response = post(CUSTOMERS, body);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        JsonNode problem = problem(response);
        assertThat(problem.path("code").asText()).isEqualTo("DEM0502");
        assertThat(problem.path("errors").get(0).path("field").asText()).isEqualTo("name");
    }

    @Test
    @DisplayName("a missing name still reaches the field rules: 422 DEM0502 on name")
    void missingNameIsStillABusinessRuleFailure() {
        Map<String, Object> body = validFields();
        body.remove("name");

        ResponseEntity<String> response = post(CUSTOMERS, body);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        JsonNode problem = problem(response);
        assertThat(problem.path("code").asText()).isEqualTo("DEM0502");
        assertThat(problem.path("errors").get(0).path("field").asText()).isEqualTo("name");
    }

    @Test
    @DisplayName("an add without active still stores Y")
    void addWithoutActiveStillDefaultsToY() {
        String custId = addValidCustomer();

        assertThat(custId).isEqualTo(DatabaseCleaner.FIRST_INTERACTIVE_ID);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT active FROM custmast WHERE custid = ?", String.class, custId)).isEqualTo("Y");
    }

    // ---------------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------------

    /**
     * Returns an add body that passes every rule, without {@code active}, so the add default applies.
     *
     * @return a new mutable map in JSON order
     */
    private static Map<String, Object> validFields() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", "ACME INC");
        body.put("addr", "1 MAIN ST");
        body.put("city", "AUBURN");
        body.put("state", "ME");
        body.put("zip", "04210");
        body.put("corpPhone", "(207) 555-0100");
        body.put("acctMgr", "JANE DOE");
        body.put("acctPhone", "(207) 555-0101");
        return body;
    }

    /**
     * Returns an ADD review body that passes every rule.
     *
     * @return a new mutable map, {@code purpose} first
     */
    private static Map<String, Object> reviewBody() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("purpose", "ADD");
        body.putAll(validFields());
        return body;
    }

    /**
     * Adds {@link #validFields()} and returns the new id.
     *
     * @return the allocated customer id
     */
    private String addValidCustomer() {
        ResponseEntity<String> response = post(CUSTOMERS, validFields());
        assertThat(response.getStatusCode()).as(response.getBody()).isEqualTo(HttpStatus.CREATED);
        return json(response).path("custId").asText();
    }

    /**
     * Posts a JSON body as the maintenance user.
     *
     * @param path the request path
     * @param body the body, serialized with the application's mapper
     * @return the response, any status
     */
    private ResponseEntity<String> post(String path, Map<String, Object> body) {
        return maintenanceXhr().post().uri(path).contentType(MediaType.APPLICATION_JSON).body(toJson(body))
                .retrieve().toEntity(String.class);
    }

    /**
     * Puts a JSON body to one customer as the maintenance user.
     *
     * @param custId the path id, sent as given
     * @param body the body, serialized with the application's mapper
     * @return the response, any status
     */
    private ResponseEntity<String> put(String custId, Map<String, Object> body) {
        return maintenanceXhr().put().uri(CUSTOMERS + "/" + custId).contentType(MediaType.APPLICATION_JSON)
                .body(toJson(body)).retrieve().toEntity(String.class);
    }

    /**
     * Serializes a body; a NUL becomes the JSON escape for U+0000.
     *
     * @param body the body
     * @return the JSON text
     */
    private String toJson(Map<String, Object> body) {
        try {
            return objectMapper.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new AssertionError("Cannot serialize " + body.keySet(), e);
        }
    }

    /**
     * Asserts a 400 APP0400 problem whose {@code detail} and {@code args} carry the given reason.
     *
     * @param response the response
     * @param reason the expected {@code {0}}
     * @return the problem body
     */
    private JsonNode assertInvalid(ResponseEntity<String> response, String reason) {
        assertThat(response.getStatusCode()).as(response.getBody()).isEqualTo(HttpStatus.BAD_REQUEST);
        JsonNode problem = problem(response);
        assertThat(problem.path("code").asText()).isEqualTo("APP0400");
        assertThat(problem.path("detail").asText()).isEqualTo("Request is not valid: " + reason);
        assertThat(problem.path("args")).hasSize(1);
        assertThat(problem.path("args").get(0).asText()).isEqualTo(reason);
        return problem;
    }

    /**
     * Asserts the {@code errors[]} fields, in order, each item a complete APP0400 entry.
     *
     * @param problem the problem body
     * @param fields the expected fields, the first being the one the detail names
     */
    private static void assertErrors(JsonNode problem, List<String> fields) {
        JsonNode errors = problem.path("errors");
        assertThat(errors.isArray()).as(problem.toString()).isTrue();
        List<String> actual = new ArrayList<>();
        for (JsonNode error : errors) {
            actual.add(error.path("field").asText());
            assertThat(error.path("code").asText()).isEqualTo("APP0400");
            assertThat(error.path("message").asText()).startsWith("Request is not valid: "
                    + error.path("field").asText() + " ");
        }
        assertThat(actual).containsExactlyElementsOf(fields);
        assertThat(problem.path("detail").asText()).isEqualTo(errors.get(0).path("message").asText());
    }

    /**
     * Counts the stored customers.
     *
     * @return the row count of {@code custmast}
     */
    private int customerCount() {
        Integer count = jdbcTemplate.queryForObject("SELECT count(*) FROM custmast", Integer.class);
        return count == null ? 0 : count;
    }
}
