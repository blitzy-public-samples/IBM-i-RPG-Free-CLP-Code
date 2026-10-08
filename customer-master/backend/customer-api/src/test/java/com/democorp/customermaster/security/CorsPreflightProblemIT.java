package com.democorp.customermaster.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.democorp.customermaster.support.AbstractPostgresIT;
import com.democorp.customermaster.support.DatabaseCleaner;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;
import org.springframework.web.servlet.DispatcherServlet;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.handler.AbstractHandlerMapping;

/**
 * Proves, against the running server, that a cross-origin request Spring MVC rejects is answered
 * 403 {@code APP0403} {@code application/problem+json}, never Spring's plain-text
 * {@code Invalid CORS request}, while no CORS is ever granted and every other CORS outcome is
 * unchanged.
 *
 * <p><b>Rejected.</b> An authenticated preflight ({@code OPTIONS} with {@code Origin} and
 * {@code Access-Control-Request-Method}) from a foreign origin, for every kind of handler mapping: the
 * controllers (customers, states, session, API docs), the Actuator endpoints, the Swagger UI
 * resources, and an unknown path, which only the static resource mapping matches. So is an anonymous
 * request for a public Swagger UI resource whose {@code Origin} cannot be parsed. Each answer keeps
 * the {@code Vary} and security headers and carries no {@code Access-Control-*} header.
 *
 * <p><b>Unchanged.</b> An anonymous preflight is answered 401 {@code APP0401} by the entry point; a
 * same-origin preflight and a plain {@code OPTIONS} are answered 200 with no body; a cross-origin
 * {@code GET} succeeds without any {@code Access-Control-*} header.
 *
 * <p>Uses the base context of {@link AbstractPostgresIT}: no {@code @MockitoBean}, no {@code @Import}
 * and no nested configuration, so this class adds no context variant. The JDK HTTP client sends
 * {@code Origin} and {@code Access-Control-Request-Method} as given.
 */
class CorsPreflightProblemIT extends AbstractPostgresIT {

    /** An origin other than the server's own {@code http://localhost:<port>}. */
    private static final String FOREIGN_ORIGIN = "https://example.org";

    /** An {@code Origin} value Spring cannot parse: the port is not a number. */
    private static final String UNPARSABLE_ORIGIN = "http://example.org:abc";

    /** A public static resource of the Swagger UI, served by the resource handler mapping. */
    private static final String SWAGGER_UI_RESOURCE = "/swagger-ui/index.html";

    /** The {@code Vary} values Spring's CORS processing adds to the responses it handles. */
    private static final List<String> VARY = List.of(HttpHeaders.ORIGIN,
            HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS);

    /** The name prefix of every CORS response header. */
    private static final String CORS_HEADER_PREFIX = "Access-Control-";

    /** The members of a 403 body, in order. */
    private static final List<String> MEMBERS =
            List.of("type", "title", "status", "detail", "instance", "code", "args");

    /** Every bean of the application context, for the handler mapping check. */
    @Autowired
    private ApplicationContext context;

    /** The servlet whose handler mappings answer every request. */
    @Autowired
    private DispatcherServlet dispatcherServlet;

    /**
     * Authenticated preflights from a foreign origin: both roles, each kind of handler mapping, and an
     * unknown path.
     *
     * @return the user label, the path and the preflight's requested method
     */
    static Stream<Arguments> authenticatedPreflights() {
        Stream.Builder<Arguments> cases = Stream.builder();
        for (String user : List.of(INQUIRY_USER, MAINTENANCE_USER)) {
            cases.add(Arguments.of(user, "/api/customers", HttpMethod.GET));
            cases.add(Arguments.of(user, "/api/customers/AAAB", HttpMethod.GET));
            cases.add(Arguments.of(user, "/api/customers/AAAB", HttpMethod.PUT));
            cases.add(Arguments.of(user, "/api/customers/review", HttpMethod.POST));
            cases.add(Arguments.of(user, "/api/states", HttpMethod.GET));
            cases.add(Arguments.of(user, "/api/session", HttpMethod.GET));
            cases.add(Arguments.of(user, "/api/nope", HttpMethod.GET));
            cases.add(Arguments.of(user, "/actuator/health", HttpMethod.GET));
            cases.add(Arguments.of(user, "/v3/api-docs", HttpMethod.GET));
            cases.add(Arguments.of(user, SWAGGER_UI_RESOURCE, HttpMethod.GET));
        }
        return cases.build();
    }

    @ParameterizedTest(name = "{0}: preflight {2} {1}")
    @MethodSource("authenticatedPreflights")
    void authenticatedCrossOriginPreflightIsAnswered403App0403ProblemJson(String user, String path,
            HttpMethod requestedMethod) {
        RestClient client = INQUIRY_USER.equals(user) ? inquiry() : maintenance();

        ResponseEntity<String> response = preflight(client, path, FOREIGN_ORIGIN, requestedMethod);

        assertForbiddenProblem(response, path);
    }

    @Test
    void preflightFromTheSpaWithItsHeaderIsAnsweredTheSameWay() {
        ResponseEntity<String> response = inquiryXhr().options().uri("/api/customers")
                .header(HttpHeaders.ORIGIN, FOREIGN_ORIGIN)
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, HttpMethod.GET.name())
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "authorization,x-requested-with")
                .retrieve().toEntity(String.class);

        assertForbiddenProblem(response, "/api/customers");
    }

    @Test
    void unparsableOriginOnAPublicResourceIsAnswered403App0403ProblemJson() {
        ResponseEntity<String> response = anonymous().get().uri(SWAGGER_UI_RESOURCE)
                .header(HttpHeaders.ORIGIN, UNPARSABLE_ORIGIN)
                .retrieve().toEntity(String.class);

        assertForbiddenProblem(response, SWAGGER_UI_RESOURCE);
    }

    @Test
    void anonymousPreflightIsStillAnswered401App0401ByTheEntryPoint() {
        ResponseEntity<String> response =
                preflight(anonymous(), "/api/customers", FOREIGN_ORIGIN, HttpMethod.GET);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        JsonNode body = problem(response);
        assertThat(body.path("code").asText()).isEqualTo("APP0401");
        assertThat(body.path("instance").asText()).isEqualTo("/api/customers");
        assertThat(response.getHeaders().getFirst(HttpHeaders.WWW_AUTHENTICATE))
                .isEqualTo(SecurityConfig.BASIC_CHALLENGE);
        assertNoAccessControlHeader(response);
    }

    @Test
    void sameOriginPreflightIsAnswered200WithoutABody() {
        ResponseEntity<String> response = preflight(inquiry(), "/api/customers", baseUrl(), HttpMethod.GET);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNullOrEmpty();
        assertThat(response.getHeaders().getContentType()).isNull();
        assertThat(response.getHeaders().containsKey(HttpHeaders.ALLOW)).isTrue();
        assertThat(response.getHeaders().getVary()).containsAll(VARY);
        assertNoAccessControlHeader(response);
    }

    @Test
    void plainOptionsWithoutOriginIsAnswered200WithTheAllowedMethods() {
        ResponseEntity<String> response = inquiry().options().uri("/api/customers")
                .retrieve().toEntity(String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNullOrEmpty();
        assertThat(response.getHeaders().getAllow()).contains(HttpMethod.GET, HttpMethod.POST);
        assertNoAccessControlHeader(response);
    }

    @Test
    void crossOriginGetSucceedsWithoutAnyCorsGrant() {
        ResponseEntity<String> response = inquiry().get().uri("/api/states")
                .header(HttpHeaders.ORIGIN, FOREIGN_ORIGIN)
                .retrieve().toEntity(String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        MediaType contentType = response.getHeaders().getContentType();
        assertThat(contentType).isNotNull();
        assertThat(contentType.isCompatibleWith(MediaType.APPLICATION_JSON)).isTrue();
        assertThat(json(response)).hasSize(DatabaseCleaner.STATE_COUNT);
        assertNoAccessControlHeader(response);
    }

    @Test
    void everyHandlerMappingOfTheDispatcherUsesTheProblemCorsProcessor() {
        // A request makes sure the dispatcher has initialized its handler mappings.
        assertThat(anonymous().get().uri("/api/messages").retrieve().toBodilessEntity().getStatusCode())
                .isEqualTo(HttpStatus.OK);

        Map<String, AbstractHandlerMapping> beans = context.getBeansOfType(AbstractHandlerMapping.class);
        List<HandlerMapping> used = dispatcherServlet.getHandlerMappings();

        assertThat(beans).isNotEmpty();
        assertThat(beans.values()).allSatisfy(mapping -> assertThat(mapping.getCorsProcessor())
                .isInstanceOf(SecurityConfig.ProblemCorsProcessor.class));
        assertThat(used).isNotNull().isNotEmpty();
        assertThat(used).allSatisfy(mapping -> assertThat(mapping)
                .isInstanceOfSatisfying(AbstractHandlerMapping.class, abstractMapping ->
                        assertThat(abstractMapping.getCorsProcessor())
                                .isInstanceOf(SecurityConfig.ProblemCorsProcessor.class)));
    }

    /**
     * Sends a preflight: {@code OPTIONS} with {@code Origin} and {@code Access-Control-Request-Method}.
     *
     * @param client the client, with or without credentials
     * @param path the request path
     * @param origin the {@code Origin} header value
     * @param requestedMethod the method the preflight asks for
     * @return the response, whatever its status
     */
    private static ResponseEntity<String> preflight(RestClient client, String path, String origin,
            HttpMethod requestedMethod) {
        return client.options().uri(path)
                .header(HttpHeaders.ORIGIN, origin)
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, requestedMethod.name())
                .retrieve().toEntity(String.class);
    }

    /**
     * Asserts the 403 {@code APP0403} problem answer to a rejected cross-origin request, with its
     * {@code Vary} and security headers and without any CORS grant.
     *
     * @param response the response
     * @param path the request path, expected as {@code instance}
     */
    private void assertForbiddenProblem(ResponseEntity<String> response, String path) {
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        JsonNode body = problem(response);
        List<String> members = body.properties().stream().map(Map.Entry::getKey).toList();
        assertThat(members).containsExactlyElementsOf(MEMBERS);
        assertThat(body.path("type").asText()).isEqualTo("urn:customer-master:problem:APP0403");
        assertThat(body.path("title").asText()).isEqualTo("Forbidden");
        assertThat(body.path("status").asInt()).isEqualTo(403);
        assertThat(body.path("detail").asText()).isEqualTo("You are not authorized to perform this action.");
        assertThat(body.path("instance").asText()).isEqualTo(path);
        assertThat(body.path("code").asText()).isEqualTo("APP0403");
        assertThat(body.path("args").isArray()).isTrue();
        assertThat(body.path("args")).isEmpty();
        assertThat(response.getBody())
                .doesNotContain("Invalid CORS request")
                .doesNotContain("example.org");

        HttpHeaders headers = response.getHeaders();
        assertThat(headers.getVary()).containsAll(VARY);
        assertThat(headers.getFirst("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(headers.getFirst("X-Frame-Options")).isEqualTo("DENY");
        assertThat(headers.getCacheControl()).contains("no-store");
        assertNoAccessControlHeader(response);
    }

    /**
     * Asserts that the response grants no CORS: it carries no {@code Access-Control-*} header.
     *
     * @param response the response
     */
    private static void assertNoAccessControlHeader(ResponseEntity<String> response) {
        assertThat(response.getHeaders().keySet())
                .noneMatch(name -> name.regionMatches(
                        true, 0, CORS_HEADER_PREFIX, 0, CORS_HEADER_PREFIX.length()));
    }
}
