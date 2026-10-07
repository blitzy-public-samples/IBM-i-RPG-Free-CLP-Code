package com.democorp.customermaster.controller;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import com.democorp.customermaster.support.AbstractPostgresIT;
import com.democorp.customermaster.support.DatabaseCleaner;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * Proves that the three maintenance request bodies bind strictly, through the
 * {@code spring.jackson} settings of {@code application.yml}, {@code config.StrictJsonBindingConfig} and
 * the {@code PurposeDeserializer} of {@code ReviewRequest}: a {@code version} that is not an exact
 * integral JSON number, a {@code purpose} that is not exactly the JSON string {@code "ADD"} or
 * {@code "EDIT"}, a JSON number or boolean for any of the nine text fields, any content after the
 * body's JSON object and a member given twice are rejected with 400 APP0400 before a service runs, so
 * no row changes and no customer id is consumed. A text field sent with the wrong JSON type is named by
 * the {@code detail} "Request is not valid: {field} has an invalid value" and by the one
 * {@code errors[]} entry; a repeated member is "Request is not valid: malformed request body", with no
 * {@code errors} member and no ERROR log line.
 *
 * <p><b>Why each case matters.</b> Jackson's defaults would truncate {@code 0.9} to version 0, read
 * {@code "0"} as 0 and {@code 1e0} as 1, bind {@code 0}, {@code 1}, {@code "0"} and {@code "1"} as the
 * {@code ADD} and {@code EDIT} ordinals, trim {@code " ADD"} and {@code "\tEDIT"} to a constant name,
 * bind a number or boolean sent for a text field as its text ({@code name 123} stored as {@code "123"}),
 * and ignore whatever follows the first JSON value. They would also keep the last value of a member
 * repeated before the record is complete, and fail a member repeated after it, which no record component
 * can take, with 500 DEM9999 and an ERROR stack trace. The PUT cases
 * therefore store the very version each malformed value would coerce to, so a lenient binder would turn
 * the request into a successful update rather than a 409.
 *
 * <p><b>Controls.</b> The same bodies with an integral version or a valid purpose and no trailing
 * content succeed (200, 201, DEM0000, DEM0009), a stale integral version still answers 409 DEM1002, and
 * a missing version, a null or missing purpose and an unknown property still answer 400 APP0400. A
 * {@code name} sent as JSON {@code null} or as the empty string still reaches the field rules (422
 * DEM0502), an {@code ADD} review with a {@code null} {@code active} still defaults it to {@code Y}, and
 * digits sent as a JSON string still bind as text.
 *
 * <p>The class runs in the base context of {@link AbstractPostgresIT}: it declares no
 * {@code @MockitoBean} and no {@code @Import}, so it adds no context variant; console output is captured
 * by {@link OutputCaptureExtension}, a JUnit extension that leaves the context untouched. Every test
 * starts from an empty {@code custmast} and next id {@value DatabaseCleaner#FIRST_INTERACTIVE_ID}.
 */
@ExtendWith(OutputCaptureExtension.class)
class StrictRequestBindingIT extends AbstractPostgresIT {

    /** The customer the PUT cases change. */
    private static final String CUST_ID = "AAAB";

    /** The maintenance base path. */
    private static final String CUSTOMERS = "/api/customers";

    /** The review endpoint. */
    private static final String REVIEW = CUSTOMERS + "/review";

    /** The update endpoint of {@link #CUST_ID}. */
    private static final String CUSTOMER = CUSTOMERS + "/" + CUST_ID;

    /** The catalog key of every request-shape failure. */
    private static final String APP0400 = "APP0400";

    /** The APP0400 reason of a body that cannot be read, a member given twice included; it names no field. */
    private static final String MALFORMED_BODY = "malformed request body";

    /**
     * {@link #FIELDS_WITHOUT_ACTIVE} without {@code name}: the seven members from {@code addr} to
     * {@code acctPhone}.
     */
    private static final String FIELDS_WITHOUT_ACTIVE_AND_NAME = "\"addr\":\"2 elm st\","
            + "\"city\":\"shelbyville\",\"state\":\"CA\",\"zip\":\"90211\",\"corpPhone\":\"(555) 555-0110\","
            + "\"acctMgr\":\"doe, john\",\"acctPhone\":\"(555) 555-0111\"";

    /**
     * Eight of the nine data fields as JSON members, without {@code active}, all valid and all different
     * from the stored row, so an update that wrongly ran would be visible in every column.
     */
    private static final String FIELDS_WITHOUT_ACTIVE =
            "\"name\":\"changed name\"," + FIELDS_WITHOUT_ACTIVE_AND_NAME;

    /** All nine data fields as JSON members. */
    private static final String FIELDS = FIELDS_WITHOUT_ACTIVE + ",\"active\":\"Y\"";

    /** Columns compared before and after a rejected request: every column of {@code custmast}. */
    private static final String ROW_QUERY = "SELECT custid, name, addr, city, state, zip, corpphone, acctmgr,"
            + " acctphone, active, chgtime, chguser, row_version FROM custmast WHERE custid = ?";

    /** The nine text fields of every customer request body, in JSON order. */
    private static final List<String> TEXT_FIELDS = List.of("name", "addr", "city", "state", "zip",
            "corpPhone", "acctMgr", "acctPhone", "active");

    /**
     * JSON numbers and booleans sent for a text field: integral, zero, negative, fraction, exponent and
     * both booleans. A lenient binder would store each as its text, {@code 1e3} as {@code "1e3"}.
     */
    private static final List<String> NON_TEXT_SCALARS = List.of("123", "0", "-1", "1.5", "1e3", "true", "false");

    /** The only members of a 400 APP0400 problem, so none can carry the value the client sent. */
    private static final List<String> APP0400_MEMBERS =
            List.of("type", "title", "status", "detail", "instance", "code", "args", "errors");

    /**
     * A PUT whose version is a fraction, an exponent, a string or a boolean is rejected with 400 APP0400
     * and leaves the row as stored, although each value would coerce to the stored version.
     *
     * @param storedVersion the {@code row_version} of the stored row
     * @param versionJson   the JSON text sent as {@code version}
     */
    @ParameterizedTest(name = "stored {0}, version {1}")
    @CsvSource(delimiter = '|', value = {
        "0|0.9",
        "1|1.0",
        "1|1e0",
        "1|1.9",
        "0|\"0\"",
        "5|\"5\"",
        "0|\"\"",
        "1|true",
        "0|false"
    })
    void putRejectsAVersionThatIsNotAnExactIntegralNumber(long storedVersion, String versionJson) {
        insertCustomer(storedVersion);
        Map<String, Object> before = storedRow();

        ResponseEntity<String> response = put("{" + FIELDS + ",\"version\":" + versionJson + "}");

        assertApp0400(response);
        assertThat(storedRow()).isEqualTo(before);
    }

    /**
     * A PUT without {@code version}, or with {@code null}, is rejected with 400 APP0400 and changes nothing.
     *
     * @param versionMember the {@code version} member as sent, empty for none
     */
    @ParameterizedTest(name = "version member [{0}]")
    @ValueSource(strings = {"", ",\"version\":null"})
    void putRejectsAMissingVersion(String versionMember) {
        insertCustomer(0);
        Map<String, Object> before = storedRow();

        ResponseEntity<String> response = put("{" + FIELDS + versionMember + "}");

        assertApp0400(response);
        assertThat(storedRow()).isEqualTo(before);
    }

    /** A PUT with a stale integral version still answers 409 DEM1002 with the stored row and changes nothing. */
    @Test
    void putWithAStaleIntegralVersionAnswersDem1002() {
        insertCustomer(1);
        Map<String, Object> before = storedRow();

        ResponseEntity<String> response = put("{" + FIELDS + ",\"version\":0}");

        assertThat(response.getStatusCode().value()).isEqualTo(409);
        JsonNode problem = problem(response);
        assertThat(problem.path("code").asText()).isEqualTo("DEM1002");
        assertThat(problem.path("current").path("version").asLong()).isEqualTo(1L);
        assertThat(storedRow()).isEqualTo(before);
    }

    /** A PUT with the stored integral version succeeds and the stored version becomes {@code version + 1}. */
    @Test
    void putWithTheStoredIntegralVersionUpdates() {
        insertCustomer(0);

        ResponseEntity<String> response = put("{" + FIELDS + ",\"version\":0}");

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(json(response).path("version").asLong()).isEqualTo(1L);
        assertThat(json(response).path("name").asText()).isEqualTo("CHANGED NAME");
        assertThat(storedRow()).containsEntry("row_version", 1L).containsEntry("name", "CHANGED NAME");
    }

    /**
     * A review whose purpose is a number, a quoted index, a fraction, a boolean, an object, an array, a
     * name in another case, a name with a leading or trailing blank, tab or line break, an empty string or
     * an unknown name is rejected with 400 APP0400 on {@code purpose}. The tab and line break are sent as
     * the JSON escapes {@code \t} and {@code \n}, so the body is valid JSON and only the binding rejects it.
     *
     * @param purposeJson the JSON text sent as {@code purpose}
     */
    @ParameterizedTest(name = "purpose {0}")
    @ValueSource(strings = {"0", "1", "\"0\"", "\"1\"", "1.5", "true", "{}", "[\"ADD\"]", "\"add\"", "\"edit\"",
        "\"Add\"", "\" ADD\"", "\"ADD \"", "\"\\tEDIT\"", "\"EDIT\\n\"", "\"\"", "\"DELETE\""})
    void reviewRejectsAPurposeThatIsNotAddOrEdit(String purposeJson) {
        ResponseEntity<String> response = post(REVIEW, "{\"purpose\":" + purposeJson + "," + FIELDS + "}");

        assertPurposeError(response, "purpose has an invalid value");
        assertNothingStored();
    }

    /**
     * A review whose purpose is JSON {@code null} or absent is rejected with 400 APP0400
     * "purpose is required" on {@code purpose}.
     *
     * @param purposeMember the {@code purpose} member and its comma as sent, empty for none
     */
    @ParameterizedTest(name = "purpose member [{0}]")
    @ValueSource(strings = {"", "\"purpose\":null,"})
    void reviewRejectsAMissingPurpose(String purposeMember) {
        ResponseEntity<String> response = post(REVIEW, "{" + purposeMember + FIELDS + "}");

        assertPurposeError(response, "purpose is required");
        assertNothingStored();
    }

    /** {@code "ADD"} without {@code active} reviews to DEM0009 with the add default {@code Y}. */
    @Test
    void reviewAcceptsAdd() {
        ResponseEntity<String> response = post(REVIEW, "{\"purpose\":\"ADD\"," + FIELDS_WITHOUT_ACTIVE + "}");

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        JsonNode body = json(response);
        assertThat(body.path("notice").path("code").asText()).isEqualTo("DEM0009");
        assertThat(body.path("customer").path("active").asText()).isEqualTo("Y");
        assertNothingStored();
    }

    /** {@code "EDIT"} reviews to DEM0000. */
    @Test
    void reviewAcceptsEdit() {
        ResponseEntity<String> response = post(REVIEW, "{\"purpose\":\"EDIT\"," + FIELDS + "}");

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(json(response).path("notice").path("code").asText()).isEqualTo("DEM0000");
        assertNothingStored();
    }

    /**
     * A review body followed by more content is rejected with 400 APP0400.
     *
     * @param trailing the content sent after the JSON object
     */
    @ParameterizedTest(name = "trailing [{0}]")
    @ValueSource(strings = {" {}", " x", " {\"x\":1}", " 1", "]"})
    void reviewRejectsTrailingContent(String trailing) {
        ResponseEntity<String> response = post(REVIEW, "{\"purpose\":\"ADD\"," + FIELDS + "}" + trailing);

        assertApp0400(response);
        assertNothingStored();
    }

    /**
     * An add body followed by more content is rejected with 400 APP0400, stores no row and consumes no id.
     *
     * @param trailing the content sent after the JSON object
     */
    @ParameterizedTest(name = "trailing [{0}]")
    @ValueSource(strings = {" {}", " x", " {\"x\":1}", " 1", "]"})
    void addRejectsTrailingContent(String trailing) {
        ResponseEntity<String> response = post(CUSTOMERS, "{" + FIELDS + "}" + trailing);

        assertApp0400(response);
        assertNothingStored();
    }

    /** The same add body without trailing content is created under the first id. */
    @Test
    void addAcceptsTheBodyWithoutTrailingContent() {
        ResponseEntity<String> response = post(CUSTOMERS, "{" + FIELDS + "}");

        assertThat(response.getStatusCode().value()).isEqualTo(201);
        assertThat(json(response).path("custId").asText()).isEqualTo(DatabaseCleaner.FIRST_INTERACTIVE_ID);
    }

    /**
     * A PUT body followed by more content is rejected with 400 APP0400 and changes nothing, although the
     * body itself carries the stored version.
     *
     * @param trailing the content sent after the JSON object
     */
    @ParameterizedTest(name = "trailing [{0}]")
    @ValueSource(strings = {" {}", " x", " {\"x\":1}", " 1", "]"})
    void putRejectsTrailingContent(String trailing) {
        insertCustomer(0);
        Map<String, Object> before = storedRow();

        ResponseEntity<String> response = put("{" + FIELDS + ",\"version\":0}" + trailing);

        assertApp0400(response);
        assertThat(storedRow()).isEqualTo(before);
    }

    /** An unknown property is still rejected with 400 APP0400 on review, add and PUT. */
    @Test
    void unknownPropertiesAreStillRejected() {
        insertCustomer(0);
        Map<String, Object> before = storedRow();

        assertApp0400(post(REVIEW, "{\"purpose\":\"ADD\"," + FIELDS + ",\"version\":0}"));
        assertApp0400(post(CUSTOMERS, "{\"custId\":\"ZZZZ\"," + FIELDS + "}"));
        assertApp0400(put("{" + FIELDS + ",\"version\":0,\"chgUser\":\"intruder\"}"));

        assertThat(storedRow()).isEqualTo(before);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM custmast", Long.class)).isEqualTo(1L);
        assertSequenceUntouched();
    }

    /**
     * A review that gives a member twice is rejected with 400 APP0400 "malformed request body", whether
     * the repeat follows the tenth property (once a 500 DEM9999) or precedes it (once silently the last
     * value), and logs no ERROR line.
     *
     * @param body   the exact body text
     * @param output the captured console output
     */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
        "{\"purpose\":\"EDIT\"," + FIELDS + ",\"name\":\"\"}",
        "{\"purpose\":\"EDIT\"," + FIELDS + ",\"zip\":\"1\",\"zip\":\"2\"}",
        "{\"name\":\"FIRST\",\"purpose\":\"EDIT\"," + FIELDS + "}",
        "{\"purpose\":\"ADD\",\"name\":\"A\",\"name\":\"B\"," + FIELDS_WITHOUT_ACTIVE_AND_NAME + "}",
        "{\"purpose\":\"ADD\",\"purpose\":\"EDIT\"," + FIELDS + "}",
        "{\"purpose\":\"EDIT\"," + FIELDS + ",\"purpose\":\"ADD\"}"
    })
    void reviewRejectsARepeatedMember(String body, CapturedOutput output) {
        int logMark = output.getAll().length();

        ResponseEntity<String> response = post(REVIEW, body);

        assertMalformedBody(response, output, logMark);
        assertNothingStored();
    }

    /**
     * An add that gives a member twice, after or before the ninth property, is rejected with 400 APP0400
     * "malformed request body", stores no row, consumes no id and logs no ERROR line.
     *
     * @param body   the exact body text
     * @param output the captured console output
     */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
        "{" + FIELDS + ",\"name\":\"X\"}",
        "{" + FIELDS + ",\"active\":\"N\"}",
        "{\"name\":\"FIRST\"," + FIELDS + "}",
        "{\"name\":\"a\",\"na\\u006de\":\"b\"," + FIELDS_WITHOUT_ACTIVE_AND_NAME + ",\"active\":\"Y\"}"
    })
    void addRejectsARepeatedMember(String body, CapturedOutput output) {
        int logMark = output.getAll().length();

        ResponseEntity<String> response = post(CUSTOMERS, body);

        assertMalformedBody(response, output, logMark);
        assertNothingStored();
    }

    /**
     * A PUT that gives a member twice, {@code version} included, after or before the tenth property, is
     * rejected with 400 APP0400 "malformed request body", leaves the row as stored, although the body
     * carries the stored version, and logs no ERROR line.
     *
     * @param body   the exact body text
     * @param output the captured console output
     */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
        "{" + FIELDS + ",\"version\":0,\"city\":\"X\"}",
        "{\"city\":\"X\"," + FIELDS + ",\"version\":0}",
        "{" + FIELDS + ",\"version\":0,\"version\":0}",
        "{\"version\":0," + FIELDS + ",\"version\":0}"
    })
    void putRejectsARepeatedMember(String body, CapturedOutput output) {
        insertCustomer(0);
        Map<String, Object> before = storedRow();
        int logMark = output.getAll().length();

        ResponseEntity<String> response = put(body);

        assertMalformedBody(response, output, logMark);
        assertThat(storedRow()).isEqualTo(before);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM custmast", Long.class)).isEqualTo(1L);
        assertSequenceUntouched();
    }

    /**
     * A repeated name fails where the parser reads it: it pre-empts any failure binding would report
     * later, but none already reported. A repeat, also one within an unknown member's object value, wins
     * over the unknown property, which the record reports only once complete or at the end of the body;
     * once the record is complete, a later unknown member is reported before its value is read; and a
     * value of the wrong type is reported before its name is repeated. Repeats in separate objects are no
     * repeat.
     *
     * @param body   the exact add body text
     * @param reason the {@code {0}} of "Request is not valid: {0}"
     * @param field  the {@code errors[0].field}, or {@code null} for no {@code errors} member
     */
    @ParameterizedTest(name = "{1}: {0}")
    @MethodSource("repeatedMemberPrecedence")
    void aRepeatedMemberFailsWhereTheParserReadsIt(String body, String reason, String field) {
        ResponseEntity<String> response = post(CUSTOMERS, body);

        assertApp0400(response);
        JsonNode problem = problem(response);
        assertThat(problem.path("detail").asText()).isEqualTo("Request is not valid: " + reason);
        if (field == null) {
            assertThat(problem.has("errors")).as(problem.toString()).isFalse();
        } else {
            assertThat(problem.path("errors").size()).as(problem.toString()).isEqualTo(1);
            assertThat(problem.path("errors").get(0).path("field").asText()).isEqualTo(field);
        }
        assertNothingStored();
    }

    static Stream<Arguments> repeatedMemberPrecedence() {
        return Stream.of(
                Arguments.of("{\"x\":1,\"x\":2," + FIELDS + "}", MALFORMED_BODY, null),
                Arguments.of("{\"x\":1," + FIELDS + ",\"x\":2}", MALFORMED_BODY, null),
                Arguments.of("{\"x\":1,\"name\":\"a\",\"name\":\"b\"," + FIELDS_WITHOUT_ACTIVE_AND_NAME + "}",
                        MALFORMED_BODY, null),
                Arguments.of("{\"x\":{\"a\":1,\"a\":2}," + FIELDS + "}", MALFORMED_BODY, null),
                Arguments.of("{\"x\":[{\"a\":1,\"a\":2}]," + FIELDS + "}", MALFORMED_BODY, null),
                Arguments.of("{\"name\":\"a\",\"name\":\"b\",\"addr\":1}", MALFORMED_BODY, null),
                Arguments.of("{" + FIELDS + ",\"x\":{\"a\":1,\"a\":2}}", "unknown property x", null),
                Arguments.of("{" + FIELDS + ",\"x\":1,\"name\":\"b\"}", "unknown property x", null),
                Arguments.of("{\"x\":[{\"a\":1},{\"a\":2}]," + FIELDS + "}", "unknown property x", null),
                Arguments.of("{\"x\":{\"name\":1,\"y\":{\"name\":2}}," + FIELDS + "}", "unknown property x", null),
                Arguments.of("{\"name\":1,\"name\":\"x\"," + FIELDS_WITHOUT_ACTIVE_AND_NAME + "}",
                        "name has an invalid value", "name"));
    }

    /**
     * An add whose text field is a JSON number or boolean is rejected with 400 APP0400 on that field,
     * stores no row and consumes no id, rather than storing the value's text.
     *
     * @param field     the text field
     * @param valueJson the JSON text sent for it
     */
    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("nonTextScalarPerField")
    void addRejectsANumberOrBooleanForATextField(String field, String valueJson) {
        ResponseEntity<String> response = post(CUSTOMERS, "{" + fieldsWith(field, valueJson) + "}");

        assertTextFieldError(response, field);
        assertNothingStored();
    }

    /**
     * An {@code ADD} or {@code EDIT} review whose text field is a JSON number or boolean is rejected with
     * 400 APP0400 on that field, rather than reviewed as the value's text, and stores nothing.
     *
     * @param purpose   the review purpose
     * @param field     the text field
     * @param valueJson the JSON text sent for it
     */
    @ParameterizedTest(name = "{0} {1} {2}")
    @MethodSource("nonTextScalarPerPurposeAndField")
    void reviewRejectsANumberOrBooleanForATextField(String purpose, String field, String valueJson) {
        ResponseEntity<String> response =
                post(REVIEW, "{\"purpose\":\"" + purpose + "\"," + fieldsWith(field, valueJson) + "}");

        assertTextFieldError(response, field);
        assertNothingStored();
    }

    /**
     * A PUT whose text field is a JSON number or boolean is rejected with 400 APP0400 on that field and
     * leaves the row as stored, although the body carries the stored version.
     *
     * @param field     the text field
     * @param valueJson the JSON text sent for it
     */
    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("nonTextScalarPerField")
    void putRejectsANumberOrBooleanForATextField(String field, String valueJson) {
        insertCustomer(0);
        Map<String, Object> before = storedRow();

        ResponseEntity<String> response = put("{" + fieldsWith(field, valueJson) + ",\"version\":0}");

        assertTextFieldError(response, field);
        assertThat(storedRow()).isEqualTo(before);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM custmast", Long.class)).isEqualTo(1L);
        assertSequenceUntouched();
    }

    static Stream<Arguments> nonTextScalarPerField() {
        return TEXT_FIELDS.stream()
                .flatMap(field -> NON_TEXT_SCALARS.stream().map(value -> Arguments.of(field, value)));
    }

    static Stream<Arguments> nonTextScalarPerPurposeAndField() {
        return Stream.of("ADD", "EDIT").flatMap(purpose -> nonTextScalarPerField()
                .map(arguments -> Arguments.of(purpose, arguments.get()[0], arguments.get()[1])));
    }

    /**
     * A review whose {@code name} is JSON {@code null} or the empty string still reaches the field rules:
     * 422 DEM0502 on {@code name}, not a binding failure.
     *
     * @param purpose  the review purpose
     * @param nameJson the JSON text sent as {@code name}
     */
    @ParameterizedTest(name = "{0} name {1}")
    @CsvSource(delimiter = '|', value = {"ADD|null", "ADD|\"\"", "EDIT|null", "EDIT|\"\""})
    void reviewStillSendsANullOrEmptyNameToTheFieldRules(String purpose, String nameJson) {
        ResponseEntity<String> response =
                post(REVIEW, "{\"purpose\":\"" + purpose + "\"," + fieldsWith("name", nameJson) + "}");

        assertThat(response.getStatusCode().value()).as("status of %s", response.getBody()).isEqualTo(422);
        JsonNode problem = problem(response);
        assertThat(problem.path("code").asText()).isEqualTo("DEM0502");
        assertThat(problem.path("detail").asText()).isEqualTo("Name: Must not be blank");
        assertThat(problem.path("errors").size()).as(problem.toString()).isEqualTo(1);
        assertThat(problem.path("errors").get(0).path("field").asText()).isEqualTo("name");
        assertNothingStored();
    }

    /** An {@code ADD} review whose {@code active} is JSON {@code null} still applies the add default {@code Y}. */
    @Test
    void reviewAddWithANullActiveStillDefaultsToY() {
        ResponseEntity<String> response =
                post(REVIEW, "{\"purpose\":\"ADD\"," + fieldsWith("active", "null") + "}");

        assertThat(response.getStatusCode().value()).as("status of %s", response.getBody()).isEqualTo(200);
        JsonNode body = json(response);
        assertThat(body.path("notice").path("code").asText()).isEqualTo("DEM0009");
        assertThat(body.path("customer").path("active").asText()).isEqualTo("Y");
        assertNothingStored();
    }

    /** Digits sent as a JSON string are text and still bind: an add stores the name {@code "123"}. */
    @Test
    void addStillAcceptsDigitsSentAsAJsonString() {
        ResponseEntity<String> response = post(CUSTOMERS, "{" + fieldsWith("name", "\"123\"") + "}");

        assertThat(response.getStatusCode().value()).as("status of %s", response.getBody()).isEqualTo(201);
        assertThat(jdbcTemplate.queryForObject("SELECT name FROM custmast WHERE custid = ?", String.class,
                DatabaseCleaner.FIRST_INTERACTIVE_ID)).isEqualTo("123");
    }

    /**
     * Sends a raw JSON body to a POST endpoint as the maintenance user.
     *
     * @param path the request path
     * @param body the exact body text
     * @return the response, whatever its status
     */
    private ResponseEntity<String> post(String path, String body) {
        return maintenance().post().uri(path)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .toEntity(String.class);
    }

    /**
     * Sends a raw JSON body to {@code PUT /api/customers/AAAB} as the maintenance user.
     *
     * @param body the exact body text
     * @return the response, whatever its status
     */
    private ResponseEntity<String> put(String body) {
        return maintenance().put().uri(CUSTOMER)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .toEntity(String.class);
    }

    /**
     * Asserts a 400 APP0400 problem whose detail names no Jackson or exception class.
     *
     * @param response the response
     */
    private void assertApp0400(ResponseEntity<String> response) {
        assertThat(response.getStatusCode().value()).as("status of %s", response.getBody()).isEqualTo(400);
        JsonNode problem = problem(response);
        assertThat(problem.path("code").asText()).isEqualTo(APP0400);
        assertThat(problem.path("status").asInt()).isEqualTo(400);
        assertThat(problem.path("detail").asText()).doesNotContainIgnoringCase("jackson")
                .doesNotContain("Exception");
    }

    /**
     * Asserts the 400 APP0400 problem of a body the parser refused: the detail "Request is not valid:
     * malformed request body", {@code args} holding that reason only, no {@code errors} member, nothing in
     * the body that names the repeated member, a class or the parser, and no ERROR line logged since
     * {@code logMark}.
     *
     * @param response the response
     * @param output   the captured console output
     * @param logMark  the length of {@code output.getAll()} before the request was sent
     */
    private void assertMalformedBody(ResponseEntity<String> response, CapturedOutput output, int logMark) {
        assertApp0400(response);
        JsonNode problem = problem(response);
        assertThat(problem.path("detail").asText()).isEqualTo("Request is not valid: " + MALFORMED_BODY);
        assertThat(problem.path("args").size()).as(problem.toString()).isEqualTo(1);
        assertThat(problem.path("args").get(0).asText()).isEqualTo(MALFORMED_BODY);
        assertThat(problem.has("errors")).as(problem.toString()).isFalse();
        assertThat(problem.has("errorId")).as(problem.toString()).isFalse();
        assertThat(response.getBody()).doesNotContainIgnoringCase("duplicate")
                .doesNotContainIgnoringCase("jackson").doesNotContain("java.").doesNotContain("Exception");
        assertThat(output.getAll().substring(logMark).lines())
                .noneMatch(line -> line.matches("^\\S+\\s+ERROR\\s.*"));
    }

    /**
     * Asserts a 400 APP0400 problem whose one {@code errors[]} entry is on {@code purpose}, phrased as the
     * detail.
     *
     * @param response the response
     * @param reason   the {@code {0}} of "Request is not valid: {0}"
     */
    private void assertPurposeError(ResponseEntity<String> response, String reason) {
        assertApp0400(response);
        JsonNode problem = problem(response);
        assertThat(problem.path("detail").asText()).isEqualTo("Request is not valid: " + reason);
        JsonNode errors = problem.path("errors");
        assertThat(errors.size()).as(problem.toString()).isEqualTo(1);
        assertThat(errors.get(0).path("field").asText()).isEqualTo("purpose");
        assertThat(errors.get(0).path("code").asText()).isEqualTo(APP0400);
        assertThat(errors.get(0).path("message").asText()).isEqualTo("Request is not valid: " + reason);
    }

    /**
     * Asserts the 400 APP0400 problem of a text field sent as a JSON number or boolean: the detail and
     * the one {@code errors[]} entry name the field, {@code args} holds the reason only, and the problem
     * has no member beyond {@link #APP0400_MEMBERS}, so neither the value sent nor a class name appears.
     *
     * @param response the response
     * @param field    the text field
     */
    private void assertTextFieldError(ResponseEntity<String> response, String field) {
        String reason = field + " has an invalid value";
        assertApp0400(response);
        JsonNode problem = problem(response);
        assertThat(problem.path("detail").asText()).isEqualTo("Request is not valid: " + reason);
        assertThat(problem.path("args").size()).as(problem.toString()).isEqualTo(1);
        assertThat(problem.path("args").get(0).asText()).isEqualTo(reason);
        JsonNode errors = problem.path("errors");
        assertThat(errors.size()).as(problem.toString()).isEqualTo(1);
        assertThat(errors.get(0).path("field").asText()).isEqualTo(field);
        assertThat(errors.get(0).path("code").asText()).isEqualTo(APP0400);
        assertThat(errors.get(0).path("message").asText()).isEqualTo("Request is not valid: " + reason);
        List<String> members = new ArrayList<>();
        problem.fieldNames().forEachRemaining(members::add);
        assertThat(members).containsExactlyInAnyOrderElementsOf(APP0400_MEMBERS);
        assertThat(response.getBody()).doesNotContainIgnoringCase("jackson").doesNotContainIgnoringCase("coerc")
                .doesNotContain("java.").doesNotContain("Exception");
    }

    /**
     * Returns {@link #FIELDS} with the value of one text field replaced.
     *
     * @param field     the text field, one of {@link #TEXT_FIELDS}
     * @param valueJson the JSON text sent instead of its string
     * @return the nine members, in JSON order
     */
    private static String fieldsWith(String field, String valueJson) {
        Matcher member = Pattern.compile("\"" + field + "\":\"[^\"]*\"").matcher(FIELDS);
        assertThat(member.find()).as("member %s of FIELDS", field).isTrue();
        return FIELDS.substring(0, member.start()) + "\"" + field + "\":" + valueJson
                + FIELDS.substring(member.end());
    }

    /** Asserts that {@code custmast} is still empty and no id was allocated. */
    private void assertNothingStored() {
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM custmast", Long.class)).isZero();
        assertSequenceUntouched();
    }

    /** Asserts that {@code custmast_id_seq} has not been called since {@code DatabaseCleaner} restarted it. */
    private void assertSequenceUntouched() {
        Map<String, Object> sequence =
                jdbcTemplate.queryForMap("SELECT last_value, is_called FROM custmast_id_seq");
        assertThat(sequence).containsEntry("is_called", false)
                .containsEntry("last_value", (long) DatabaseCleaner.SEQUENCE_START);
    }

    /**
     * Inserts {@link #CUST_ID} with fixed values and the given version.
     *
     * @param rowVersion the stored {@code row_version}
     */
    private void insertCustomer(long rowVersion) {
        jdbcTemplate.update(
                "INSERT INTO custmast (custid, name, addr, city, state, zip, corpphone, acctmgr, acctphone,"
                        + " active, chgtime, chguser, row_version)"
                        + " VALUES (?, 'ORIGINAL NAME', '1 MAIN ST', 'SPRINGFIELD', 'CA', '90210',"
                        + " '(555) 555-0100', 'SMITH, JANE', '(555) 555-0101', 'Y', ?, 'maint2', ?)",
                CUST_ID, OffsetDateTime.of(2024, 1, 2, 3, 4, 5, 0, ZoneOffset.UTC), rowVersion);
    }

    /**
     * Reads every column of {@link #CUST_ID}.
     *
     * @return the stored row, column name to value
     */
    private Map<String, Object> storedRow() {
        return jdbcTemplate.queryForMap(ROW_QUERY, CUST_ID);
    }
}
