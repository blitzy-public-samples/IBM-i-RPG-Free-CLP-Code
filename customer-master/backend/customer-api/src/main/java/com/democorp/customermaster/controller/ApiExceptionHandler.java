package com.democorp.customermaster.controller;

import com.democorp.customermaster.address.AddressServiceUnavailableException;
import com.democorp.customermaster.controller.ProblemFactory.FieldProblem;
import com.democorp.customermaster.controller.dto.CustomerResponse;
import com.democorp.customermaster.service.exception.CustomerIdExhaustedException;
import com.democorp.customermaster.service.exception.CustomerLockedException;
import com.democorp.customermaster.service.exception.CustomerNotFoundException;
import com.democorp.customermaster.service.exception.CustomerValidationException;
import com.democorp.customermaster.service.exception.InvalidSearchCriteriaException;
import com.democorp.customermaster.service.exception.ReviewFailedException;
import com.democorp.customermaster.service.exception.StaleCustomerException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import io.swagger.v3.oas.annotations.Hidden;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.TypeMismatchException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.context.support.DefaultMessageSourceResolvable;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.lang.Nullable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.validation.ObjectError;
import org.springframework.validation.method.ParameterErrors;
import org.springframework.validation.method.ParameterValidationResult;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.filter.ServerHttpObservationFilter;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.web.util.DisconnectedClientHelper;

/**
 * The status map for every exception that reaches Spring MVC: each one becomes an RFC 9457
 * {@code application/problem+json} response built by {@link ProblemFactory}.
 *
 * <p><b>What it replaces.</b> On the IBM i, a program reported a condition by sending a {@code CUSTMSGF}
 * message id and its substitution data to the message subfile through {@code SndMsgPgmQ}
 * ({@code Service_Pgms/SRV_MSG.RPGLE:69-177}); MTNCUSTR's {@code UpdateRecd} turned "no row updated" into
 * DEM1002 and "row locked" (SQLSTATE 57033) into DEM1001 ({@code 5250_Subfile/MTNCUSTR.SQLRPGLE:593-606});
 * and every other SQL failure went to {@code SQLProblem} ({@code Service_Pgms/SRV_SQL.SQLRPGLE:20-57}), which
 * read {@code GET DIAGNOSTICS}, dumped the program and ended it with a CPF9898 escape message carrying the
 * SQLSTATE and the SQL message text. Here the services throw typed exceptions, and this class maps them,
 * exactly once, to the status and catalog code below.
 *
 * <p><b>Status map.</b>
 * <table>
 *   <caption>Exception to problem</caption>
 *   <tr><th>Exception</th><th>Status and code</th><th>Extra members</th></tr>
 *   <tr><td>Spring MVC framework 4xx (unreadable or invalid body, unknown property, type mismatch, constraint
 *       violation on a parameter, missing parameter, no route, 405, 406, 413, 415, ...)</td>
 *       <td>the framework's own status, {@code APP0400} "Request is not valid: {0}" with a short fixed
 *       reason; 401 and 403 become {@code APP0401} and {@code APP0403}</td>
 *       <td>framework headers such as {@code Allow} and {@code Accept} are kept; {@code errors}, one
 *       {@code APP0400} item per property or parameter at fault, when the reason names one</td></tr>
 *   <tr><td>{@link InvalidSearchCriteriaException}</td><td>400 {@code DEM0007} or {@code APP0400}</td>
 *       <td>{@code errors[0]} on the named field</td></tr>
 *   <tr><td>{@link CustomerNotFoundException}</td><td>404 {@code DEM0599}</td><td>none</td></tr>
 *   <tr><td>{@link StaleCustomerException}</td><td>409 {@code DEM1002}</td>
 *       <td>{@code current}: the stored record in the {@link CustomerResponse} shape</td></tr>
 *   <tr><td>{@link CustomerLockedException}</td><td>409 {@code DEM1001}</td><td>none, never SQLERRMC</td></tr>
 *   <tr><td>{@link CustomerValidationException}</td><td>422 {@code DEM0501}, {@code DEM0502},
 *       {@code DEM0503} or {@code DEM9898}</td><td>{@code errors}, in rule order</td></tr>
 *   <tr><td>{@link AddressServiceUnavailableException}</td><td>502 {@code APP0502}</td><td>none</td></tr>
 *   <tr><td>{@link CustomerIdExhaustedException}</td><td>503 {@code APP0503}</td><td>none</td></tr>
 *   <tr><td>{@link ReviewFailedException}</td><td>the problem of its cause (422 or 502)</td>
 *       <td>{@code stateAccepted}</td></tr>
 *   <tr><td>anything else, a framework 5xx included</td><td>500 {@code DEM9999}</td>
 *       <td>{@code errorId}, also written to the ERROR log line</td></tr>
 * </table>
 *
 * <p><b>What never travels.</b> No body carries SQL text, an SQLSTATE, a stack trace, an exception message
 * or a class name. The SQLSTATE of a 500 goes only to its ERROR log line, found through
 * {@link ProblemFactory#findSqlState(Throwable)}, beside the same {@code errorId} the body carries. That
 * line logs the exception as its {@link RedactedThrowable} copy, types and stack frames without any
 * message, so neither the customer values a persistence failure quotes nor control characters reach the
 * log. The reasons of {@code APP0400} are fixed English phrases that name at most a property or parameter,
 * never a value the client sent.
 *
 * <p><b>Scope.</b> Failures that never reach a controller (servlet filter exceptions, {@code sendError},
 * failures before handler mapping) are forwarded by the container to {@code /error} and answered by
 * {@link ProblemErrorController} with the same map; 401 and 403 decided by the security filter chain are
 * written by the security configuration's entry point and access-denied handler. The advice is
 * {@link Hidden} so springdoc derives no generic responses from it: each controller operation declares the
 * error responses of its own outcomes, and {@link ProblemResponsesCustomizer} adds the 405, 406 and 500
 * responses every operation shares and the 415 of every operation with a request body, all described by
 * the {@code Problem} schema. The web-application condition keeps it out of the generator's non-web
 * context.
 *
 * <p><b>Thread safety.</b> The only instance state is the final, thread-safe {@link ProblemFactory}; every
 * method works on request-local values, so one instance serves all request threads.
 */
@Hidden
@RestControllerAdvice
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    /** Catalog key of a request the API cannot serve, "Request is not valid: {0}". */
    static final String CODE_INVALID_REQUEST = "APP0400";

    /** Catalog key of a missing or rejected sign-in. */
    static final String CODE_UNAUTHORIZED = "APP0401";

    /** Catalog key of an authenticated user lacking the required role. */
    static final String CODE_FORBIDDEN = "APP0403";

    /** Catalog key of a customer that does not exist, "Customer deleted. Exit &amp; redo search.". */
    static final String CODE_NOT_FOUND = "DEM0599";

    /** Catalog key of a row locked past the lock timeout (the source's SQLSTATE 57033 branch). */
    static final String CODE_LOCKED = "DEM1001";

    /** Catalog key of a stale update, "Someone else changed record. Review data.". */
    static final String CODE_STALE = "DEM1002";

    /** Catalog key of an unusable answer from the address service. */
    static final String CODE_ADDRESS_UNAVAILABLE = "APP0502";

    /** Catalog key of an exhausted customer id space. */
    static final String CODE_IDS_EXHAUSTED = "APP0503";

    /** Catalog key of every unexpected failure, "Program Error! Please contact IT now.". */
    static final String CODE_PROGRAM_ERROR = "DEM9999";

    /** Longest property or parameter name quoted in an {@code APP0400} reason, in code points. */
    static final int MAX_NAME_LENGTH = 64;

    /** Reason of an unreadable body that names no property. */
    static final String REASON_MALFORMED_BODY = "malformed request body";

    /** Reason of a request that matched no route and no static resource. */
    static final String REASON_NO_SUCH_RESOURCE = "no such resource";

    /** Reason of a 405. */
    static final String REASON_METHOD_NOT_ALLOWED = "method not allowed";

    /** Reason of a 406. */
    static final String REASON_NOT_ACCEPTABLE = "not acceptable";

    /** Reason of a 415. */
    static final String REASON_UNSUPPORTED_MEDIA_TYPE = "unsupported media type";

    /** Reason of a 4xx status that has no standard reason phrase. */
    static final String REASON_CLIENT_ERROR = "client error";

    /** Subject of a constraint violation on the request body as a whole rather than one property. */
    static final String SUBJECT_REQUEST_BODY = "request body";

    /** Subject used when a parameter or property has no usable name. */
    static final String SUBJECT_UNNAMED = "parameter";

    /** Placeholder for an absent value in a log line. */
    private static final String ABSENT = "-";

    /** Upper bound on the cause chain walked when looking for the Jackson failure of a body. */
    private static final int MAX_CAUSE_DEPTH = 20;

    /** Orders field errors deterministically: by property path, then by constraint code. */
    private static final Comparator<FieldError> FIELD_ERROR_ORDER =
            Comparator.comparing(FieldError::getField).thenComparing(ApiExceptionHandler::constraintCode);

    /** Orders object-level and value errors deterministically, by constraint code. */
    private static final Comparator<MessageSourceResolvable> RESOLVABLE_ORDER =
            Comparator.comparing(ApiExceptionHandler::constraintCode);

    /**
     * SLF4J logger. Named {@code log} because {@link ResponseEntityExceptionHandler} already declares a
     * protected commons-logging field named {@code logger}.
     */
    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    /** The single builder of problem bodies. */
    private final ProblemFactory problems;

    /**
     * Creates the handler.
     *
     * @param problems the factory that builds every problem body
     * @throws NullPointerException when {@code problems} is {@code null}
     */
    public ApiExceptionHandler(ProblemFactory problems) {
        this.problems = Objects.requireNonNull(problems, "problems");
    }

    // ---------------------------------------------------------------------------------------------
    // Framework exceptions
    // ---------------------------------------------------------------------------------------------

    /**
     * Turns every exception {@link ResponseEntityExceptionHandler} recognises into a problem body.
     *
     * <p>The base class has already chosen the status and the headers; the {@code body} it proposes, a
     * Spring default {@link ProblemDetail} whose {@code detail} may quote the exception, is ignored. A 4xx
     * keeps its status: 401 becomes {@code APP0401}, 403 becomes {@code APP0403}, and every other 4xx
     * becomes {@code APP0400} with the fixed reason of {@link #reason(Exception, HttpStatusCode)}. A 5xx,
     * such as a {@code MissingPathVariableException} or an {@code AsyncRequestTimeoutException}, takes the
     * catch-all path: 500 {@code DEM9999} with an {@code errorId} and one ERROR log line. The framework
     * headers are passed on, so {@code Allow} on a 405 and {@code Accept} on a 415 survive.
     *
     * @param ex the framework exception
     * @param body the body the base class proposes; ignored
     * @param headers the headers the base class chose
     * @param statusCode the status the base class chose
     * @param request the current request
     * @return the problem response, or {@code null} when the response is already committed and can take
     *     neither a status nor a body, as the base class does
     */
    @Override
    @Nullable
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex, @Nullable Object body,
            HttpHeaders headers, HttpStatusCode statusCode, WebRequest request) {
        HttpServletRequest servletRequest = null;
        HttpServletResponse servletResponse = null;
        if (request instanceof ServletWebRequest servletWebRequest) {
            servletRequest = servletWebRequest.getRequest();
            servletResponse = servletWebRequest.getResponse();
        }
        String instance = servletRequest == null ? null : servletRequest.getRequestURI();
        if (servletResponse != null && servletResponse.isCommitted()) {
            log.warn("Response already committed; {} not answered status={} uri={}",
                    ex.getClass().getSimpleName(), statusCode.value(), orAbsent(instance));
            return null;
        }
        ProblemDetail problem;
        if (statusCode.is4xxClientError()) {
            problem = clientProblem(ex, statusCode, instance);
            log.debug("Client error status={} code={} uri={} exception={}", statusCode.value(),
                    codeOf(problem), orAbsent(instance), ex.getClass().getSimpleName());
        } else {
            problem = programError(ex, servletRequest, instance);
        }
        return problems.response(problem, headers);
    }

    /**
     * Builds the problem of a framework 4xx, with the same map {@link ProblemErrorController} applies to
     * an ERROR dispatch: 401 is {@code APP0401}, 403 is {@code APP0403}, and any other 4xx keeps its own
     * status with {@code APP0400}.
     *
     * <p>When the reason of an {@code APP0400} names a JSON property or a query or path parameter, the
     * problem also carries {@code errors}: one {@code APP0400} item per property or parameter at fault,
     * each with its own reason as message, the one the {@code detail} names first, so the client
     * highlights them and focuses that one. A failure that names no field (malformed JSON, an unknown
     * property, an object-level constraint, a route or media-type failure) carries no {@code errors}.
     *
     * @param ex the framework exception
     * @param status a 4xx status
     * @param instance the request path; may be {@code null}
     * @return the problem
     */
    private ProblemDetail clientProblem(Exception ex, HttpStatusCode status, @Nullable String instance) {
        // 401 and 403 keep the codes the security handlers and ProblemErrorController send, so a status
        // never carries two different codes depending on where it was raised.
        if (status.value() == HttpStatus.UNAUTHORIZED.value()) {
            return problems.create(status, CODE_UNAUTHORIZED, List.of(), instance);
        }
        if (status.value() == HttpStatus.FORBIDDEN.value()) {
            return problems.create(status, CODE_FORBIDDEN, List.of(), instance);
        }
        List<Violation> violations = violations(ex, status);
        Violation named = violations.get(0);
        ProblemDetail problem = problems.create(status, CODE_INVALID_REQUEST, List.of(named.reason()),
                instance);
        if (named.field() == null) {
            return problem;
        }
        // One item per field, the first violation of each in reporting order, so the field the detail
        // names is errors[0].
        Map<String, Violation> byField = new LinkedHashMap<>();
        for (Violation violation : violations) {
            if (violation.field() != null) {
                byField.putIfAbsent(violation.field(), violation);
            }
        }
        List<FieldProblem> errors = byField.values().stream()
                .map(violation -> problems.fieldProblem(violation.field(), CODE_INVALID_REQUEST,
                        List.of(violation.reason())))
                .toList();
        return problems.withErrors(problem, errors);
    }

    // ---------------------------------------------------------------------------------------------
    // Domain exceptions
    // ---------------------------------------------------------------------------------------------

    /**
     * Answers a rejected search or state-list criterion: 400 with the exception's code ({@code DEM0007}
     * for the State filter, {@code APP0400} for size, cursor, length and sort guards), its args, and
     * {@code errors[0]} on the named field, so the client highlights and focuses it as PMTCUSTR positioned
     * the cursor on the State filter.
     *
     * @param ex the exception
     * @param request the current request
     * @return the problem response
     */
    @ExceptionHandler(InvalidSearchCriteriaException.class)
    public ResponseEntity<Object> handleInvalidSearchCriteria(InvalidSearchCriteriaException ex,
            HttpServletRequest request) {
        return problems.response(toProblem(ex, request));
    }

    /**
     * Answers a customer that does not exist: 404 {@code DEM0599}, the message MTNCUSTR sent when the
     * re-read of a row found nothing.
     *
     * @param ex the exception
     * @param request the current request
     * @return the problem response
     */
    @ExceptionHandler(CustomerNotFoundException.class)
    public ResponseEntity<Object> handleCustomerNotFound(CustomerNotFoundException ex,
            HttpServletRequest request) {
        return problems.response(toProblem(ex, request));
    }

    /**
     * Answers a stale update: 409 {@code DEM1002} with {@code current}, the row as now stored, so the
     * client can compare and refresh, as {@code UpdateRecd} sent DEM1002 and re-read the row on
     * {@code SQLNODATA}.
     *
     * @param ex the exception
     * @param request the current request
     * @return the problem response
     */
    @ExceptionHandler(StaleCustomerException.class)
    public ResponseEntity<Object> handleStaleCustomer(StaleCustomerException ex, HttpServletRequest request) {
        return problems.response(toProblem(ex, request));
    }

    /**
     * Answers a row lock that outlasted the lock timeout: 409 {@code DEM1001}. The source passed SQLERRMC
     * as message data, which DEM1001's text never displayed; no data travels here at all.
     *
     * @param ex the exception
     * @param request the current request
     * @return the problem response
     */
    @ExceptionHandler(CustomerLockedException.class)
    public ResponseEntity<Object> handleCustomerLocked(CustomerLockedException ex, HttpServletRequest request) {
        return problems.response(toProblem(ex, request));
    }

    /**
     * Answers a failed field rule or address standardization: 422 with the exception's code and args, and
     * one {@code errors[]} item per field in the given order, the first receiving focus.
     *
     * @param ex the exception
     * @param request the current request
     * @return the problem response
     */
    @ExceptionHandler(CustomerValidationException.class)
    public ResponseEntity<Object> handleCustomerValidation(CustomerValidationException ex,
            HttpServletRequest request) {
        return problems.response(toProblem(ex, request));
    }

    /**
     * Answers an address-service fault: 502 {@code APP0502}, never {@code DEM9898} and never a 500. The
     * source ended the program through SQLProblem on such a fault.
     *
     * @param ex the exception
     * @param request the current request
     * @return the problem response
     */
    @ExceptionHandler(AddressServiceUnavailableException.class)
    public ResponseEntity<Object> handleAddressServiceUnavailable(AddressServiceUnavailableException ex,
            HttpServletRequest request) {
        return problems.response(toProblem(ex, request));
    }

    /**
     * Answers an exhausted customer id space: 503 {@code APP0503}. The source's BASE36ADD rolled over to
     * {@code AAAA} and reissued ids instead.
     *
     * @param ex the exception
     * @param request the current request
     * @return the problem response
     */
    @ExceptionHandler(CustomerIdExhaustedException.class)
    public ResponseEntity<Object> handleCustomerIdExhausted(CustomerIdExhaustedException ex,
            HttpServletRequest request) {
        return problems.response(toProblem(ex, request));
    }

    /**
     * Answers a review that failed after the State rule passed: the problem of the wrapped failure (422 or
     * 502) plus {@code stateAccepted}, which the client keeps as its working State.
     *
     * @param ex the exception
     * @param request the current request
     * @return the problem response
     */
    @ExceptionHandler(ReviewFailedException.class)
    public ResponseEntity<Object> handleReviewFailed(ReviewFailedException ex, HttpServletRequest request) {
        return problems.response(toProblem(ex, request));
    }

    // ---------------------------------------------------------------------------------------------
    // Catch-all
    // ---------------------------------------------------------------------------------------------

    /**
     * Answers every other exception, a {@code DuplicateKeyException} or any {@code DataAccessException}
     * included: 500 {@code DEM9999} "Program Error! Please contact IT now." with a new {@code errorId}.
     * The ERROR log line carries the same id, the SQLSTATE when there is one, the URI and the exception
     * types and stack frames, with every exception message withheld ({@link RedactedThrowable}); the body
     * carries none of them except the id. This is the counterpart of SQLProblem's diagnostics and dump,
     * kept on the server.
     *
     * <p>Framework exceptions never get here, because the more specific handlers inherited from
     * {@link ResponseEntityExceptionHandler} win for their types. Three cases are not answered:
     * <ul>
     *   <li>A Spring Security {@link AccessDeniedException} or {@link AuthenticationException} raised
     *       inside a handler is rethrown, so the security filter chain's {@code ExceptionTranslationFilter}
     *       answers it with 401 {@code APP0401} or 403 {@code APP0403} through the configured entry point
     *       and access-denied handler, as it answers every other authorization failure, instead of a
     *       500.</li>
     *   <li>A client that has disconnected cannot read a body; the failure is logged at DEBUG only.</li>
     *   <li>A response that is already committed can take neither a status nor a body; the failure is
     *       still logged at ERROR with its {@code errorId}.</li>
     * </ul>
     *
     * @param ex the exception
     * @param request the current request
     * @param response the current response
     * @return the problem response, or {@code null} when nothing can be written
     */
    @ExceptionHandler(Exception.class)
    @Nullable
    public ResponseEntity<Object> handleUnexpected(Exception ex, HttpServletRequest request,
            HttpServletResponse response) {
        // Rethrowing the exception being handled makes Spring MVC continue with it unresolved, so it
        // propagates to the security filter chain, which owns the 401 and 403 answers.
        if (ex instanceof AccessDeniedException accessDenied) {
            throw accessDenied;
        }
        if (ex instanceof AuthenticationException authentication) {
            throw authentication;
        }
        if (DisconnectedClientHelper.isClientDisconnectedException(ex)) {
            log.debug("Client disconnected uri={} exception={}", request.getRequestURI(),
                    ex.getClass().getSimpleName());
            return null;
        }
        ProblemDetail problem = programError(ex, request, request.getRequestURI());
        if (response.isCommitted()) {
            log.warn("Response already committed; errorId={} not answered uri={}",
                    problem.getProperties() == null
                            ? ABSENT : problem.getProperties().get(ProblemFactory.PROPERTY_ERROR_ID),
                    request.getRequestURI());
            return null;
        }
        return problems.response(problem);
    }

    // ---------------------------------------------------------------------------------------------
    // The status map
    // ---------------------------------------------------------------------------------------------

    /**
     * Applies the domain status map to one failure. Every domain handler and the review wrapper use this
     * method, so a failure wrapped in {@link ReviewFailedException} gets exactly the problem it would get
     * when thrown alone.
     *
     * @param failure the failure to map
     * @param request the current request; its URI becomes {@code instance}
     * @return the problem
     */
    private ProblemDetail toProblem(Throwable failure, HttpServletRequest request) {
        String instance = request.getRequestURI();
        return switch (failure) {
            case InvalidSearchCriteriaException invalid -> invalidCriteriaProblem(invalid, instance);
            case CustomerNotFoundException notFound ->
                    problems.create(HttpStatus.NOT_FOUND, CODE_NOT_FOUND, List.of(), instance);
            case StaleCustomerException stale -> staleProblem(stale, request);
            case CustomerLockedException locked ->
                    problems.create(HttpStatus.CONFLICT, CODE_LOCKED, List.of(), instance);
            case CustomerValidationException invalid -> validationProblem(invalid, instance);
            case AddressServiceUnavailableException unavailable -> addressProblem(unavailable, instance);
            case CustomerIdExhaustedException exhausted ->
                    problems.create(HttpStatus.SERVICE_UNAVAILABLE, CODE_IDS_EXHAUSTED, List.of(), instance);
            case ReviewFailedException reviewFailed -> reviewFailedProblem(reviewFailed, request);
            default -> programError(failure, request, instance);
        };
    }

    /**
     * Builds the 400 of a rejected search criterion, with {@code errors[0]} on its field.
     *
     * @param ex the exception
     * @param instance the request path
     * @return the problem
     */
    private ProblemDetail invalidCriteriaProblem(InvalidSearchCriteriaException ex, String instance) {
        ProblemDetail problem = problems.create(HttpStatus.BAD_REQUEST, ex.code(), ex.args(), instance);
        String field = ex.field();
        if (field != null && !field.isBlank()) {
            problems.withErrors(problem, List.of(problems.fieldProblem(field, ex.code(), ex.args())));
        }
        return problem;
    }

    /**
     * Builds the 409 {@code DEM1002} of a stale update with the stored record as {@code current}. An
     * exception without its record, possible only for an instance restored by Java serialization, cannot
     * honour the contract and is answered as a program error.
     *
     * @param ex the exception
     * @param request the current request
     * @return the problem
     */
    private ProblemDetail staleProblem(StaleCustomerException ex, HttpServletRequest request) {
        // Inferred rather than named: this advice depends on DTO and service-exception types only.
        var current = ex.current();
        if (current == null) {
            return programError(ex, request, request.getRequestURI());
        }
        ProblemDetail problem = problems.create(HttpStatus.CONFLICT, CODE_STALE, List.of(),
                request.getRequestURI());
        return problems.withCurrent(problem, CustomerResponse.from(current));
    }

    /**
     * Builds the 422 of a failed field rule or standardization. The top-level {@code code} and
     * {@code args} are the exception's; each {@code errors[]} message is its item's code resolved with the
     * same args, so DEM9898's four items share the one message {@code "USPS: <description>"}.
     *
     * @param ex the exception
     * @param instance the request path
     * @return the problem
     */
    private ProblemDetail validationProblem(CustomerValidationException ex, String instance) {
        ProblemDetail problem = problems.create(HttpStatus.UNPROCESSABLE_ENTITY, ex.code(), ex.args(),
                instance);
        List<FieldProblem> errors = ex.errors().stream()
                .map(error -> problems.fieldProblem(error.field(), error.code(), ex.args()))
                .toList();
        return problems.withErrors(problem, errors);
    }

    /**
     * Builds the 502 {@code APP0502} of an address-service fault. The WARN line carries the exception's
     * message only, which names the kind of fault and never a URL, a credential or an address value; no
     * stack trace is written, so no chained message can undo that.
     *
     * @param ex the exception
     * @param instance the request path
     * @return the problem
     */
    private ProblemDetail addressProblem(AddressServiceUnavailableException ex, String instance) {
        log.warn("Address service unavailable fault={} uri={}", orAbsent(ex.getMessage()), instance);
        return problems.create(HttpStatus.BAD_GATEWAY, CODE_ADDRESS_UNAVAILABLE, List.of(), instance);
    }

    /**
     * Builds the problem of a review that failed after the State rule passed: the problem of its cause,
     * then {@code stateAccepted}. Only the two documented causes, a {@link CustomerValidationException}
     * (422) and an {@link AddressServiceUnavailableException} (502), carry {@code stateAccepted}; any other
     * cause is unexpected and becomes a 500 {@code DEM9999}, which never carries it.
     *
     * @param ex the exception
     * @param request the current request
     * @return the problem
     */
    private ProblemDetail reviewFailedProblem(ReviewFailedException ex, HttpServletRequest request) {
        RuntimeException cause = ex.cause();
        if (cause instanceof CustomerValidationException || cause instanceof AddressServiceUnavailableException) {
            return problems.withStateAccepted(toProblem(cause, request), ex.stateAccepted());
        }
        return programError(ex, request, request.getRequestURI());
    }

    /**
     * Builds the 500 {@code DEM9999} of an unexpected failure and writes its one ERROR log line, which
     * carries the new {@code errorId}, the SQLSTATE found in the cause chain of the original failure
     * ({@code -} when there is none), the URI and the {@link RedactedThrowable} copy of the failure: the
     * exception types and stack frames of the whole graph, with every message withheld, because a
     * persistence failure's message quotes the customer being written and the driver's {@code DETAIL}.
     * The original failure is recorded on the request's HTTP server observation, when there is one, so
     * the request metrics count it although the response was produced normally.
     *
     * @param failure the failure
     * @param request the current request; may be {@code null} outside a servlet request
     * @param instance the request path; may be {@code null}
     * @return the problem, with {@code errorId}
     */
    private ProblemDetail programError(Throwable failure, @Nullable HttpServletRequest request,
            @Nullable String instance) {
        String errorId = ProblemFactory.newErrorId();
        log.error("Unhandled error errorId={} sqlState={} uri={}", errorId,
                ProblemFactory.findSqlState(failure).orElse(ABSENT), orAbsent(instance),
                RedactedThrowable.of(failure));
        if (request != null) {
            ServerHttpObservationFilter.findObservationContext(request)
                    .ifPresent(context -> context.setError(failure));
        }
        ProblemDetail problem = problems.create(HttpStatus.INTERNAL_SERVER_ERROR, CODE_PROGRAM_ERROR,
                List.of(), instance);
        return problems.withErrorId(problem, errorId);
    }

    // ---------------------------------------------------------------------------------------------
    // APP0400 reasons
    // ---------------------------------------------------------------------------------------------

    /**
     * Returns the {@code {0}} of {@code APP0400} "Request is not valid: {0}" for a framework 4xx: a short,
     * deterministic English phrase. It never contains a class name, an exception message, SQL or a value
     * the client sent; at most it names a property or parameter, cut to {@value #MAX_NAME_LENGTH} code
     * points with control characters replaced. The rows marked "field" name a property or parameter that
     * {@link #clientProblem} also reports as {@code errors[].field}; the others carry no {@code errors}.
     *
     * <table>
     *   <caption>Exception to reason</caption>
     *   <tr><th>Exception</th><th>Reason</th></tr>
     *   <tr><td>unreadable body, unknown JSON property</td><td>{@code unknown property chgUser}</td></tr>
     *   <tr><td>unreadable body, value of the wrong type or format (field)</td>
     *       <td>{@code version has an invalid value}</td></tr>
     *   <tr><td>unreadable body, anything else</td><td>{@code malformed request body}</td></tr>
     *   <tr><td>bean or method validation (field, unless object-level)</td><td>{@code version is required},
     *       {@code name is too long}, {@code custId has an invalid format},
     *       {@code addr contains a character that cannot be stored}, {@code <name> is invalid}</td></tr>
     *   <tr><td>type mismatch of a parameter (field)</td><td>{@code size has an invalid value}</td></tr>
     *   <tr><td>missing request parameter (field)</td><td>{@code <name> is required}</td></tr>
     *   <tr><td>no route or static resource</td><td>{@code no such resource}</td></tr>
     *   <tr><td>405, 406, 415</td><td>{@code method not allowed}, {@code not acceptable},
     *       {@code unsupported media type}</td></tr>
     *   <tr><td>any other 4xx</td><td>the reason phrase in lower case, e.g. {@code payload too large}</td></tr>
     * </table>
     *
     * @param ex the framework exception
     * @param status the 4xx status the framework chose
     * @return the reason, never blank
     */
    static String reason(Exception ex, HttpStatusCode status) {
        return violations(ex, status).get(0).reason();
    }

    /**
     * One cause of an {@code APP0400}: its reason, the {@code {0}} of "Request is not valid: {0}", and the
     * JSON property or query or path parameter the reason names, which becomes {@code errors[].field}.
     *
     * @param reason the reason, never blank
     * @param field the sanitized property or parameter name; {@code null} when the reason names none, as
     *     for the body as a whole, an unknown property, an unnamed parameter or a route failure
     */
    private record Violation(String reason, @Nullable String field) {

        /**
         * Builds the violation of a named property or parameter.
         *
         * @param field the sanitized name
         * @param phrase the phrase that follows the name, such as {@code is too long}
         * @return the violation, whose reason is the name, a space and the phrase
         */
        static Violation onField(String field, String phrase) {
            return new Violation(field + " " + phrase, field);
        }

        /**
         * Builds a violation that names no field.
         *
         * @param reason the reason
         * @return the violation
         */
        static Violation general(String reason) {
            return new Violation(reason, null);
        }
    }

    /**
     * Returns the causes of a framework 4xx in reporting order; the first is the one the {@code detail}
     * names. Several causes arise only from bean or method validation, one for each failing field or
     * parameter, the fields in a fixed order: property path, then constraint code.
     *
     * @param ex the framework exception
     * @param status the 4xx status the framework chose
     * @return the causes, never empty
     */
    private static List<Violation> violations(Exception ex, HttpStatusCode status) {
        return switch (ex) {
            case HttpMessageNotReadableException unreadable -> List.of(unreadableViolation(unreadable));
            case MethodArgumentNotValidException invalid -> bindingViolations(
                    invalid.getBindingResult().getFieldErrors(), invalid.getBindingResult().getGlobalErrors(),
                    invalid.getParameter(), status);
            case HandlerMethodValidationException invalid -> methodValidationViolations(invalid, status);
            // Before TypeMismatchException, its superclass: the parameter name is the one a client sent.
            case MethodArgumentTypeMismatchException mismatch ->
                    List.of(isRequestParameter(mismatch.getParameter())
                            ? named(mismatch.getName(), "has an invalid value")
                            : Violation.general(safeName(mismatch.getName()) + " has an invalid value"));
            case TypeMismatchException mismatch ->
                    List.of(named(mismatch.getPropertyName(), "has an invalid value"));
            case MissingServletRequestParameterException missing ->
                    List.of(named(missing.getParameterName(), "is required"));
            case NoResourceFoundException noResource -> List.of(Violation.general(REASON_NO_SUCH_RESOURCE));
            case NoHandlerFoundException noHandler -> List.of(Violation.general(REASON_NO_SUCH_RESOURCE));
            case HttpRequestMethodNotSupportedException notAllowed ->
                    List.of(Violation.general(REASON_METHOD_NOT_ALLOWED));
            case HttpMediaTypeNotAcceptableException notAcceptable ->
                    List.of(Violation.general(REASON_NOT_ACCEPTABLE));
            case HttpMediaTypeNotSupportedException unsupported ->
                    List.of(Violation.general(REASON_UNSUPPORTED_MEDIA_TYPE));
            default -> List.of(Violation.general(statusReason(status)));
        };
    }

    /**
     * Builds the violation of a property or parameter known by name. A missing or blank name yields a
     * violation phrased by {@value #SUBJECT_UNNAMED} that names no field, so no {@code errors[]} item is
     * ever invented.
     *
     * @param name the property or parameter name; may be {@code null}
     * @param phrase the phrase that follows the name
     * @return the violation
     */
    private static Violation named(@Nullable String name, String phrase) {
        if (name == null || name.isBlank()) {
            return Violation.general(SUBJECT_UNNAMED + " " + phrase);
        }
        return Violation.onField(safeName(name), phrase);
    }

    /**
     * Returns the cause of an unreadable body from the Jackson failure in its cause chain: an unknown
     * property names the property but is no field of the request, so it carries no field; a value Jackson
     * could not bind names the path of the property, which is the field; anything else, including invalid
     * JSON syntax, trailing content, a member given twice in one object (the parser's
     * {@code strict-duplicate-detection}) and a missing body, is {@value #REASON_MALFORMED_BODY} with no
     * field.
     *
     * @param ex the exception
     * @return the cause
     */
    private static Violation unreadableViolation(HttpMessageNotReadableException ex) {
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        Throwable current = ex.getCause();
        for (int depth = 0; current != null && depth < MAX_CAUSE_DEPTH && seen.add(current); depth++) {
            if (current instanceof UnrecognizedPropertyException unknown) {
                return Violation.general("unknown property " + safeName(unknown.getPropertyName()));
            }
            if (current instanceof MismatchedInputException mismatched) {
                String path = propertyPath(mismatched);
                return path.isEmpty()
                        ? Violation.general(REASON_MALFORMED_BODY)
                        : Violation.onField(safeName(path), "has an invalid value");
            }
            current = current.getCause();
        }
        return Violation.general(REASON_MALFORMED_BODY);
    }

    /**
     * Joins the property names of a Jackson failure's path with {@code '.'}; array positions, which carry
     * no name, are left out.
     *
     * @param ex the Jackson failure
     * @return the path, empty when no element of it has a name
     */
    private static String propertyPath(JsonMappingException ex) {
        return ex.getPath().stream()
                .map(JsonMappingException.Reference::getFieldName)
                .filter(name -> name != null && !name.isBlank())
                .collect(Collectors.joining("."));
    }

    /**
     * Returns the causes of method validation, parameter by parameter in index order. Spring 6.2 reports a
     * {@code @Valid @RequestBody} failure here, as {@link ParameterErrors}, whenever the handler also
     * constrains a plain parameter such as a {@code @Pattern} path variable; such a result contributes its
     * field errors as in {@link #bindingViolations}, any other result one cause, its first violation in a
     * fixed order, on its request parameter or path variable name.
     *
     * @param ex the exception
     * @param status the status, for the fallback when no violation can be named
     * @return the causes, never empty
     */
    private static List<Violation> methodValidationViolations(HandlerMethodValidationException ex,
            HttpStatusCode status) {
        List<ParameterValidationResult> results = new ArrayList<>(ex.getParameterValidationResults());
        results.sort(Comparator.comparingInt(result -> result.getMethodParameter().getParameterIndex()));
        List<Violation> causes = new ArrayList<>();
        for (ParameterValidationResult result : results) {
            if (result instanceof ParameterErrors errors) {
                if (errors.hasErrors()) {
                    causes.addAll(bindingViolations(errors.getFieldErrors(), errors.getGlobalErrors(),
                            result.getMethodParameter(), status));
                }
                continue;
            }
            List<MessageSourceResolvable> violations = new ArrayList<>(result.getResolvableErrors());
            if (!violations.isEmpty()) {
                violations.sort(RESOLVABLE_ORDER);
                causes.add(parameterViolation(result.getMethodParameter(),
                        constraintPhrase(constraintCode(violations.get(0)))));
            }
        }
        if (causes.isEmpty()) {
            causes.add(Violation.general(statusReason(status)));
        }
        return causes;
    }

    /**
     * Returns the causes of bean validation errors: one per field error, in a fixed order (property path,
     * then constraint), each on its property; or, when only object-level errors exist, the first of those
     * against the argument as a whole, which names no field.
     *
     * @param fieldErrors the field errors
     * @param globalErrors the object-level errors
     * @param parameter the validated argument; may be {@code null}
     * @param status the status, for the fallback when there is no error at all
     * @return the causes, never empty
     */
    private static List<Violation> bindingViolations(List<FieldError> fieldErrors,
            List<ObjectError> globalErrors, @Nullable MethodParameter parameter, HttpStatusCode status) {
        if (!fieldErrors.isEmpty()) {
            return fieldErrors.stream()
                    .sorted(FIELD_ERROR_ORDER)
                    .map(error -> named(error.getField(), constraintPhrase(constraintCode(error))))
                    .toList();
        }
        if (!globalErrors.isEmpty()) {
            ObjectError first = globalErrors.stream().min(RESOLVABLE_ORDER).orElseThrow();
            String subject = parameter == null ? SUBJECT_REQUEST_BODY : parameterName(parameter);
            return List.of(Violation.general(subject + " " + constraintPhrase(constraintCode(first))));
        }
        return List.of(Violation.general(statusReason(status)));
    }

    /**
     * Builds the cause of a constraint violation on a plain handler parameter: on its name as a field when
     * it is a named request parameter or path variable, otherwise phrased by {@link #parameterName} with
     * no field.
     *
     * @param parameter the handler parameter
     * @param phrase the phrase of the violated constraint
     * @return the cause
     */
    private static Violation parameterViolation(MethodParameter parameter, String phrase) {
        String name = clientName(parameter);
        if (isRequestParameter(parameter) && name != null && !name.isBlank()) {
            return Violation.onField(safeName(name), phrase);
        }
        return Violation.general(parameterName(parameter) + " " + phrase);
    }

    /**
     * Tells whether a handler parameter is a query parameter or path variable, the only parameters a
     * client can be pointed to by name.
     *
     * @param parameter the handler parameter
     * @return {@code true} for a {@code @RequestParam} or {@code @PathVariable} that is not the body
     */
    private static boolean isRequestParameter(MethodParameter parameter) {
        return !parameter.hasParameterAnnotation(RequestBody.class)
                && (parameter.hasParameterAnnotation(RequestParam.class)
                        || parameter.hasParameterAnnotation(PathVariable.class));
    }

    /**
     * Returns the name a client knows a handler parameter by: {@value #SUBJECT_REQUEST_BODY} for the body,
     * the {@code @RequestParam} or {@code @PathVariable} name when one is declared, otherwise the Java
     * parameter name, which the build compiles with {@code -parameters}.
     *
     * @param parameter the handler parameter
     * @return the name, sanitized and cut to {@value #MAX_NAME_LENGTH} code points
     */
    private static String parameterName(MethodParameter parameter) {
        if (parameter.hasParameterAnnotation(RequestBody.class)) {
            return SUBJECT_REQUEST_BODY;
        }
        return safeName(clientName(parameter));
    }

    /**
     * Returns the unsanitized name of a handler parameter that is not the body: the {@code @RequestParam}
     * or {@code @PathVariable} name when one is declared, otherwise the Java parameter name.
     *
     * @param parameter the handler parameter
     * @return the name, or {@code null} when none is declared and the Java name is unknown
     */
    @Nullable
    private static String clientName(MethodParameter parameter) {
        RequestParam requestParam = parameter.getParameterAnnotation(RequestParam.class);
        if (requestParam != null) {
            String declared = firstNonEmpty(requestParam.name(), requestParam.value());
            if (declared != null) {
                return declared;
            }
        }
        PathVariable pathVariable = parameter.getParameterAnnotation(PathVariable.class);
        if (pathVariable != null) {
            String declared = firstNonEmpty(pathVariable.name(), pathVariable.value());
            if (declared != null) {
                return declared;
            }
        }
        return parameter.getParameterName();
    }

    /**
     * Returns the phrase of a constraint, from its annotation's simple name and never from its localized
     * default message.
     *
     * @param code the constraint code, such as {@code NotNull}; may be empty
     * @return {@code is required}, {@code is too long} (a {@code Size} maximum, or a
     *     {@code CodePointLength} maximum, which counts characters as the column does),
     *     {@code has an invalid format}, {@code contains a character that cannot be stored} (a
     *     character a text column cannot hold, which
     *     {@link com.democorp.customermaster.controller.dto.StorableText} rejects) or {@code is invalid}
     */
    private static String constraintPhrase(String code) {
        return switch (code) {
            case "NotNull" -> "is required";
            case "Size", "CodePointLength" -> "is too long";
            case "Pattern" -> "has an invalid format";
            case "StorableText" -> "contains a character that cannot be stored";
            default -> "is invalid";
        };
    }

    /**
     * Returns the constraint code of a validation error: the last, least specific of its message codes,
     * which bean validation and Spring's method validation both set to the constraint annotation's simple
     * name ({@code NotNull}, {@code Size}, {@code Pattern}).
     *
     * @param error the error
     * @return the code, or {@code ""} when the error has none
     */
    private static String constraintCode(MessageSourceResolvable error) {
        String code = null;
        if (error instanceof DefaultMessageSourceResolvable resolvable) {
            code = resolvable.getCode();
        } else {
            String[] codes = error.getCodes();
            if (codes != null && codes.length > 0) {
                code = codes[codes.length - 1];
            }
        }
        return code == null ? "" : code;
    }

    /**
     * Returns the lower-case reason phrase of a status, or {@value #REASON_CLIENT_ERROR} for a status with
     * no standard phrase.
     *
     * @param status the status
     * @return the phrase
     */
    private static String statusReason(HttpStatusCode status) {
        HttpStatus resolved = HttpStatus.resolve(status.value());
        return resolved == null ? REASON_CLIENT_ERROR : resolved.getReasonPhrase().toLowerCase(Locale.ROOT);
    }

    /**
     * Makes a property or parameter name safe to quote in a reason: control characters become {@code ?}
     * and the name is cut to {@value #MAX_NAME_LENGTH} code points, so a client cannot make the
     * {@code detail} arbitrarily long through an unknown JSON property. A missing name becomes
     * {@value #SUBJECT_UNNAMED}.
     *
     * @param name the name; may be {@code null}
     * @return the safe name, never blank
     */
    static String safeName(@Nullable String name) {
        if (name == null || name.isBlank()) {
            return SUBJECT_UNNAMED;
        }
        StringBuilder safe = new StringBuilder();
        name.codePoints()
                .limit(MAX_NAME_LENGTH)
                .map(codePoint -> Character.isISOControl(codePoint) ? '?' : codePoint)
                .forEach(safe::appendCodePoint);
        return safe.toString();
    }

    /**
     * Returns the first of two annotation attribute values that is not empty.
     *
     * @param first the first value
     * @param second the second value
     * @return the value, or {@code null} when both are empty
     */
    @Nullable
    private static String firstNonEmpty(String first, String second) {
        if (!first.isEmpty()) {
            return first;
        }
        return second.isEmpty() ? null : second;
    }

    // ---------------------------------------------------------------------------------------------
    // Logging helpers
    // ---------------------------------------------------------------------------------------------

    /**
     * Returns the catalog code of a problem, for log lines.
     *
     * @param problem the problem
     * @return the code, or {@code -} when it has none
     */
    private static Object codeOf(ProblemDetail problem) {
        return problem.getProperties() == null
                ? ABSENT : problem.getProperties().getOrDefault(ProblemFactory.PROPERTY_CODE, ABSENT);
    }

    /**
     * Returns a value for a log line, {@code -} when it is absent.
     *
     * @param value the value; may be {@code null}
     * @return the value or {@code -}
     */
    private static String orAbsent(@Nullable String value) {
        return value == null || value.isBlank() ? ABSENT : value;
    }
}
