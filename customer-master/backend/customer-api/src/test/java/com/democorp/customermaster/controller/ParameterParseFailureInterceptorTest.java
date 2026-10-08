package com.democorp.customermaster.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.democorp.customermaster.controller.ProblemFactory.FieldProblem;
import com.democorp.customermaster.messages.MessageCatalog;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.DispatcherType;
import java.util.List;
import org.apache.catalina.Globals;
import org.apache.tomcat.util.http.Parameters.FailReason;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.context.request.ServletWebRequest;

/**
 * Specifies how a parameter parse failure of Tomcat becomes a problem: which query parameter
 * {@link ParameterParseFailureInterceptor} names, which status each Tomcat failure reason gets, when the
 * interceptor rejects a request at all, and the body {@link ApiExceptionHandler} writes for the
 * resulting {@link ParameterParseFailedException}. {@code MalformedQueryParameterIT} drives the same
 * rules through a real Tomcat.
 *
 * <p>Plain JUnit 5 and AssertJ over a real {@link ProblemFactory} and {@link MessageCatalog}, with
 * Spring's mock servlet request standing in for Tomcat's request attributes: no Spring context, no
 * database, no Docker.
 */
@DisplayName("ParameterParseFailureInterceptor: Tomcat parameter parse failures become problems")
final class ParameterParseFailureInterceptorTest {

    /** The interceptor under test. */
    private ParameterParseFailureInterceptor interceptor;

    /** The handler that answers the interceptor's exception. */
    private ApiExceptionHandler handler;

    @BeforeEach
    void create() {
        interceptor = new ParameterParseFailureInterceptor();
        handler = new ApiExceptionHandler(new ProblemFactory(new MessageCatalog(), new ObjectMapper()));
    }

    // ---------------------------------------------------------------------------------------------
    // The query parameter at fault
    // ---------------------------------------------------------------------------------------------

    @ParameterizedTest(name = "{0} names {1}")
    @CsvSource(delimiter = '|', value = {
        "cursor=%%%|cursor",
        "name=ZQ%&size=2|name",
        "state=C%|state",
        "size=1%|size",
        "includeInactive=tr%ue|includeInactive",
        "nameContains=car%|nameContains",
        "name=%ZZ|name",
        "state=%ZZ|state",
        "cursor=%ZZ|cursor",
        "size=%ZZ|size",
        "x=%4|x",
        "x=%G1|x",
        "name=AB&city=%G1&state=%|city",
        "=%%&&size=%|size",
        "c%75rsor=%%%|cursor",
        "first+name=%|first name",
        "%E2%82%AC=%|\u20AC",
        "flag&x=a=%|x",
        "x=%\uFF10\uFF10|x"
    })
    @DisplayName("the first chunk with an undecodable name or value gives its decoded name; full-width"
            + " digits are no escape")
    void namesTheFirstUndecodableParameter(String queryString, String expected) {
        assertThat(ParameterParseFailureInterceptor.undecodableParameter(queryString)).isEqualTo(expected);
    }

    @ParameterizedTest(name = "{0} names nothing")
    @ValueSource(strings = {"na%ZZme=x", "x%=1", "flag%", "%=x&y=%"})
    @DisplayName("an undecodable name is never guessed at")
    void undecodableNameNamesNothing(String queryString) {
        assertThat(ParameterParseFailureInterceptor.undecodableParameter(queryString)).isNull();
    }

    @ParameterizedTest(name = "{0} names nothing")
    @NullAndEmptySource
    @ValueSource(strings = {"cursor=%25%25%25", "name=ZQ%25&size=2", "x=%41%42", "a+b=c+d", "&&", "x"})
    @DisplayName("a query string Tomcat decodes, or none, names nothing")
    void decodableQueryNamesNothing(String queryString) {
        assertThat(ParameterParseFailureInterceptor.undecodableParameter(queryString)).isNull();
    }

    // ---------------------------------------------------------------------------------------------
    // Failure reason to exception
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("URL_DECODING is a 400 on the parameter found in the query string")
    void decodingFailureNamesTheParameter() {
        ParameterParseFailedException failure =
                ParameterParseFailureInterceptor.failureOf(FailReason.URL_DECODING, "size=2&cursor=%%%");

        assertThat(failure.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(failure.parameterName()).isEqualTo("cursor");
        assertThat(failure.reason()).isNull();
    }

    @Test
    @DisplayName("URL_DECODING with nothing undecodable in the query string, a form body, names nothing")
    void decodingFailureOutsideTheQueryNamesNothing() {
        ParameterParseFailedException failure =
                ParameterParseFailureInterceptor.failureOf(FailReason.URL_DECODING, null);

        assertThat(failure.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(failure.parameterName()).isNull();
        assertThat(failure.reason()).isNull();
    }

    @ParameterizedTest(name = "{0} -> {1} {2}")
    @CsvSource(delimiter = '|', value = {
        "NO_NAME|400|parameter without a name",
        "TOO_MANY_PARAMETERS|400|too many parameters",
        "POST_TOO_LARGE|413|request body too large",
        "IO_ERROR|500|request parameters could not be read",
        "UNKNOWN|400|malformed request parameters",
        "INVALID_CONTENT_TYPE|400|malformed request parameters",
        "MULTIPART_CONFIG_INVALID|400|malformed request parameters",
        "REQUEST_BODY_INCOMPLETE|400|malformed request parameters",
        "CLIENT_DISCONNECT|400|malformed request parameters"
    })
    @DisplayName("every other reason gets the status of Tomcat's FailedRequestFilter and a fixed reason")
    void otherReasonsAreFixed(FailReason reason, int status, String expected) {
        ParameterParseFailedException failure =
                ParameterParseFailureInterceptor.failureOf(reason, "cursor=%%%");

        assertThat(failure.getStatusCode().value()).isEqualTo(status);
        assertThat(failure.reason()).isEqualTo(expected);
        assertThat(failure.parameterName()).isNull();
    }

    @Test
    @DisplayName("a reason that is no Tomcat FailReason is an unknown failure")
    void foreignReasonIsUnknown() {
        assertThat(ParameterParseFailureInterceptor.failureOf(null, "x=%").reason())
                .isEqualTo("malformed request parameters");
        assertThat(ParameterParseFailureInterceptor.failureOf("URL_DECODING", "x=%").reason())
                .isEqualTo("malformed request parameters");
    }

    // ---------------------------------------------------------------------------------------------
    // When the interceptor rejects
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("a REQUEST dispatch whose parameters parsed is let through")
    void parsedRequestPasses() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/customers");
        request.setQueryString("cursor=%25%25%25");

        assertThat(interceptor.preHandle(request, new MockHttpServletResponse(), new Object())).isTrue();
    }

    @Test
    @DisplayName("a REQUEST dispatch with a parse failure is rejected with the parameter at fault")
    void failedRequestIsRejected() {
        MockHttpServletRequest request = failedRequest(FailReason.URL_DECODING, "name=ZQ%&size=2");

        assertThatThrownBy(() -> interceptor.preHandle(request, new MockHttpServletResponse(), new Object()))
                .isInstanceOfSatisfying(ParameterParseFailedException.class, failure -> {
                    assertThat(failure.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(failure.parameterName()).isEqualTo("name");
                });
    }

    @Test
    @DisplayName("an ERROR or ASYNC dispatch is never rejected, whatever the attributes say")
    void otherDispatchesPass() {
        for (DispatcherType type : List.of(DispatcherType.ERROR, DispatcherType.ASYNC, DispatcherType.FORWARD,
                DispatcherType.INCLUDE)) {
            MockHttpServletRequest request = failedRequest(FailReason.URL_DECODING, "cursor=%%%");
            request.setDispatcherType(type);

            assertThat(interceptor.preHandle(request, new MockHttpServletResponse(), new Object()))
                    .as(type.name()).isTrue();
        }
    }

    // ---------------------------------------------------------------------------------------------
    // The problem ApiExceptionHandler writes
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("an undecodable named parameter: 400 APP0400 with one errors[] item on it, as size=abc")
    void undecodableParameterIsAFieldError() throws Exception {
        ProblemDetail problem = handle(ParameterParseFailedException.undecodable("cursor"), HttpStatus.BAD_REQUEST);

        assertInvalid(problem, "cursor has an invalid value");
        assertThat(problem.getProperties()).containsKey("errors");
        assertThat(problem.getProperties().get("errors")).isEqualTo(List.of(
                new FieldProblem("cursor", "APP0400", "Request is not valid: cursor has an invalid value")));
    }

    @Test
    @DisplayName("an undecodable parameter with no usable name: APP0400 that invents no field")
    void undecodableUnnamedParameterHasNoErrors() throws Exception {
        ProblemDetail problem = handle(ParameterParseFailedException.undecodable(null), HttpStatus.BAD_REQUEST);

        assertInvalid(problem, "parameter has an invalid value");
        assertThat(problem.getProperties()).doesNotContainKey("errors");
    }

    @Test
    @DisplayName("a failure of the parameters as a whole: APP0400 with its fixed reason and no field")
    void wholeRequestFailureHasNoErrors() throws Exception {
        ProblemDetail problem = handle(
                ParameterParseFailureInterceptor.failureOf(FailReason.NO_NAME, "=x"), HttpStatus.BAD_REQUEST);

        assertInvalid(problem, "parameter without a name");
        assertThat(problem.getProperties()).doesNotContainKey("errors");
    }

    @Test
    @DisplayName("a form body over the limit keeps 413 with APP0400")
    void bodyTooLargeKeepsItsStatus() throws Exception {
        ProblemDetail problem = handle(
                ParameterParseFailureInterceptor.failureOf(FailReason.POST_TOO_LARGE, null),
                HttpStatus.PAYLOAD_TOO_LARGE);

        assertInvalid(problem, "request body too large");
        assertThat(problem.getProperties()).doesNotContainKey("errors");
    }

    @Test
    @DisplayName("an I/O failure reading the body is 500 DEM9999 with an errorId, never APP0400")
    void ioFailureIsAProgramError() throws Exception {
        ProblemDetail problem = handle(
                ParameterParseFailureInterceptor.failureOf(FailReason.IO_ERROR, null),
                HttpStatus.INTERNAL_SERVER_ERROR);

        assertThat(problem.getProperties()).isNotNull();
        assertThat(problem.getProperties().get("code")).isEqualTo("DEM9999");
        assertThat(problem.getProperties()).containsKey("errorId");
        assertThat(problem.getDetail()).doesNotContain("parameters");
    }

    private static MockHttpServletRequest failedRequest(FailReason reason, String queryString) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/customers");
        request.setQueryString(queryString);
        request.setAttribute(Globals.PARAMETER_PARSE_FAILED_ATTR, Boolean.TRUE);
        request.setAttribute(Globals.PARAMETER_PARSE_FAILED_REASON_ATTR, reason);
        return request;
    }

    private ProblemDetail handle(Exception ex, HttpStatus status) throws Exception {
        ServletWebRequest request = new ServletWebRequest(new MockHttpServletRequest("GET", "/api/customers"),
                new MockHttpServletResponse());
        ResponseEntity<Object> response = handler.handleException(ex, request);
        assertThat(response).isNotNull();
        assertThat(response.getStatusCode()).isEqualTo(status);
        assertThat(response.getBody()).isInstanceOf(ProblemDetail.class);
        return (ProblemDetail) response.getBody();
    }

    private static void assertInvalid(ProblemDetail problem, String reason) {
        assertThat(problem.getDetail()).isEqualTo("Request is not valid: " + reason);
        assertThat(problem.getProperties()).isNotNull();
        assertThat(problem.getProperties().get("code")).isEqualTo("APP0400");
        assertThat(problem.getProperties().get("args")).isEqualTo(List.of(reason));
    }
}
