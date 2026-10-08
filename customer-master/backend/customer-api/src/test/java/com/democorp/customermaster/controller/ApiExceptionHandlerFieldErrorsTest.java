package com.democorp.customermaster.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.democorp.customermaster.config.ConnectionPoolSaturation;
import com.democorp.customermaster.controller.ProblemFactory.FieldProblem;
import com.democorp.customermaster.messages.MessageCatalog;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.beans.PropertyChangeEvent;
import java.lang.reflect.Method;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.TypeMismatchException;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * Specifies the {@code errors[]} of APP0400 for the framework failures no route of the API can provoke
 * over HTTP: a missing required query parameter, a type mismatch outside a handler argument, and a type
 * mismatch on a handler argument that is neither a query parameter nor a path variable.
 * {@code RequestFieldErrorsIT} covers the failures the routes do provoke.
 *
 * <p>The rule under test: the {@code detail} and {@code args} keep their reason, and {@code errors} holds
 * an APP0400 item on the named query parameter or property, with that reason as message; a failure that
 * names nothing a client can be pointed to carries no {@code errors}.
 *
 * <p>Plain JUnit 5 and AssertJ over a real {@link ProblemFactory} and {@link MessageCatalog}, with
 * Spring's mock servlet request: no Spring context, no database, no Docker. The handler's
 * {@link ConnectionPoolSaturation} wraps an unconfigured {@link DriverManagerDataSource}, which none of
 * these failures consults.
 */
@DisplayName("ApiExceptionHandler: APP0400 errors[] for parameter failures")
final class ApiExceptionHandlerFieldErrorsTest {

    /** The handler under test. */
    private ApiExceptionHandler handler;

    /** The request the failures belong to. */
    private ServletWebRequest request;

    @BeforeEach
    void createHandler() {
        handler = new ApiExceptionHandler(new ProblemFactory(new MessageCatalog(), new ObjectMapper()),
                new ConnectionPoolSaturation(new DriverManagerDataSource()));
        request = new ServletWebRequest(new MockHttpServletRequest("GET", "/api/states"),
                new MockHttpServletResponse());
    }

    @Test
    @DisplayName("a missing required query parameter is an item on that parameter")
    void missingParameterIsAFieldError() throws Exception {
        ProblemDetail problem = handle(new MissingServletRequestParameterException("nameContains", "String"));

        assertInvalid(problem, "nameContains is required");
        assertThat(errorsOf(problem)).containsExactly(
                new FieldProblem("nameContains", "APP0400", "Request is not valid: nameContains is required"));
    }

    @Test
    @DisplayName("a type mismatch on a named property is an item on that property")
    void namedTypeMismatchIsAFieldError() throws Exception {
        TypeMismatchException mismatch = new TypeMismatchException(
                new PropertyChangeEvent(this, "size", null, "abc"), Integer.class);

        ProblemDetail problem = handle(mismatch);

        assertInvalid(problem, "size has an invalid value");
        assertThat(errorsOf(problem)).containsExactly(
                new FieldProblem("size", "APP0400", "Request is not valid: size has an invalid value"));
    }

    @Test
    @DisplayName("a type mismatch with no property name invents no field")
    void unnamedTypeMismatchHasNoErrors() throws Exception {
        ProblemDetail problem = handle(new TypeMismatchException("abc", Integer.class));

        assertInvalid(problem, "parameter has an invalid value");
        assertThat(problem.getProperties()).doesNotContainKey("errors");
    }

    @Test
    @DisplayName("a type mismatch on a query parameter argument is an item on that parameter")
    void queryParameterArgumentMismatchIsAFieldError() throws Exception {
        MethodParameter size = new MethodParameter(fixture(), 0);

        ProblemDetail problem = handle(
                new MethodArgumentTypeMismatchException("abc", Integer.class, "size", size, null));

        assertInvalid(problem, "size has an invalid value");
        assertThat(errorsOf(problem)).extracting(FieldProblem::field).containsExactly("size");
    }

    @Test
    @DisplayName("a type mismatch on a header argument keeps its reason but invents no field")
    void headerArgumentMismatchHasNoErrors() throws Exception {
        MethodParameter header = new MethodParameter(fixture(), 1);

        ProblemDetail problem = handle(
                new MethodArgumentTypeMismatchException("abc", Integer.class, "X-Count", header, null));

        assertInvalid(problem, "X-Count has an invalid value");
        assertThat(problem.getProperties()).doesNotContainKey("errors");
    }

    /**
     * A handler signature for {@link MethodParameter}s: a query parameter and a header.
     *
     * @param size a query parameter
     * @param count a request header
     */
    @SuppressWarnings("unused")
    private static void handlerSignature(@RequestParam("size") Integer size,
            @RequestHeader("X-Count") Integer count) {
        // Only its parameter annotations are read.
    }

    private static Method fixture() throws NoSuchMethodException {
        return ApiExceptionHandlerFieldErrorsTest.class.getDeclaredMethod("handlerSignature", Integer.class,
                Integer.class);
    }

    private ProblemDetail handle(Exception ex) throws Exception {
        ResponseEntity<Object> response = handler.handleException(ex, request);
        assertThat(response).isNotNull();
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isInstanceOf(ProblemDetail.class);
        return (ProblemDetail) response.getBody();
    }

    private static void assertInvalid(ProblemDetail problem, String reason) {
        assertThat(problem.getDetail()).isEqualTo("Request is not valid: " + reason);
        assertThat(problem.getProperties()).isNotNull();
        assertThat(problem.getProperties().get("code")).isEqualTo("APP0400");
        assertThat(problem.getProperties().get("args")).isEqualTo(List.of(reason));
    }

    @SuppressWarnings("unchecked")
    private static List<FieldProblem> errorsOf(ProblemDetail problem) {
        assertThat(problem.getProperties()).containsKey("errors");
        return (List<FieldProblem>) problem.getProperties().get("errors");
    }
}
