package com.democorp.customermaster.controller;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import com.democorp.customermaster.support.AbstractPostgresIT;
import com.fasterxml.jackson.databind.JsonNode;
import org.apache.catalina.Host;
import org.apache.catalina.Valve;
import org.apache.catalina.core.StandardHost;
import org.apache.catalina.valves.ErrorReportValve;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.boot.web.embedded.tomcat.TomcatWebServer;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.lang.Nullable;

/**
 * Proves that requests Tomcat's connector rejects before any filter or servlet runs are answered with the
 * error model's {@code application/problem+json}, built by {@link ProblemErrorController}'s status map and
 * written by {@link ProblemFactory} through {@link ProblemErrorReportValve}, never with Tomcat's HTML
 * "HTTP Status 400 – Bad Request" page or an empty body.
 *
 * <p><b>Cases.</b> A request line or header block over the connector's 8 KB limit (a {@code cursor} of
 * 8,100, 9,000 and 20,000 characters, a 9,000-character {@code name} and {@code nameContains}, an
 * anonymous 9,000-character query and a 20 KB header, signed in and anonymous); an encoded {@code /},
 * {@code \} or NUL and an invalid percent escape in the path; a raw <code>|</code> or <code>&#123;</code>
 * in the request target; a method that is not a token; and {@code TRACE}. Each must answer 400
 * {@code APP0400} "Request is not valid: bad request", except {@code TRACE}, which keeps the 405 the
 * connector chose, as every framework 4xx does, with "method not allowed" and the connector's
 * {@code Allow} header. A 5xx the connector chooses, the 501 of {@code CONNECT}, follows the same map to
 * 500 {@code DEM9999} with an {@code errorId} that its ERROR log line repeats. Every answer carries the
 * security headers the filter chain adds to other responses, no HSTS over plain HTTP, and no server name,
 * version or HTML.
 *
 * <p><b>Controls.</b> A 7,000-character {@code cursor} still reaches the application and gets its own 400
 * "cursor is not valid"; a request the security firewall rejects ({@code //api/session}) still goes through
 * the ERROR dispatch to {@code /error}; a valid request still gets its 200; and the host's pipeline holds
 * exactly one error-report valve, {@link ProblemErrorReportValve}.
 *
 * <p>The JDK HTTP client refuses most of these requests before sending them, so they are written to a
 * plain {@link Socket} with {@code Connection: close} and read to the end of the stream.
 *
 * <p>The class runs in the base context of {@link AbstractPostgresIT}: it declares no
 * {@code @MockitoBean}, no {@code @Import} and no nested configuration, so it adds no context variant.
 */
@DisplayName("Connector rejections: problem+json APP0400 from the error-report valve")
class ConnectorErrorProblemIT extends AbstractPostgresIT {

    /** The catalog key of every request the API cannot serve. */
    private static final String APP0400 = "APP0400";

    /** The catalog key of every unexpected failure, a 5xx the connector chose included. */
    private static final String DEM9999 = "DEM9999";

    /** A canonical UUID, the form of every {@code errorId}. */
    private static final Pattern UUID_FORM =
            Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

    /** The reason of a 400 the connector chose. */
    private static final String BAD_REQUEST = "bad request";

    /** The reason of the 405 the connector answers {@code TRACE} with. */
    private static final String METHOD_NOT_ALLOWED = "method not allowed";

    /** Members of a problem body for a 4xx; {@code instance} is added when the request had a path. */
    private static final Set<String> CLIENT_ERROR_MEMBERS =
            Set.of("type", "title", "status", "detail", "code", "args");

    /** Longest wait for the connection to open, in milliseconds. */
    private static final int CONNECT_TIMEOUT_MILLIS = 5_000;

    /** Longest wait for the next bytes of a response, in milliseconds. */
    private static final int READ_TIMEOUT_MILLIS = 30_000;

    /** Separator of the head and the body of an HTTP/1.1 message. */
    private static final String HEAD_END = "\r\n\r\n";

    /** The running application, whose embedded Tomcat the valve test inspects. */
    @Autowired
    private ApplicationContext applicationContext;

    /**
     * Requests the connector rejects with 400, each with the {@code instance} the problem must carry:
     * the request path as Tomcat read it, quoted where it is not a valid URI, or {@code null} when the
     * connector rejected the request line before it had a path.
     *
     * @return the case name, the raw request head and the expected {@code instance}
     */
    static Stream<Arguments> rejectedWithBadRequest() {
        String inquiry = basic(INQUIRY_USER, INQUIRY_PASSWORD);
        String maintenance = basic(MAINTENANCE_USER, MAINTENANCE_PASSWORD);
        // The 8 KB limit covers the request line and every header together: with this head, a cursor of
        // 8,000 characters still fits (about 8,150 bytes). At 8,100 the request line itself still fits,
        // so the path is read and the headers overflow; from 9,000 the request line alone is too long.
        return Stream.of(
                Arguments.of("cursor of 8,100 characters, SPA call",
                        head("GET", "/api/customers?cursor=" + repeat(8_100), inquiry,
                                X_REQUESTED_WITH + ": " + XML_HTTP_REQUEST),
                        "/api/customers"),
                Arguments.of("cursor of 9,000 characters",
                        head("GET", "/api/customers?cursor=" + repeat(9_000), inquiry), null),
                Arguments.of("cursor of 20,000 characters",
                        head("GET", "/api/customers?cursor=" + repeat(20_000), inquiry), null),
                Arguments.of("name of 9,000 characters",
                        head("GET", "/api/customers?name=" + repeat(9_000), inquiry), null),
                Arguments.of("nameContains of 9,000 characters",
                        head("GET", "/api/states?nameContains=" + repeat(9_000), inquiry), null),
                Arguments.of("anonymous query of 9,000 characters",
                        head("GET", "/api/messages?x=" + repeat(9_000), null), null),
                Arguments.of("20 KB header, signed in",
                        head("GET", "/api/session", inquiry, "X-Big: " + repeat(20_000)), "/api/session"),
                Arguments.of("20 KB header, anonymous",
                        head("GET", "/api/session", null, "X-Big: " + repeat(20_000)), "/api/session"),
                Arguments.of("encoded slash",
                        head("GET", "/api/customers/AA%2FB", inquiry), "/api/customers/AA%2FB"),
                Arguments.of("encoded backslash",
                        head("GET", "/api/customers/AA%5CB", inquiry), "/api/customers/AA%5CB"),
                Arguments.of("encoded NUL inside the id",
                        head("GET", "/api/customers/AA%00B", inquiry), "/api/customers/AA%00B"),
                Arguments.of("encoded NUL after the id, SPA call",
                        head("GET", "/api/customers/AAA%00", maintenance,
                                X_REQUESTED_WITH + ": " + XML_HTTP_REQUEST),
                        "/api/customers/AAA%00"),
                Arguments.of("encoded NUL, anonymous",
                        head("GET", "/api/messages%00", null), "/api/messages%00"),
                Arguments.of("invalid escape %ZZ",
                        head("GET", "/api/customers/AA%ZZ", inquiry), "/api/customers/AA%25ZZ"),
                Arguments.of("invalid escape %G1",
                        head("GET", "/api/customers/%G1AB", inquiry), "/api/customers/%25G1AB"),
                Arguments.of("raw pipe in the query",
                        head("GET", "/api/customers?name=A|B", inquiry), null),
                Arguments.of("raw brace in the path",
                        head("GET", "/api/customers/{AAB", inquiry), null),
                Arguments.of("method that is not a token",
                        head("GE(T", "/api/states", inquiry), null));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("rejectedWithBadRequest")
    @DisplayName("connector 400: problem+json APP0400 \"bad request\" with security headers")
    void connectorBadRequestIsProblemJson(String name, String request, @Nullable String instance)
            throws IOException {
        RawResponse response = exchange(request);

        JsonNode problem = assertClientProblem(response, HttpStatus.BAD_REQUEST, BAD_REQUEST, instance);
        assertThat(problem.path("instance").asText()).doesNotContain("?");
        assertSecurityHeaders(response);
    }

    @ParameterizedTest(name = "TRACE {0}")
    @MethodSource("traceTargets")
    @DisplayName("TRACE: 405 problem+json APP0400 \"method not allowed\" with Allow")
    void traceIsMethodNotAllowedProblem(String path, @Nullable String authorization) throws IOException {
        RawResponse response = exchange(head("TRACE", path, authorization));

        assertClientProblem(response, HttpStatus.METHOD_NOT_ALLOWED, METHOD_NOT_ALLOWED, path);
        assertSecurityHeaders(response);
        String allow = response.headers().getFirst(HttpHeaders.ALLOW);
        assertThat(allow).as("Allow header of the 405").isNotBlank().contains("GET")
                .doesNotContain("TRACE");
    }

    /**
     * The {@code TRACE} cases: the public message catalog anonymously, and the state list signed in.
     *
     * @return the path and the {@code Authorization} header, {@code null} for none
     */
    static Stream<Arguments> traceTargets() {
        return Stream.of(
                Arguments.of("/api/messages", null),
                Arguments.of("/api/states", basic(INQUIRY_USER, INQUIRY_PASSWORD)));
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    @DisplayName("connector 5xx (CONNECT, 501): 500 DEM9999 with an errorId logged at ERROR")
    void connectorServerErrorIsProgramError(CapturedOutput output) throws IOException {
        RawResponse response = exchange(head("CONNECT", "localhost:443", null));

        assertThat(response.status()).as("status; body: %s", response.body())
                .isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR.value());
        MediaType contentType = response.headers().getContentType();
        assertThat(contentType).isNotNull();
        assertThat(MediaType.APPLICATION_PROBLEM_JSON.isCompatibleWith(contentType)).isTrue();
        assertThat(response.body().toLowerCase(Locale.ROOT)).doesNotContain("<html", "tomcat", "connect");
        JsonNode problem = objectMapper.readTree(response.body());
        assertThat(problem.path("type").asText()).isEqualTo(ProblemFactory.TYPE_PREFIX + DEM9999);
        assertThat(problem.path("code").asText()).isEqualTo(DEM9999);
        String errorId = problem.path("errorId").asText();
        assertThat(errorId).matches(UUID_FORM);
        assertSecurityHeaders(response);
        assertThat(output.getOut()).contains("ERROR").contains("errorId=" + errorId + " status=501");
    }

    @Test
    @DisplayName("control: a 7,000-character cursor reaches the application, 400 \"cursor is not valid\"")
    void cursorUnderTheLimitReachesTheApplication() {
        ResponseEntity<String> response = inquiryXhr().get()
                .uri("/api/customers?cursor={cursor}", repeat(7_000))
                .retrieve()
                .toEntity(String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        JsonNode problem = problem(response);
        assertThat(problem.path("code").asText()).isEqualTo(APP0400);
        assertThat(problem.path("detail").asText()).isEqualTo("Request is not valid: cursor is not valid");
        assertThat(problem.path("errors").path(0).path("field").asText()).isEqualTo("cursor");
    }

    @Test
    @DisplayName("control: a firewall rejection still goes through /error, 400 APP0400")
    void firewallRejectionStillUsesTheErrorDispatch() throws IOException {
        RawResponse response = exchange(head("GET", "//api/session", basic(INQUIRY_USER, INQUIRY_PASSWORD)));

        // The ERROR dispatch writes the body, so the valve, which reports only an error nothing has
        // answered yet, leaves it as it is: one problem body, the request's own path as instance.
        assertClientProblem(response, HttpStatus.BAD_REQUEST, BAD_REQUEST, "//api/session");
    }

    @Test
    @DisplayName("control: a valid request still gets its 200 JSON body and security headers")
    void validRequestIsUnchanged() {
        ResponseEntity<String> response = anonymous().get().uri("/api/messages")
                .retrieve()
                .toEntity(String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getContentType()).isNotNull();
        assertThat(MediaType.APPLICATION_JSON.isCompatibleWith(response.getHeaders().getContentType()))
                .isTrue();
        assertThat(json(response).has(APP0400)).isTrue();
        assertThat(response.getHeaders().getFirst("X-Content-Type-Options")).isEqualTo("nosniff");
    }

    @Test
    @DisplayName("the host's only error-report valve is ProblemErrorReportValve")
    void hostHasOnlyTheProblemValve() {
        assertThat(applicationContext).isInstanceOf(WebServerApplicationContext.class);
        assertThat(((WebServerApplicationContext) applicationContext).getWebServer())
                .isInstanceOf(TomcatWebServer.class);
        Host host = ((TomcatWebServer) ((WebServerApplicationContext) applicationContext).getWebServer())
                .getTomcat().getHost();

        List<Valve> errorValves = Arrays.stream(host.getPipeline().getValves())
                .filter(ErrorReportValve.class::isInstance)
                .toList();

        assertThat(errorValves).singleElement().isInstanceOf(ProblemErrorReportValve.class);
        assertThat(host).isInstanceOf(StandardHost.class);
        assertThat(((StandardHost) host).getErrorReportValveClass())
                .isEqualTo(ProblemErrorReportValve.class.getName());
    }

    /**
     * Asserts that a response is the error model's 4xx problem for {@code APP0400} with the given reason
     * and reveals neither HTML nor the server, in its body or its headers.
     *
     * @param response the raw response
     * @param status the expected status
     * @param reason the expected {@code APP0400} reason
     * @param instance the expected {@code instance}; {@code null} when it must be absent
     * @return the parsed problem
     * @throws IOException when the body is not JSON
     */
    private JsonNode assertClientProblem(RawResponse response, HttpStatus status, String reason,
            @Nullable String instance) throws IOException {
        assertThat(response.status()).as("status; body: %s", response.body()).isEqualTo(status.value());
        MediaType contentType = response.headers().getContentType();
        assertThat(contentType).as("Content-Type; body: %s", response.body()).isNotNull();
        assertThat(MediaType.APPLICATION_PROBLEM_JSON.isCompatibleWith(contentType))
                .as("Content-Type %s is problem+json", contentType).isTrue();
        assertThat(response.body().toLowerCase(Locale.ROOT)).doesNotContain("<html", "<!doctype", "tomcat",
                "apache");

        JsonNode problem = objectMapper.readTree(response.body());
        assertThat(problem.path("type").asText()).isEqualTo(ProblemFactory.TYPE_PREFIX + APP0400);
        assertThat(problem.path("title").asText()).isEqualTo(status.getReasonPhrase());
        assertThat(problem.path("status").asInt()).isEqualTo(status.value());
        assertThat(problem.path("code").asText()).isEqualTo(APP0400);
        assertThat(problem.path("detail").asText()).isEqualTo("Request is not valid: " + reason);
        assertThat(problem.path("args").isArray()).isTrue();
        assertThat(problem.path("args")).hasSize(1);
        assertThat(problem.path("args").path(0).asText()).isEqualTo(reason);

        Set<String> expectedMembers = new HashSet<>(CLIENT_ERROR_MEMBERS);
        if (instance == null) {
            assertThat(problem.has("instance")).as("instance of a request without a path").isFalse();
        } else {
            expectedMembers.add("instance");
            assertThat(problem.path("instance").asText()).isEqualTo(instance);
        }
        Set<String> members = new HashSet<>();
        problem.fieldNames().forEachRemaining(members::add);
        assertThat(members).isEqualTo(expectedMembers);

        assertThat(response.headers().containsKey(HttpHeaders.CONTENT_LANGUAGE)).isFalse();
        response.headers().forEach((headerName, values) -> assertThat(
                String.join(",", values).toLowerCase(Locale.ROOT))
                .as("header %s", headerName).doesNotContain("tomcat", "apache"));
        return problem;
    }

    /**
     * Asserts the headers the security filter chain adds to every response it answers: Spring Security's
     * defaults, and the strict {@code Content-Security-Policy} and the {@code Referrer-Policy}, each sent
     * exactly once; and no HSTS, since the test server speaks plain HTTP.
     *
     * @param response the raw response
     */
    private static void assertSecurityHeaders(RawResponse response) {
        HttpHeaders headers = response.headers();
        assertThat(headers.getFirst("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(headers.getFirst("X-XSS-Protection")).isEqualTo("0");
        assertThat(headers.getFirst(HttpHeaders.CACHE_CONTROL))
                .isEqualTo("no-cache, no-store, max-age=0, must-revalidate");
        assertThat(headers.getFirst(HttpHeaders.PRAGMA)).isEqualTo("no-cache");
        assertThat(headers.getFirst(HttpHeaders.EXPIRES)).isEqualTo("0");
        assertThat(headers.getFirst("X-Frame-Options")).isEqualTo("DENY");
        assertThat(headers.get("Content-Security-Policy")).as("Content-Security-Policy values")
                .containsExactly("default-src 'none'; frame-ancestors 'none'");
        assertThat(headers.get("Referrer-Policy")).as("Referrer-Policy values")
                .containsExactly("no-referrer");
        assertThat(headers.containsKey("Strict-Transport-Security"))
                .as("HSTS over plain HTTP").isFalse();
    }

    /**
     * Sends a raw request head and reads the whole response, which the server ends by closing the
     * connection.
     *
     * @param request the request head, ending with an empty line
     * @return the parsed response
     * @throws IOException when the connection fails or times out
     */
    private RawResponse exchange(String request) throws IOException {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), port),
                    CONNECT_TIMEOUT_MILLIS);
            socket.setSoTimeout(READ_TIMEOUT_MILLIS);
            OutputStream out = socket.getOutputStream();
            out.write(request.getBytes(StandardCharsets.ISO_8859_1));
            out.flush();
            return RawResponse.parse(socket.getInputStream().readAllBytes());
        }
    }

    /**
     * Builds a request head with {@code Host} and {@code Connection: close}.
     *
     * @param method the method, written as given
     * @param target the request target, written as given
     * @param authorization the {@code Authorization} header value; {@code null} for none
     * @param extraHeaders further header lines, each {@code Name: value}
     * @return the head, ending with an empty line
     */
    private static String head(String method, String target, @Nullable String authorization,
            String... extraHeaders) {
        StringBuilder head = new StringBuilder()
                .append(method).append(' ').append(target).append(" HTTP/1.1\r\n")
                .append("Host: localhost\r\n");
        if (authorization != null) {
            head.append(HttpHeaders.AUTHORIZATION).append(": ").append(authorization).append("\r\n");
        }
        for (String header : extraHeaders) {
            head.append(header).append("\r\n");
        }
        return head.append("Connection: close").append(HEAD_END).toString();
    }

    /**
     * Returns an HTTP Basic {@code Authorization} value.
     *
     * @param username the user
     * @param password the password
     * @return {@code Basic <base64(username:password)>}
     */
    private static String basic(String username, String password) {
        return "Basic " + Base64.getEncoder()
                .encodeToString((username + ":" + password).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Returns {@code count} letters {@code A}.
     *
     * @param count the length
     * @return the text
     */
    private static String repeat(int count) {
        return "A".repeat(count);
    }

    /**
     * A response read from a raw socket.
     *
     * @param status the status code
     * @param headers the headers, matched without regard to case
     * @param body the body as UTF-8 text, exactly {@code Content-Length} bytes
     */
    private record RawResponse(int status, HttpHeaders headers, String body) {

        /**
         * Parses a complete HTTP/1.1 response whose body is framed by {@code Content-Length}, as every
         * problem body is.
         *
         * @param raw the bytes read until the server closed the connection
         * @return the response
         * @throws AssertionError when the bytes are not such a response; the message shows them
         */
        static RawResponse parse(byte[] raw) {
            String text = new String(raw, StandardCharsets.ISO_8859_1);
            int headEnd = text.indexOf(HEAD_END);
            if (headEnd < 0) {
                throw new AssertionError("No complete response head in: <" + text + ">");
            }
            String[] lines = text.substring(0, headEnd).split("\r\n");
            String[] statusLine = lines[0].split(" ", 3);
            if (statusLine.length < 2 || !statusLine[0].startsWith("HTTP/1.")) {
                throw new AssertionError("Not an HTTP/1.x status line: <" + lines[0] + ">");
            }
            HttpHeaders headers = new HttpHeaders();
            for (int i = 1; i < lines.length; i++) {
                int colon = lines[i].indexOf(':');
                if (colon <= 0) {
                    throw new AssertionError("Malformed header line: <" + lines[i] + ">");
                }
                headers.add(lines[i].substring(0, colon).trim(), lines[i].substring(colon + 1).trim());
            }
            String length = headers.getFirst(HttpHeaders.CONTENT_LENGTH);
            if (length == null) {
                throw new AssertionError("Response without Content-Length: <" + text + ">");
            }
            int bodyStart = headEnd + HEAD_END.length();
            int bodyLength = Integer.parseInt(length);
            if (raw.length < bodyStart + bodyLength) {
                throw new AssertionError("Body shorter than Content-Length " + bodyLength + ": <" + text + ">");
            }
            String body = new String(raw, bodyStart, bodyLength, StandardCharsets.UTF_8);
            return new RawResponse(Integer.parseInt(statusLine[1]), headers, body);
        }
    }
}
