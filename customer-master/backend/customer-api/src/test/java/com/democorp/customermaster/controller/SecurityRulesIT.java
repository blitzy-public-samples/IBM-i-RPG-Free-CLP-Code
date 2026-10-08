package com.democorp.customermaster.controller;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Stream;

import com.democorp.customermaster.support.AbstractPostgresIT;
import com.democorp.customermaster.support.DatabaseCleaner;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.lang.Nullable;
import org.springframework.web.client.RestClient;

/**
 * Proves, against the running server, that the API enforces the roles the IBM i mode letters became,
 * for every endpoint and every kind of caller, and that its 401 and 403 answers are the documented
 * problem bodies.
 *
 * <p><b>What it replaces.</b> PMTCUSTR took its mode as a caller-asserted first parameter,
 * {@code pParmType} ({@code I}, {@code M} or {@code S}; {@code 5250_Subfile/PMTCUSTR.SQLRPGLE}, lines
 * 76-79), and trusted that "in a production environment, this would be called from a tested menu or
 * some program that enforced security" (lines 230-231). {@code I} gave 5=Display only, {@code M} added
 * 2=Edit and F6=Add ({@code 5250_Subfile/README.md}, lines 33-40), and MTNCUSTR's function code
 * {@code pMaintain} was whatever the caller passed. The target authenticates every request with
 * stateless HTTP Basic and authorizes it in {@code security/SecurityConfig}: {@code INQUIRY} reads,
 * {@code MAINTENANCE} (which implies {@code INQUIRY}) also reviews, adds and changes, so a caller can
 * no longer assert a mode it does not hold.
 *
 * <p><b>Rules under test</b>, in the order {@code SecurityConfig} evaluates them:
 * <table>
 *   <caption>Authorization rules</caption>
 *   <tr><th>Request</th><th>Required</th></tr>
 *   <tr><td>GET {@code /api/messages}, {@code /actuator/health/**}, {@code /v3/api-docs/**},
 *       {@code /swagger-ui/**}</td><td>public</td></tr>
 *   <tr><td>GET {@code /api/session}, {@code /api/customers}, {@code /api/customers/{custId}},
 *       {@code /api/states}</td><td>{@code INQUIRY}</td></tr>
 *   <tr><td>POST {@code /api/customers/review}, POST {@code /api/customers},
 *       PUT {@code /api/customers/{custId}}</td><td>{@code MAINTENANCE}</td></tr>
 *   <tr><td>anything else, {@code /error} requested directly included</td><td>authenticated</td></tr>
 * </table>
 * A 401 is {@code APP0401} "Sign in required." and carries {@code WWW-Authenticate: Basic
 * realm="customer-master"} only when the request has no {@code X-Requested-With} header, so the SPA
 * never triggers the browser's own sign-in prompt while curl and k6 are still challenged. A 403 is
 * {@code APP0403} "You are not authorized to perform this action.". Both are
 * {@code application/problem+json}, and authorization is decided before any request body is read, so
 * a denied write never reaches validation, consumes no customer id and changes no row.
 *
 * <p><b>Shape.</b> {@link #endpointMatrix()} sends each request as an anonymous caller, then as the
 * {@code INQUIRY} user {@value AbstractPostgresIT#INQUIRY_USER}, then as the {@code MAINTENANCE} user
 * {@value AbstractPostgresIT#MAINTENANCE_USER}, and checks the status, the problem body and the stored
 * rows after each call. The maintenance call comes last because it is the only one allowed to change
 * the customer the matrix shares. The focused tests then pin the session payload, bad credentials, the
 * challenge rule, the order of authentication and validation, and the two "authenticated" fallbacks.
 *
 * <p><b>Context.</b> The class runs in the base context of {@link AbstractPostgresIT}: no
 * {@code @MockitoBean}, no {@code @Import} and no property override, so it adds no context variant.
 * Review therefore runs against the default stub address client, which echoes a non-fixture address
 * in upper case, so a valid review answers 200. Test databases hold no seed rows, so
 * {@link #createCustomer()} adds the one customer the by-id requests need; its id is always
 * {@value DatabaseCleaner#FIRST_INTERACTIVE_ID}.
 */
class SecurityRulesIT extends AbstractPostgresIT {

    /** The customer collection path. */
    private static final String CUSTOMERS = "/api/customers";

    /** Placeholder in a matrix path for the id of the customer {@link #createCustomer()} adds. */
    private static final String ID_VARIABLE = "{id}";

    /** The path of the one customer the matrix reads and changes. */
    private static final String CUSTOMER = CUSTOMERS + "/" + ID_VARIABLE;

    /** Catalog key of a request the API cannot serve, a missing route included. */
    private static final String APP0400 = "APP0400";

    /** Catalog key of a missing or rejected sign-in. */
    private static final String APP0401 = "APP0401";

    /** Catalog key of an authenticated user without the required role. */
    private static final String APP0403 = "APP0403";

    /** The catalog text of {@value #APP0401}. */
    private static final String SIGN_IN_REQUIRED = "Sign in required.";

    /** The catalog text of {@value #APP0403}. */
    private static final String NOT_AUTHORIZED = "You are not authorized to perform this action.";

    /** The {@value #APP0400} detail of a path no route or static resource serves. */
    private static final String NO_SUCH_RESOURCE = "Request is not valid: no such resource";

    /** The {@value #APP0400} detail of a direct request to {@code /error}, which carries no error attributes. */
    private static final String ERROR_NOT_FOUND = "Request is not valid: not found";

    /** The exact Basic challenge a non-SPA client receives with a 401. */
    private static final String BASIC_CHALLENGE = "Basic realm=\"customer-master\"";

    /**
     * The nine data fields as JSON members, all valid for every rule: {@code active Y}, a state present
     * in {@code states}, and every required field non-blank. Lower case shows the server uppercases.
     */
    private static final String FIELDS = "\"name\":\"secure co\",\"addr\":\"9 oak st\",\"city\":\"peoria\","
            + "\"state\":\"IL\",\"zip\":\"61602\",\"corpPhone\":\"(309) 555-0100\",\"acctMgr\":\"pat quinn\","
            + "\"acctPhone\":\"(309) 555-0101\",\"active\":\"Y\"";

    /** Body of {@code POST /api/customers}: the nine fields. */
    private static final String ADD_BODY = "{" + FIELDS + "}";

    /** Body of {@code POST /api/customers/review}: an EDIT review of the nine fields. */
    private static final String REVIEW_BODY = "{\"purpose\":\"EDIT\"," + FIELDS + "}";

    /** Body of {@code PUT /api/customers/{id}}: the nine fields and the version the setup row has. */
    private static final String UPDATE_BODY = "{" + FIELDS + ",\"version\":0}";

    /** Counts the stored customers. */
    private static final String COUNT_CUSTOMERS = "SELECT count(*) FROM custmast";

    /** Reads the stored version of one customer. */
    private static final String ROW_VERSION = "SELECT row_version FROM custmast WHERE custid = ?";

    /** The id of the customer {@link #createCustomer()} adds before each test. */
    private String custId;

    /** The callers of the matrix, in the order each request is sent. */
    private enum Caller {
        /** No {@code Authorization} header. */
        ANONYMOUS,
        /** {@value AbstractPostgresIT#INQUIRY_USER}, role {@code INQUIRY}. */
        INQUIRY,
        /** {@value AbstractPostgresIT#MAINTENANCE_USER}, role {@code MAINTENANCE}. */
        MAINTENANCE
    }

    /**
     * What the stored data must look like after a request: the number of customers and the version of
     * the setup customer. Every anonymous and {@code INQUIRY} call must leave {@link #NONE}.
     */
    private enum Effect {
        /** Nothing changed: the setup customer alone, at version 0. */
        NONE(1, 0L),
        /** An add committed: a second customer, the setup customer untouched. */
        ADDS_CUSTOMER(2, 0L),
        /** An update of the setup customer committed: still one customer, now at version 1. */
        UPDATES_CUSTOMER(1, 1L);

        /** Expected {@code count(*)} of {@code custmast}. */
        private final long customers;

        /** Expected {@code row_version} of the setup customer. */
        private final long setupRowVersion;

        Effect(long customers, long setupRowVersion) {
            this.customers = customers;
            this.setupRowVersion = setupRowVersion;
        }
    }

    /**
     * Adds the one customer the by-id requests read and change, as {@value AbstractPostgresIT#MAINTENANCE_USER}
     * through the API. JUnit runs it after the base class's {@code cleanDatabase()}, so the table holds
     * this customer only and its id is the first interactive id.
     */
    @BeforeEach
    void createCustomer() {
        ResponseEntity<String> created = send(maintenance(), HttpMethod.POST, CUSTOMERS, () -> ADD_BODY);

        assertThat(created.getStatusCode())
                .as("setup add (body %s)", created.getBody())
                .isEqualTo(HttpStatus.CREATED);
        custId = json(created).path("custId").asText();
        assertThat(custId).isEqualTo(DatabaseCleaner.FIRST_INTERACTIVE_ID);
        assertStored(Effect.NONE, "after setup");
    }

    /**
     * The request matrix: every endpoint of the authorization rules with its expected status for an
     * anonymous, an {@code INQUIRY} and a {@code MAINTENANCE} caller, and the stored state the
     * maintenance call must leave. {@value #ID_VARIABLE} in a path stands for the setup customer's id.
     * The last two rows cover "anything else: authenticated": an unknown path and a method no rule or
     * route serves, so neither can be probed anonymously and no customer can be deleted.
     *
     * @return one argument set per request: label, method, path, body supplier ({@code null} for no
     *     body), the three expected statuses and the maintenance effect
     */
    static Stream<Arguments> endpointMatrix() {
        Supplier<String> review = () -> REVIEW_BODY;
        Supplier<String> add = () -> ADD_BODY;
        Supplier<String> update = () -> UPDATE_BODY;
        return Stream.of(
                // INQUIRY reads; MAINTENANCE passes them through the role hierarchy.
                row("GET /api/session", HttpMethod.GET, "/api/session", null, 401, 200, 200, Effect.NONE),
                row("GET /api/customers", HttpMethod.GET, CUSTOMERS, null, 401, 200, 200, Effect.NONE),
                row("GET /api/customers/{id}", HttpMethod.GET, CUSTOMER, null, 401, 200, 200, Effect.NONE),
                row("GET /api/states", HttpMethod.GET, "/api/states", null, 401, 200, 200, Effect.NONE),
                // MAINTENANCE writes; the update is valid for the setup row's version 0.
                row("POST /api/customers/review", HttpMethod.POST, CUSTOMERS + "/review", review,
                        401, 403, 200, Effect.NONE),
                row("POST /api/customers", HttpMethod.POST, CUSTOMERS, add, 401, 403, 201, Effect.ADDS_CUSTOMER),
                row("PUT /api/customers/{id}", HttpMethod.PUT, CUSTOMER, update,
                        401, 403, 200, Effect.UPDATES_CUSTOMER),
                // Public reads: message catalog, health probes, API docs and Swagger UI.
                row("GET /api/messages", HttpMethod.GET, "/api/messages", null, 200, 200, 200, Effect.NONE),
                row("GET /actuator/health", HttpMethod.GET, "/actuator/health", null, 200, 200, 200, Effect.NONE),
                row("GET /actuator/health/readiness", HttpMethod.GET, "/actuator/health/readiness", null,
                        200, 200, 200, Effect.NONE),
                row("GET /actuator/health/liveness", HttpMethod.GET, "/actuator/health/liveness", null,
                        200, 200, 200, Effect.NONE),
                row("GET /v3/api-docs", HttpMethod.GET, "/v3/api-docs", null, 200, 200, 200, Effect.NONE),
                row("GET /v3/api-docs.yaml", HttpMethod.GET, "/v3/api-docs.yaml", null,
                        200, 200, 200, Effect.NONE),
                row("GET /swagger-ui/index.html", HttpMethod.GET, "/swagger-ui/index.html", null,
                        200, 200, 200, Effect.NONE),
                // Anything else: authenticated first, then the MVC layer's own answer.
                row("GET /api/nope", HttpMethod.GET, "/api/nope", null, 401, 404, 404, Effect.NONE),
                row("DELETE /api/customers/{id}", HttpMethod.DELETE, CUSTOMER, null, 401, 405, 405, Effect.NONE));
    }

    /**
     * Builds one matrix row.
     *
     * @param label the display name of the row
     * @param method the HTTP method
     * @param path the path, possibly holding {@value #ID_VARIABLE}
     * @param body the JSON body supplier, or {@code null} for a request without a body
     * @param anonymous the expected status without credentials
     * @param inquiry the expected status as the {@code INQUIRY} user
     * @param maintenance the expected status as the {@code MAINTENANCE} user
     * @param maintenanceEffect the stored state after the maintenance call
     * @return the argument set
     */
    private static Arguments row(String label, HttpMethod method, String path, @Nullable Supplier<String> body,
            int anonymous, int inquiry, int maintenance, Effect maintenanceEffect) {
        return Arguments.of(label, method, path, body, anonymous, inquiry, maintenance, maintenanceEffect);
    }

    /**
     * Each endpoint answers each caller as the rules require: 401 {@code APP0401} without credentials
     * on every protected path, 403 {@code APP0403} for an {@code INQUIRY} user on every write, and the
     * endpoint's own success for an allowed caller. No anonymous or {@code INQUIRY} call changes any
     * row; the {@code MAINTENANCE} call, sent last, leaves exactly the effect of its request.
     *
     * @param label the display name of the row
     * @param method the HTTP method
     * @param path the path, possibly holding {@value #ID_VARIABLE}
     * @param body the JSON body supplier, or {@code null} for a request without a body
     * @param expectedAnonymous the expected status without credentials
     * @param expectedInquiry the expected status as the {@code INQUIRY} user
     * @param expectedMaintenance the expected status as the {@code MAINTENANCE} user
     * @param maintenanceEffect the stored state after the maintenance call
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("endpointMatrix")
    void everyEndpointEnforcesItsRuleForEveryCaller(String label, HttpMethod method, String path,
            @Nullable Supplier<String> body, int expectedAnonymous, int expectedInquiry,
            int expectedMaintenance, Effect maintenanceEffect) {
        String uri = path.replace(ID_VARIABLE, custId);

        ResponseEntity<String> anonymous = send(anonymous(), method, uri, body);
        assertOutcome(label, Caller.ANONYMOUS, anonymous, expectedAnonymous, uri);
        assertStored(Effect.NONE, label + " as " + Caller.ANONYMOUS);

        ResponseEntity<String> inquiry = send(inquiry(), method, uri, body);
        assertOutcome(label, Caller.INQUIRY, inquiry, expectedInquiry, uri);
        assertStored(Effect.NONE, label + " as " + Caller.INQUIRY);

        ResponseEntity<String> maintenance = send(maintenance(), method, uri, body);
        assertOutcome(label, Caller.MAINTENANCE, maintenance, expectedMaintenance, uri);
        assertStored(maintenanceEffect, label + " as " + Caller.MAINTENANCE);
    }

    /**
     * {@code GET /api/session} names the authenticated principal and its granted role, without the
     * {@code ROLE_} prefix and without hierarchy expansion, and nothing else: the browser derives its
     * Inquiry or Maintenance mode from these roles, never from a caller-asserted parameter.
     */
    @Test
    void sessionReportsPrincipalAndRoles() {
        JsonNode inquirySession = json(expectStatus(
                send(inquiry(), HttpMethod.GET, "/api/session", null), HttpStatus.OK));
        assertThat(inquirySession.path("username").asText()).isEqualTo(INQUIRY_USER);
        assertThat(textValues(inquirySession.path("roles"))).containsExactly("INQUIRY");
        assertThat(fieldNames(inquirySession)).containsExactlyInAnyOrder("username", "roles");

        JsonNode maintenanceSession = json(expectStatus(
                send(maintenance(), HttpMethod.GET, "/api/session", null), HttpStatus.OK));
        assertThat(maintenanceSession.path("username").asText()).isEqualTo(MAINTENANCE_USER);
        assertThat(textValues(maintenanceSession.path("roles"))).containsExactly("MAINTENANCE");
        assertThat(fieldNames(maintenanceSession)).containsExactlyInAnyOrder("username", "roles");
    }

    /**
     * A wrong password, an unknown user and another user's password are all answered 401
     * {@code APP0401}, on a protected read, on a write and on a public endpoint alike, because the
     * Basic filter rejects bad credentials before any rule is applied. The body names neither the
     * attempted user nor the password, and the rejected write changes nothing.
     */
    @Test
    void badCredentials() {
        List<String[]> rejected = List.of(
                new String[] {MAINTENANCE_USER, "wrong"},
                new String[] {"nobody", "x"},
                new String[] {INQUIRY_USER, MAINTENANCE_PASSWORD});
        for (String[] credentials : rejected) {
            String who = "the rejected credentials of " + credentials[0];
            RestClient client = as(credentials[0], credentials[1]);

            ResponseEntity<String> read = send(client, HttpMethod.GET, CUSTOMERS, null);
            assertProblem(read, HttpStatus.UNAUTHORIZED, APP0401, SIGN_IN_REQUIRED, CUSTOMERS);
            // Exactly the error-model members with no arguments: no member can carry the attempt.
            JsonNode body = problem(read);
            assertThat(fieldNames(body)).as(who)
                    .containsExactlyInAnyOrder("type", "title", "status", "detail", "instance", "code", "args");
            assertThat(textValues(body.path("args"))).as(who).isEmpty();
            assertThat(read.getBody()).as(who).doesNotContain(credentials[0]);

            ResponseEntity<String> write = send(client, HttpMethod.POST, CUSTOMERS, () -> ADD_BODY);
            assertProblem(write, HttpStatus.UNAUTHORIZED, APP0401, SIGN_IN_REQUIRED, CUSTOMERS);
            assertStored(Effect.NONE, "after a POST by " + who);

            ResponseEntity<String> publicRead = send(client, HttpMethod.GET, "/api/messages", null);
            assertProblem(publicRead, HttpStatus.UNAUTHORIZED, APP0401, SIGN_IN_REQUIRED, "/api/messages");
        }
    }

    /**
     * The Basic challenge {@code WWW-Authenticate: Basic realm="customer-master"} accompanies a 401
     * only when the request carries no {@code X-Requested-With} header, whether the credentials are
     * missing or wrong; the SPA's requests, which always carry it, get the same problem body without
     * the header, and a 403 never challenges.
     */
    @Test
    void challengeHeaderOnlyWithoutXhr() {
        ResponseEntity<String> anonymous = send(anonymous(), HttpMethod.GET, CUSTOMERS, null);
        assertProblem(anonymous, HttpStatus.UNAUTHORIZED, APP0401, SIGN_IN_REQUIRED, CUSTOMERS);
        assertThat(anonymous.getHeaders().get(HttpHeaders.WWW_AUTHENTICATE)).containsExactly(BASIC_CHALLENGE);

        ResponseEntity<String> anonymousSpa = send(anonymousXhr(), HttpMethod.GET, CUSTOMERS, null);
        assertProblem(anonymousSpa, HttpStatus.UNAUTHORIZED, APP0401, SIGN_IN_REQUIRED, CUSTOMERS);
        assertNoChallenge(anonymousSpa);

        ResponseEntity<String> wrongPassword = send(as(MAINTENANCE_USER, "wrong"), HttpMethod.GET, CUSTOMERS, null);
        assertProblem(wrongPassword, HttpStatus.UNAUTHORIZED, APP0401, SIGN_IN_REQUIRED, CUSTOMERS);
        assertThat(wrongPassword.getHeaders().get(HttpHeaders.WWW_AUTHENTICATE)).containsExactly(BASIC_CHALLENGE);

        ResponseEntity<String> wrongPasswordSpa =
                send(asXhr(MAINTENANCE_USER, "wrong"), HttpMethod.GET, CUSTOMERS, null);
        assertProblem(wrongPasswordSpa, HttpStatus.UNAUTHORIZED, APP0401, SIGN_IN_REQUIRED, CUSTOMERS);
        assertNoChallenge(wrongPasswordSpa);

        ResponseEntity<String> forbidden = send(inquiry(), HttpMethod.POST, CUSTOMERS, () -> ADD_BODY);
        assertProblem(forbidden, HttpStatus.FORBIDDEN, APP0403, NOT_AUTHORIZED, CUSTOMERS);
        assertNoChallenge(forbidden);
    }

    /**
     * Authorization is decided before the body is read or validated: an empty, malformed or
     * otherwise invalid body, or a malformed id, is answered 401 without credentials and 403 as
     * {@code INQUIRY}, never 400 or 422. None of these denied writes uses up a customer id, so the
     * next authorized add still receives the id after the setup customer; once authorized, the same
     * empty body does reach the field rules.
     */
    @Test
    void authenticationPrecedesValidation() {
        List<String> invalidBodies = List.of("{}", "{", "not json", "{\"custId\":\"ZZZZ\"}");
        String customer = CUSTOMER.replace(ID_VARIABLE, custId);
        for (String invalid : invalidBodies) {
            Supplier<String> body = () -> invalid;

            assertProblem(send(anonymous(), HttpMethod.POST, CUSTOMERS, body),
                    HttpStatus.UNAUTHORIZED, APP0401, SIGN_IN_REQUIRED, CUSTOMERS);
            assertProblem(send(inquiry(), HttpMethod.POST, CUSTOMERS, body),
                    HttpStatus.FORBIDDEN, APP0403, NOT_AUTHORIZED, CUSTOMERS);
            assertProblem(send(anonymous(), HttpMethod.POST, CUSTOMERS + "/review", body),
                    HttpStatus.UNAUTHORIZED, APP0401, SIGN_IN_REQUIRED, CUSTOMERS + "/review");
            assertProblem(send(inquiry(), HttpMethod.POST, CUSTOMERS + "/review", body),
                    HttpStatus.FORBIDDEN, APP0403, NOT_AUTHORIZED, CUSTOMERS + "/review");
            assertProblem(send(anonymous(), HttpMethod.PUT, customer, body),
                    HttpStatus.UNAUTHORIZED, APP0401, SIGN_IN_REQUIRED, customer);
            assertProblem(send(inquiry(), HttpMethod.PUT, customer, body),
                    HttpStatus.FORBIDDEN, APP0403, NOT_AUTHORIZED, customer);
        }
        // A path id the controller would reject with 400 is still denied first.
        String malformedId = CUSTOMERS + "/abc";
        assertProblem(send(anonymous(), HttpMethod.PUT, malformedId, () -> UPDATE_BODY),
                HttpStatus.UNAUTHORIZED, APP0401, SIGN_IN_REQUIRED, malformedId);
        assertProblem(send(inquiry(), HttpMethod.PUT, malformedId, () -> UPDATE_BODY),
                HttpStatus.FORBIDDEN, APP0403, NOT_AUTHORIZED, malformedId);
        assertStored(Effect.NONE, "after the denied writes");

        ResponseEntity<String> added = send(maintenance(), HttpMethod.POST, CUSTOMERS, () -> ADD_BODY);
        assertThat(added.getStatusCode()).as("authorized add (body %s)", added.getBody())
                .isEqualTo(HttpStatus.CREATED);
        assertThat(json(added).path("custId").asText())
                .as("the denied adds consumed no customer id")
                .isEqualTo("EEEG");

        ResponseEntity<String> validated = send(maintenance(), HttpMethod.POST, CUSTOMERS, () -> "{}");
        assertThat(validated.getStatusCode()).as("authorized empty add (body %s)", validated.getBody())
                .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(problem(validated).path("code").asText()).isEqualTo("DEM0502");
    }

    /**
     * {@code /error} is not public: the security rules permit only the container's ERROR dispatch to
     * it, so a direct anonymous request is answered 401 {@code APP0401} like any protected path, and
     * a direct authenticated request, which carries no error attributes, is answered 404
     * {@code APP0400} by {@code ProblemErrorController}. Both are problem+json, never Spring Boot's
     * error format.
     */
    @Test
    void errorPathNotPublic() {
        ResponseEntity<String> anonymous = send(anonymous(), HttpMethod.GET, "/error", null);
        assertProblem(anonymous, HttpStatus.UNAUTHORIZED, APP0401, SIGN_IN_REQUIRED, "/error");
        assertThat(anonymous.getHeaders().get(HttpHeaders.WWW_AUTHENTICATE)).containsExactly(BASIC_CHALLENGE);

        assertProblem(send(anonymous(), HttpMethod.POST, "/error", () -> "{}"),
                HttpStatus.UNAUTHORIZED, APP0401, SIGN_IN_REQUIRED, "/error");

        ResponseEntity<String> inquiry = send(inquiry(), HttpMethod.GET, "/error", null);
        assertProblem(inquiry, HttpStatus.NOT_FOUND, APP0400, ERROR_NOT_FOUND, "/error");
        assertThat(fieldNames(problem(inquiry))).doesNotContain("timestamp", "error", "path");
    }

    /**
     * A path no rule names falls under "anything else: authenticated": without credentials it is
     * answered 401 {@code APP0401}, so the API's routes cannot be probed anonymously, and only an
     * authenticated caller learns that it does not exist, from a 404 {@code APP0400}.
     */
    @Test
    void unknownPathRequiresAuthentication() {
        assertProblem(send(anonymous(), HttpMethod.GET, "/api/nope", null),
                HttpStatus.UNAUTHORIZED, APP0401, SIGN_IN_REQUIRED, "/api/nope");
        assertProblem(send(anonymous(), HttpMethod.GET, "/nope", null),
                HttpStatus.UNAUTHORIZED, APP0401, SIGN_IN_REQUIRED, "/nope");
        assertProblem(send(anonymous(), HttpMethod.POST, "/api/nope", () -> "{}"),
                HttpStatus.UNAUTHORIZED, APP0401, SIGN_IN_REQUIRED, "/api/nope");

        ResponseEntity<String> anonymousSpa = send(anonymousXhr(), HttpMethod.GET, "/api/nope", null);
        assertProblem(anonymousSpa, HttpStatus.UNAUTHORIZED, APP0401, SIGN_IN_REQUIRED, "/api/nope");
        assertNoChallenge(anonymousSpa);

        assertProblem(send(inquiry(), HttpMethod.GET, "/api/nope", null),
                HttpStatus.NOT_FOUND, APP0400, NO_SUCH_RESOURCE, "/api/nope");
        assertProblem(send(maintenance(), HttpMethod.GET, "/api/nope", null),
                HttpStatus.NOT_FOUND, APP0400, NO_SUCH_RESOURCE, "/api/nope");
    }

    /**
     * Sends one request and reads its response whatever the status.
     *
     * @param client the caller's client
     * @param method the HTTP method
     * @param uri the path, already resolved
     * @param body the JSON body supplier, or {@code null} to send no body and no {@code Content-Type}
     * @return the response with its body as text
     */
    private ResponseEntity<String> send(RestClient client, HttpMethod method, String uri,
            @Nullable Supplier<String> body) {
        RestClient.RequestBodySpec request = client.method(method).uri(uri);
        if (body != null) {
            request = request.contentType(MediaType.APPLICATION_JSON).body(body.get());
        }
        return request.retrieve().toEntity(String.class);
    }

    /**
     * Asserts the status a matrix caller received and, for an error, the problem body: the full
     * {@code APP0401} or {@code APP0403} body for 401 and 403, and {@code APP0400} for any other 4xx.
     *
     * @param label the matrix row
     * @param caller who sent the request
     * @param response the response
     * @param expected the expected status code
     * @param uri the request path, expected as the problem's {@code instance}
     */
    private void assertOutcome(String label, Caller caller, ResponseEntity<String> response, int expected,
            String uri) {
        assertThat(response.getStatusCode().value())
                .as("%s as %s: status (body %s)", label, caller, response.getBody())
                .isEqualTo(expected);
        if (expected == HttpStatus.UNAUTHORIZED.value()) {
            assertProblem(response, HttpStatus.UNAUTHORIZED, APP0401, SIGN_IN_REQUIRED, uri);
        } else if (expected == HttpStatus.FORBIDDEN.value()) {
            assertProblem(response, HttpStatus.FORBIDDEN, APP0403, NOT_AUTHORIZED, uri);
        } else if (HttpStatus.valueOf(expected).is4xxClientError()) {
            assertThat(problem(response).path("code").asText())
                    .as("%s as %s: problem code", label, caller)
                    .isEqualTo(APP0400);
        }
    }

    /**
     * Asserts a problem+json response: its status, and the members {@code type}, {@code title},
     * {@code status}, {@code detail}, {@code instance} and {@code code}.
     *
     * @param response the response
     * @param status the expected status
     * @param code the expected catalog key
     * @param detail the expected catalog text
     * @param instance the expected request path
     */
    private void assertProblem(ResponseEntity<String> response, HttpStatus status, String code, String detail,
            String instance) {
        assertThat(response.getStatusCode())
                .as("status of %s (body %s)", instance, response.getBody())
                .isEqualTo(status);
        JsonNode problem = problem(response);
        assertThat(problem.path("type").asText()).isEqualTo(ProblemFactory.TYPE_PREFIX + code);
        assertThat(problem.path("title").asText()).isEqualTo(status.getReasonPhrase());
        assertThat(problem.path("status").asInt()).isEqualTo(status.value());
        assertThat(problem.path("code").asText()).isEqualTo(code);
        assertThat(problem.path("detail").asText()).isEqualTo(detail);
        assertThat(problem.path("instance").asText()).isEqualTo(instance);
    }

    /**
     * Asserts that a response carries no {@code WWW-Authenticate} header.
     *
     * @param response the response
     */
    private static void assertNoChallenge(ResponseEntity<String> response) {
        assertThat(response.getHeaders().containsKey(HttpHeaders.WWW_AUTHENTICATE))
                .as("WWW-Authenticate on %s: %s", response.getStatusCode(),
                        response.getHeaders().get(HttpHeaders.WWW_AUTHENTICATE))
                .isFalse();
    }

    /**
     * Asserts a response's status and returns the response.
     *
     * @param response the response
     * @param status the expected status
     * @return {@code response}
     */
    private static ResponseEntity<String> expectStatus(ResponseEntity<String> response, HttpStatus status) {
        assertThat(response.getStatusCode()).as("status (body %s)", response.getBody()).isEqualTo(status);
        return response;
    }

    /**
     * Asserts the stored customers: how many there are and the setup customer's version.
     *
     * @param effect the expected state
     * @param when when the state is checked, for the failure message
     */
    private void assertStored(Effect effect, String when) {
        Long customers = jdbcTemplate.queryForObject(COUNT_CUSTOMERS, Long.class);
        assertThat(customers).as("customers %s", when).isEqualTo(effect.customers);
        Long version = jdbcTemplate.queryForObject(ROW_VERSION, Long.class, custId);
        assertThat(version).as("row_version of %s %s", custId, when).isEqualTo(effect.setupRowVersion);
    }

    /**
     * Returns the text of each element of a JSON array.
     *
     * @param array the node expected to be an array
     * @return the element texts, in order
     */
    private static List<String> textValues(JsonNode array) {
        assertThat(array.isArray()).as("an array: %s", array).isTrue();
        List<String> values = new ArrayList<>();
        array.forEach(element -> values.add(element.asText()));
        return values;
    }

    /**
     * Returns the member names of a JSON object.
     *
     * @param object the node expected to be an object
     * @return its member names, in order
     */
    private static List<String> fieldNames(JsonNode object) {
        assertThat(object.isObject()).as("an object: %s", object).isTrue();
        List<String> names = new ArrayList<>();
        object.fieldNames().forEachRemaining(names::add);
        return names;
    }
}

