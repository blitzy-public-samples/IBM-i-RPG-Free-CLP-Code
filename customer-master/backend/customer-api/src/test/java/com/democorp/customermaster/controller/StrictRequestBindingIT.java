package com.democorp.customermaster.controller;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;

import com.democorp.customermaster.support.AbstractPostgresIT;
import com.democorp.customermaster.support.DatabaseCleaner;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * Proves that the three maintenance request bodies bind strictly, through the
 * {@code spring.jackson} settings of {@code application.yml} and the {@code PurposeDeserializer} of
 * {@code ReviewRequest}: a {@code version} that is not an exact integral JSON number, a {@code purpose}
 * that is not exactly the JSON string {@code "ADD"} or {@code "EDIT"}, and any content after the body's
 * JSON object are rejected with 400 APP0400 before a service runs, so no row changes and no customer id
 * is consumed.
 *
 * <p><b>Why each case matters.</b> Jackson's defaults would truncate {@code 0.9} to version 0, read
 * {@code "0"} as 0 and {@code 1e0} as 1, bind {@code 0}, {@code 1}, {@code "0"} and {@code "1"} as the
 * {@code ADD} and {@code EDIT} ordinals, trim {@code " ADD"} and {@code "\tEDIT"} to a constant name,
 * and ignore whatever follows the first JSON value. The PUT cases
 * therefore store the very version each malformed value would coerce to, so a lenient binder would turn
 * the request into a successful update rather than a 409.
 *
 * <p><b>Controls.</b> The same bodies with an integral version or a valid purpose and no trailing
 * content succeed (200, 201, DEM0000, DEM0009), a stale integral version still answers 409 DEM1002, and
 * a missing version, a null or missing purpose and an unknown property still answer 400 APP0400.
 *
 * <p>The class runs in the base context of {@link AbstractPostgresIT}: it declares no
 * {@code @MockitoBean} and no {@code @Import}, so it adds no context variant. Every test starts from an
 * empty {@code custmast} and next id {@value DatabaseCleaner#FIRST_INTERACTIVE_ID}.
 */
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

    /**
     * Eight of the nine data fields as JSON members, without {@code active}, all valid and all different
     * from the stored row, so an update that wrongly ran would be visible in every column.
     */
    private static final String FIELDS_WITHOUT_ACTIVE = "\"name\":\"changed name\",\"addr\":\"2 elm st\","
            + "\"city\":\"shelbyville\",\"state\":\"CA\",\"zip\":\"90211\",\"corpPhone\":\"(555) 555-0110\","
            + "\"acctMgr\":\"doe, john\",\"acctPhone\":\"(555) 555-0111\"";

    /** All nine data fields as JSON members. */
    private static final String FIELDS = FIELDS_WITHOUT_ACTIVE + ",\"active\":\"Y\"";

    /** Columns compared before and after a rejected request: every column of {@code custmast}. */
    private static final String ROW_QUERY = "SELECT custid, name, addr, city, state, zip, corpphone, acctmgr,"
            + " acctphone, active, chgtime, chguser, row_version FROM custmast WHERE custid = ?";

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
