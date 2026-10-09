package com.democorp.customermaster.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.democorp.customermaster.messages.MessageCatalog;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.json.ProblemDetailJacksonMixin;
import org.springframework.lang.Nullable;

/**
 * Holds the error bodies the Compose {@code frontend}'s nginx writes itself under {@code /api/} to the
 * bodies the API builds for the same status, so every error under {@code /api/} reaches the UI as an
 * API problem.
 *
 * <p><b>Why.</b> Every error body the API sends comes from {@link ProblemFactory}. nginx answers an
 * {@code /api/} request itself in two cases, from bodies that are text in {@code frontend/nginx.conf}:
 * <ul>
 *   <li><b>Gateway.</b> No API answer exists for nginx to forward, because {@code app} is unreachable
 *       or its answer outlasts the {@code proxy_read_timeout} cutoff. nginx answers 502 or 504 from the
 *       named locations {@code @api_bad_gateway} and {@code @api_gateway_timeout}, and each body must
 *       equal what {@code create(status, "DEM9999", List.of(), instance)} serializes to.</li>
 *   <li><b>Rejection.</b> nginx rejects the request before forwarding it: a malformed or oversized
 *       request line, header or body, {@code TRACE}, an unknown transfer coding or HTTP version. The
 *       {@code error_page} lists of the server block and of {@code location /api/} send each such
 *       status to its internal page under {@code /_nginx/rejection/}, which answers problem+json when
 *       {@code $api_request} places the request target under {@code /api/}, and nginx's own HTML page
 *       otherwise. For 400, 405, 413, 414 and 494 (which nginx sends as 400) the body must equal what
 *       {@link ProblemErrorController#problemFor} builds for the status sent, as for a request Tomcat's
 *       connector rejects: {@code APP0400} with the lower-case reason phrase. For 501 and 505 it must
 *       equal DEM9999 at that status, as for the gateway.</li>
 * </ul>
 * Equal means the same members, values and member order, the catalog's {@code detail} and the
 * {@code args} included, with no member repeated. A change to a catalog text or to the problem shape
 * that is not carried into nginx.conf fails here.
 *
 * <p><b>{@code instance}.</b> nginx fills it from the map {@code $api_problem_instance} over
 * {@code $request_path_no_query}, the raw request path cut at its query, which is the
 * {@code getRequestURI()} that {@link ProblemFactory} receives. The test evaluates each map as nginx
 * does (exact keys, then regular expressions in order, then the default; a value may reference the
 * map's source variable and the groups of the expression that matched) with
 * {@link java.util.regex}, which reads its ASCII character classes, escapes and {@code \z} anchor as
 * PCRE does. The member must be present exactly when the path lies under {@code /api/} and holds only
 * characters a URI path allows as they are, so it is never broken JSON and always the path
 * {@link ProblemFactory} would report; any other path leaves the member out, and the body is then the
 * factory's body without {@code instance}. The bodies may reference no variable other than
 * {@code $api_problem_instance}, and the map's value none other than {@code $request_path_no_query}, so
 * nothing else from the request is echoed.
 *
 * <p><b>Classification and logging.</b> {@code $api_request} is evaluated over request lines as the
 * client sends them: API targets in origin and absolute form, lines nginx cuts short or cannot parse,
 * and targets outside {@code /api/} that must keep nginx's page. Because {@code error_page} turns a
 * rejected request into a GET of its page, the {@code redacted} access-log format must log the method
 * from the request line ({@code $request_line_method}), never {@code $request_method}, and that map
 * must yield only a method-shaped first word or {@code -}, never other text of a malformed line. The
 * server's access log uses that format and, by its {@code if=$access_loggable} condition, skips only
 * the Compose healthcheck's {@code GET /} from {@code 127.0.0.1} answered 200: that map is evaluated
 * over the client address, the request line's method, the raw URI and the status, so a request from
 * any other address, a failing probe, another path, a query or another method is logged.
 *
 * <p><b>Wiring.</b> {@code location /api/} must route nginx's 502 and 504 to those named locations,
 * and each must answer {@code application/problem+json}. The server block and {@code location /api/}
 * must each route every rejection status to its page exactly once. Each page must be internal, discard
 * its error log, answer {@code application/problem+json}, return the status without a body for a
 * non-API request, and end with the problem body. The server block must add each browser security
 * header once, with {@code always}, as a literal, so every location, page and gateway body inherits it
 * whatever a failed upstream sent; {@code location /api/} must add each once, with {@code always}, from
 * a map over the API's own header that yields the server's value only when the API sent none; and no
 * other level may set {@code add_header} or {@code add_header_inherit}.
 *
 * <p><b>Configuration file.</b> System property {@value #NGINX_CONF_PATH_PROPERTY} names it. When
 * absent, the path {@value #DEFAULT_NGINX_CONF_PATH} is resolved against the working directory, which
 * is the {@code customer-api} module directory under Surefire.
 *
 * <p>Pure JUnit 5 and AssertJ over the real factory, with the real catalog and a mapper carrying
 * Spring's {@code ProblemDetail} mixin, as Boot configures it: no Spring context, no database, no
 * Docker, no nginx.
 */
@DisplayName("nginx.conf: nginx's own error bodies under /api/, for gateway errors and request rejections,"
        + " are the API's problems")
final class NginxGatewayProblemTest {

    /** System property naming the nginx configuration file. */
    private static final String NGINX_CONF_PATH_PROPERTY = "nginx.conf.path";

    /** The nginx configuration file relative to the {@code customer-api} module directory. */
    private static final String DEFAULT_NGINX_CONF_PATH = "../../frontend/nginx.conf";

    /** The catalog key of nginx's gateway bodies and of its 5xx rejection bodies. */
    private static final String PROGRAM_ERROR = "DEM9999";

    /** The variable nginx's bodies insert the {@code instance} member from. */
    private static final String INSTANCE_VARIABLE = "api_problem_instance";

    /** The source variable of the {@code instance} map: the raw request path without its query. */
    private static final String PATH_VARIABLE = "request_path_no_query";

    /** The raw request line, the source variable of the request classification and method maps. */
    private static final String REQUEST_VARIABLE = "request";

    /** {@code "1"} for a request whose target lies under {@code /api/}, {@code ""} otherwise. */
    private static final String API_REQUEST_VARIABLE = "api_request";

    /** The method as the request line carries it, which the access log records. */
    private static final String METHOD_VARIABLE = "request_line_method";

    /** The method variable an error page rewrites to {@code GET}, which the access log must not use. */
    private static final String REWRITTEN_METHOD_VARIABLE = "request_method";

    /** The access-log format of the server. */
    private static final String ACCESS_LOG_FORMAT = "redacted";

    /** {@code "0"} for the healthcheck request the server's access log skips, {@code "1"} otherwise. */
    private static final String LOGGABLE_VARIABLE = "access_loggable";

    /** The client address, as nginx received the connection. */
    private static final String REMOTE_ADDRESS_VARIABLE = "remote_addr";

    /** The raw request URI, its query included. */
    private static final String RAW_URI_VARIABLE = "request_uri";

    /** The status nginx sent. */
    private static final String STATUS_VARIABLE = "status";

    /** The browser security headers the server block adds, each with a part its value must hold. */
    private static final Map<String, String> SECURITY_HEADERS = Map.of(
            "Content-Security-Policy", "frame-ancestors 'none'",
            "X-Content-Type-Options", "nosniff",
            "X-Frame-Options", "DENY",
            "Referrer-Policy", "no-referrer");

    /** A request path under {@code /api/}, expected back as {@code instance}. */
    private static final String SAMPLE_PATH = "/api/customers/AAAD";

    /**
     * The characters RFC 3986 allows in a path as they are, apart from the {@code %} of an escape: the
     * unreserved characters, the sub-delimiters, {@code :}, {@code @} and the separator {@code /}.
     * {@link java.net.URI} accepts the same set in a path, so {@link ProblemFactory} keeps a path made
     * of them, and of well-formed escapes, unchanged.
     */
    private static final String PATH_CHARACTERS_AS_IS =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~!$&'()*+,;=:@/";

    /** A variable reference in an nginx value: {@code $name} or {@code ${name}}. */
    private static final Pattern VARIABLE = Pattern.compile("\\$(?:\\{(\\w+)}|(\\w+))");

    /** A mapper carrying Spring's {@code ProblemDetail} mixin, so properties are top-level members. */
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .addMixIn(ProblemDetail.class, ProblemDetailJacksonMixin.class);

    /** Reads bodies and rejects a member that appears twice, which a plain tree read would overwrite. */
    private static final ObjectMapper STRICT_READER = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .build();

    /** The real factory, with the real catalog. */
    private static final ProblemFactory PROBLEMS = new ProblemFactory(new MessageCatalog(), MAPPER);

    /** The real ERROR-dispatch status map, which also answers requests Tomcat's connector rejects. */
    private static final ProblemErrorController ERRORS = new ProblemErrorController(PROBLEMS);

    /** The parsed top level of nginx.conf: the directives of the {@code http} block it is included in. */
    private static List<Directive> config;

    /** The {@code $api_problem_instance} map. */
    private static NginxMap instanceMap;

    /** The {@code $api_request} map. */
    private static NginxMap apiRequestMap;

    /** The {@code $request_line_method} map. */
    private static NginxMap methodMap;

    /**
     * The two statuses nginx answers itself under {@code /api/} when no API answer exists, with their
     * named locations.
     */
    enum Gateway {

        /** {@code app} is stopped, refuses connections or cannot be resolved. */
        BAD_GATEWAY(HttpStatus.BAD_GATEWAY, "@api_bad_gateway"),

        /** The connect or the API's answer outlasts its timeout. */
        GATEWAY_TIMEOUT(HttpStatus.GATEWAY_TIMEOUT, "@api_gateway_timeout");

        /** The status nginx answers. */
        private final HttpStatus status;

        /** The named location that writes the body. */
        private final String location;

        /**
         * Pairs a status with its named location.
         *
         * @param status the status nginx answers
         * @param location the named location that writes the body
         */
        Gateway(HttpStatus status, String location) {
            this.status = status;
            this.location = location;
        }
    }

    /** The statuses nginx rejects a request with, the status it sends for each, and the page answering. */
    enum Rejection {

        /** A malformed request line, URI, header or chunked body. */
        BAD_REQUEST(400, 400, "/_nginx/rejection/400"),

        /** A header line over nginx's buffer, or headers beyond its buffers; nginx sends it as 400. */
        REQUEST_HEADER_TOO_LARGE(494, 400, "/_nginx/rejection/494"),

        /** {@code TRACE} or {@code CONNECT}. */
        METHOD_NOT_ALLOWED(405, 405, "/_nginx/rejection/405"),

        /** A body over {@code client_max_body_size}. */
        PAYLOAD_TOO_LARGE(413, 413, "/_nginx/rejection/413"),

        /** A request line over nginx's buffer. */
        URI_TOO_LONG(414, 414, "/_nginx/rejection/414"),

        /** An unknown {@code Transfer-Encoding}. */
        NOT_IMPLEMENTED(501, 501, "/_nginx/rejection/501"),

        /** An HTTP major version above 1. */
        HTTP_VERSION_NOT_SUPPORTED(505, 505, "/_nginx/rejection/505");

        /** The status nginx gives the rejection, which {@code error_page} matches. */
        private final int nginxStatus;

        /** The status nginx sends. */
        private final int wireStatus;

        /** The internal page that answers the rejection. */
        private final String page;

        /**
         * Pairs nginx's status with the status sent and the page.
         *
         * @param nginxStatus the status nginx gives the rejection
         * @param wireStatus the status nginx sends
         * @param page the internal page that answers the rejection
         */
        Rejection(int nginxStatus, int wireStatus, String page) {
            this.nginxStatus = nginxStatus;
            this.wireStatus = wireStatus;
            this.page = page;
        }
    }

    /**
     * Reads and parses nginx.conf once, and finds its maps.
     *
     * @throws IOException if the file cannot be read
     */
    @BeforeAll
    static void readConfig() throws IOException {
        Path file = Path.of(System.getProperty(NGINX_CONF_PATH_PROPERTY, DEFAULT_NGINX_CONF_PATH))
                .toAbsolutePath()
                .normalize();
        assertThat(file).as("nginx configuration (system property %s)", NGINX_CONF_PATH_PROPERTY)
                .isRegularFile();
        config = parse(Files.readString(file, StandardCharsets.UTF_8));
        instanceMap = NginxMap.of(map(PATH_VARIABLE, INSTANCE_VARIABLE));
        apiRequestMap = NginxMap.of(map(REQUEST_VARIABLE, API_REQUEST_VARIABLE));
        methodMap = NginxMap.of(map(REQUEST_VARIABLE, METHOD_VARIABLE));
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Gateway.class)
    @DisplayName("the body for a path under /api/ is ProblemFactory's, with the path as instance")
    void bodyWithInstanceIsProblemFactorysBody(Gateway gateway) throws IOException {
        assertThat(instanceMap.valueFor(SAMPLE_PATH)).isNotEmpty();

        assertSameProblem(nginxBody(gateway, SAMPLE_PATH), factoryBody(gateway, SAMPLE_PATH));
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Gateway.class)
    @DisplayName("the body for a path holding a double quote is ProblemFactory's without instance")
    void bodyWithoutInstanceIsProblemFactorysBody(Gateway gateway) throws IOException {
        String quoted = "/api/x\"y";
        assertThat(instanceMap.valueFor(quoted)).isEmpty();

        assertSameProblem(nginxBody(gateway, quoted), factoryBody(gateway, null));
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Gateway.class)
    @DisplayName("location /api/ routes the status to its named location, which answers problem+json")
    void statusReachesItsNamedLocationAsProblemJson(Gateway gateway) {
        Directive server = only(config, d -> d.isBlock("server"), "server");
        Directive api = only(server.children(), d -> d.isBlock("location") && d.args().equals(List.of("/api/")),
                "location /api/");
        only(api.children(), d -> d.name().equals("error_page")
                && d.args().equals(List.of(String.valueOf(gateway.status.value()), gateway.location)),
                "error_page " + gateway.status.value() + " " + gateway.location);

        Directive named = namedLocation(gateway);
        Directive defaultType = only(named.children(), d -> d.name().equals("default_type"), "default_type");
        assertThat(defaultType.args()).containsExactly("application/problem+json");
        Directive types = only(named.children(), d -> d.isBlock("types"), "types");
        assertThat(types.children()).as("the empty types block of %s", gateway.location).isEmpty();
    }

    @ParameterizedTest(name = "<{0}>")
    @ValueSource(strings = {"/api/customers/AAAD", "/api/", "/api/a%2Fb", "/api/customers/A%20AD",
            "/api/%E2%82%AC", "/api/x$y'(z)*+,;=:@!~._-"})
    @DisplayName("a path under /api/ that a URI keeps as it is becomes instance unchanged")
    void usablePathBecomesInstance(String path) throws IOException {
        assertThat(instanceMap.valueFor(path)).isNotEmpty();

        for (Gateway gateway : Gateway.values()) {
            assertSameProblem(nginxBody(gateway, path), factoryBody(gateway, path));
        }
        for (Rejection rejection : Rejection.values()) {
            assertSameProblem(nginxBody(rejection, path), apiBody(rejection, path));
        }
    }

    @ParameterizedTest(name = "<{0}>")
    @ValueSource(strings = {"", "/", "/healthz", "/apix/a", "/API/a", "/x/../api/a", "api/a",
            "/api/x\"y", "/api/x\\y", "/api/a b", "/api/a\tb", "/api/a%zz", "/api/a%2", "/api/a%",
            "/api/a?b", "/api/a#b", "/api/caf\u00e9", "/api/a\u2028b", "/api/a\n", "/api/a\r\n"})
    @DisplayName("any other path leaves instance out")
    void otherPathLeavesInstanceOut(String path) throws IOException {
        assertThat(instanceMap.valueFor(path)).isEmpty();

        for (Gateway gateway : Gateway.values()) {
            assertSameProblem(nginxBody(gateway, path), factoryBody(gateway, null));
        }
        for (Rejection rejection : Rejection.values()) {
            assertSameProblem(nginxBody(rejection, path), apiBody(rejection, null));
        }
    }

    @Test
    @DisplayName("instance is set for exactly the characters a URI path allows as they are")
    void instanceIsSetForExactlyThePathCharacters() throws IOException {
        List<String> mismatches = new ArrayList<>();
        for (char c = 0; c < 0x80; c++) {
            String path = "/api/a" + c + "b";
            boolean expected = PATH_CHARACTERS_AS_IS.indexOf(c) >= 0;
            boolean actual = !instanceMap.valueFor(path).isEmpty();
            if (actual != expected) {
                mismatches.add(String.format("U+%04X %s", (int) c, actual ? "accepted" : "rejected"));
                continue;
            }
            for (Gateway gateway : Gateway.values()) {
                assertSameProblem(nginxBody(gateway, path), factoryBody(gateway, expected ? path : null));
            }
            for (Rejection rejection : Rejection.values()) {
                assertSameProblem(nginxBody(rejection, path), apiBody(rejection, expected ? path : null));
            }
        }

        assertThat(mismatches).as("characters the instance map judges wrongly").isEmpty();
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Rejection.class)
    @DisplayName("a rejection's body for a path under /api/ is the API's, with the path as instance")
    void rejectionBodyWithInstanceIsTheApisBody(Rejection rejection) throws IOException {
        assertThat(instanceMap.valueFor(SAMPLE_PATH)).isNotEmpty();

        assertSameProblem(nginxBody(rejection, SAMPLE_PATH), apiBody(rejection, SAMPLE_PATH));
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Rejection.class)
    @DisplayName("a rejection's body without a usable path, as when nginx cannot parse it, is the API's"
            + " without instance")
    void rejectionBodyWithoutInstanceIsTheApisBody(Rejection rejection) throws IOException {
        // An unparsable request line or URI leaves $request_uri empty, and so the path.
        assertThat(instanceMap.valueFor("")).isEmpty();

        assertSameProblem(nginxBody(rejection, ""), apiBody(rejection, null));
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Rejection.class)
    @DisplayName("the server block and location /api/ each route the rejection to its page exactly once")
    void rejectionReachesItsPageAtServerAndApiLevel(Rejection rejection) {
        Directive server = server();
        String status = String.valueOf(rejection.nginxStatus);
        for (Directive level : List.of(server, apiLocation())) {
            String where = level == server ? "server" : "location /api/";
            List<Directive> routes = level.children().stream()
                    .filter(d -> d.name().equals("error_page")
                            && d.args().subList(0, Math.max(0, d.args().size() - 1)).contains(status))
                    .toList();
            assertThat(routes).as("error_page %s in %s", status, where).hasSize(1);
            assertThat(routes.get(0).args()).as("error_page %s in %s", status, where)
                    .containsExactly(status, rejection.page);
        }
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Rejection.class)
    @DisplayName("a rejection page is internal and answers problem+json for /api/, nginx's status otherwise")
    void rejectionPageAnswersProblemJsonOnlyForApiRequests(Rejection rejection) {
        List<Directive> page = rejectionPage(rejection).children();

        assertThat(only(page, d -> d.name().equals("internal"), "internal in " + rejection.page).args())
                .isEmpty();
        assertThat(only(page, d -> d.name().equals("error_log"), "error_log in " + rejection.page).args())
                .containsExactly("/dev/null");
        assertThat(only(page, d -> d.isBlock("types"), "types in " + rejection.page).children())
                .as("the empty types block of %s", rejection.page).isEmpty();
        assertThat(only(page, d -> d.name().equals("default_type"), "default_type in " + rejection.page)
                .args()).containsExactly("application/problem+json");

        Directive condition = only(page, d -> d.isBlock("if"), "if in " + rejection.page);
        assertThat(condition.args()).as("the condition in %s", rejection.page)
                .containsExactly("($" + API_REQUEST_VARIABLE, "=", "", ")");
        assertThat(condition.children()).as("the non-API answer in %s", rejection.page)
                .containsExactly(new Directive("return", List.of(String.valueOf(rejection.nginxStatus)),
                        null));

        Directive answer = only(page, d -> d.name().equals("return"), "return in " + rejection.page);
        assertThat(page.indexOf(condition)).as("the if precedes the return in %s", rejection.page)
                .isLessThan(page.indexOf(answer));
        assertThat(page.get(page.size() - 1)).as("the last directive of %s", rejection.page).isEqualTo(answer);
    }

    @ParameterizedTest(name = "<{0}>")
    @ValueSource(strings = {"GET /api/customers HTTP/1.1", "GET /api/ HTTP/1.1", "GET /api/a%zz HTTP/1.1",
            "GET /api/a%zz?name=SMITH HTTP/1.1", "GET /api/../../x HTTP/1.1", "GET /api/x%00y HTTP/1.1",
            "TRACE /api/customers HTTP/1.1", "POST /api/customers HTTP/1.1", "GET  /api/a%zz HTTP/1.1",
            "GET /api/customers?cursor=xxxx", "get /api/x HTTP/1.1", "GET /api/x HTTP/2.0",
            "GET http://h/api/a%zz HTTP/1.1", "TRACE http://h/api/x HTTP/1.1",
            "GET HTTPS://h:8080/api/x HTTP/1.1", "GET svn+ssh://[::1]:22/api/x HTTP/1.1"})
    @DisplayName("a request line whose target, as sent, lies under /api/ is an API request")
    void requestUnderApiIsAnApiRequest(String requestLine) {
        assertThat(apiRequestMap.valueFor(requestLine)).isEqualTo("1");
    }

    @ParameterizedTest(name = "<{0}>")
    @ValueSource(strings = {"", "GET / HTTP/1.1", "GET /customers%zz HTTP/1.1", "GET /api HTTP/1.1",
            "GET /api%zz HTTP/1.1", "GET /apix/a HTTP/1.1", "GET /API/a HTTP/1.1", "GET /%61pi/a HTTP/1.1",
            "CONNECT t:443 HTTP/1.1", "GET http://h/customers HTTP/1.1", "GET http://h?/api/ HTTP/1.1",
            "GET http://h/x/api/ HTTP/1.1", "GET /customers?next=/api/ HTTP/1.1",
            "GET /customers /api/ HTTP/1.1", "GET api/x HTTP/1.1", "GET 1http://h/api/x HTTP/1.1"})
    @DisplayName("any other request line is not an API request and keeps nginx's page")
    void otherRequestIsNotAnApiRequest(String requestLine) {
        assertThat(apiRequestMap.valueFor(requestLine)).isEmpty();
    }

    @Test
    @DisplayName("the access log records the method of the request line, not the one error_page rewrites")
    void accessLogRecordsTheRequestLineMethod() {
        Directive format = only(config, d -> d.name().equals("log_format") && !d.args().isEmpty()
                && d.args().get(0).equals(ACCESS_LOG_FORMAT), "log_format " + ACCESS_LOG_FORMAT);
        Matcher reference = VARIABLE.matcher(String.join("", format.args().subList(1, format.args().size())));
        List<String> logged = new ArrayList<>();
        while (reference.find()) {
            logged.add(reference.group(1) != null ? reference.group(1) : reference.group(2));
        }
        assertThat(logged).as("variables of log_format %s", ACCESS_LOG_FORMAT)
                .contains(METHOD_VARIABLE)
                .doesNotContain(REWRITTEN_METHOD_VARIABLE);

        Directive accessLog = serverAccessLog();
        assertThat(accessLog.args()).as("the server's access_log").hasSizeGreaterThan(1);
        assertThat(accessLog.args().get(1)).as("the format of the server's access_log")
                .isEqualTo(ACCESS_LOG_FORMAT);
    }

    /**
     * Holds the server's access log to skipping the Compose healthcheck's second request and nothing
     * else. Its only parameter is {@code if=$access_loggable}, and nginx skips a request whose condition
     * is {@code "0"} or {@code ""}; the map, whose source may reference only the client address, the
     * request line's method, the raw URI and the status, must yield such a value for a {@code GET /}
     * from {@code 127.0.0.1} answered 200, in any protocol version, and a logged value for a browser
     * through the published port (the Docker network's gateway), another container, a failing probe,
     * another path, a query or another method.
     *
     * @param remoteAddress the client address
     * @param requestLine the request line as sent
     * @param status the status nginx sent
     * @param logged whether the request must be logged
     */
    @ParameterizedTest(name = "{0} <{1}> answered {2} is logged: {3}")
    @CsvSource({"127.0.0.1, 'GET / HTTP/1.1', 200, false", "127.0.0.1, 'GET / HTTP/1.0', 200, false",
            "172.18.0.1, 'GET / HTTP/1.1', 200, true", "172.18.0.4, 'GET / HTTP/1.1', 200, true",
            "127.0.0.10, 'GET / HTTP/1.1', 200, true", "127.0.0.1, 'GET / HTTP/1.1', 403, true",
            "127.0.0.1, 'GET / HTTP/1.1', 500, true", "127.0.0.1, 'GET /customers HTTP/1.1', 200, true",
            "127.0.0.1, 'GET /index.html HTTP/1.1', 200, true", "127.0.0.1, 'GET /?x=1 HTTP/1.1', 200, true",
            "127.0.0.1, 'HEAD / HTTP/1.1', 200, true", "127.0.0.1, 'POST / HTTP/1.1', 200, true"})
    @DisplayName("the access log skips only the healthcheck's GET / from 127.0.0.1 answered 200")
    void accessLogSkipsOnlyTheHealthcheckProbe(String remoteAddress, String requestLine, int status,
            boolean logged) {
        Directive accessLog = serverAccessLog();
        assertThat(accessLog.args()).as("the server's access_log").hasSizeGreaterThan(1);
        assertThat(accessLog.args().subList(2, accessLog.args().size()))
                .as("parameters of the server's access_log")
                .containsExactly("if=$" + LOGGABLE_VARIABLE);

        Directive map = only(config, d -> d.isBlock("map") && d.args().size() == 2
                && d.args().get(1).equals("$" + LOGGABLE_VARIABLE), "map to $" + LOGGABLE_VARIABLE);
        String source = expand(map.args().get(0), Map.of(
                REMOTE_ADDRESS_VARIABLE, remoteAddress,
                METHOD_VARIABLE, methodMap.valueFor(requestLine),
                RAW_URI_VARIABLE, requestLine.split(" ")[1],
                STATUS_VARIABLE, String.valueOf(status)));
        String condition = NginxMap.of(map).valueFor(source);
        assertThat(!condition.isEmpty() && !condition.equals("0"))
                .as("%s <%s> answered %d is logged ($%s is \"%s\")", remoteAddress, requestLine, status,
                        LOGGABLE_VARIABLE, condition)
                .isEqualTo(logged);
    }

    @ParameterizedTest(name = "<{0}> logs {1}")
    @CsvSource({"'TRACE /api/x HTTP/1.1', TRACE", "'POST /api/customers HTTP/1.1', POST",
            "'GET / HTTP/1.1', GET", "'GET /api/customers?cursor=xxxx', GET", "'M-SEARCH * HTTP/1.1', M-SEARCH",
            "'', -", "'get /x HTTP/1.1', -", "'GET/api?name=SMITH HTTP/1.1', -", "' /api/x HTTP/1.1', -",
            "'GE(T /api/x?name=SMITH HTTP/1.1', -", "'GET', -"})
    @DisplayName("the logged method is the request line's method-shaped first word, or - for any other line")
    void loggedMethodIsTheRequestLinesMethodOrDash(String requestLine, String method) {
        assertThat(methodMap.valueFor(requestLine)).isEqualTo(method);
    }

    /**
     * Holds the browser security headers to the two levels that set them. The server block adds each
     * once, with {@code always}, as a literal, so the locations, rejection pages, their {@code if}
     * branches and the gateway locations, which inherit it, send it whatever a failed upstream sent
     * first. {@code location /api/}, which inherits none because it sets its own, adds each once, with
     * {@code always}, from a map over the API's own header that yields the server's literal when the API
     * sent none and nothing otherwise, so a header the API sent is neither replaced nor duplicated. No
     * other directive below the server block, in a location, an {@code if} or a block inside
     * {@code location /api/}, may be {@code add_header} or {@code add_header_inherit}.
     */
    @Test
    @DisplayName("the server block adds every security header as a literal, location /api/ each the API did not"
            + " send, and no other level sets any")
    void serverAddsLiteralSecurityHeadersApiAddsThoseTheApiDidNotSend() {
        Directive server = server();
        Directive api = apiLocation();
        List<Directive> apiHeaders = new ArrayList<>();
        for (Map.Entry<String, String> header : SECURITY_HEADERS.entrySet()) {
            String literal = securityHeader(server, "server", header.getKey()).args().get(1);
            assertThat(literal).as("the server's %s", header.getKey())
                    .doesNotContain("$")
                    .contains(header.getValue());

            Directive added = securityHeader(api, "location /api/", header.getKey());
            Matcher reference = VARIABLE.matcher(added.args().get(1));
            assertThat(reference.matches()).as("the value of add_header %s in location /api/ is one variable",
                    header.getKey()).isTrue();
            String apiHeader = "upstream_http_" + header.getKey().toLowerCase(Locale.ROOT).replace('-', '_');
            NginxMap value = NginxMap.of(map(apiHeader,
                    reference.group(1) != null ? reference.group(1) : reference.group(2)));
            assertThat(value.exact()).as("keys of the %s map", header.getKey()).containsOnlyKeys("");
            assertThat(value.patterns()).as("expressions of the %s map", header.getKey()).isEmpty();
            assertThat(value.defaultValue()).as("%s when the API sent its own", header.getKey()).isEmpty();
            assertThat(value.valueFor("")).as("%s when the API sent none", header.getKey()).isEqualTo(literal);
            apiHeaders.add(added);
        }

        assertThat(nested(server)
                .filter(d -> d.name().equals("add_header") || d.name().equals("add_header_inherit")))
                .as("add_header and add_header_inherit below the server block")
                .containsExactlyInAnyOrderElementsOf(apiHeaders);
    }

    /**
     * Returns the one {@code add_header} of a level for a header, which must carry a value and
     * {@code always}.
     *
     * @param level the block
     * @param where the block, for the failure message
     * @param header the header name
     * @return the {@code add_header} directive
     */
    private static Directive securityHeader(Directive level, String where, String header) {
        Directive added = only(level.children(), d -> d.name().equals("add_header") && !d.args().isEmpty()
                && d.args().get(0).equalsIgnoreCase(header), "add_header " + header + " in " + where);
        assertThat(added.args()).as("add_header %s in %s", header, where).hasSize(3).endsWith("always");
        return added;
    }

    /**
     * Asserts that two problem bodies are the same JSON object with the same top-level member order.
     *
     * @param actual the body nginx writes
     * @param expected the body {@link ProblemFactory} builds
     * @throws IOException if either body is not JSON or repeats a member
     */
    private static void assertSameProblem(String actual, String expected) throws IOException {
        JsonNode actualTree = STRICT_READER.readTree(actual);
        JsonNode expectedTree = STRICT_READER.readTree(expected);

        assertThat(actualTree).as("nginx body %s", actual).isEqualTo(expectedTree);
        assertThat(memberNames(actualTree)).as("member order of %s", actual)
                .containsExactlyElementsOf(memberNames(expectedTree));
    }

    /**
     * Returns the top-level member names of a JSON object in document order.
     *
     * @param tree the parsed object
     * @return the names
     */
    private static List<String> memberNames(JsonNode tree) {
        assertThat(tree.isObject()).as("a JSON object: %s", tree).isTrue();
        return tree.properties().stream().map(Map.Entry::getKey).toList();
    }

    /**
     * Returns the body {@link ProblemFactory} builds for DEM9999 at the gateway's status, serialized as
     * {@link ProblemFactory#write} serializes it.
     *
     * @param gateway the status
     * @param instance the request path, or {@code null} for none
     * @return the JSON text
     * @throws IOException if serialization fails
     */
    private static String factoryBody(Gateway gateway, @Nullable String instance) throws IOException {
        ProblemDetail problem = PROBLEMS.create(gateway.status, PROGRAM_ERROR, List.of(), instance);
        return new String(MAPPER.writeValueAsBytes(problem), StandardCharsets.UTF_8);
    }

    /**
     * Returns the body nginx writes for a request path: the {@code return} text of the gateway's named
     * location with {@code $api_problem_instance} expanded through the map. The location must return
     * the gateway's status.
     *
     * @param gateway the status
     * @param path the raw request path without its query
     * @return the JSON text
     */
    private static String nginxBody(Gateway gateway, String path) {
        Directive named = namedLocation(gateway);
        Directive answer = only(named.children(), d -> d.name().equals("return"), "return in " + gateway.location);
        assertThat(answer.args()).as("return in %s", gateway.location).hasSize(2);
        assertThat(answer.args().get(0)).isEqualTo(String.valueOf(gateway.status.value()));
        return expand(answer.args().get(1), Map.of(INSTANCE_VARIABLE, instanceMap.valueFor(path)));
    }

    /**
     * Returns the server's named location of a gateway status.
     *
     * @param gateway the status
     * @return the {@code location} block
     */
    private static Directive namedLocation(Gateway gateway) {
        Directive server = only(config, d -> d.isBlock("server"), "server");
        return only(server.children(), d -> d.isBlock("location") && d.args().equals(List.of(gateway.location)),
                "location " + gateway.location);
    }

    /**
     * Returns the body the API builds for the status nginx sends for a rejection: for a 4xx, what the
     * ERROR-dispatch status map, which also answers requests Tomcat's connector rejects, builds for that
     * status with no exception; for a 5xx, DEM9999 at that status, as for the gateway. Serialized as
     * {@link ProblemFactory#write} serializes it.
     *
     * @param rejection the rejection
     * @param instance the request path, or {@code null} for none
     * @return the JSON text
     * @throws IOException if serialization fails
     */
    private static String apiBody(Rejection rejection, @Nullable String instance) throws IOException {
        HttpStatusCode status = HttpStatusCode.valueOf(rejection.wireStatus);
        ProblemDetail problem = status.is4xxClientError()
                ? ERRORS.problemFor(rejection.wireStatus, null, instance)
                : PROBLEMS.create(status, PROGRAM_ERROR, List.of(), instance);
        return new String(MAPPER.writeValueAsBytes(problem), StandardCharsets.UTF_8);
    }

    /**
     * Returns the body nginx writes for an API request it rejects, by request path: the {@code return}
     * text of the rejection's page with {@code $api_problem_instance} expanded through the map. The page
     * must return the status nginx sends for the rejection.
     *
     * @param rejection the rejection
     * @param path the raw request path without its query, empty when nginx could not read one
     * @return the JSON text
     */
    private static String nginxBody(Rejection rejection, String path) {
        Directive answer = only(rejectionPage(rejection).children(), d -> d.name().equals("return"),
                "return in " + rejection.page);
        assertThat(answer.args()).as("return in %s", rejection.page).hasSize(2);
        assertThat(answer.args().get(0)).isEqualTo(String.valueOf(rejection.wireStatus));
        return expand(answer.args().get(1), Map.of(INSTANCE_VARIABLE, instanceMap.valueFor(path)));
    }

    /**
     * Returns the server's exact location of a rejection's page.
     *
     * @param rejection the rejection
     * @return the {@code location = <page>} block
     */
    private static Directive rejectionPage(Rejection rejection) {
        return only(server().children(),
                d -> d.isBlock("location") && d.args().equals(List.of("=", rejection.page)),
                "location = " + rejection.page);
    }

    /**
     * Returns the server block.
     *
     * @return the {@code server} block
     */
    private static Directive server() {
        return only(config, d -> d.isBlock("server"), "server");
    }

    /**
     * Returns the server's {@code access_log}, which every location but {@code /healthz} inherits.
     *
     * @return the {@code access_log} directive
     */
    private static Directive serverAccessLog() {
        return only(server().children(), d -> d.name().equals("access_log"), "access_log");
    }

    /**
     * Returns the server's {@code location /api/}.
     *
     * @return the {@code location /api/} block
     */
    private static Directive apiLocation() {
        return only(server().children(), d -> d.isBlock("location") && d.args().equals(List.of("/api/")),
                "location /api/");
    }

    /**
     * Returns the top-level {@code map} from one variable to another.
     *
     * @param source the map's source variable, without {@code $}
     * @param target the variable the map sets, without {@code $}
     * @return the {@code map} block
     */
    private static Directive map(String source, String target) {
        return only(config, d -> d.isBlock("map") && d.args().equals(List.of("$" + source, "$" + target)),
                "map $" + source + " $" + target);
    }

    /**
     * Returns every directive nested inside the blocks of a block, at any depth.
     *
     * @param block the block
     * @return the directives of its child blocks and of theirs
     */
    private static Stream<Directive> nested(Directive block) {
        return block.children().stream()
                .filter(d -> d.block() != null)
                .flatMap(d -> Stream.concat(d.children().stream(), nested(d)));
    }

    /**
     * Expands the variable references of an nginx value, failing on any variable not given, so a value
     * that echoes anything else from the request fails the test.
     *
     * @param template the value as written in nginx.conf
     * @param variables the values of the variables it may reference
     * @return the expanded text
     */
    private static String expand(String template, Map<String, String> variables) {
        Matcher reference = VARIABLE.matcher(template);
        StringBuilder text = new StringBuilder();
        while (reference.find()) {
            String name = reference.group(1) != null ? reference.group(1) : reference.group(2);
            assertThat(variables).as("variables %s may reference", template).containsKey(name);
            reference.appendReplacement(text, Matcher.quoteReplacement(variables.get(name)));
        }
        reference.appendTail(text);
        return text.toString();
    }

    /**
     * Returns the one directive of a list that matches.
     *
     * @param directives the directives of one level
     * @param match the predicate
     * @param description what is looked for, for the failure message
     * @return the directive
     */
    private static Directive only(List<Directive> directives, Predicate<Directive> match, String description) {
        List<Directive> found = directives.stream().filter(match).toList();
        assertThat(found).as("exactly one %s in nginx.conf", description).hasSize(1);
        return found.get(0);
    }

    /**
     * One nginx directive: its name, its arguments with quotes removed, and the directives of its block.
     *
     * @param name the directive name
     * @param args the arguments
     * @param block the directives of its block, or {@code null} for a simple directive
     */
    private record Directive(String name, List<String> args, @Nullable List<Directive> block) {

        /**
         * Tells whether this is a block directive of the given name.
         *
         * @param blockName the name
         * @return {@code true} for a {@code blockName ... { }} directive
         */
        boolean isBlock(String blockName) {
            return block != null && name.equals(blockName);
        }

        /**
         * Returns the directives of this block.
         *
         * @return the directives
         * @throws IllegalStateException for a simple directive
         */
        List<Directive> children() {
            if (block == null) {
                throw new IllegalStateException(name + " has no block");
            }
            return block;
        }
    }

    /**
     * One token of nginx.conf: a word, unquoted, or one of the delimiters {@code ;}, <code>{</code> and
     * <code>}</code>.
     *
     * @param text the word or the delimiter
     * @param delimiter {@code true} for a delimiter
     */
    private record Token(String text, boolean delimiter) {
    }

    /**
     * An nginx {@code map} as nginx evaluates it: an exact key first, then the regular expressions in
     * the order written, then the default ({@code ""} when none is given). The value found is expanded
     * with the map's source variable and, after a regular expression matched, its numbered and named
     * groups; a reference to any other variable fails the test.
     *
     * @param source the map's source variable, without {@code $}
     * @param defaultValue the value when nothing matches
     * @param exact the exact keys and their values
     * @param patterns the regular expressions and their values, in order
     */
    private record NginxMap(String source, String defaultValue, Map<String, String> exact,
            Map<Pattern, String> patterns) {

        /**
         * Reads a {@code map} block whose keys are plain strings or {@code ~} / {@code ~*} regular
         * expressions; any other directive in the block fails the test.
         *
         * @param map the block
         * @return the map
         */
        static NginxMap of(Directive map) {
            assertThat(map.args()).as("map %s", map.args()).hasSize(2);
            String sourceVariable = map.args().get(0);
            assertThat(sourceVariable).as("source of map %s", map.args()).startsWith("$");
            String defaultValue = "";
            Map<String, String> exact = new LinkedHashMap<>();
            Map<Pattern, String> patterns = new LinkedHashMap<>();
            for (Directive entry : map.children()) {
                assertThat(entry.block()).as("map entry %s", entry.name()).isNull();
                assertThat(entry.args()).as("map entry %s", entry.name()).hasSize(1);
                String key = entry.name();
                String value = entry.args().get(0);
                if (key.equals("default")) {
                    defaultValue = value;
                } else if (key.startsWith("~*")) {
                    patterns.put(Pattern.compile(key.substring(2), Pattern.CASE_INSENSITIVE), value);
                } else if (key.startsWith("~")) {
                    patterns.put(Pattern.compile(key.substring(1)), value);
                } else {
                    exact.put(key.startsWith("\\") ? key.substring(1) : key, value);
                }
            }
            return new NginxMap(sourceVariable.substring(1), defaultValue, Map.copyOf(exact), patterns);
        }

        /**
         * Returns the expanded value of the map for a source string: a reference to the map's source
         * variable becomes the source string itself, and a reference to a numbered ({@code $1}) or named
         * group of the regular expression that matched becomes the text it captured ({@code ""} when it
         * captured nothing).
         *
         * @param sourceValue the value of the map's source variable
         * @return the value
         */
        String valueFor(String sourceValue) {
            Map<String, String> variables = new HashMap<>();
            String value = exact.get(sourceValue);
            if (value == null) {
                value = defaultValue;
                for (Map.Entry<Pattern, String> entry : patterns.entrySet()) {
                    Matcher matcher = entry.getKey().matcher(sourceValue);
                    if (matcher.find()) {
                        for (int group = 1; group <= matcher.groupCount(); group++) {
                            variables.put(String.valueOf(group),
                                    Objects.requireNonNullElse(matcher.group(group), ""));
                        }
                        matcher.namedGroups().forEach((name, group) ->
                                variables.put(name, Objects.requireNonNullElse(matcher.group(group), "")));
                        value = entry.getValue();
                        break;
                    }
                }
            }
            variables.put(source, sourceValue);
            return expand(value, variables);
        }
    }

    /**
     * Parses nginx.conf into its top-level directives.
     *
     * @param text the file content
     * @return the directives
     */
    private static List<Directive> parse(String text) {
        Iterator<Token> tokens = tokenize(text).iterator();
        return parseBlock(tokens, false);
    }

    /**
     * Parses directives up to the end of the enclosing block, or of the file at the top level.
     *
     * @param tokens the remaining tokens
     * @param nested {@code true} inside a block, which must end with <code>}</code>
     * @return the directives
     */
    private static List<Directive> parseBlock(Iterator<Token> tokens, boolean nested) {
        List<Directive> directives = new ArrayList<>();
        List<String> words = new ArrayList<>();
        while (tokens.hasNext()) {
            Token token = tokens.next();
            if (!token.delimiter()) {
                words.add(token.text());
                continue;
            }
            switch (token.text()) {
                case ";" -> directives.add(directive(words, null));
                case "{" -> directives.add(directive(words, parseBlock(tokens, true)));
                default -> {
                    if (!nested || !words.isEmpty()) {
                        throw new IllegalStateException("nginx.conf: unexpected } after " + words);
                    }
                    return List.copyOf(directives);
                }
            }
            words.clear();
        }
        if (nested || !words.isEmpty()) {
            throw new IllegalStateException("nginx.conf: unexpected end of file after " + words);
        }
        return List.copyOf(directives);
    }

    /**
     * Builds a directive from the words before its delimiter.
     *
     * @param words the name and the arguments
     * @param block the block's directives, or {@code null} for a simple directive
     * @return the directive
     */
    private static Directive directive(List<String> words, @Nullable List<Directive> block) {
        if (words.isEmpty()) {
            throw new IllegalStateException("nginx.conf: a delimiter without a directive");
        }
        return new Directive(words.get(0), List.copyOf(words.subList(1, words.size())), block);
    }

    /**
     * Splits nginx.conf into tokens as nginx does: a {@code #} at the start of a token comments out the
     * rest of the line; a word in single or double quotes may hold spaces and delimiters, and
     * {@code \"}, {@code \'}, {@code \\}, {@code \t}, {@code \r} and {@code \n} are unescaped in it while
     * any other backslash is kept; an unquoted word ends at a space or a delimiter, except inside a
     * <code>${name}</code> reference.
     *
     * @param text the file content
     * @return the tokens
     */
    private static List<Token> tokenize(String text) {
        List<Token> tokens = new ArrayList<>();
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
            } else if (c == '#') {
                while (i < text.length() && text.charAt(i) != '\n') {
                    i++;
                }
            } else if (c == ';' || c == '{' || c == '}') {
                tokens.add(new Token(String.valueOf(c), true));
                i++;
            } else if (c == '"' || c == '\'') {
                StringBuilder word = new StringBuilder();
                i++;
                while (true) {
                    if (i >= text.length()) {
                        throw new IllegalStateException("nginx.conf: unterminated quoted string");
                    }
                    char d = text.charAt(i);
                    if (d == c) {
                        i++;
                        break;
                    }
                    if (d == '\\' && i + 1 < text.length()) {
                        char e = text.charAt(i + 1);
                        String unescaped = switch (e) {
                            case '"', '\'', '\\' -> String.valueOf(e);
                            case 't' -> "\t";
                            case 'r' -> "\r";
                            case 'n' -> "\n";
                            default -> null;
                        };
                        if (unescaped != null) {
                            word.append(unescaped);
                            i += 2;
                            continue;
                        }
                    }
                    word.append(d);
                    i++;
                }
                tokens.add(new Token(word.toString(), false));
            } else {
                int start = i;
                while (i < text.length()) {
                    char d = text.charAt(i);
                    if (d == '$' && i + 1 < text.length() && text.charAt(i + 1) == '{') {
                        int close = text.indexOf('}', i);
                        if (close < 0) {
                            throw new IllegalStateException("nginx.conf: unterminated ${ reference");
                        }
                        i = close + 1;
                    } else if (Character.isWhitespace(d) || d == ';' || d == '{' || d == '}') {
                        break;
                    } else {
                        i++;
                    }
                }
                tokens.add(new Token(text.substring(start, i), false));
            }
        }
        return tokens;
    }
}
