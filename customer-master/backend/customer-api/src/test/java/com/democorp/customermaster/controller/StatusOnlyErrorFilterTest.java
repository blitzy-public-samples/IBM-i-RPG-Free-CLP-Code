package com.democorp.customermaster.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIOException;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.FilterChain;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * Specifies {@link StatusOnlyErrorFilter}: a 4xx the rest of the chain leaves with only a status is sent
 * as an error with that status, so the container's ERROR dispatch answers it, and every other response is
 * left exactly as the chain left it.
 *
 * <p><b>Sent as an error.</b> A status from 400 to 499 with no body started, no {@code sendError}, an
 * uncommitted response and no asynchronous processing; headers set before are kept.
 *
 * <p><b>Left alone.</b> A started body (writer or stream, even with nothing written), a {@code sendError}
 * of the chain's own (with or without a message), a committed response, started asynchronous processing,
 * a status outside 4xx (the 503 of a DOWN health probe included), an exception from the chain, and an
 * ERROR or asynchronous dispatch, which the filter does not run on.
 *
 * <p>Pure JUnit 5 and AssertJ over Spring's servlet mocks: no web server, no database. The response is a
 * {@link ContainerResponse}, whose {@code sendError} leaves it uncommitted as Tomcat's does, so each
 * condition is proved on its own. That the ERROR dispatch then builds the problem body is proved against
 * the running server by {@code ErrorModelIT}.
 */
@DisplayName("StatusOnlyErrorFilter: a status-only 4xx becomes an ERROR dispatch, nothing else changes")
class StatusOnlyErrorFilterTest {

    /** The path of every request; any path would do. */
    private static final String PATH = "/actuator/health/db";

    /** A header the chain sets before the filter acts, which must survive. */
    private static final String KEPT_HEADER = "X-Content-Type-Options";

    /** The filter under test; JUnit creates one per test. */
    private final StatusOnlyErrorFilter filter = new StatusOnlyErrorFilter();

    /** A REQUEST dispatch of {@value #PATH}, which a test may turn into an ERROR or async dispatch. */
    private final MockHttpServletRequest request = new MockHttpServletRequest("GET", PATH);

    /** The container's response, recording every {@code sendError}. */
    private final ContainerResponse response = new ContainerResponse();

    /**
     * A chain that sets only a 4xx status gets that status sent as an error, once.
     *
     * @param status the client-error status the chain sets
     * @throws Exception never; the filter's checked exceptions are declared
     */
    @ParameterizedTest
    @ValueSource(ints = {400, 404, 405, 412, 499})
    @DisplayName("a status-only 4xx is sent as an error with its status, keeping earlier headers")
    void statusOnlyClientErrorIsSent(int status) throws Exception {
        filter.doFilter(request, response, (req, res) -> {
            HttpServletResponse chained = (HttpServletResponse) res;
            chained.setHeader(KEPT_HEADER, "nosniff");
            chained.setStatus(status);
        });

        assertThat(response.errorsSent()).containsExactly(status);
        assertThat(response.getStatus()).isEqualTo(status);
        assertThat(response.getHeader(KEPT_HEADER)).isEqualTo("nosniff");
        assertThat(response.isCommitted()).isFalse();
    }

    /**
     * A 404 whose body the chain wrote through the writer keeps that body.
     *
     * @throws Exception never; the filter's checked exceptions are declared
     */
    @Test
    @DisplayName("a 404 with a written body is left alone")
    void writtenBodyIsLeftAlone() throws Exception {
        filter.doFilter(request, response, (req, res) -> {
            HttpServletResponse chained = (HttpServletResponse) res;
            chained.setStatus(404);
            chained.getWriter().write("{}");
        });

        assertThat(response.errorsSent()).isEmpty();
        assertThat(response.getStatus()).isEqualTo(404);
        assertThat(response.getContentAsString()).isEqualTo("{}");
    }

    /**
     * A 404 whose chain took the output stream counts as answered, whether or not it wrote to it.
     *
     * @throws Exception never; the filter's checked exceptions are declared
     */
    @Test
    @DisplayName("a 404 whose output stream was taken is left alone, even with nothing written")
    void startedStreamIsLeftAlone() throws Exception {
        filter.doFilter(request, response, (req, res) -> {
            HttpServletResponse chained = (HttpServletResponse) res;
            chained.setStatus(404);
            chained.getOutputStream();
        });

        assertThat(response.errorsSent()).isEmpty();
        assertThat(response.getStatus()).isEqualTo(404);
    }

    /**
     * A {@code sendError(int)} the chain made itself is the only one; the uncommitted response proves the
     * filter's own record of it, not the commit check, keeps the filter out.
     *
     * @throws Exception never; the filter's checked exceptions are declared
     */
    @Test
    @DisplayName("a sendError of the chain's own is not repeated")
    void chainSendErrorIsNotRepeated() throws Exception {
        filter.doFilter(request, response, (req, res) -> ((HttpServletResponse) res).sendError(404));

        assertThat(response.errorsSent()).containsExactly(404);
    }

    /**
     * A {@code sendError(int, String)} the chain made itself is the only one.
     *
     * @throws Exception never; the filter's checked exceptions are declared
     */
    @Test
    @DisplayName("a sendError with a message of the chain's own is not repeated")
    void chainSendErrorWithMessageIsNotRepeated() throws Exception {
        filter.doFilter(request, response,
                (req, res) -> ((HttpServletResponse) res).sendError(405, "Method Not Allowed"));

        assertThat(response.errorsSent()).containsExactly(405);
    }

    /**
     * A 404 the chain committed with no body can take no error any more and is left as sent.
     *
     * @throws Exception never; the filter's checked exceptions are declared
     */
    @Test
    @DisplayName("a committed 404 is left alone")
    void committedResponseIsLeftAlone() throws Exception {
        filter.doFilter(request, response, (req, res) -> {
            HttpServletResponse chained = (HttpServletResponse) res;
            chained.setStatus(404);
            chained.flushBuffer();
        });

        assertThat(response.errorsSent()).isEmpty();
        assertThat(response.isCommitted()).isTrue();
        assertThat(response.getStatus()).isEqualTo(404);
    }

    /**
     * A 404 set by a chain that started asynchronous processing is left to that processing.
     *
     * @throws Exception never; the filter's checked exceptions are declared
     */
    @Test
    @DisplayName("a 404 after asynchronous processing started is left alone")
    void asyncStartedIsLeftAlone() throws Exception {
        request.setAsyncSupported(true);

        filter.doFilter(request, response, (req, res) -> {
            req.startAsync();
            ((HttpServletResponse) res).setStatus(404);
        });

        assertThat(request.isAsyncStarted()).isTrue();
        assertThat(response.errorsSent()).isEmpty();
    }

    /**
     * A status-only response outside 400 to 499 keeps its status with no error sent.
     *
     * @param status the status the chain sets
     * @throws Exception never; the filter's checked exceptions are declared
     */
    @ParameterizedTest
    @ValueSource(ints = {200, 204, 304, 399, 500, 503})
    @DisplayName("a status-only response outside 4xx is left alone, the 503 of a DOWN probe included")
    void statusOutsideClientErrorsIsLeftAlone(int status) throws Exception {
        filter.doFilter(request, response, (req, res) -> ((HttpServletResponse) res).setStatus(status));

        assertThat(response.errorsSent()).isEmpty();
        assertThat(response.getStatus()).isEqualTo(status);
    }

    /**
     * An exception the chain throws reaches the caller as the same instance, and no error is sent, so
     * {@code ErrorDispatchFilter} handles it as before.
     */
    @Test
    @DisplayName("an exception from the chain passes through unchanged, with no sendError")
    void chainExceptionPassesThrough() {
        IOException failure = new IOException("chain failure");
        FilterChain failing = (req, res) -> {
            ((HttpServletResponse) res).setStatus(404);
            throw failure;
        };

        assertThatIOException().isThrownBy(() -> filter.doFilter(request, response, failing))
                .isSameAs(failure);
        assertThat(response.errorsSent()).isEmpty();
    }

    /**
     * The forward to {@code /error} runs the chain without the filter, so a 404 written there is never
     * sent as an error again.
     *
     * @throws Exception never; the filter's checked exceptions are declared
     */
    @Test
    @DisplayName("an ERROR dispatch is not filtered, so /error itself is never sent back as an error")
    void errorDispatchIsNotFiltered() throws Exception {
        // As the container marks the forward to /error: the dispatcher type and the original URI.
        request.setDispatcherType(DispatcherType.ERROR);
        request.setAttribute(RequestDispatcher.ERROR_REQUEST_URI, PATH);

        filter.doFilter(request, response, (req, res) -> ((HttpServletResponse) res).setStatus(404));

        assertThat(response.errorsSent()).isEmpty();
        assertThat(response.getStatus()).isEqualTo(404);
    }

    /**
     * An asynchronous dispatch runs the chain without the filter.
     *
     * @throws Exception never; the filter's checked exceptions are declared
     */
    @Test
    @DisplayName("an asynchronous dispatch is not filtered")
    void asyncDispatchIsNotFiltered() throws Exception {
        request.setDispatcherType(DispatcherType.ASYNC);

        filter.doFilter(request, response, (req, res) -> ((HttpServletResponse) res).setStatus(404));

        assertThat(response.errorsSent()).isEmpty();
        assertThat(response.getStatus()).isEqualTo(404);
    }

    /**
     * A response whose {@code sendError} behaves as Tomcat's does for this filter: it sets the status and
     * leaves the response uncommitted, so the container can still forward to {@code /error}. Each call is
     * recorded with its status.
     */
    private static final class ContainerResponse extends MockHttpServletResponse {

        /** The status of each {@code sendError} call, in call order. */
        private final List<Integer> errorsSent = new ArrayList<>();

        /**
         * Records the call and sets the status, leaving the response uncommitted.
         *
         * @param status the error status
         */
        @Override
        public void sendError(int status) {
            errorsSent.add(status);
            setStatus(status);
        }

        /**
         * Records the call and sets the status, leaving the response uncommitted.
         *
         * @param status the error status
         * @param errorMessage the error message, which this double does not keep
         */
        @Override
        public void sendError(int status, String errorMessage) {
            errorsSent.add(status);
            setStatus(status);
        }

        /**
         * Returns the status of each {@code sendError} call so far.
         *
         * @return the statuses, in call order
         */
        List<Integer> errorsSent() {
            return List.copyOf(errorsSent);
        }
    }
}
