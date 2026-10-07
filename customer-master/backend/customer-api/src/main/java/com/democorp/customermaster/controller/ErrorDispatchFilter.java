package com.democorp.customermaster.controller;

import jakarta.servlet.FilterChain;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.ServletException;
import jakarta.servlet.UnavailableException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.apache.coyote.BadRequestException;
import org.apache.coyote.CloseNowException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Hands an exception that escapes the servlet filter chain to the container's ERROR dispatch without
 * letting the container see it, so {@link ProblemErrorController} writes the one ERROR line for the
 * failure, with its {@code errorId}, and the container writes none.
 *
 * <p><b>Why.</b> When an exception leaves the filter chain, Tomcat's {@code StandardWrapperValve} logs it
 * at ERROR on logger
 * {@code org.apache.catalina.core.ContainerBase.[Tomcat].[localhost].[/].[dispatcherServlet]}, as
 * {@code Servlet.service() ... threw exception [<message>]} with the raw exception, and only then forwards
 * the request to {@code /error}, where {@link ProblemErrorController} logs the same failure again. The
 * container's line has no {@code errorId} and prints every message of the exception graph, which for a
 * persistence failure quotes the customer being written and may hold CR or LF characters that forge log
 * records.
 *
 * <p><b>What it does.</b> It runs first ({@link Ordered#HIGHEST_PRECEDENCE}), around the security filter
 * chain, every other filter and the {@code DispatcherServlet}, on REQUEST dispatches; an ERROR dispatch,
 * and with it {@code /error} itself, is not filtered. For an {@link IOException}, a
 * {@link ServletException} or a {@link RuntimeException} thrown below it:
 * <ul>
 *   <li><b>Response not committed:</b> it sets {@link RequestDispatcher#ERROR_EXCEPTION} to the failure
 *       and calls {@code sendError(500)}. Tomcat's host valve then reads that attribute and forwards to
 *       {@code /error} exactly as for a thrown exception, unwrapping a {@code ServletException} to its root
 *       cause, so {@link ProblemErrorController} answers 500 {@code DEM9999} and logs the failure at ERROR
 *       with the {@code errorId} of the body.</li>
 *   <li><b>Response committed:</b> neither a status nor a body can follow, and the ERROR dispatch would
 *       only log at DEBUG, so this filter writes the ERROR line itself, in
 *       {@link ProblemErrorController}'s format ({@code Unhandled error errorId={} status={} uri={}
 *       sqlState={}}, the status being the one already sent), plus a WARN line that the response is
 *       incomplete. It then throws a {@link CloseNowException} with a fixed message and no cause: Tomcat
 *       logs that type at DEBUG only and closes the connection, so the client sees a truncated response
 *       rather than one that looks complete.</li>
 *   <li><b>Left to the container unchanged:</b> Tomcat's {@link BadRequestException} (its
 *       {@code ClientAbortException} included) and {@link CloseNowException}, which Tomcat answers with
 *       400 or a closed connection and logs at DEBUG; an {@link UnavailableException}, which marks the
 *       servlet unavailable (503); a failure after asynchronous processing started; and every
 *       {@link Error}, which the container treats as fatal.</li>
 * </ul>
 * Both lines this filter writes carry the exception as its {@link RedactedThrowable} copy (types and
 * stack frames, no message) and the SQLSTATE read from the original failure.
 *
 * <p>The web-application condition keeps the filter out of the generator's non-web context. It holds no
 * state, so one instance serves all request threads.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
final class ErrorDispatchFilter extends OncePerRequestFilter {

    /** Message of the {@link CloseNowException} that has Tomcat close a committed response's connection. */
    static final String CLOSE_MESSAGE = "Unhandled error after the response was committed";

    /** Placeholder for an absent value in a log line. */
    private static final String ABSENT = "-";

    /** Logger of the committed-response lines; the ERROR dispatch logs through its own controller. */
    private static final Logger log = LoggerFactory.getLogger(ErrorDispatchFilter.class);

    /** Creates the filter, which needs no collaborator and no configuration. */
    ErrorDispatchFilter() {
        super();
    }

    /**
     * Runs the rest of the chain and hands any exception it throws to the ERROR dispatch, as described on
     * this class.
     *
     * @param request the request
     * @param response the response
     * @param filterChain the rest of the chain
     * @throws ServletException a failure left to the container
     * @throws IOException a failure left to the container, the {@link CloseNowException} of a committed
     *     response, or a failure of {@code sendError}
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        try {
            filterChain.doFilter(request, response);
        } catch (IOException | ServletException | RuntimeException failure) {
            if (leftToContainer(failure, request)) {
                throw failure;
            }
            if (response.isCommitted()) {
                logCommitted(failure, request, response);
                throw new CloseNowException(CLOSE_MESSAGE);
            }
            request.setAttribute(RequestDispatcher.ERROR_EXCEPTION, failure);
            response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
        }
    }

    /**
     * Whether a failure keeps the container's own handling: Tomcat's bad-request, client-abort and
     * close-now exceptions, a servlet's unavailability, and any failure once asynchronous processing has
     * started.
     *
     * @param failure the failure thrown by the chain
     * @param request the request
     * @return {@code true} when the failure is rethrown unchanged
     */
    private static boolean leftToContainer(Exception failure, HttpServletRequest request) {
        return failure instanceof BadRequestException
                || failure instanceof CloseNowException
                || failure instanceof UnavailableException
                || request.isAsyncStarted();
    }

    /**
     * Writes the ERROR line of a failure whose response is already committed, with a new
     * {@code errorId}, then the WARN line that the response stays incomplete.
     *
     * @param failure the failure thrown by the chain
     * @param request the request
     * @param response the committed response, whose status has been sent
     */
    private static void logCommitted(Exception failure, HttpServletRequest request,
            HttpServletResponse response) {
        String errorId = ProblemFactory.newErrorId();
        String uri = request.getRequestURI();
        log.error("Unhandled error errorId={} status={} uri={} sqlState={}", errorId, response.getStatus(),
                uri, ProblemFactory.findSqlState(failure).orElse(ABSENT), RedactedThrowable.of(failure));
        log.warn("Response already committed; errorId={} not answered, connection closed uri={}", errorId,
                uri);
    }
}
