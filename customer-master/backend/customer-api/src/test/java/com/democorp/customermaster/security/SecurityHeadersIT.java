package com.democorp.customermaster.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.democorp.customermaster.support.AbstractPostgresIT;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;

/**
 * Proves, against the running server, that every response the security filter chain answers on the
 * API's own port carries exactly one {@code Content-Security-Policy} and exactly one
 * {@code Referrer-Policy: no-referrer}, next to Spring Security's default headers, with no cookie.
 *
 * <p><b>Strict policy</b> ({@value #API_POLICY}): a 200 of the API for a maintenance user, the public
 * message catalog, a 401 with and without {@code X-Requested-With}, a 401 for a wrong password, the 403
 * of an inquiry user's review, the 404 of an unknown API route, the health probe and its ERROR-dispatch
 * 404, and the OpenAPI document as JSON, as YAML and as the Swagger UI's configuration.
 *
 * <p><b>Swagger UI policy</b> ({@value #SWAGGER_UI_POLICY}): the Swagger UI page, its configured path's
 * redirect, a script it loads and an unknown resource under it. Two further tests read what springdoc
 * serves: the page and its configuration load nothing that policy refuses, and the policy's only hash
 * sources are those of the inline stylesheets the Swagger UI scripts render.
 *
 * <p>Uses the base context of {@link AbstractPostgresIT}: no {@code @MockitoBean}, no {@code @Import}
 * and no nested configuration, so this class adds no context variant. The JDK HTTP client follows no
 * redirect, so the redirect's own headers are read.
 */
class SecurityHeadersIT extends AbstractPostgresIT {

    /** The policy of every response outside the Swagger UI. */
    private static final String API_POLICY = "default-src 'none'; frame-ancestors 'none'";

    /** The policy of the Swagger UI's pages and resources. */
    private static final String SWAGGER_UI_POLICY = "default-src 'none'; script-src 'self'; "
            + "style-src 'self' 'sha256-RL3ie0nH+Lzz2YNqQN83mnU0J1ot4QL7b99vMdIX99w='; img-src 'self' data:; "
            + "connect-src 'self'; object-src 'none'; base-uri 'none'; form-action 'self'; "
            + "frame-ancestors 'none'";

    /** The scripts of the Swagger UI page, which render every element the page shows. */
    private static final List<String> SWAGGER_UI_SCRIPTS = List.of(
            "/swagger-ui/swagger-ui-bundle.js",
            "/swagger-ui/swagger-ui-standalone-preset.js");

    /** A {@code <style>} element a React component of those scripts renders, with its literal text. */
    private static final Pattern INLINE_STYLESHEET =
            Pattern.compile("createElement\\(\"style\",null,\"([^\"\\\\]*)\"\\)");

    /** A hash source of a policy directive. */
    private static final Pattern HASH_SOURCE = Pattern.compile("'(sha256-[A-Za-z0-9+/=]+)'");

    /** The name of the content security policy header. */
    private static final String CONTENT_SECURITY_POLICY = "Content-Security-Policy";

    /** The name of the referrer policy header. */
    private static final String REFERRER_POLICY = "Referrer-Policy";

    /** The Swagger UI page springdoc serves. */
    private static final String SWAGGER_UI_PAGE = "/swagger-ui/index.html";

    /** The JSON body of the review request; the 403 is decided before the body is read. */
    private static final String REVIEW_BODY = "{\"purpose\":\"EDIT\"}";

    /** One {@code <script>} element: its attributes and its inline content. */
    private static final Pattern SCRIPT = Pattern.compile("<script\\b([^>]*)>(.*?)</script>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    /** A {@code src} or {@code href} attribute value. */
    private static final Pattern RESOURCE = Pattern.compile("\\b(?:src|href)=\"([^\"]*)\"",
            Pattern.CASE_INSENSITIVE);

    /** Who sends a request. */
    enum Caller {
        /** No {@code Authorization} header. */
        ANONYMOUS,
        /** No {@code Authorization} header, but {@code X-Requested-With: XMLHttpRequest}, as the SPA. */
        ANONYMOUS_SPA,
        /** The inquiry user's name with a wrong password. */
        WRONG_PASSWORD,
        /** The inquiry user. */
        INQUIRY,
        /** The maintenance user. */
        MAINTENANCE
    }

    /** Created by JUnit for each test; Spring injects the fields of the base class. */
    SecurityHeadersIT() {
    }

    /**
     * Every response kind of the direct API port, with the policy it must carry.
     *
     * @return the case name, the caller, the method, the path, the expected status and the expected
     *     {@code Content-Security-Policy}
     */
    static Stream<Arguments> responses() {
        return Stream.of(
                Arguments.of("API 200, maintenance user", Caller.MAINTENANCE, HttpMethod.GET,
                        "/api/customers", HttpStatus.OK, API_POLICY),
                Arguments.of("public message catalog", Caller.ANONYMOUS, HttpMethod.GET,
                        "/api/messages", HttpStatus.OK, API_POLICY),
                Arguments.of("401 with challenge, no X-Requested-With", Caller.ANONYMOUS, HttpMethod.GET,
                        "/api/customers", HttpStatus.UNAUTHORIZED, API_POLICY),
                Arguments.of("401 without challenge, X-Requested-With", Caller.ANONYMOUS_SPA, HttpMethod.GET,
                        "/api/customers", HttpStatus.UNAUTHORIZED, API_POLICY),
                Arguments.of("401 for a wrong password", Caller.WRONG_PASSWORD, HttpMethod.GET,
                        "/api/session", HttpStatus.UNAUTHORIZED, API_POLICY),
                Arguments.of("403 of an inquiry user's review", Caller.INQUIRY, HttpMethod.POST,
                        "/api/customers/review", HttpStatus.FORBIDDEN, API_POLICY),
                Arguments.of("404 of an unknown API route", Caller.INQUIRY, HttpMethod.GET,
                        "/api/nope", HttpStatus.NOT_FOUND, API_POLICY),
                Arguments.of("health probe", Caller.ANONYMOUS, HttpMethod.GET,
                        "/actuator/health", HttpStatus.OK, API_POLICY),
                Arguments.of("health 404 through the ERROR dispatch", Caller.ANONYMOUS, HttpMethod.GET,
                        "/actuator/health/db", HttpStatus.NOT_FOUND, API_POLICY),
                Arguments.of("OpenAPI document", Caller.ANONYMOUS, HttpMethod.GET,
                        "/v3/api-docs", HttpStatus.OK, API_POLICY),
                Arguments.of("OpenAPI document as YAML", Caller.ANONYMOUS, HttpMethod.GET,
                        "/v3/api-docs.yaml", HttpStatus.OK, API_POLICY),
                Arguments.of("Swagger UI configuration", Caller.ANONYMOUS, HttpMethod.GET,
                        "/v3/api-docs/swagger-config", HttpStatus.OK, API_POLICY),
                Arguments.of("Swagger UI page", Caller.ANONYMOUS, HttpMethod.GET,
                        SWAGGER_UI_PAGE, HttpStatus.OK, SWAGGER_UI_POLICY),
                Arguments.of("Swagger UI path redirect", Caller.ANONYMOUS, HttpMethod.GET,
                        "/swagger-ui.html", HttpStatus.FOUND, SWAGGER_UI_POLICY),
                Arguments.of("Swagger UI script", Caller.ANONYMOUS, HttpMethod.GET,
                        "/swagger-ui/swagger-initializer.js", HttpStatus.OK, SWAGGER_UI_POLICY),
                Arguments.of("unknown Swagger UI resource", Caller.ANONYMOUS, HttpMethod.GET,
                        "/swagger-ui/nope.js", HttpStatus.NOT_FOUND, SWAGGER_UI_POLICY));
    }

    /**
     * Sends one request of {@link #responses()} and checks its status and headers: one
     * {@code Content-Security-Policy} with the expected value, one {@code Referrer-Policy: no-referrer},
     * Spring Security's defaults, no cookie, no HSTS, and on a 401 a challenge only without
     * {@code X-Requested-With}.
     *
     * @param name the case name
     * @param caller who sends the request
     * @param method the method
     * @param path the request path
     * @param status the expected status
     * @param policy the expected {@code Content-Security-Policy}
     */
    @ParameterizedTest(name = "{0}: {2} {3}")
    @MethodSource("responses")
    void everyResponseCarriesOnePolicyAndNoReferrer(String name, Caller caller, HttpMethod method,
            String path, HttpStatus status, String policy) {
        ResponseEntity<String> response = send(caller, method, path);

        assertThat(response.getStatusCode()).as("status; body: %s", response.getBody()).isEqualTo(status);
        HttpHeaders headers = response.getHeaders();
        assertThat(headers.get(CONTENT_SECURITY_POLICY)).as("Content-Security-Policy values")
                .containsExactly(policy);
        assertThat(headers.get(REFERRER_POLICY)).as("Referrer-Policy values").containsExactly("no-referrer");
        assertThat(headers.get("X-Content-Type-Options")).containsExactly("nosniff");
        assertThat(headers.get("X-Frame-Options")).containsExactly("DENY");
        assertThat(headers.get("X-XSS-Protection")).containsExactly("0");
        assertThat(headers.getCacheControl()).contains("no-store");
        assertThat(headers.containsKey(HttpHeaders.SET_COOKIE)).as("Set-Cookie").isFalse();
        assertThat(headers.containsKey("Strict-Transport-Security")).as("HSTS over plain HTTP").isFalse();
        if (status == HttpStatus.UNAUTHORIZED) {
            assertThat(headers.containsKey(HttpHeaders.WWW_AUTHENTICATE)).as("challenge")
                    .isEqualTo(caller != Caller.ANONYMOUS_SPA);
        }
    }

    /**
     * Reads what the Swagger UI loads, as springdoc serves it, and checks it against the Swagger UI
     * policy: the page has no inline script or style, no base URL, frame or plugin, and refers only to
     * relative resources; its configuration and initializer point at this origin, and the validator
     * badge, an image from another origin, is disabled.
     */
    @Test
    void swaggerUiLoadsNothingItsPolicyRefuses() {
        ResponseEntity<String> page = send(Caller.ANONYMOUS, HttpMethod.GET, SWAGGER_UI_PAGE);

        assertThat(page.getStatusCode()).isEqualTo(HttpStatus.OK);
        String html = page.getBody();
        assertThat(html).isNotBlank();
        assertThat(html.toLowerCase(Locale.ROOT)).doesNotContain("<style", " style=", "<base",
                "<iframe", "<object", "javascript:");
        Matcher script = SCRIPT.matcher(html);
        int scripts = 0;
        while (script.find()) {
            scripts++;
            assertThat(script.group(1)).as("script attributes").contains("src=");
            assertThat(script.group(2)).as("inline content of script %s", script.group(1)).isBlank();
        }
        assertThat(scripts).as("scripts on the page").isPositive();
        Matcher resource = RESOURCE.matcher(html);
        List<String> resources = resource.results().map(found -> found.group(1)).toList();
        assertThat(resources).isNotEmpty().allSatisfy(url -> assertThat(url)
                .as("resource of the page").doesNotContain(":").doesNotStartWith("//"));

        JsonNode config = json(send(Caller.ANONYMOUS, HttpMethod.GET, "/v3/api-docs/swagger-config"));
        assertThat(config.path("url").asText()).isEqualTo("/v3/api-docs");
        assertThat(config.path("configUrl").asText()).isEqualTo("/v3/api-docs/swagger-config");
        assertThat(config.path("validatorUrl").asText()).as("validator badge disabled").isEmpty();
        assertThat(config.path("oauth2RedirectUrl").asText()).as("OAuth2 redirect page on this origin")
                .startsWith(baseUrl() + "/swagger-ui/");

        ResponseEntity<String> initializer =
                send(Caller.ANONYMOUS, HttpMethod.GET, "/swagger-ui/swagger-initializer.js");
        assertThat(initializer.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(initializer.getBody())
                .contains("\"configUrl\" : \"/v3/api-docs/swagger-config\"", "\"validatorUrl\" : \"\"");
    }

    /**
     * Holds the hash sources of the Swagger UI policy to the inline stylesheets its scripts render: each
     * {@code <style>} text the scripts create has its SHA-256 hash in {@code style-src}, and the policy
     * names no other hash, so an upgrade that changes the text fails here instead of in the browser.
     *
     * @throws NoSuchAlgorithmException never, since every JDK provides SHA-256
     */
    @Test
    void swaggerUiPolicyHashesExactlyTheInlineStylesheetsOfItsScripts() throws NoSuchAlgorithmException {
        MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
        List<String> rendered = new ArrayList<>();
        for (String script : SWAGGER_UI_SCRIPTS) {
            ResponseEntity<String> response = send(Caller.ANONYMOUS, HttpMethod.GET, script);
            assertThat(response.getStatusCode()).as(script).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).as(script).isNotBlank();
            Matcher stylesheet = INLINE_STYLESHEET.matcher(response.getBody());
            while (stylesheet.find()) {
                byte[] digest = sha256.digest(stylesheet.group(1).getBytes(StandardCharsets.UTF_8));
                rendered.add("sha256-" + Base64.getEncoder().encodeToString(digest));
            }
        }
        List<String> allowed = HASH_SOURCE.matcher(SWAGGER_UI_POLICY).results()
                .map(found -> found.group(1)).toList();

        assertThat(rendered).as("hashes of the inline stylesheets").isNotEmpty();
        assertThat(allowed).as("hash sources of the policy").containsExactlyInAnyOrderElementsOf(rendered);
    }

    /**
     * Sends one request; a {@code POST} carries {@link #REVIEW_BODY} as JSON.
     *
     * @param caller who sends it
     * @param method the method
     * @param path the request path
     * @return the response, whatever its status
     */
    private ResponseEntity<String> send(Caller caller, HttpMethod method, String path) {
        RestClient.RequestBodySpec request = client(caller).method(method).uri(path);
        if (method == HttpMethod.POST) {
            request = request.contentType(MediaType.APPLICATION_JSON).body(REVIEW_BODY);
        }
        return request.retrieve().toEntity(String.class);
    }

    /**
     * Returns the client of a caller.
     *
     * @param caller who sends the request
     * @return a new client with that caller's headers
     */
    private RestClient client(Caller caller) {
        return switch (caller) {
            case ANONYMOUS -> anonymous();
            case ANONYMOUS_SPA -> anonymousXhr();
            case WRONG_PASSWORD -> as(INQUIRY_USER, INQUIRY_PASSWORD + "-wrong");
            case INQUIRY -> inquiry();
            case MAINTENANCE -> maintenance();
        };
    }
}
