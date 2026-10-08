package com.democorp.customermaster.controller;

import io.swagger.v3.oas.annotations.Hidden;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.web.servlet.error.ErrorController;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * Answers the servlet container's ERROR dispatch to {@code /error} with {@code application/problem+json},
 * so no error ever reaches a client in Spring Boot's whitelabel page or its JSON error format
 * ({@code timestamp}, {@code error}, {@code path}).
 *
 * <p><b>Why it exists.</b> {@code ApiExceptionHandler} sees only exceptions raised inside Spring MVC's
 * handler invocation. Three kinds of failure never get there: an exception thrown by a servlet filter
 * (the security chain included), a {@code response.sendError(...)} call made by any component, and a
 * failure before a handler is mapped. The container forwards each of them to {@code /error} with the
 * {@code jakarta.servlet.error.*} request attributes set, and this controller turns them into the same
 * problem bodies the rest of the API sends, built by {@link ProblemFactory}. An exception escaping the
 * filter chain reaches the container through {@link ErrorDispatchFilter}, which sets the exception
 * attribute and calls {@code sendError(500)} instead of rethrowing, so the container logs nothing and
 * this controller writes the only ERROR line of the failure. A request Tomcat's connector rejects before
 * any filter runs (an oversized request line or header, a malformed request target, an encoded
 * {@code /}, {@code \} or NUL in the path, {@code TRACE}) is never forwarded here at all;
 * {@link ProblemErrorReportValve} answers it in the container with the body {@link #problemFor} builds,
 * so this status map stays the only one.
 *
 * <p><b>What it replaces.</b> On the IBM i, a "never should happen" failure went through
 * {@code SQLProblem} ({@code SRV_SQL}): {@code GET DIAGNOSTICS} read the SQLSTATE and message text,
 * {@code DUMP(A)} dumped the program, and a CPF9898 escape message carrying the SQLSTATE and SQL text
 * ended it. Here such a failure becomes a 500 {@code DEM9999} ("Program Error! Please contact IT now.",
 * from {@code CUSTMSGF}) with a random {@code errorId}; the exception and its SQLSTATE go only to the
 * ERROR log line that carries the same {@code errorId}, never into the body. That line logs the
 * exception as its {@link RedactedThrowable} copy, types and stack frames with every message withheld,
 * so no customer value, SQL text or control character of a message reaches the log.
 *
 * <p><b>Status map</b> (the same map {@code ApiExceptionHandler} and the security handlers apply):
 * <table>
 *   <caption>ERROR dispatch to problem</caption>
 *   <tr><th>Request attributes</th><th>Response</th></tr>
 *   <tr><td>no status and no exception (a direct request to {@code /error})</td>
 *       <td>404 {@code APP0400} "Request is not valid: not found"</td></tr>
 *   <tr><td>status 401</td><td>401 {@code APP0401}</td></tr>
 *   <tr><td>status 403</td><td>403 {@code APP0403}</td></tr>
 *   <tr><td>any other 4xx status</td>
 *       <td>that status, {@code APP0400} with the lower-case reason phrase, or {@code client error}
 *       when the status has no standard phrase</td></tr>
 *   <tr><td>anything else: a 5xx status, an exception with no status, a status outside 4xx or one
 *       that is not a number</td>
 *       <td>500 {@code DEM9999} with {@code errorId}, logged at ERROR</td></tr>
 * </table>
 * A direct anonymous request to {@code /error} never reaches this class: the security configuration
 * treats {@code /error} as "any other request: authenticated" and answers 401 {@code APP0401}. It
 * permits only the ERROR dispatch type, so the forward is never rejected itself and the original 401 or
 * 403 decision stands.
 *
 * <p><b>Response.</b> The body is written by {@link ProblemFactory#write(HttpServletResponse,
 * ProblemDetail)}, which sets the status, {@code application/problem+json} and UTF-8 whatever the
 * request's {@code Accept} header says, so a browser asking for {@code text/html} still receives the
 * problem body. The handler therefore returns no view and no {@code ResponseEntity}, and declares no
 * {@code produces} condition that could turn the error into a 406. Headers already on the response are
 * kept, so a {@code WWW-Authenticate} challenge set before a {@code sendError(401)} survives; this
 * class never adds that header itself, since only the security entry point decides when to challenge.
 * The {@code instance} member is the URI of the request that failed, not {@code /error}.
 *
 * <p><b>Spring Boot wiring.</b> Being an {@link ErrorController} bean, this class makes
 * {@code ErrorMvcAutoConfiguration} skip its {@code BasicErrorController}, while Boot's error-page
 * registration that forwards to {@code /error} stays in place. Boot's {@code ErrorAttributes} is
 * deliberately not used: the three servlet attributes hold everything the status map needs, and
 * {@code ErrorAttributes} would add exactly the members this class keeps out. The mapping follows
 * {@code server.error.path} (default {@code /error}) and accepts every HTTP method, because the
 * container forwards a failed request with its original method; Spring MVC also matches
 * {@code OPTIONS} for ERROR dispatches to a mapping without methods. The controller is hidden from the
 * OpenAPI document, and the web-application condition keeps it out of the generator's non-web context.
 *
 * <p><b>Thread safety.</b> The only state is the final, thread-safe {@link ProblemFactory}; every
 * request is handled with request-local values, so one instance serves all request threads.
 */
@Hidden
@Controller
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RequestMapping("${server.error.path:/error}")
public class ProblemErrorController implements ErrorController {

    /** Catalog key of a request the API cannot serve; its {@code {0}} is the reason. */
    static final String CODE_INVALID_REQUEST = "APP0400";

    /** Catalog key of a missing or rejected sign-in. */
    static final String CODE_UNAUTHORIZED = "APP0401";

    /** Catalog key of an authenticated user lacking the required role. */
    static final String CODE_FORBIDDEN = "APP0403";

    /** Catalog key of every unexpected failure. */
    static final String CODE_PROGRAM_ERROR = "DEM9999";

    /** The {@code APP0400} reason of a direct request to {@code /error}, which names no resource. */
    static final String REASON_NOT_FOUND = "not found";

    /** The {@code APP0400} reason of a 4xx status that has no standard reason phrase, such as 499. */
    static final String REASON_CLIENT_ERROR = "client error";

    /** Lowest 4xx status code. */
    private static final int MIN_CLIENT_ERROR = 400;

    /** Highest 4xx status code. */
    private static final int MAX_CLIENT_ERROR = 499;

    /** Placeholder for an absent value in the ERROR log line. */
    private static final String ABSENT = "-";

    private static final Logger log = LoggerFactory.getLogger(ProblemErrorController.class);

    /** The single builder and writer of problem bodies. */
    private final ProblemFactory problemFactory;

    /**
     * Creates the controller.
     *
     * @param problemFactory the factory that builds and writes every problem body
     * @throws NullPointerException when {@code problemFactory} is {@code null}
     */
    public ProblemErrorController(ProblemFactory problemFactory) {
        this.problemFactory = Objects.requireNonNull(problemFactory, "problemFactory");
    }

    /**
     * Answers an ERROR dispatch, or a direct request to {@code /error}, with a problem body.
     *
     * <p>The container's attributes are read first: {@link RequestDispatcher#ERROR_STATUS_CODE},
     * {@link RequestDispatcher#ERROR_EXCEPTION} and {@link RequestDispatcher#ERROR_REQUEST_URI}. A
     * response that is already committed can no longer take a status or a body, so it is left as it is
     * with a DEBUG line only: an exception from the filter chain was logged at ERROR, with an
     * {@code errorId}, by {@link ErrorDispatchFilter} before the container closed the connection, and
     * any other failure the container brings here was logged by the container.
     *
     * @param request the dispatched request, carrying the container's error attributes
     * @param response the response of the failed request
     * @throws IOException when writing the body to the client fails
     */
    @RequestMapping
    public void error(HttpServletRequest request, HttpServletResponse response) throws IOException {
        Object status = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
        Throwable failure = request.getAttribute(RequestDispatcher.ERROR_EXCEPTION) instanceof Throwable t
                ? t : null;
        String uri = originalUri(request);
        if (response.isCommitted()) {
            log.debug("Response already committed; error dispatch not answered status={} uri={}",
                    status == null ? ABSENT : status, uri, RedactedThrowable.of(failure));
            return;
        }
        problemFactory.write(response, problemFor(status, failure, uri));
    }

    /**
     * Applies the status map to the container's error attributes. {@link ProblemErrorReportValve} calls
     * it too, with the status of a request the connector rejected, so both answer alike.
     *
     * <p>The 500 branch draws a new {@code errorId} and writes the one ERROR line for it, carrying the
     * status, the URI ({@code -} when there is none), the SQLSTATE found in the cause chain of the
     * original failure ({@code -} when there is none) and the {@link RedactedThrowable} copy of the
     * failure: exception types and stack frames with every message withheld. None of these reaches the
     * body except the {@code errorId}.
     *
     * @param status the {@code jakarta.servlet.error.status_code} attribute as set, normally an
     *     {@link Integer}; {@code null} when absent
     * @param failure the {@code jakarta.servlet.error.exception} attribute; {@code null} when absent
     * @param uri the URI of the request that failed, used as {@code instance}; {@code null} when the
     *     connector could not read one, which leaves {@code instance} unset
     * @return the problem to send
     */
    ProblemDetail problemFor(@Nullable Object status, @Nullable Throwable failure, @Nullable String uri) {
        if (status == null && failure == null) {
            return problemFactory.create(HttpStatus.NOT_FOUND, CODE_INVALID_REQUEST,
                    List.of(REASON_NOT_FOUND), uri);
        }
        if (status instanceof Number number) {
            int code = number.intValue();
            if (code == HttpStatus.UNAUTHORIZED.value()) {
                return problemFactory.create(HttpStatus.UNAUTHORIZED, CODE_UNAUTHORIZED, List.of(), uri);
            }
            if (code == HttpStatus.FORBIDDEN.value()) {
                return problemFactory.create(HttpStatus.FORBIDDEN, CODE_FORBIDDEN, List.of(), uri);
            }
            // Range test first: HttpStatusCode.valueOf rejects codes outside 100..999, and a status the
            // container could not have sent must fall through to the 500 branch, not fail here.
            if (code >= MIN_CLIENT_ERROR && code <= MAX_CLIENT_ERROR) {
                return problemFactory.create(HttpStatusCode.valueOf(code), CODE_INVALID_REQUEST,
                        List.of(clientErrorReason(code)), uri);
            }
        }
        String errorId = ProblemFactory.newErrorId();
        log.error("Unhandled error errorId={} status={} uri={} sqlState={}", errorId,
                status == null ? ABSENT : status, uri == null ? ABSENT : uri,
                ProblemFactory.findSqlState(failure).orElse(ABSENT),
                RedactedThrowable.of(failure));
        ProblemDetail problem = problemFactory.create(HttpStatus.INTERNAL_SERVER_ERROR, CODE_PROGRAM_ERROR,
                List.of(), uri);
        return problemFactory.withErrorId(problem, errorId);
    }

    /**
     * Returns the {@code APP0400} reason of a 4xx status: its standard reason phrase in lower case, for
     * example {@code method not allowed} for 405, or {@value #REASON_CLIENT_ERROR} for a status with no
     * standard phrase.
     *
     * @param code a status code from 400 to 499
     * @return the reason, never blank
     */
    private static String clientErrorReason(int code) {
        HttpStatus resolved = HttpStatus.resolve(code);
        return resolved == null ? REASON_CLIENT_ERROR : resolved.getReasonPhrase().toLowerCase(Locale.ROOT);
    }

    /**
     * Returns the URI of the request that failed: the container's
     * {@link RequestDispatcher#ERROR_REQUEST_URI} when present, otherwise the current request's URI,
     * which is {@code /error} itself for a direct request. Both are the container's percent-encoded
     * form, without the query string.
     *
     * @param request the dispatched request
     * @return the URI used as {@code instance} and in the log line
     */
    private static String originalUri(HttpServletRequest request) {
        if (request.getAttribute(RequestDispatcher.ERROR_REQUEST_URI) instanceof String original
                && !original.isBlank()) {
            return original;
        }
        return request.getRequestURI();
    }
}
