package com.democorp.customermaster.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.democorp.customermaster.address.AddressServiceUnavailableException;
import com.democorp.customermaster.address.AddressValidationClient;
import com.democorp.customermaster.address.AddressValidationRequest;
import com.democorp.customermaster.address.AddressValidationResult;
import com.democorp.customermaster.support.AbstractPostgresIT;
import com.democorp.customermaster.support.DatabaseCleaner;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.lang.Nullable;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.client.RestClient;

/**
 * Specifies display, review, add and update of one customer over HTTP: {@code GET}, {@code POST /review},
 * {@code POST} and {@code PUT} on {@code /api/customers}, the target of MTNCUSTR's display, edit and add
 * loops.
 *
 * <p><b>Behaviour under test</b>, as read from the source:
 * <ul>
 *   <li><b>Field rules.</b> {@code EditUpdData} runs nine rules in the order ACTIVE, NAME, ADDR, CITY,
 *       STATE, ZIP, ACCTPH, ACCTMGR, CORPPH and stops at the first error, sending DEM0501, DEM0502 with
 *       the field's label, or DEM0503 without an argument [5250_Subfile/MTNCUSTR.SQLRPGLE:387-544]. Each
 *       surfaces as 422 problem+json with one {@code errors} entry. Add and update re-run the rules as an
 *       API guard, so a client that skips review cannot store a blank field.</li>
 *   <li><b>Accepted State.</b> {@code Edit_SD_STATE} moves a valid State into the program's working
 *       {@code STATE} before the later rules run [5250_Subfile/MTNCUSTR.SQLRPGLE:488-494], so a review that
 *       fails after the State rule carries {@code stateAccepted}, the normalized input State, whether the
 *       failure is a later field rule, DEM9898, the standardized-State DEM0503 or 502 APP0502. A failure at
 *       or before the State rule carries none.</li>
 *   <li><b>Address standardization.</b> {@code Edit_Address} sends the street as {@code Address2} with
 *       City, State and the first five ZIP characters, overwrites the address on success, and answers
 *       DEM9898 with the USPS description on {@code addr}, {@code city}, {@code state} and {@code zip}
 *       otherwise [USPS_Address/MTNCUSTR.SQLRPGLE:471-499]. It runs in review only, never in add or
 *       update.</li>
 *   <li><b>Key allocation.</b> {@code AddRecd} increments CUSTNEXT, created as {@code EEEE} by CRTDTAARA,
 *       with BASE36ADD before use [5250_Subfile/MTNCUSTR.SQLRPGLE:548-566], so the first add on a fresh
 *       database receives {@code EEEF} ({@link DatabaseCleaner#FIRST_INTERACTIVE_ID}) and the next
 *       {@code EEEG}. ACTIVE defaults to {@code Y} on add [5250_Subfile/MTNCUSTR.SQLRPGLE:251-297]; through
 *       the API only an absent {@code active} defaults, while a present blank or {@code X} fails
 *       DEM0501.</li>
 *   <li><b>Update.</b> {@code UpdateRecd} writes the screen data with a new change stamp
 *       [5250_Subfile/MTNCUSTR.SQLRPGLE:574-607]; the target increments {@code version} and stamps the
 *       authenticated principal, not the database role. A client cannot supply {@code custId},
 *       {@code chgUser} or any other undeclared property: 400 APP0400.</li>
 *   <li><b>Normalization.</b> No MTNCUSTD input field declares {@code CHECK(LC)}, so every value is stored
 *       and reviewed in upper case [5250_Subfile/MTNCUSTD.DSPF:54-133].</li>
 * </ul>
 * Every message is asserted with its catalog code and its exact text [5250_Subfile/CRTMSGF.CLLE:12-46],
 * the DEM0009 double space corrected.
 *
 * <p><b>Context variant (recorded as {@code AbstractPostgresIT} requires).</b> This class is the
 * {@code @MockitoBean AddressValidationClient} variant of the suite's context policy, and declares that one
 * override and nothing else. The reason: the stub client's fixtures cannot produce every review outcome
 * this class must specify, namely a standardized State outside STATES and a service fault, and they key
 * standardized answers on fictitious addresses; a mock makes each outcome explicit and lets the tests
 * capture the exact request {@code Edit_Address} builds. {@code AddressValidationAutoConfiguration}
 * registers its client only {@code @ConditionalOnMissingBean}, and {@code AddressStandardizationService}
 * resolves the client through an {@code ObjectProvider}, so the mock is the one client review calls.
 * Spring resets the mock after every test; {@link #echoAddresses()} then stubs it again.
 *
 * <p><b>Data.</b> The base resets the database before each test: an empty {@code custmast}, the 58 states
 * and next id {@code EEEF}. Test databases hold no seed rows, so every test creates the customers it reads.
 */
@DisplayName("Customer display, review, add and update over HTTP")
class CustomerMaintenanceApiIT extends AbstractPostgresIT {

    /** The collection path, also the prefix of a customer's path. */
    private static final String CUSTOMERS = "/api/customers";

    /** The review path. */
    private static final String REVIEW = "/api/customers/review";

    /** DEM0000, the edit confirmation [5250_Subfile/CRTMSGF.CLLE:12]. */
    private static final String EDIT_CONFIRMATION = "Press Enter to update. F12 to Cancel.";

    /** DEM0009, the add confirmation, with the source's double space corrected [5250_Subfile/CRTMSGF.CLLE:28]. */
    private static final String ADD_CONFIRMATION = "Press Enter to add. Press F12 to cancel";

    /** DEM0501 with the label {@code Edit_SD_ACTIVE} passes. */
    private static final String ACTIVE_INVALID = "Active Status: Must be Y or N";

    /** DEM0502 with the label {@code Edit_SD_NAME} passes. */
    private static final String NAME_BLANK = "Name: Must not be blank";

    /** DEM0502 with the label {@code Edit_SD_ZIP} passes. */
    private static final String ZIP_BLANK = "ZIP: Must not be blank";

    /** DEM0503, which carries no argument. */
    private static final String STATE_INVALID = "State invalid. Can use F4 to prompt.";

    /** DEM0599, the answer for a customer that does not exist. */
    private static final String CUSTOMER_DELETED = "Customer deleted. Exit & redo search.";

    /** APP0502, the answer for an address-service fault. */
    private static final String ADDRESS_UNAVAILABLE = "Address service is unavailable. Try again later.";

    /** The prefix of every APP0400 detail, "Request is not valid: {0}". */
    private static final String INVALID_REQUEST_PREFIX = "Request is not valid: ";

    /** The USPS description of the address-level error the stub also answers for {@code BADADDR}. */
    private static final String ADDRESS_NOT_FOUND = "Address Not Found.";

    /** The id the second add on a fresh database receives, the BASE36ADD successor of {@code EEEF}. */
    private static final String SECOND_INTERACTIVE_ID = "EEEG";

    /** The request {@code Edit_Address} builds from {@link #validFields()}: street as Address2, no Zip4. */
    private static final AddressValidationRequest EXPECTED_REQUEST =
            new AddressValidationRequest("", "12 MAIN ST", "SPRINGFIELD", "IL", "62701", "");

    /**
     * A successful answer to {@link #EXPECTED_REQUEST} that differs from it in every address component: another
     * street, another city, another State of STATES, another Zip5, and a Zip4.
     */
    private static final AddressValidationResult STANDARDIZED =
            AddressValidationResult.success("", "1200 N MAIN ST STE 4", "CHATHAM", "MO", "62702", "1234");

    /**
     * The address service, mocked for this class only. By default it echoes the request as a standardized
     * address without ZIP+4 ({@link #echoAddresses()}); tests that need another outcome override it.
     */
    @MockitoBean
    private AddressValidationClient addressClient;

    /**
     * Stubs {@link #addressClient} to standardize every request to itself, with a blank ZIP+4. Because every
     * street in this class is at most 30 characters and every ZIP 5 digits, the echo leaves the reviewed
     * values equal to the normalized input. Runs after the base's database reset.
     * {@link #reviewReturnsTheStandardizedAddress()} overrides it with {@link #STANDARDIZED}, which proves
     * that the reviewed address is the service's answer and not the draft's.
     */
    @BeforeEach
    void echoAddresses() {
        when(addressClient.validate(any())).thenAnswer(invocation -> {
            AddressValidationRequest request = invocation.getArgument(0);
            return AddressValidationResult.success(request.address1(), request.address2(), request.city(),
                    request.state(), request.zip5(), "");
        });
    }

    // ---------------------------------------------------------------------------------------------
    // Display: GET /api/customers/{custId}
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("GET returns the stored customer, uppercased, with the principal's stamp and version 0")
    void getFound() {
        String id = create(validFields()).path("custId").asText();

        ResponseEntity<String> response = get(inquiry(), CUSTOMERS + "/" + id);

        assertThat(response.getStatusCode()).as("body: %s", response.getBody()).isEqualTo(HttpStatus.OK);
        JsonNode customer = json(response);
        assertThat(customer.path("custId").asText()).isEqualTo(id);
        assertNormalizedFields(customer);
        assertThat(customer.path("chgUser").asText()).isEqualTo(MAINTENANCE_USER);
        assertThat(customer.path("version").isIntegralNumber()).isTrue();
        assertThat(customer.path("version").asLong()).isZero();
        Instant chgTime = Instant.parse(customer.path("chgTime").asText());
        assertThat(Duration.between(chgTime, Instant.now()).abs()).isLessThanOrEqualTo(Duration.ofMinutes(1));
    }

    @Test
    @DisplayName("GET of a missing id is 404 DEM0599; a malformed id is 400 APP0400")
    void getMissingAndMalformed() {
        JsonNode missing = assertProblem(get(inquiry(), CUSTOMERS + "/ZZZZ"), HttpStatus.NOT_FOUND, "DEM0599",
                CUSTOMER_DELETED);
        assertThat(missing.path("instance").asText()).isEqualTo(CUSTOMERS + "/ZZZZ");
        assertThat(missing.has("errors")).isFalse();

        assertInvalidRequest(get(inquiry(), CUSTOMERS + "/AB"));
        // Ids are issued in upper case and must be sent as issued: a lower-case id is not uppercased.
        assertInvalidRequest(get(inquiry(), CUSTOMERS + "/abcd"));
    }

    // ---------------------------------------------------------------------------------------------
    // Review: POST /api/customers/review
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("review answers the normalized, standardized values with DEM0000 or DEM0009 and stores nothing")
    void reviewEditAndAdd() {
        ResponseEntity<String> edit = review("EDIT", validFields());

        assertThat(edit.getStatusCode()).as("body: %s", edit.getBody()).isEqualTo(HttpStatus.OK);
        JsonNode editBody = json(edit);
        assertNormalizedFields(editBody.path("customer"));
        assertThat(editBody.path("standardized").isBoolean()).isTrue();
        assertThat(editBody.path("standardized").asBoolean()).isTrue();
        assertNotice(editBody.path("notice"), "DEM0000", EDIT_CONFIRMATION);

        ResponseEntity<String> add = review("ADD", validFields());

        assertThat(add.getStatusCode()).as("body: %s", add.getBody()).isEqualTo(HttpStatus.OK);
        JsonNode addBody = json(add);
        assertNormalizedFields(addBody.path("customer"));
        assertThat(addBody.path("standardized").asBoolean()).isTrue();
        assertNotice(addBody.path("notice"), "DEM0009", ADD_CONFIRMATION);

        ArgumentCaptor<AddressValidationRequest> sent = ArgumentCaptor.forClass(AddressValidationRequest.class);
        verify(addressClient, times(2)).validate(sent.capture());
        assertThat(sent.getAllValues()).containsExactly(EXPECTED_REQUEST, EXPECTED_REQUEST);
        assertThat(count()).isZero();
    }

    @Test
    @DisplayName("review answers the standardized address, not the draft's, with its notice, and stores nothing")
    void reviewReturnsTheStandardizedAddress() {
        // doReturn, not when(addressClient.validate(any())), which would call the echo with a null argument.
        doReturn(STANDARDIZED).when(addressClient).validate(any());

        ResponseEntity<String> edit = review("EDIT", validFields());

        assertThat(edit.getStatusCode()).as("body: %s", edit.getBody()).isEqualTo(HttpStatus.OK);
        JsonNode editBody = json(edit);
        assertStandardizedFields(editBody.path("customer"));
        assertThat(editBody.path("standardized").isBoolean()).isTrue();
        assertThat(editBody.path("standardized").asBoolean()).isTrue();
        assertNotice(editBody.path("notice"), "DEM0000", EDIT_CONFIRMATION);

        ResponseEntity<String> add = review("ADD", validFields());

        assertThat(add.getStatusCode()).as("body: %s", add.getBody()).isEqualTo(HttpStatus.OK);
        JsonNode addBody = json(add);
        assertStandardizedFields(addBody.path("customer"));
        assertThat(addBody.path("standardized").isBoolean()).isTrue();
        assertThat(addBody.path("standardized").asBoolean()).isTrue();
        assertNotice(addBody.path("notice"), "DEM0009", ADD_CONFIRMATION);

        // The service received the draft's address; only the answer carries the standardized one.
        ArgumentCaptor<AddressValidationRequest> sent = ArgumentCaptor.forClass(AddressValidationRequest.class);
        verify(addressClient, times(2)).validate(sent.capture());
        assertThat(sent.getAllValues()).containsExactly(EXPECTED_REQUEST, EXPECTED_REQUEST);
        assertThat(count()).isZero();
    }

    @ParameterizedTest(name = "{0} = \"{1}\" -> {2} \"{3}\"")
    @MethodSource("fieldRules")
    @DisplayName("each field rule surfaces through review as 422 with its one field error, before any address call")
    void eachFieldRuleThroughReview(String field, String value, String code, String detail) {
        Map<String, Object> fields = validFields();
        fields.put(field, value);

        JsonNode problem = assertProblem(review("EDIT", fields), HttpStatus.UNPROCESSABLE_ENTITY, code, detail);

        assertSingleError(problem, field, code, detail);
        verifyNoInteractions(addressClient);
        assertThat(count()).isZero();
    }

    /**
     * The nine rules of {@code EditUpdData} with a value that fails each, in source order; the State rule
     * twice, for an unknown and for a blank code. The labels are the {@code SndSflMsg} arguments.
     *
     * @return (field, value, code, detail) per case
     */
    static Stream<Arguments> fieldRules() {
        return Stream.of(
                Arguments.of("active", "X", "DEM0501", ACTIVE_INVALID),
                Arguments.of("name", "", "DEM0502", NAME_BLANK),
                Arguments.of("addr", "   ", "DEM0502", "Address: Must not be blank"),
                Arguments.of("city", "", "DEM0502", "City: Must not be blank"),
                Arguments.of("state", "XX", "DEM0503", STATE_INVALID),
                Arguments.of("state", "", "DEM0503", STATE_INVALID),
                Arguments.of("zip", "", "DEM0502", ZIP_BLANK),
                Arguments.of("acctPhone", "", "DEM0502", "Account Manager Phone: Must not be blank"),
                Arguments.of("acctMgr", "", "DEM0502", "Account Manager Name: Must not be blank"),
                Arguments.of("corpPhone", "", "DEM0502", "Corporate Phone: Must not be blank"));
    }

    @Test
    @DisplayName("the first failing rule stops evaluation: blank name and zip report only name")
    void firstErrorStops() {
        Map<String, Object> fields = validFields();
        fields.put("name", "");
        fields.put("zip", "");

        JsonNode problem = assertProblem(review("EDIT", fields), HttpStatus.UNPROCESSABLE_ENTITY, "DEM0502",
                NAME_BLANK);

        assertSingleError(problem, "name", "DEM0502", NAME_BLANK);
        verifyNoInteractions(addressClient);
    }

    // ---------------------------------------------------------------------------------------------
    // Add and update re-run the field rules
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("POST and PUT re-run the field rules: 422, nothing stored, no id consumed, row unchanged")
    void fieldRulesAlsoGuardPostAndPut() {
        Map<String, Object> noZip = validFields();
        noZip.put("zip", "");

        JsonNode addProblem = assertProblem(post(maintenance(), CUSTOMERS, noZip),
                HttpStatus.UNPROCESSABLE_ENTITY, "DEM0502", ZIP_BLANK);

        assertSingleError(addProblem, "zip", "DEM0502", ZIP_BLANK);
        assertThat(addProblem.has("stateAccepted")).as("stateAccepted belongs to review failures only").isFalse();
        assertThat(count()).isZero();

        JsonNode existing = create(validFields());
        String id = existing.path("custId").asText();
        // The rules run before the id is allocated, so the rejected add consumed none.
        assertThat(id).isEqualTo(DatabaseCleaner.FIRST_INTERACTIVE_ID);
        JsonNode before = json(get(inquiry(), CUSTOMERS + "/" + id));

        Map<String, Object> noName = validFields();
        noName.put("name", "");
        noName.put("version", existing.path("version").asLong());
        JsonNode updateProblem = assertProblem(put(maintenance(), id, noName), HttpStatus.UNPROCESSABLE_ENTITY,
                "DEM0502", NAME_BLANK);

        assertSingleError(updateProblem, "name", "DEM0502", NAME_BLANK);
        assertThat(updateProblem.has("stateAccepted")).isFalse();
        assertThat(json(get(inquiry(), CUSTOMERS + "/" + id))).isEqualTo(before);
        assertThat(count()).isEqualTo(1);
        verify(addressClient, never()).validate(any());
    }

    // ---------------------------------------------------------------------------------------------
    // Add: POST /api/customers
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("add allocates EEEF then EEEG, answers 201 with Location, and never standardizes")
    void addAllocatesEeefWithLocation() {
        ResponseEntity<String> first = post(maintenance(), CUSTOMERS, validFields());

        assertThat(first.getStatusCode()).as("body: %s", first.getBody()).isEqualTo(HttpStatus.CREATED);
        URI location = first.getHeaders().getLocation();
        assertThat(location).as("Location header").isNotNull();
        assertThat(location.toString()).endsWith(CUSTOMERS + "/" + DatabaseCleaner.FIRST_INTERACTIVE_ID);
        JsonNode added = json(first);
        assertThat(added.path("custId").asText()).isEqualTo(DatabaseCleaner.FIRST_INTERACTIVE_ID);
        assertNormalizedFields(added);
        assertThat(added.path("version").asLong()).isZero();
        assertThat(added.path("chgUser").asText()).isEqualTo(MAINTENANCE_USER);

        ResponseEntity<String> located = get(inquiry(), location.getPath());
        assertThat(located.getStatusCode()).as("body: %s", located.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(json(located).path("custId").asText()).isEqualTo(DatabaseCleaner.FIRST_INTERACTIVE_ID);

        JsonNode second = create(validFields());
        assertThat(second.path("custId").asText()).isEqualTo(SECOND_INTERACTIVE_ID);
        assertThat(count()).isEqualTo(2);
        verify(addressClient, never()).validate(any());
    }

    @Test
    @DisplayName("active defaults to Y only when absent; a present blank or X fails DEM0501")
    void activeDefaultsToYOnlyWhenAbsent() {
        Map<String, Object> withoutActive = validFields();
        withoutActive.remove("active");

        JsonNode added = create(withoutActive);

        assertThat(added.path("active").asText()).isEqualTo("Y");
        String stored = jdbcTemplate.queryForObject("select active from custmast where custid = ?", String.class,
                added.path("custId").asText());
        assertThat(stored).isEqualTo("Y");

        ResponseEntity<String> reviewed = review("ADD", withoutActive);
        assertThat(reviewed.getStatusCode()).as("body: %s", reviewed.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(json(reviewed).path("customer").path("active").asText()).isEqualTo("Y");

        for (String present : List.of("", "X")) {
            Map<String, Object> fields = validFields();
            fields.put("active", present);

            JsonNode problem = assertProblem(post(maintenance(), CUSTOMERS, fields),
                    HttpStatus.UNPROCESSABLE_ENTITY, "DEM0501", ACTIVE_INVALID);

            assertSingleError(problem, "active", "DEM0501", ACTIVE_INVALID);
        }
        assertThat(count()).isEqualTo(1);
    }

    // ---------------------------------------------------------------------------------------------
    // stateAccepted on review failures
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("a review failing at active, name or the State rule carries no stateAccepted")
    void stateAcceptedAbsentBeforeOrAtStateRule() {
        assertNoStateAccepted("active", "X", "DEM0501", ACTIVE_INVALID);
        assertNoStateAccepted("name", "", "DEM0502", NAME_BLANK);
        assertNoStateAccepted("state", "XX", "DEM0503", STATE_INVALID);
        verifyNoInteractions(addressClient);
    }

    @Test
    @DisplayName("a review failing after the State rule carries the normalized input State, whatever failed")
    void stateAcceptedAfterStateRule() {
        Map<String, Object> california = validFields();
        california.put("state", "ca");

        // Rule 6: the State rule passed first.
        Map<String, Object> noZip = new LinkedHashMap<>(california);
        noZip.put("zip", "");
        JsonNode zipProblem = assertProblem(review("EDIT", noZip), HttpStatus.UNPROCESSABLE_ENTITY, "DEM0502",
                ZIP_BLANK);
        assertSingleError(zipProblem, "zip", "DEM0502", ZIP_BLANK);
        assertThat(zipProblem.path("stateAccepted").asText()).isEqualTo("CA");
        verify(addressClient, never()).validate(any());

        // Per-test outcomes use doReturn/doThrow: re-stubbing through when(addressClient.validate(any()))
        // would invoke the echo answer of echoAddresses() with the matcher's null argument.
        String usps = "USPS: " + ADDRESS_NOT_FOUND;
        doReturn(AddressValidationResult.error("", "", "", "", "", "", -2147219401, "clsAMS", ADDRESS_NOT_FOUND))
                .when(addressClient).validate(any());
        JsonNode notFound = assertProblem(review("EDIT", california), HttpStatus.UNPROCESSABLE_ENTITY, "DEM9898",
                usps);
        assertThat(errorFields(notFound)).containsExactly("addr", "city", "state", "zip");
        for (JsonNode error : notFound.path("errors")) {
            assertThat(error.path("code").asText()).isEqualTo("DEM9898");
            assertThat(error.path("message").asText()).isEqualTo(usps);
        }
        assertThat(notFound.path("stateAccepted").asText()).isEqualTo("CA");

        // The standardized-State check, distinct from the State rule: ZZ is not in STATES.
        doReturn(AddressValidationResult.success("", "12 MAIN ST", "SPRINGFIELD", "ZZ", "62701", ""))
                .when(addressClient).validate(any());
        JsonNode badState = assertProblem(review("EDIT", california), HttpStatus.UNPROCESSABLE_ENTITY, "DEM0503",
                STATE_INVALID);
        assertThat(badState.path("errors").get(0).path("field").asText()).isEqualTo("state");
        assertThat(badState.path("stateAccepted").asText()).isEqualTo("CA");

        // A service fault is 502 APP0502, never DEM9898, and its message never reaches the client.
        doThrow(new AddressServiceUnavailableException("test fault")).when(addressClient).validate(any());
        ResponseEntity<String> unavailable = review("EDIT", california);
        JsonNode fault = assertProblem(unavailable, HttpStatus.BAD_GATEWAY, "APP0502", ADDRESS_UNAVAILABLE);
        JsonNode faultErrors = fault.path("errors");
        assertThat(faultErrors.isMissingNode() || (faultErrors.isArray() && faultErrors.isEmpty()))
                .as("errors of a 502: %s", faultErrors)
                .isTrue();
        assertThat(fault.path("stateAccepted").asText()).isEqualTo("CA");
        assertThat(unavailable.getBody()).doesNotContain("test fault");

        ArgumentCaptor<AddressValidationRequest> sent = ArgumentCaptor.forClass(AddressValidationRequest.class);
        verify(addressClient, times(3)).validate(sent.capture());
        assertThat(sent.getAllValues()).extracting(AddressValidationRequest::state).containsOnly("CA");
        assertThat(count()).isZero();
    }

    // ---------------------------------------------------------------------------------------------
    // Update: PUT /api/customers/{custId}
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("update increments version and stamps the authenticated principal")
    void updateIncrementsVersionAndStampsPrincipal() {
        JsonNode created = create(validFields());
        String id = created.path("custId").asText();
        Instant createdAt = Instant.parse(created.path("chgTime").asText());
        assertThat(created.path("version").asLong()).isZero();

        Map<String, Object> west = validFields();
        west.put("name", "acme tools west");
        west.put("version", 0);
        ResponseEntity<String> first = put(maintenance(), id, west);

        assertThat(first.getStatusCode()).as("body: %s", first.getBody()).isEqualTo(HttpStatus.OK);
        JsonNode firstBody = json(first);
        assertThat(firstBody.path("custId").asText()).isEqualTo(id);
        assertThat(firstBody.path("name").asText()).isEqualTo("ACME TOOLS WEST");
        assertThat(firstBody.path("version").asLong()).isEqualTo(1);
        assertThat(firstBody.path("chgUser").asText()).isEqualTo(MAINTENANCE_USER);
        Instant firstAt = Instant.parse(firstBody.path("chgTime").asText());
        assertThat(firstAt).isAfterOrEqualTo(createdAt);

        Map<String, Object> again = validFields();
        again.put("name", "acme tools west");
        again.put("version", 1);
        ResponseEntity<String> second = put(maintenance2(), id, again);

        assertThat(second.getStatusCode()).as("body: %s", second.getBody()).isEqualTo(HttpStatus.OK);
        JsonNode secondBody = json(second);
        assertThat(secondBody.path("version").asLong()).isEqualTo(2);
        assertThat(secondBody.path("chgUser").asText()).isEqualTo(MAINTENANCE2_USER);
        assertThat(Instant.parse(secondBody.path("chgTime").asText())).isAfterOrEqualTo(firstAt);

        JsonNode stored = json(get(inquiry(), CUSTOMERS + "/" + id));
        assertThat(stored.path("name").asText()).isEqualTo("ACME TOOLS WEST");
        assertThat(stored.path("version").asLong()).isEqualTo(2);
        assertThat(stored.path("chgUser").asText()).isEqualTo(MAINTENANCE2_USER);

        assertInvalidRequest(put(maintenance(), id, validFields()));

        Map<String, Object> ghost = validFields();
        ghost.put("version", 0);
        assertProblem(put(maintenance(), "ZZZZ", ghost), HttpStatus.NOT_FOUND, "DEM0599", CUSTOMER_DELETED);

        assertThat(json(get(inquiry(), CUSTOMERS + "/" + id))).isEqualTo(stored);
        assertThat(count()).isEqualTo(1);
        verify(addressClient, never()).validate(any());
    }

    @Test
    @DisplayName("a body naming chgUser or custId is 400 APP0400 and stores nothing")
    void unknownPropertyRejected() {
        Map<String, Object> stamped = validFields();
        stamped.put("chgUser", "hacker");
        assertInvalidRequest(post(maintenance(), CUSTOMERS, stamped));
        assertThat(count()).isZero();

        Map<String, Object> keyed = validFields();
        keyed.put("custId", "AAAA");
        assertInvalidRequest(post(maintenance(), CUSTOMERS, keyed));
        assertThat(count()).isZero();

        // Neither rejection consumed an id.
        assertThat(create(validFields()).path("custId").asText()).isEqualTo(DatabaseCleaner.FIRST_INTERACTIVE_ID);
    }

    // ---------------------------------------------------------------------------------------------
    // Fixtures and helpers
    // ---------------------------------------------------------------------------------------------

    /**
     * A valid draft in screen order, typed in lower case. Every street is at most 30 characters and every
     * ZIP 5 digits, so standardization by {@link #echoAddresses()} leaves the reviewed values equal to the
     * normalized input checked by {@link #assertNormalizedFields(JsonNode)}.
     *
     * @return a new mutable map of the nine data fields
     */
    private static Map<String, Object> validFields() {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("active", "Y");
        fields.put("name", "acme tools");
        fields.put("addr", "12 main st");
        fields.put("city", "springfield");
        fields.put("state", "il");
        fields.put("zip", "62701");
        fields.put("acctPhone", "(217) 555-0101");
        fields.put("acctMgr", "jane doe");
        fields.put("corpPhone", "(217) 555-0100");
        return fields;
    }

    /**
     * Asserts the nine data fields of {@link #validFields()} as normalized: uppercased, nothing else changed.
     *
     * @param customer a {@code CustomerResponse} body or the {@code customer} member of a review
     */
    private static void assertNormalizedFields(JsonNode customer) {
        assertThat(customer.path("name").asText()).isEqualTo("ACME TOOLS");
        assertThat(customer.path("addr").asText()).isEqualTo("12 MAIN ST");
        assertThat(customer.path("city").asText()).isEqualTo("SPRINGFIELD");
        assertThat(customer.path("state").asText()).isEqualTo("IL");
        assertThat(customer.path("zip").asText()).isEqualTo("62701");
        assertThat(customer.path("corpPhone").asText()).isEqualTo("(217) 555-0100");
        assertThat(customer.path("acctMgr").asText()).isEqualTo("JANE DOE");
        assertThat(customer.path("acctPhone").asText()).isEqualTo("(217) 555-0101");
        assertThat(customer.path("active").asText()).isEqualTo("Y");
    }

    /**
     * Asserts the {@code customer} member of a review of {@link #validFields()} answered by {@link #STANDARDIZED}:
     * {@code Edit_Address} overwrites the street with {@code Address2}, the city and the State, and builds
     * the ZIP as {@code Zip5-Zip4} [USPS_Address/MTNCUSTR.SQLRPGLE:480-488]; the five other fields stay
     * normalized.
     *
     * @param customer the {@code customer} member of a review
     */
    private static void assertStandardizedFields(JsonNode customer) {
        assertThat(customer.path("addr").asText()).isEqualTo("1200 N MAIN ST STE 4");
        assertThat(customer.path("city").asText()).isEqualTo("CHATHAM");
        assertThat(customer.path("state").asText()).isEqualTo("MO");
        assertThat(customer.path("zip").asText()).isEqualTo("62702-1234");
        assertThat(customer.path("name").asText()).isEqualTo("ACME TOOLS");
        assertThat(customer.path("corpPhone").asText()).isEqualTo("(217) 555-0100");
        assertThat(customer.path("acctMgr").asText()).isEqualTo("JANE DOE");
        assertThat(customer.path("acctPhone").asText()).isEqualTo("(217) 555-0101");
        assertThat(customer.path("active").asText()).isEqualTo("Y");
    }

    /**
     * Asserts a review notice.
     *
     * @param notice  the {@code notice} member
     * @param code    the expected catalog key
     * @param message the expected catalog text
     */
    private static void assertNotice(JsonNode notice, String code, String message) {
        assertThat(notice.path("code").asText()).isEqualTo(code);
        assertThat(notice.path("message").asText()).isEqualTo(message);
    }

    /**
     * Asserts that a review failing on {@code field} := {@code value} is 422 without {@code stateAccepted}.
     */
    private void assertNoStateAccepted(String field, String value, String code, String detail) {
        Map<String, Object> fields = validFields();
        fields.put(field, value);
        JsonNode problem = assertProblem(review("EDIT", fields), HttpStatus.UNPROCESSABLE_ENTITY, code, detail);
        assertSingleError(problem, field, code, detail);
        assertThat(problem.has("stateAccepted")).as("stateAccepted of a failure at %s", field).isFalse();
    }

    /**
     * Asserts a problem+json response with the given status, catalog code and, when given, exact detail.
     *
     * @param response the response
     * @param status   the expected status
     * @param code     the expected catalog key
     * @param detail   the expected detail, or {@code null} to leave it unchecked
     * @return the parsed problem
     */
    private JsonNode assertProblem(ResponseEntity<String> response, HttpStatus status, String code,
            @Nullable String detail) {
        assertThat(response.getStatusCode()).as("status; body: %s", response.getBody()).isEqualTo(status);
        JsonNode problem = problem(response);
        assertThat(problem.path("status").asInt()).isEqualTo(status.value());
        assertThat(problem.path("code").asText()).isEqualTo(code);
        assertThat(problem.path("type").asText()).isEqualTo(ProblemFactory.TYPE_PREFIX + code);
        if (detail != null) {
            assertThat(problem.path("detail").asText()).isEqualTo(detail);
        }
        return problem;
    }

    /**
     * Asserts a 400 APP0400, whose detail is "Request is not valid: " plus a fixed reason.
     *
     * @param response the response
     */
    private void assertInvalidRequest(ResponseEntity<String> response) {
        JsonNode problem = assertProblem(response, HttpStatus.BAD_REQUEST, "APP0400", null);
        assertThat(problem.path("detail").asText()).startsWith(INVALID_REQUEST_PREFIX);
    }

    /**
     * Asserts that a problem carries exactly one {@code errors} entry with the given members.
     */
    private static void assertSingleError(JsonNode problem, String field, String code, String message) {
        JsonNode errors = problem.path("errors");
        assertThat(errors.isArray()).as("errors of %s", problem).isTrue();
        assertThat(errors).hasSize(1);
        JsonNode error = errors.get(0);
        assertThat(error.path("field").asText()).isEqualTo(field);
        assertThat(error.path("code").asText()).isEqualTo(code);
        assertThat(error.path("message").asText()).isEqualTo(message);
    }

    /**
     * Returns the {@code field} of every {@code errors} entry, in order.
     */
    private static List<String> errorFields(JsonNode problem) {
        List<String> fields = new ArrayList<>();
        for (JsonNode error : problem.path("errors")) {
            fields.add(error.path("field").asText());
        }
        return fields;
    }

    /**
     * Posts {@code {purpose, ...fields}} to the review endpoint as {@value #MAINTENANCE_USER}.
     */
    private ResponseEntity<String> review(String purpose, Map<String, Object> fields) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("purpose", purpose);
        body.putAll(fields);
        return post(maintenance(), REVIEW, body);
    }

    /**
     * Adds a customer as {@value #MAINTENANCE_USER} and asserts 201.
     *
     * @return the parsed {@code CustomerResponse}
     */
    private JsonNode create(Map<String, Object> fields) {
        ResponseEntity<String> response = post(maintenance(), CUSTOMERS, fields);
        assertThat(response.getStatusCode()).as("add; body: %s", response.getBody()).isEqualTo(HttpStatus.CREATED);
        return json(response);
    }

    /**
     * Returns the number of {@code custmast} rows.
     */
    private int count() {
        Integer rows = jdbcTemplate.queryForObject("select count(*) from custmast", Integer.class);
        assertThat(rows).isNotNull();
        return rows;
    }

    private ResponseEntity<String> get(RestClient client, String path) {
        return client.get().uri(path).retrieve().toEntity(String.class);
    }

    private ResponseEntity<String> post(RestClient client, String path, Map<String, Object> body) {
        return client.post().uri(path).contentType(MediaType.APPLICATION_JSON).body(toJson(body))
                .retrieve().toEntity(String.class);
    }

    private ResponseEntity<String> put(RestClient client, String custId, Map<String, Object> body) {
        return client.put().uri(CUSTOMERS + "/" + custId).contentType(MediaType.APPLICATION_JSON)
                .body(toJson(body)).retrieve().toEntity(String.class);
    }

    /**
     * Serializes a request body with the application's mapper.
     *
     * @throws IllegalStateException if the body cannot be serialized
     */
    private String toJson(Map<String, Object> body) {
        try {
            return objectMapper.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialize the request body", e);
        }
    }
}
