package com.democorp.customermaster.controller;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import java.io.IOException;
import java.io.PrintWriter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Hands a client-error response that carries only a status to the container's ERROR dispatch, so
 * {@link ProblemErrorController} answers it with the {@code application/problem+json} body every other
 * error carries.
 *
 * <p><b>Why.</b> Some framework code answers a client error by setting the status alone, without
 * {@code sendError}. The actuator health endpoint answers an unknown component or group member, such as
 * {@code /actuator/health/db} or {@code /actuator/health/readiness/xyz}, with a 404
 * {@code WebEndpointResponse} that Spring MVC writes as a {@code ResponseEntity} without a body. Nothing
 * then calls {@code sendError}, so the container never forwards the request to {@code /error}, and the
 * client receives the status with an empty body and no {@code Content-Type}.
 *
 * <p><b>What it does.</b> It runs just inside {@link ErrorDispatchFilter}
 * ({@link Ordered#HIGHEST_PRECEDENCE} + 1), around the security filter chain, every other filter and the
 * {@code DispatcherServlet}, on REQUEST dispatches; an ERROR dispatch, and with it {@code /error} itself,
 * and an asynchronous dispatch are not filtered. It hands the rest of the chain a response wrapper that
 * records whether a body was started ({@code getOutputStream} or {@code getWriter}) and whether
 * {@code sendError} was called. When the chain returns with a status from 400 to 499, no body started, no
 * {@code sendError} made, the response not committed and no asynchronous processing started, it calls
 * {@code sendError} with that status on the container's response. The container then forwards to
 * {@code /error}, where {@link ProblemErrorController} applies its status map and {@link ProblemFactory}
 * builds the body: a 404 becomes {@code APP0400} "Request is not valid: no such resource", as for an
 * unknown API route. Headers already set, the security headers included, are kept.
 *
 * <p><b>Left alone.</b> A response with a body or a {@code sendError} of its own, which is answered
 * already; a committed response, which can take no status or body; a request whose asynchronous
 * processing has started, whose response is completed later on another thread; an exception thrown below
 * it, which reaches {@link ErrorDispatchFilter} unchanged; and every status outside 4xx. A 5xx is never
 * turned into an ERROR dispatch: the 503 of a DOWN health probe carries its own body, and
 * {@link ProblemErrorController} would answer a status-only 5xx as 500 {@code DEM9999}.
 *
 * <p>The web-application condition keeps the filter out of the generator's non-web context. It holds no
 * state; each request gets its own wrapper, so one instance serves all request threads.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
final class StatusOnlyErrorFilter extends OncePerRequestFilter {

    /** Lowest status this filter hands to the ERROR dispatch. */
    private static final int MIN_CLIENT_ERROR = 400;

    /** Highest status this filter hands to the ERROR dispatch. */
    private static final int MAX_CLIENT_ERROR = 499;

    /** Creates the filter, which needs no collaborator and no configuration. */
    StatusOnlyErrorFilter() {
        super();
    }

    /**
     * Runs the rest of the chain with a tracking response and, when it leaves a client error with only a
     * status, calls {@code sendError} with that status, as described on this class.
     *
     * @param request the request
     * @param response the response
     * @param filterChain the rest of the chain
     * @throws ServletException a failure of the rest of the chain, passed on unchanged
     * @throws IOException a failure of the rest of the chain, passed on unchanged, or of {@code sendError}
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        TrackingResponse tracked = new TrackingResponse(response);
        filterChain.doFilter(request, tracked);
        int status = response.getStatus();
        if (status >= MIN_CLIENT_ERROR && status <= MAX_CLIENT_ERROR
                && !tracked.answered()
                && !response.isCommitted()
                && !request.isAsyncStarted()) {
            response.sendError(status);
        }
    }

    /**
     * The response the rest of the chain writes to. It records whether a body was started and whether
     * {@code sendError} was called, and passes every call on to the wrapped response unchanged. It is used
     * by the request's own thread only.
     */
    private static final class TrackingResponse extends HttpServletResponseWrapper {

        /** Whether {@link #getOutputStream()} or {@link #getWriter()} has been called. */
        private boolean bodyStarted;

        /** Whether {@link #sendError(int)} or {@link #sendError(int, String)} has been called. */
        private boolean errorSent;

        /**
         * Wraps a response.
         *
         * @param response the container's response
         * @throws IllegalArgumentException when {@code response} is {@code null}
         */
        TrackingResponse(HttpServletResponse response) {
            super(response);
        }

        /**
         * Records that a body was started and returns the wrapped response's stream.
         *
         * @return the wrapped response's output stream
         * @throws IOException when the wrapped response cannot provide it
         */
        @Override
        public ServletOutputStream getOutputStream() throws IOException {
            bodyStarted = true;
            return super.getOutputStream();
        }

        /**
         * Records that a body was started and returns the wrapped response's writer.
         *
         * @return the wrapped response's writer
         * @throws IOException when the wrapped response cannot provide it
         */
        @Override
        public PrintWriter getWriter() throws IOException {
            bodyStarted = true;
            return super.getWriter();
        }

        /**
         * Records the {@code sendError} and passes it on.
         *
         * @param sc the error status
         * @throws IOException when the wrapped response fails to send it
         */
        @Override
        public void sendError(int sc) throws IOException {
            errorSent = true;
            super.sendError(sc);
        }

        /**
         * Records the {@code sendError} and passes it on.
         *
         * @param sc the error status
         * @param msg the error message
         * @throws IOException when the wrapped response fails to send it
         */
        @Override
        public void sendError(int sc, String msg) throws IOException {
            errorSent = true;
            super.sendError(sc, msg);
        }

        /**
         * Whether the rest of the chain answered the request itself, by starting a body or by calling
         * {@code sendError}.
         *
         * @return {@code true} when this filter must leave the response alone
         */
        boolean answered() {
            return bodyStarted || errorSent;
        }
    }
}
