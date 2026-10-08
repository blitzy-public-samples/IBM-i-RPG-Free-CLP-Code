package com.democorp.customermaster.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.democorp.customermaster.domain.CustomerId;
import com.democorp.customermaster.service.CustomerMaintenanceService;
import com.democorp.customermaster.service.CustomerSearchService;
import com.democorp.customermaster.service.exception.CustomerLockedException;
import com.democorp.customermaster.service.exception.CustomerNotFoundException;
import com.democorp.customermaster.support.AbstractPostgresIT;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.Filter;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Proves the error model end to end: every error the API sends is an RFC 9457
 * {@code application/problem+json} body built by {@link ProblemFactory}, and none carries anything
 * internal.
 *
 * <p><b>What it replaces.</b> On the IBM i, {@code SQLProblem} ({@code SRV_SQL}) copied the SQLSTATE and
 * the SQL message text into a CPF9898 escape message and dumped the program
 * [Service_Pgms/SRV_SQL.SQLRPGLE:20-57]; MTNCUSTR sent DEM1001 with SQLERRMC as message data that the
 * DEM1001 text never displays [5250_Subfile/MTNCUSTR.SQLRPGLE:600-602], [5250_Subfile/CRTMSGF.CLLE:38-39].
 * Here the members are {@code type} ({@value ProblemFactory#TYPE_PREFIX}{@code <code>}), {@code title}
 * (the reason phrase), {@code status}, {@code instance} (the request path), {@code detail}, {@code code}
 * and {@code args}; a 500 adds an {@code errorId} that also appears on its ERROR log line beside the
 * SQLSTATE, and no body carries SQL text, an SQLSTATE, a stack trace or a class name.
 *
 * <p><b>Paths covered.</b>
 * <ul>
 *   <li>A domain exception handled by {@code ApiExceptionHandler}: 404 DEM0599 and 409 DEM1001.</li>
 *   <li>The catch-all: a {@code DuplicateKeyException} whose messages quote SQL and a secret becomes an
 *       opaque 500 DEM9999.</li>
 *   <li>Framework 4xx (405, 415, unreadable JSON, an unknown route) keep their status with APP0400.</li>
 *   <li>The security filter chain, before any handler mapping: an anonymous request to an unknown route is
 *       rejected and answered by {@code SecurityConfig}'s authentication entry point (401 APP0401).</li>
 *   <li>ERROR dispatch answered by {@code ProblemErrorController}: an exception thrown by a servlet filter
 *       outside any controller (500 DEM9999).</li>
 *   <li>An {@code Accept: text/html} request still receives problem+json, never an HTML page, and no
 *       body ever holds Spring Boot's {@code timestamp}, {@code error} or {@code path} members.</li>
 * </ul>
 *
 * <p><b>Context variant.</b> This class is the fourth variant of the suite's context policy
 * ({@code AbstractPostgresIT}): exactly the two {@link MockitoBean} services below, which let a test
 * throw any exception from inside a controller call without touching the database, plus the nested
 * {@link ThrowingFilterConfig}, the only way to make a servlet filter fail outside every controller.
 * Mockito resets both mocks after each test, so no test resets anything itself.
 *
 * <p>Console output is captured by {@link OutputCaptureExtension}; the tests that read the log take a
 * {@link CapturedOutput} parameter.
 */
@ExtendWith(OutputCaptureExtension.class)
@DisplayName("Error model: problem+json everywhere, nothing internal leaks")
class ErrorModelIT extends AbstractPostgresIT {

    /** The secret every provoked failure quotes in its messages; it must never reach a body. */
    private static final String SECRET = "secret-xyz";

    /** The id used on every customer path; any valid id would do, because the services are mocked. */
    private static final String CUST_ID = "EEEF";

    /** The path of one customer. */
    private static final String CUSTOMER_PATH = "/api/customers/" + CUST_ID;

    /** The collection path of search and add. */
    private static final String CUSTOMERS_PATH = "/api/customers";

    /** A route no controller, resource handler or filter serves. */
    private static final String UNKNOWN_ROUTE = "/api/does-not-exist";

    /** The members {@code ProblemFactory.create} writes for every problem, and no others. */
    private static final Set<String> BASE_MEMBERS =
            Set.of("type", "title", "status", "detail", "instance", "code", "args");

    /** Spring Boot's own error-format members, which no problem body may hold. */
    private static final List<String> BOOT_ERROR_MEMBERS = List.of("timestamp", "error", "path");

    /** Members that would expose internals: Boot's {@code trace} and {@code exception}, a raw message. */
    private static final List<String> INTERNAL_MEMBERS = List.of("trace", "exception", "message");

    /** A canonical lower-case UUID, the form of every {@code errorId}. */
    private static final Pattern UUID_FORM =
            Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

    /** A console log line at level ERROR in Spring Boot's default pattern. */
    private static final Pattern ERROR_LINE = Pattern.compile("^\\S+\\s+ERROR\\s.*");

    /** Mocked so a search can throw a persistence failure without a database fault. */
    @MockitoBean
    CustomerSearchService searchService;

    /** Mocked so get and update can throw domain exceptions on demand. */
    @MockitoBean
    CustomerMaintenanceService maintenanceService;

    @Test
    @DisplayName("domain error: 404 DEM0599 with type, title, status, instance, detail and code")
    void problemMembersOnDomainError() {
        when(maintenanceService.get(any())).thenThrow(new CustomerNotFoundException(CustomerId.parse(CUST_ID)));

        ResponseEntity<String> response = inquiry().get().uri(CUSTOMER_PATH)
                .retrieve()
                .toEntity(String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        JsonNode problem = problem(response);
        assertThat(problem.path("type").asText()).isEqualTo(ProblemFactory.TYPE_PREFIX + "DEM0599");
        assertThat(problem.path("type").asText()).isEqualTo("urn:customer-master:problem:DEM0599");
        assertThat(problem.path("title").asText()).isEqualTo("Not Found");
        assertThat(problem.path("status").asInt()).isEqualTo(404);
        assertThat(problem.path("instance").asText()).isEqualTo(CUSTOMER_PATH);
        assertThat(problem.path("detail").asText()).isEqualTo("Customer deleted. Exit & redo search.");
        assertThat(problem.path("code").asText()).isEqualTo("DEM0599");
        assertThat(problem.path("args").isArray()).isTrue();
        assertThat(problem.path("args")).isEmpty();
        assertThat(memberNames(problem)).isSubsetOf(BASE_MEMBERS);
        verify(maintenanceService).get(CustomerId.parse(CUST_ID));
    }

    @Test
    @DisplayName("persistence failure: opaque 500 DEM9999, errorId and SQLSTATE only on the ERROR log line")
    void dataAccessFailureIsOpaque500(CapturedOutput output) {
        when(searchService.search(any(), any(), any(), anyBoolean(), any(), any()))
                .thenThrow(new DuplicateKeyException("insert into custmast " + SECRET,
                        new SQLException("duplicate key value violates unique constraint " + SECRET, "23505")));

        ResponseEntity<String> response = inquiry().get().uri(CUSTOMERS_PATH)
                .retrieve()
                .toEntity(String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        JsonNode problem = problem(response);
        assertThat(problem.path("code").asText()).isEqualTo("DEM9999");
        assertThat(problem.path("detail").asText()).isEqualTo("Program Error! Please contact IT now.");
        assertThat(problem.path("type").asText()).isEqualTo("urn:customer-master:problem:DEM9999");
        assertThat(problem.path("title").asText()).isEqualTo("Internal Server Error");
        assertThat(problem.path("status").asInt()).isEqualTo(500);
        assertThat(problem.path("instance").asText()).isEqualTo(CUSTOMERS_PATH);
        String errorId = assertErrorId(problem);
        assertThat(response.getBody()).doesNotContain(
                SECRET, "23505", "SQLException", "DuplicateKey", "custmast", "insert", "java.",
                "org.springframework", "\tat ");
        assertNoInternalMembers(problem);
        assertNoBootErrorMembers(problem);

        assertThat(errorLines(output))
                .as("one ERROR line carries the errorId of the body and the SQLSTATE")
                .anySatisfy(line -> assertThat(line).contains(errorId).contains("23505"));
        verify(searchService).search(any(), any(), any(), anyBoolean(), any(), any());
    }

    @Test
    @DisplayName("row lock: 409 DEM1001 with no data beside the catalog text")
    void lockedIs409WithoutData() {
        when(maintenanceService.update(any(), any(), anyLong())).thenThrow(new CustomerLockedException());

        ResponseEntity<String> response = maintenance().put().uri(CUSTOMER_PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .body(validUpdateBody())
                .retrieve()
                .toEntity(String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        JsonNode problem = problem(response);
        assertThat(problem.path("code").asText()).isEqualTo("DEM1001");
        assertThat(problem.path("detail").asText()).isEqualTo("Customer being updated by another user or job.");
        assertThat(problem.path("type").asText()).isEqualTo("urn:customer-master:problem:DEM1001");
        assertThat(problem.path("title").asText()).isEqualTo("Conflict");
        assertThat(problem.path("status").asInt()).isEqualTo(409);
        assertThat(problem.path("instance").asText()).isEqualTo(CUSTOMER_PATH);
        assertThat(memberNames(problem))
                .as("no SQLERRMC-like data travels with DEM1001")
                .isSubsetOf(BASE_MEMBERS);
        if (problem.has("args")) {
            assertThat(problem.path("args").isArray()).isTrue();
            assertThat(problem.path("args")).isEmpty();
        }
        verify(maintenanceService).update(eq(CustomerId.parse(CUST_ID)), any(), eq(0L));
    }

    @Test
    @DisplayName("framework 4xx keep their status as APP0400 problems: 405, 415 and unreadable JSON")
    void frameworkErrorsAreProblems() {
        ResponseEntity<String> methodNotAllowed = maintenance().delete().uri(CUSTOMER_PATH)
                .retrieve()
                .toEntity(String.class);

        assertThat(methodNotAllowed.getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        JsonNode notAllowed = problem(methodNotAllowed);
        assertThat(notAllowed.path("status").asInt()).isEqualTo(405);
        assertThat(notAllowed.path("code").asText()).isEqualTo("APP0400");
        assertThat(notAllowed.path("type").asText()).isEqualTo("urn:customer-master:problem:APP0400");
        assertThat(notAllowed.path("title").asText()).isEqualTo("Method Not Allowed");
        assertThat(notAllowed.path("detail").asText()).startsWith("Request is not valid: ");
        assertThat(notAllowed.path("instance").asText()).isEqualTo(CUSTOMER_PATH);
        assertThat(methodNotAllowed.getHeaders().getAllow())
                .as("the framework's Allow header survives the problem body")
                .contains(HttpMethod.GET, HttpMethod.PUT);
        assertNoBootErrorMembers(notAllowed);

        ResponseEntity<String> unsupportedType = maintenance().post().uri(CUSTOMERS_PATH)
                .contentType(MediaType.TEXT_PLAIN)
                .body("hello")
                .retrieve()
                .toEntity(String.class);

        assertThat(unsupportedType.getStatusCode()).isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
        JsonNode unsupported = problem(unsupportedType);
        assertThat(unsupported.path("status").asInt()).isEqualTo(415);
        assertThat(unsupported.path("code").asText()).isEqualTo("APP0400");
        assertThat(unsupported.path("title").asText()).isEqualTo("Unsupported Media Type");
        assertThat(unsupported.path("detail").asText()).startsWith("Request is not valid: ");
        assertNoBootErrorMembers(unsupported);

        // A String body is written as is by the String converter, so the malformed JSON reaches the server.
        ResponseEntity<String> unreadableBody = maintenance().post().uri(CUSTOMERS_PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{not json")
                .retrieve()
                .toEntity(String.class);

        assertThat(unreadableBody.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        JsonNode unreadable = problem(unreadableBody);
        assertThat(unreadable.path("status").asInt()).isEqualTo(400);
        assertThat(unreadable.path("code").asText()).isEqualTo("APP0400");
        assertThat(unreadable.path("title").asText()).isEqualTo("Bad Request");
        assertThat(unreadable.path("detail").asText()).startsWith("Request is not valid: ");
        assertThat(unreadableBody.getBody())
                .as("no parser message or class name reaches the body")
                .doesNotContain("JsonParseException", "Unexpected character", "java.", "com.fasterxml");
        assertNoBootErrorMembers(unreadable);

        verifyNoInteractions(maintenanceService);
    }

    @Test
    @DisplayName("unknown route, authenticated: 404 APP0400 problem without Boot's error members")
    void unknownRouteAuthenticated() {
        ResponseEntity<String> response = inquiry().get().uri(UNKNOWN_ROUTE)
                .retrieve()
                .toEntity(String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        JsonNode problem = problem(response);
        assertThat(problem.path("status").asInt()).isEqualTo(404);
        assertThat(problem.path("code").asText()).isEqualTo("APP0400");
        assertThat(problem.path("type").asText()).isEqualTo("urn:customer-master:problem:APP0400");
        assertThat(problem.path("title").asText()).isEqualTo("Not Found");
        assertThat(problem.path("detail").asText()).startsWith("Request is not valid: ");
        assertThat(problem.path("instance").asText()).isEqualTo(UNKNOWN_ROUTE);
        assertNoBootErrorMembers(problem);
    }

    @Test
    @DisplayName("unknown route with Accept: text/html: still 404 application/problem+json, never HTML")
    void unknownRouteWithHtmlAccept() {
        ResponseEntity<String> response = inquiry().get().uri(UNKNOWN_ROUTE)
                .header(HttpHeaders.ACCEPT, MediaType.TEXT_HTML_VALUE)
                .retrieve()
                .toEntity(String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        MediaType contentType = response.getHeaders().getContentType();
        assertThat(contentType).isNotNull();
        assertThat(contentType.getType() + "/" + contentType.getSubtype())
                .isEqualTo(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        assertThat(response.getBody()).isNotBlank();
        assertThat(response.getBody().strip()).doesNotStartWith("<");
        JsonNode problem = problem(response);
        assertThat(problem.path("status").asInt()).isEqualTo(404);
        assertThat(problem.path("code").asText()).isEqualTo("APP0400");
        assertNoBootErrorMembers(problem);
    }

    @Test
    @DisplayName("unknown route, anonymous: 401 APP0401 problem")
    void unknownRouteAnonymous() {
        ResponseEntity<String> response = anonymous().get().uri(UNKNOWN_ROUTE)
                .retrieve()
                .toEntity(String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        JsonNode problem = problem(response);
        assertThat(problem.path("status").asInt()).isEqualTo(401);
        assertThat(problem.path("code").asText()).isEqualTo("APP0401");
        assertThat(problem.path("type").asText()).isEqualTo("urn:customer-master:problem:APP0401");
        assertThat(problem.path("title").asText()).isEqualTo("Unauthorized");
        assertThat(problem.path("detail").asText()).isEqualTo("Sign in required.");
        assertNoBootErrorMembers(problem);
    }

    @Test
    @DisplayName("filter failure outside any controller: ERROR dispatch answers 500 DEM9999 with a logged errorId")
    void filterExceptionIsErrorDispatched500(CapturedOutput output) {
        String path = ThrowingFilterConfig.PATH + "/x";

        ResponseEntity<String> response = maintenance().get().uri(path)
                .retrieve()
                .toEntity(String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        JsonNode problem = problem(response);
        assertThat(problem.path("code").asText()).isEqualTo("DEM9999");
        assertThat(problem.path("detail").asText()).isEqualTo("Program Error! Please contact IT now.");
        assertThat(problem.path("type").asText()).isEqualTo("urn:customer-master:problem:DEM9999");
        assertThat(problem.path("title").asText()).isEqualTo("Internal Server Error");
        assertThat(problem.path("status").asInt()).isEqualTo(500);
        assertThat(problem.path("instance").asText())
                .as("instance names the request that failed, not /error")
                .isEqualTo(path);
        String errorId = assertErrorId(problem);
        assertThat(response.getBody()).doesNotContain(SECRET, "IllegalStateException", "java.", "\tat ");
        assertNoInternalMembers(problem);
        assertNoBootErrorMembers(problem);

        assertThat(errorLines(output))
                .as("the ERROR line of the failure carries the errorId of the body")
                .anySatisfy(line -> assertThat(line).contains(errorId));
        verifyNoInteractions(searchService, maintenanceService);
    }

    /**
     * Returns a {@code PUT} body that passes every request-binding rule: the nine data fields within their
     * column widths and {@code version} 0.
     *
     * @return the body, in screen order
     */
    private static Map<String, Object> validUpdateBody() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("active", "Y");
        body.put("name", "x co");
        body.put("addr", "1 a st");
        body.put("city", "erie");
        body.put("state", "PA");
        body.put("zip", "16501");
        body.put("acctPhone", "(814) 555-0101");
        body.put("acctMgr", "pat lee");
        body.put("corpPhone", "(814) 555-0100");
        body.put("version", 0);
        return body;
    }

    /**
     * Asserts that a 500 problem carries an {@code errorId} in canonical UUID form and returns it.
     *
     * @param problem the parsed problem body
     * @return the {@code errorId}
     */
    private static String assertErrorId(JsonNode problem) {
        assertThat(problem.path("errorId").isTextual()).as("errorId is a string").isTrue();
        String errorId = problem.path("errorId").asText();
        assertThat(errorId).matches(UUID_FORM);
        assertThat(UUID.fromString(errorId).toString()).isEqualTo(errorId);
        return errorId;
    }

    /**
     * Asserts that a problem holds none of the members that would expose internals.
     *
     * @param problem the parsed problem body
     */
    private static void assertNoInternalMembers(JsonNode problem) {
        assertThat(memberNames(problem)).doesNotContainAnyElementsOf(INTERNAL_MEMBERS);
    }

    /**
     * Asserts that a problem holds none of Spring Boot's own error-format members.
     *
     * @param problem the parsed problem body
     */
    private static void assertNoBootErrorMembers(JsonNode problem) {
        assertThat(memberNames(problem)).doesNotContainAnyElementsOf(BOOT_ERROR_MEMBERS);
    }

    /**
     * Returns the top-level member names of a JSON object.
     *
     * @param node the parsed body
     * @return the member names in document order; empty when the node is not an object
     */
    private static List<String> memberNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    /**
     * Returns the captured console lines at level ERROR.
     *
     * @param output the captured console output
     * @return the lines whose level field is {@code ERROR}
     */
    private static List<String> errorLines(CapturedOutput output) {
        return output.getAll().lines().filter(line -> ERROR_LINE.matcher(line).matches()).toList();
    }

    /**
     * A test-only servlet filter that throws outside every controller, so the container's ERROR dispatch
     * and {@code ProblemErrorController} answer the request. Its URL pattern matches no real endpoint, so
     * it affects nothing else; it runs on REQUEST dispatches only, the registration's default, so the
     * forward to {@code /error} passes it. The web-application condition keeps it out of a non-web
     * context that scans the test classes.
     */
    @TestConfiguration(proxyBeanMethods = false)
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    static class ThrowingFilterConfig {

        /** Path prefix of the throwing filter. */
        static final String PATH = "/test-only/filter-failure";

        /**
         * Registers the throwing filter after every application filter, the Spring Security chain
         * included, so an authenticated request reaches it.
         *
         * @return the registration of the filter on {@value #PATH}{@code /*}
         */
        @Bean
        FilterRegistrationBean<Filter> throwingTestFilter() {
            FilterRegistrationBean<Filter> registration = new FilterRegistrationBean<>((request, response, chain) -> {
                throw new IllegalStateException("filter failure " + SECRET);
            });
            registration.addUrlPatterns(PATH + "/*");
            registration.setOrder(Ordered.LOWEST_PRECEDENCE);
            return registration;
        }
    }
}
