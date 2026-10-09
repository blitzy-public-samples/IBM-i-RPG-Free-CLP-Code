package com.democorp.customermaster.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.democorp.customermaster.controller.ProblemFactory;
import com.democorp.customermaster.messages.MessageCatalog;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.json.ProblemDetailJacksonMixin;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

/**
 * Specifies {@link SecurityConfig.ProblemAuthenticationEntryPoint}, the one answer to missing,
 * malformed, unknown and wrong credentials and to an anonymous request to a protected path.
 *
 * <p><b>Body and challenge.</b> Every case is answered 401 {@code APP0401}
 * {@code application/problem+json} with the request path as {@code instance}, whatever the failure.
 * {@code WWW-Authenticate: Basic realm="customer-master"} is sent exactly when the request carries no
 * {@code X-Requested-With} header, whatever its value.
 *
 * <p><b>Logging.</b> Credentials that were sent and rejected, any failure other than
 * {@link InsufficientAuthenticationException}, write exactly one line, at WARN, carrying the code, the
 * method, whether a challenge was sent and the failure's simple class name. That covers the
 * {@link BadCredentialsException} of the user store and of
 * {@link SecurityConfig.StrictBasicAuthenticationConverter}. An anonymous request
 * ({@link InsufficientAuthenticationException}) and an absent failure write no WARN line, only the
 * debug line, and nothing at all at INFO. No line carries the username, the password, the encoded
 * token, the path, the query, a header value, the client address, the failure's message or a stack
 * trace.
 *
 * <p>Pure JUnit 5, AssertJ and Logback over Spring's servlet mocks: no Spring context, no server, no
 * database. The factory is the real one, with the real catalog and a mapper carrying Spring's
 * {@code ProblemDetail} mixin, as Boot configures it. The list appender and the logger level are global
 * state, so {@link #restoreLogging()} puts them back after every case.
 */
@DisplayName("ProblemAuthenticationEntryPoint: 401 APP0401 problem+json, and one WARN line per rejected sign-in")
final class ProblemAuthenticationEntryPointTest {

    /** The username the rejected request sends; no log line may carry it. */
    private static final String USER = "sentinel-user";

    /** The password the rejected request sends; no log line may carry it. */
    private static final String PASSWORD = "sentinel-pass";

    /** The request path, expected back as {@code instance}; no log line may carry it. */
    private static final String PATH = "/api/customers/SNTL";

    /** The query string of the request; no log line may carry it. */
    private static final String QUERY = "name=SENTINELQUERY";

    /** The client address of the request, from the documentation range; no log line may carry it. */
    private static final String CLIENT_ADDRESS = "203.0.113.77";

    /** A further request header value; no log line may carry it. */
    private static final String USER_AGENT = "sentinel-agent/1.0";

    /** The message of the failure; no log line may carry it, as it may name the user. */
    private static final String FAILURE_MESSAGE = "sentinel failure message for " + USER;

    /** The value the SPA sends in {@code X-Requested-With}; no log line may carry it. */
    private static final String SPA_MARKER = "XMLHttpRequest";

    /** The members of the 401 body, in order. */
    private static final List<String> MEMBERS =
            List.of("type", "title", "status", "detail", "instance", "code", "args");

    /** A mapper carrying Spring's {@code ProblemDetail} mixin, so properties are top-level members. */
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .addMixIn(ProblemDetail.class, ProblemDetailJacksonMixin.class);

    /** The Logback context the application logs through. */
    private final LoggerContext loggerContext = (LoggerContext) LoggerFactory.getILoggerFactory();

    /** The logger of {@link SecurityConfig}, which the entry point writes through. */
    private final Logger securityLogger = loggerContext.getLogger(SecurityConfig.class);

    /** Collects every event of {@link #securityLogger}. */
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

    /** The entry point under test, over the real factory. */
    private final SecurityConfig.ProblemAuthenticationEntryPoint entryPoint =
            new SecurityConfig.ProblemAuthenticationEntryPoint(new ProblemFactory(new MessageCatalog(), MAPPER));

    /** The level of {@link #securityLogger} before the case, restored after it. */
    private Level originalLevel;

    /**
     * Attaches the list appender and sets the logger to DEBUG, so a case sees every line the entry point
     * writes, the debug line included, whatever configured the root logger.
     */
    @BeforeEach
    void captureLogging() {
        originalLevel = securityLogger.getLevel();
        securityLogger.setLevel(Level.DEBUG);
        appender.setContext(loggerContext);
        appender.start();
        securityLogger.addAppender(appender);
    }

    /** Detaches the list appender and restores the logger's level. */
    @AfterEach
    void restoreLogging() {
        securityLogger.detachAppender(appender);
        appender.stop();
        securityLogger.setLevel(originalLevel);
    }

    /**
     * The failures of credentials that were sent and rejected, with the method of the request.
     *
     * @return the failure and the method
     */
    static Stream<Arguments> rejectedCredentials() {
        return Stream.of(
                Arguments.of(new BadCredentialsException(FAILURE_MESSAGE), HttpMethod.GET),
                Arguments.of(new BadCredentialsException(FAILURE_MESSAGE), HttpMethod.POST),
                Arguments.of(new UsernameNotFoundException(FAILURE_MESSAGE), HttpMethod.PUT),
                Arguments.of(new AuthenticationServiceException(FAILURE_MESSAGE), HttpMethod.GET));
    }

    @ParameterizedTest(name = "{0} on {1}")
    @MethodSource("rejectedCredentials")
    void rejectedCredentialsWriteOneWarnLineWithMethodAndCauseOnly(AuthenticationException failure,
            HttpMethod method) throws IOException {
        for (boolean spa : List.of(false, true)) {
            appender.list.clear();
            MockHttpServletRequest request = credentialedRequest(method);
            if (spa) {
                request.addHeader(SecurityConfig.X_REQUESTED_WITH, SPA_MARKER);
            }
            MockHttpServletResponse response = new MockHttpServletResponse();

            entryPoint.commence(request, response, failure);

            assertUnauthorizedProblem(response, !spa);
            ILoggingEvent line = onlyLine();
            assertThat(line.getLevel()).as("level of the line for %s", failure).isEqualTo(Level.WARN);
            assertThat(line.getFormattedMessage())
                    .contains("APP0401")
                    .contains(method.name() + " request")
                    .contains("challenge sent: " + !spa)
                    .contains("cause: " + failure.getClass().getSimpleName());
            assertCarriesNothingFromTheRequest(line);
        }
    }

    @Test
    void malformedHeaderRejectedByTheStrictConverterWritesOneWarnLine() throws IOException {
        MockHttpServletRequest request = credentialedRequest(HttpMethod.GET);
        request.removeHeader(HttpHeaders.AUTHORIZATION);
        request.addHeader(HttpHeaders.AUTHORIZATION, "Basic!" + token());
        request.addHeader(SecurityConfig.X_REQUESTED_WITH, SPA_MARKER);
        BadCredentialsException failure = catchThrowableOfType(BadCredentialsException.class,
                () -> new SecurityConfig.StrictBasicAuthenticationConverter().convert(request));
        assertThat(failure).as("the strict converter's rejection of a scheme without its space").isNotNull();
        MockHttpServletResponse response = new MockHttpServletResponse();

        entryPoint.commence(request, response, failure);

        assertUnauthorizedProblem(response, false);
        ILoggingEvent line = onlyLine();
        assertThat(line.getLevel()).isEqualTo(Level.WARN);
        assertThat(line.getFormattedMessage())
                .contains("APP0401")
                .contains("GET request")
                .contains("challenge sent: false")
                .contains("cause: BadCredentialsException");
        assertCarriesNothingFromTheRequest(line);
    }

    @ParameterizedTest(name = "X-Requested-With present: {0}")
    @ValueSource(booleans = {false, true})
    void anonymousRequestWritesNoWarnLineOnlyTheDebugLine(boolean spa) throws IOException {
        MockHttpServletRequest request = new MockHttpServletRequest(HttpMethod.GET.name(), PATH);
        request.setQueryString(QUERY);
        request.setRemoteAddr(CLIENT_ADDRESS);
        if (spa) {
            request.addHeader(SecurityConfig.X_REQUESTED_WITH, SPA_MARKER);
        }
        MockHttpServletResponse response = new MockHttpServletResponse();

        entryPoint.commence(request, response,
                new InsufficientAuthenticationException("Full authentication is required to access this resource"));

        assertUnauthorizedProblem(response, !spa);
        ILoggingEvent line = onlyLine();
        assertThat(line.getLevel()).as("an anonymous 401 is routine").isEqualTo(Level.DEBUG);
        assertThat(line.getFormattedMessage())
                .contains("APP0401")
                .contains("challenge sent: " + !spa)
                .contains("cause: InsufficientAuthenticationException");
        assertCarriesNothingFromTheRequest(line);
    }

    @Test
    void anonymousRequestWritesNothingAtTheDefaultLevel() throws IOException {
        securityLogger.setLevel(Level.INFO);
        MockHttpServletResponse response = new MockHttpServletResponse();

        entryPoint.commence(new MockHttpServletRequest(HttpMethod.GET.name(), PATH), response,
                new InsufficientAuthenticationException("Full authentication is required to access this resource"));

        assertUnauthorizedProblem(response, true);
        assertThat(appender.list).as("lines of an anonymous 401 at INFO").isEmpty();
    }

    @Test
    void rejectedCredentialsStillWriteTheirWarnLineAtTheDefaultLevel() throws IOException {
        securityLogger.setLevel(Level.INFO);
        MockHttpServletResponse response = new MockHttpServletResponse();

        entryPoint.commence(credentialedRequest(HttpMethod.GET), response,
                new BadCredentialsException(FAILURE_MESSAGE));

        assertUnauthorizedProblem(response, true);
        ILoggingEvent line = onlyLine();
        assertThat(line.getLevel()).isEqualTo(Level.WARN);
        assertCarriesNothingFromTheRequest(line);
    }

    @Test
    void absentFailureWritesNoWarnLine() throws IOException {
        MockHttpServletResponse response = new MockHttpServletResponse();

        entryPoint.commence(credentialedRequest(HttpMethod.GET), response, null);

        assertUnauthorizedProblem(response, true);
        ILoggingEvent line = onlyLine();
        assertThat(line.getLevel()).isEqualTo(Level.DEBUG);
        assertThat(line.getFormattedMessage()).contains("cause: -");
        assertCarriesNothingFromTheRequest(line);
    }

    @ParameterizedTest(name = "X-Requested-With <{0}>")
    @ValueSource(strings = {"", "foo", SPA_MARKER})
    void anyXRequestedWithValueSuppressesTheChallenge(String value) throws IOException {
        MockHttpServletRequest request = credentialedRequest(HttpMethod.GET);
        request.addHeader(SecurityConfig.X_REQUESTED_WITH, value);
        MockHttpServletResponse response = new MockHttpServletResponse();

        entryPoint.commence(request, response, new BadCredentialsException(FAILURE_MESSAGE));

        assertUnauthorizedProblem(response, false);
    }

    @Test
    void rejectsAMissingFactory() {
        assertThatNullPointerException()
                .isThrownBy(() -> new SecurityConfig.ProblemAuthenticationEntryPoint(null))
                .withMessage("problems");
    }

    /**
     * Builds a request for {@value #PATH} carrying Basic credentials, a query, a client address and a
     * {@code User-Agent}, none of which a log line may repeat.
     *
     * @param method the request method
     * @return the request, without {@code X-Requested-With}
     */
    private static MockHttpServletRequest credentialedRequest(HttpMethod method) {
        MockHttpServletRequest request = new MockHttpServletRequest(method.name(), PATH);
        request.setQueryString(QUERY);
        request.setRemoteAddr(CLIENT_ADDRESS);
        request.addHeader(HttpHeaders.AUTHORIZATION, "Basic " + token());
        request.addHeader(HttpHeaders.USER_AGENT, USER_AGENT);
        return request;
    }

    /**
     * Returns the Basic token of {@value #USER} and {@value #PASSWORD}.
     *
     * @return the Base64 of {@code user:password}
     */
    private static String token() {
        return Base64.getEncoder().encodeToString((USER + ":" + PASSWORD).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Returns the one line the entry point wrote.
     *
     * @return the logging event
     */
    private ILoggingEvent onlyLine() {
        assertThat(appender.list).as("lines written for one 401").hasSize(1);
        return appender.list.get(0);
    }

    /**
     * Asserts that a line repeats nothing the request or the failure carried, and logs no stack trace.
     *
     * @param line the logging event
     */
    private static void assertCarriesNothingFromTheRequest(ILoggingEvent line) {
        assertThat(line.getFormattedMessage())
                .doesNotContain(USER)
                .doesNotContain(PASSWORD)
                .doesNotContain(token())
                .doesNotContain(PATH)
                .doesNotContain("/api/")
                .doesNotContain(QUERY)
                .doesNotContain("SENTINELQUERY")
                .doesNotContain(CLIENT_ADDRESS)
                .doesNotContain(USER_AGENT)
                .doesNotContain(SPA_MARKER)
                .doesNotContain(FAILURE_MESSAGE);
        assertThat(line.getThrowableProxy()).as("the line's stack trace").isNull();
    }

    /**
     * Asserts the 401 {@code APP0401} problem response for {@value #PATH} and the challenge rule.
     *
     * @param response the response the entry point wrote
     * @param challenge whether the Basic challenge must be present
     * @throws IOException if the body is not JSON
     */
    private static void assertUnauthorizedProblem(MockHttpServletResponse response, boolean challenge)
            throws IOException {
        assertThat(response.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
        assertThat(response.getContentType()).isEqualTo("application/problem+json;charset=UTF-8");
        assertThat(response.getContentLength()).isEqualTo(response.getContentAsByteArray().length);
        JsonNode body = MAPPER.readTree(response.getContentAsByteArray());
        assertThat(body.properties()).extracting(Map.Entry::getKey).containsExactlyElementsOf(MEMBERS);
        assertThat(body.path("type").asText()).isEqualTo("urn:customer-master:problem:APP0401");
        assertThat(body.path("title").asText()).isEqualTo("Unauthorized");
        assertThat(body.path("status").asInt()).isEqualTo(401);
        assertThat(body.path("detail").asText()).isEqualTo("Sign in required.");
        assertThat(body.path("instance").asText()).isEqualTo(PATH);
        assertThat(body.path("code").asText()).isEqualTo("APP0401");
        assertThat(body.path("args").isArray()).isTrue();
        assertThat(body.path("args")).isEmpty();
        assertThat(response.getContentAsString())
                .doesNotContain(USER)
                .doesNotContain(FAILURE_MESSAGE)
                .doesNotContain("Exception");
        if (challenge) {
            assertThat(response.getHeaders(HttpHeaders.WWW_AUTHENTICATE))
                    .containsExactly(SecurityConfig.BASIC_CHALLENGE);
            assertThat(SecurityConfig.BASIC_CHALLENGE).isEqualTo("Basic realm=\"customer-master\"");
        } else {
            assertThat(response.getHeader(HttpHeaders.WWW_AUTHENTICATE)).isNull();
        }
    }
}
