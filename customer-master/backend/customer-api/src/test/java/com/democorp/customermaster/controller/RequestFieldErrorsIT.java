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
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * Specifies the {@code errors[]} member of 400 APP0400 and the rejection of request text that cannot be
 * stored, shown, searched or sent to the address service as it is.
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
 * <p><b>Body widths.</b> The nine text fields of review, add and update are limited to their column
 * width in characters ({@code @CodePointLength}), as {@code varchar(n)} counts them. A 40-character
 * name ending in U+1F600 (41 UTF-16 units), or 21 or 40 of U+1F600, is accepted and stored with that
 * {@code char_length}; one character more is 400 APP0400 "{field} is too long" on that field.
 *
 * <p><b>U+0000.</b> PostgreSQL text columns cannot hold NUL. Without the {@code @StorableText} check, a
 * review would echo such a value with 200 and an add would fail at the {@code INSERT} as 500 DEM9999
 * after consuming the allocated id. A NUL in any of the nine text fields of review, add and update is
 * therefore rejected with 400 APP0400 on that field before the service runs, so no id is consumed: the
 * next valid add gets {@link DatabaseCleaner#FIRST_INTERACTIVE_ID}.
 *
 * <p><b>Unpaired surrogates.</b> A JSON escape of a lone high or low surrogate binds to a Java string
 * the driver cannot encode. Without the check the driver would store {@code ?} instead, so a review would
 * answer 200 and an add 201 echoing a value other than the one stored. The same nine fields reject it as
 * they reject U+0000, while a high and a low escape in order are one supplementary character, accepted
 * and stored.
 *
 * <p><b>Control, format and separator characters, and noncharacters.</b> A 5250 field could hold none
 * of them, and they break two later uses of a value: the USPS request is an XML 1.0 document, which
 * cannot carry U+0001–U+0008, U+000B, U+000C, U+000E–U+001F, U+FFFE or U+FFFF, so a review of such an
 * address would answer 502 APP0502 on every retry with no field marked; and a bidirectional control such
 * as U+202E, or an invisible zero-width or format character, makes a stored value display and search as
 * a different string (a name stored reversed behind U+202E reads {@code ACME COMPANY}). The same nine
 * fields therefore reject a control character (TAB, LF and CR included), a format character, U+2028,
 * U+2029 and a noncharacter as they reject U+0000: 400 APP0400 on that field, nothing stored, nothing
 * sent to the address service, no id consumed and an updated row left unchanged. Nothing is stripped or
 * replaced. A no-break space, U+FFFD, a private-use character and a combining mark are still accepted
 * and stored as sent.
 *
 * <p><b>Business rules.</b> The text checks leave the field rules to {@code CustomerValidator}: a blank
 * or missing field reaches it (422 DEM0502), and an add without {@code active} stores {@code Y}.
 *
 * <p>The base context variant: no mocked beans. Bodies are serialized with the application's mapper, which
 * writes a NUL as the JSON escape {@code \u005Cu0000}.
 */
@DisplayName("APP0400 field errors and request text that cannot be stored")
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

    /** In a body map, stands for the JSON escape of the high surrogate U+D800; see {@link #withSurrogateEscapes}. */
    private static final String HIGH_SURROGATE = "~high-surrogate~";

    /** In a body map, stands for the JSON escape of the low surrogate U+DC00; see {@link #withSurrogateEscapes}. */
    private static final String LOW_SURROGATE = "~low-surrogate~";

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
    // Body widths, counted in characters
    // ---------------------------------------------------------------------------------------------

    @ParameterizedTest(name = "[{index}] {0} of {2} characters")
    @MethodSource("valuesWithinTheColumn")
    @DisplayName("add with a value within its column in characters: 201, stored as sent with that length")
    void valueWithinTheColumnIsStored(String field, String value, int characters) {
        Map<String, Object> body = validFields();
        body.put(field, value);

        ResponseEntity<String> response = post(CUSTOMERS, body);

        assertThat(response.getStatusCode()).as(response.getBody()).isEqualTo(HttpStatus.CREATED);
        String custId = json(response).path("custId").asText();
        assertThat(json(response).path(field).asText()).isEqualTo(value);
        assertThat(storedText(field, custId)).isEqualTo(value);
        assertThat(storedLength(field, custId)).isEqualTo(characters);
    }

    static Stream<Arguments> valuesWithinTheColumn() {
        return Stream.of(
                Arguments.of("name", "A".repeat(39) + EMOJI, 40),
                Arguments.of("name", EMOJI.repeat(21), 21),
                Arguments.of("name", EMOJI.repeat(40), 40),
                Arguments.of("city", EMOJI.repeat(11), 11),
                Arguments.of("city", EMOJI.repeat(20), 20));
    }

    @Test
    @DisplayName("EDIT review with a 40-character name ending in U+1F600 (41 UTF-16 units): 200, echoed")
    void fullWidthNameOnReviewIsAccepted() {
        Map<String, Object> body = reviewBody();
        body.put("purpose", "EDIT");
        String name = "A".repeat(39) + EMOJI;
        body.put("name", name);
        body.put("active", "Y");

        ResponseEntity<String> response = post(REVIEW, body);

        assertThat(response.getStatusCode()).as(response.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(json(response).path("customer").path("name").asText()).isEqualTo(name);
        assertThat(json(response).path("notice").path("code").asText()).isEqualTo("DEM0000");
        assertThat(customerCount()).isZero();
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("valuesOverTheColumn")
    @DisplayName("add with one character over the column, U+1F600 included: 400 on the field, nothing stored")
    void valueOverTheColumnIsAFieldError(String field, String value) {
        Map<String, Object> body = validFields();
        body.put(field, value);

        JsonNode problem = assertInvalid(post(CUSTOMERS, body), field + " is too long");

        assertErrors(problem, List.of(field));
        assertThat(customerCount()).isZero();
    }

    static Stream<Arguments> valuesOverTheColumn() {
        return Stream.of(
                Arguments.of("name", "A".repeat(40) + EMOJI),
                Arguments.of("name", EMOJI.repeat(41)),
                Arguments.of("city", EMOJI.repeat(20) + "A"),
                Arguments.of("city", EMOJI.repeat(21)));
    }

    @Test
    @DisplayName("review with a 41-character name ending in U+1F600: 400 name is too long")
    void overWidthNameOnReviewIsAFieldError() {
        Map<String, Object> body = reviewBody();
        body.put("name", "A".repeat(40) + EMOJI);

        JsonNode problem = assertInvalid(post(REVIEW, body), "name is too long");

        assertErrors(problem, List.of("name"));
    }

    @Test
    @DisplayName("PUT with name and city of U+1F600 at their full widths: 200, stored, version 1")
    void fullWidthValuesOnUpdateAreStored() {
        String custId = addValidCustomer();
        Map<String, Object> body = validFields();
        body.put("name", EMOJI.repeat(40));
        body.put("city", EMOJI.repeat(20));
        body.put("active", "Y");
        body.put("version", 0);

        ResponseEntity<String> response = put(custId, body);

        assertThat(response.getStatusCode()).as(response.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(json(response).path("name").asText()).isEqualTo(EMOJI.repeat(40));
        assertThat(json(response).path("version").asLong()).isEqualTo(1L);
        assertThat(storedLength("name", custId)).isEqualTo(40);
        assertThat(storedLength("city", custId)).isEqualTo(20);
    }

    @Test
    @DisplayName("PUT with a name of 41 U+1F600: 400 name is too long, the row unchanged")
    void overWidthNameOnUpdateIsAFieldError() {
        String custId = addValidCustomer();
        Map<String, Object> body = validFields();
        body.put("name", EMOJI.repeat(41));
        body.put("active", "Y");
        body.put("version", 0);

        JsonNode problem = assertInvalid(put(custId, body), "name is too long");

        assertErrors(problem, List.of("name"));
        assertThat(storedText("name", custId)).isEqualTo("ACME INC");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT row_version FROM custmast WHERE custid = ?", Long.class, custId)).isZero();
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
    // Unpaired surrogates
    // ---------------------------------------------------------------------------------------------

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("textFields")
    @DisplayName("review with an unpaired surrogate in one text field: 400 APP0400 on that field, no 200 echo")
    void unpairedSurrogateOnReviewIsRejected(String field) {
        Map<String, Object> body = reviewBody();
        body.put(field, HIGH_SURROGATE);

        JsonNode problem = assertInvalid(postJson(REVIEW, withSurrogateEscapes(body)), field + " " + NOT_STORABLE);

        assertErrors(problem, List.of(field));
        assertThat(problem.path("errors").get(0).path("message").asText())
                .isEqualTo("Request is not valid: " + field + " " + NOT_STORABLE);
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("textFields")
    @DisplayName("add with an unpaired surrogate in one text field: 400 APP0400 on that field, nothing stored")
    void unpairedSurrogateOnAddIsRejected(String field) {
        Map<String, Object> body = validFields();
        body.put(field, LOW_SURROGATE);

        JsonNode problem = assertInvalid(postJson(CUSTOMERS, withSurrogateEscapes(body)),
                field + " " + NOT_STORABLE);

        assertErrors(problem, List.of(field));
        assertThat(customerCount()).isZero();
    }

    @Test
    @DisplayName("EDIT review and add of a name with a lone high surrogate inside: 400, no id consumed")
    void unpairedSurrogateConsumesNoId() {
        Map<String, Object> review = reviewBody();
        review.put("purpose", "EDIT");
        review.put("active", "Y");
        review.put("name", "QA " + HIGH_SURROGATE + "X");
        assertErrors(assertInvalid(postJson(REVIEW, withSurrogateEscapes(review)), "name " + NOT_STORABLE),
                List.of("name"));

        Map<String, Object> add = validFields();
        add.put("name", "QA " + HIGH_SURROGATE + "X");
        assertErrors(assertInvalid(postJson(CUSTOMERS, withSurrogateEscapes(add)), "name " + NOT_STORABLE),
                List.of("name"));

        Map<String, Object> reversed = validFields();
        reversed.put("addr", "1 MAIN " + LOW_SURROGATE + HIGH_SURROGATE + " ST");
        assertErrors(assertInvalid(postJson(CUSTOMERS, withSurrogateEscapes(reversed)), "addr " + NOT_STORABLE),
                List.of("addr"));

        assertThat(customerCount()).isZero();
        assertThat(addValidCustomer()).isEqualTo(DatabaseCleaner.FIRST_INTERACTIVE_ID);
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("textFields")
    @DisplayName("PUT with an unpaired surrogate in one text field: 400 APP0400 on that field, the row unchanged")
    void unpairedSurrogateOnUpdateIsRejected(String field) {
        String custId = addValidCustomer();
        Map<String, Object> body = validFields();
        body.put(field, HIGH_SURROGATE);
        body.put("version", 0);

        JsonNode problem = assertInvalid(putJson(custId, withSurrogateEscapes(body)), field + " " + NOT_STORABLE);

        assertErrors(problem, List.of(field));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT row_version FROM custmast WHERE custid = ?", Long.class, custId)).isZero();
        assertThat(storedText("name", custId)).isEqualTo("ACME INC");
    }

    @Test
    @DisplayName("a high and a low surrogate escape in order are one character: add 201, stored as sent")
    void escapedSurrogatePairIsStored() {
        Map<String, Object> body = validFields();
        body.put("name", "QA " + HIGH_SURROGATE + LOW_SURROGATE + "X");
        String stored = "QA " + new String(Character.toChars(0x10000)) + "X";

        ResponseEntity<String> response = postJson(CUSTOMERS, withSurrogateEscapes(body));

        assertThat(response.getStatusCode()).as(response.getBody()).isEqualTo(HttpStatus.CREATED);
        String custId = json(response).path("custId").asText();
        assertThat(json(response).path("name").asText()).isEqualTo(stored);
        assertThat(storedText("name", custId)).isEqualTo(stored);
        assertThat(storedLength("name", custId)).isEqualTo(5);
    }

    // ---------------------------------------------------------------------------------------------
    // Control, format and separator characters, and noncharacters
    // ---------------------------------------------------------------------------------------------

    @ParameterizedTest(name = "[{index}] {0} with {1}")
    @MethodSource("textFieldsWithUnstorableCharacter")
    @DisplayName("review with a control, bidi, zero-width or noncharacter in one field: 400 on that field, no 200 echo")
    void unstorableCharacterOnReviewIsRejected(String field, String codePoint, String value) {
        Map<String, Object> body = reviewBody();
        body.put(field, value);

        JsonNode problem = assertInvalid(post(REVIEW, body), field + " " + NOT_STORABLE);

        assertErrors(problem, List.of(field));
        assertThat(problem.path("errors").get(0).path("message").asText())
                .isEqualTo("Request is not valid: " + field + " " + NOT_STORABLE);
        assertThat(problem.has("stateAccepted")).as(codePoint).isFalse();
        assertThat(customerCount()).isZero();
    }

    @ParameterizedTest(name = "[{index}] {0} with {1}")
    @MethodSource("textFieldsWithUnstorableCharacter")
    @DisplayName("add with a control, bidi, zero-width or noncharacter in one field: 400 on that field, no id used")
    void unstorableCharacterOnAddIsRejected(String field, String codePoint, String value) {
        Map<String, Object> body = validFields();
        body.put(field, value);

        JsonNode problem = assertInvalid(post(CUSTOMERS, body), field + " " + NOT_STORABLE);

        assertErrors(problem, List.of(field));
        assertThat(customerCount()).as(codePoint).isZero();
        assertThat(addValidCustomer()).isEqualTo(DatabaseCleaner.FIRST_INTERACTIVE_ID);
    }

    @ParameterizedTest(name = "[{index}] {0} with {1}")
    @MethodSource("textFieldsWithUnstorableCharacter")
    @DisplayName("PUT with a control, bidi, zero-width or noncharacter in one field: 400 on that field, row unchanged")
    void unstorableCharacterOnUpdateIsRejected(String field, String codePoint, String value) {
        String custId = addValidCustomer();
        Map<String, Object> before = storedRow(custId);
        Map<String, Object> body = validFields();
        body.put(field, value);
        body.put("version", 0);

        JsonNode problem = assertInvalid(put(custId, body), field + " " + NOT_STORABLE);

        assertErrors(problem, List.of(field));
        assertThat(storedRow(custId)).as(codePoint).isEqualTo(before);
        assertThat(before.get("row_version")).isEqualTo(0L);
    }

    static Stream<Arguments> textFieldsWithUnstorableCharacter() {
        // One code point each, so every value fits every column, active's 1 included.
        return TEXT_FIELDS.stream().flatMap(field -> Stream.of(
                Arguments.of(field, "U+0001", "\u0001"),
                Arguments.of(field, "U+202E", "\u202E"),
                Arguments.of(field, "U+200B", "\u200B"),
                Arguments.of(field, "U+FFFF", "\uFFFF")));
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("xmlIllegalCodePoints")
    @DisplayName("ADD review with an address no XML 1.0 request can carry: 400 on addr, never 502 APP0502")
    void xmlIllegalAddressOnReviewIsRejected(String codePoint, String character) {
        Map<String, Object> body = reviewBody();
        body.put("addr", "1 A" + character + "B RD");

        JsonNode problem = assertInvalid(post(REVIEW, body), "addr " + NOT_STORABLE);

        assertErrors(problem, List.of("addr"));
        assertThat(problem.has("stateAccepted")).as(codePoint).isFalse();
        assertThat(customerCount()).isZero();
    }

    static Stream<Arguments> xmlIllegalCodePoints() {
        return Stream.of(
                Arguments.of("U+0001", "\u0001"),
                Arguments.of("U+0008", "\u0008"),
                Arguments.of("U+000B", "\u000B"),
                Arguments.of("U+000C", "\u000C"),
                Arguments.of("U+000E", "\u000E"),
                Arguments.of("U+001F", "\u001F"),
                Arguments.of("U+FFFE", "\uFFFE"),
                Arguments.of("U+FFFF", "\uFFFF"));
    }

    @Test
    @DisplayName("add of reversed name, city and account manager behind U+202E: 400 naming the three, nothing stored")
    void bidiOverrideOnAddIsRejected() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", "\u202EYNAPMOC EMCA");
        body.put("addr", "1 MAIN ST");
        body.put("city", "\u202ESELEGNA SOL");
        body.put("state", "CA");
        body.put("zip", "90001");
        body.put("corpPhone", "1");
        body.put("acctMgr", "\u202EHTIMS ENAJ");
        body.put("acctPhone", "2");
        body.put("active", "Y");

        JsonNode problem = assertInvalid(post(CUSTOMERS, body), "acctMgr " + NOT_STORABLE);

        assertErrors(problem, List.of("acctMgr", "city", "name"));
        assertThat(customerCount()).isZero();
        assertThat(addValidCustomer()).isEqualTo(DatabaseCleaner.FIRST_INTERACTIVE_ID);
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("unstorableInsideAValue")
    @DisplayName("add and EDIT review with a control, separator or format character inside name: 400 on name")
    void unstorableCharacterInsideAValueIsRejected(String character, String value) {
        Map<String, Object> add = validFields();
        add.put("name", value);
        assertErrors(assertInvalid(post(CUSTOMERS, add), "name " + NOT_STORABLE), List.of("name"));

        Map<String, Object> review = reviewBody();
        review.put("purpose", "EDIT");
        review.put("active", "Y");
        review.put("name", value);
        assertErrors(assertInvalid(post(REVIEW, review), "name " + NOT_STORABLE), List.of("name"));

        assertThat(customerCount()).as(character).isZero();
        assertThat(addValidCustomer()).isEqualTo(DatabaseCleaner.FIRST_INTERACTIVE_ID);
    }

    static Stream<Arguments> unstorableInsideAValue() {
        return Stream.of(
                Arguments.of("TAB", "ACME\tINC"),
                Arguments.of("LF", "ACME\nINC"),
                Arguments.of("CR", "ACME\rINC"),
                Arguments.of("CR LF", "ACME\r\nINC"),
                Arguments.of("BEL U+0007", "ACME\u0007INC"),
                Arguments.of("DEL U+007F", "ACME\u007FINC"),
                Arguments.of("NEL U+0085", "ACME\u0085INC"),
                Arguments.of("U+2028", "ACME\u2028INC"),
                Arguments.of("U+2029", "ACME\u2029INC"),
                Arguments.of("U+00AD", "AC\u00ADME INC"),
                Arguments.of("U+2066", "ACME \u2066INC\u2069"),
                Arguments.of("U+FEFF", "\uFEFFACME INC"),
                Arguments.of("U+E0001", "ACME INC" + new String(Character.toChars(0xE0001))),
                Arguments.of("U+1FFFE", "ACME INC" + new String(Character.toChars(0x1FFFE))),
                Arguments.of("U+FDD0", "ACME\uFDD0INC"),
                Arguments.of("trailing TAB", "ACME INC\t"));
    }

    @Test
    @DisplayName("add with a no-break space, U+FFFD, a private-use character and a combining mark: 201, stored as sent")
    void storableUnusualCharactersAreStored() {
        Map<String, Object> body = validFields();
        String name = "N\u00A0A \uFFFD \uE000 E\u0301 " + EMOJI;
        body.put("name", name);

        ResponseEntity<String> response = post(CUSTOMERS, body);

        assertThat(response.getStatusCode()).as(response.getBody()).isEqualTo(HttpStatus.CREATED);
        String custId = json(response).path("custId").asText();
        assertThat(json(response).path("name").asText()).isEqualTo(name);
        assertThat(storedText("name", custId)).isEqualTo(name);
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
     * Serializes a body, then writes each {@link #HIGH_SURROGATE} as the JSON escape of U+D800 and each
     * {@link #LOW_SURROGATE} as that of U+DC00. A Java string holding an unpaired surrogate cannot be
     * sent as it is, because the client's UTF-8 encoder would replace it with {@code ?}; the escapes
     * reach the server as a client writes them and bind to the surrogates themselves.
     *
     * @param body the body, with the placeholders in its values
     * @return the JSON text
     */
    private String withSurrogateEscapes(Map<String, Object> body) {
        return toJson(body).replace(HIGH_SURROGATE, "\\ud800").replace(LOW_SURROGATE, "\\udc00");
    }

    /**
     * Posts JSON text as the maintenance user.
     *
     * @param path the request path
     * @param json the body, sent as given
     * @return the response, any status
     */
    private ResponseEntity<String> postJson(String path, String json) {
        return maintenanceXhr().post().uri(path).contentType(MediaType.APPLICATION_JSON).body(json)
                .retrieve().toEntity(String.class);
    }

    /**
     * Puts JSON text to one customer as the maintenance user.
     *
     * @param custId the path id, sent as given
     * @param json the body, sent as given
     * @return the response, any status
     */
    private ResponseEntity<String> putJson(String custId, String json) {
        return maintenanceXhr().put().uri(CUSTOMERS + "/" + custId).contentType(MediaType.APPLICATION_JSON)
                .body(json).retrieve().toEntity(String.class);
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

    /**
     * Reads one stored text column of a customer.
     *
     * @param field the JSON property, {@code name} or {@code city}
     * @param custId the customer id
     * @return the stored value
     */
    private String storedText(String field, String custId) {
        return jdbcTemplate.queryForObject("SELECT " + column(field) + " FROM custmast WHERE custid = ?",
                String.class, custId);
    }

    /**
     * Reads every data, stamp and version column of one stored customer.
     *
     * @param custId the customer id
     * @return the row, keyed by column name
     */
    private Map<String, Object> storedRow(String custId) {
        return jdbcTemplate.queryForMap("SELECT name, addr, city, state, zip, corpphone, acctmgr, acctphone,"
                + " active, chgtime, chguser, row_version FROM custmast WHERE custid = ?", custId);
    }

    /**
     * Reads the length of one stored text column of a customer, in characters as PostgreSQL counts them.
     *
     * @param field the JSON property, {@code name} or {@code city}
     * @param custId the customer id
     * @return {@code char_length} of the stored value
     */
    private Integer storedLength(String field, String custId) {
        return jdbcTemplate.queryForObject(
                "SELECT char_length(" + column(field) + ") FROM custmast WHERE custid = ?", Integer.class, custId);
    }

    /**
     * Maps a JSON property to its {@code custmast} column.
     *
     * @param field {@code name} or {@code city}, whose columns have the same names
     * @return the column name
     * @throws IllegalArgumentException for any other property
     */
    private static String column(String field) {
        return switch (field) {
            case "name", "city" -> field;
            default -> throw new IllegalArgumentException("No column mapped for " + field);
        };
    }
}
