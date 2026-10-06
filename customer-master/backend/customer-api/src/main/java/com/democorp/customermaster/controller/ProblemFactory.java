package com.democorp.customermaster.controller;

import com.democorp.customermaster.messages.MessageCatalog;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.PrintWriter;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

/**
 * The single builder of RFC 9457 {@code application/problem+json} error bodies.
 *
 * <p><b>What it replaces.</b> On the IBM i, an interactive program reported a condition by sending a
 * {@code CUSTMSGF} message id plus substitution data to its program message queue through
 * {@code SndMsgPgmQ} ({@code SRV_MSG}), which the screen's message subfile then displayed; a
 * "never should happen" SQL error went through {@code SQLProblem} ({@code SRV_SQL}), which dumped the
 * program and ended it with a CPF9898 escape message carrying the SQLSTATE and the SQL message text.
 * Here every error response is a {@link ProblemDetail} whose {@code code} is the catalog key and whose
 * {@code detail} is the catalog text with the arguments substituted, and nothing internal (SQL text,
 * SQLSTATE, stack trace, exception message, class name) ever reaches the body. The SQLSTATE survives only
 * in the server log, through {@link #findSqlState(Throwable)}.
 *
 * <p><b>Ownership.</b> No other class constructs a {@code ProblemDetail} or writes a problem body:
 * {@code ApiExceptionHandler} uses {@link #response(ProblemDetail, HttpHeaders)} for errors that reach
 * Spring MVC, while {@code ProblemErrorController} (ERROR dispatch) and the security entry point and
 * access-denied handler use {@link #write(HttpServletResponse, ProblemDetail)}.
 *
 * <p><b>Dependency direction.</b> The security configuration depends on this class, so it imports only
 * {@link MessageCatalog}, Spring, Jackson, Jakarta Servlet, SLF4J and the JDK. It never imports DTOs,
 * services, security or domain types; that is why {@link #withCurrent(ProblemDetail, Object)} accepts a
 * plain {@code Object}. Importing any of them would close a cycle through the security package.
 *
 * <p><b>Body shape.</b> {@code type} ({@value #TYPE_PREFIX}{@code <code>}), {@code title} (the HTTP
 * reason phrase), {@code status}, {@code detail}, {@code instance} (the request path), then the
 * properties in insertion order: {@code code}, {@code args} (always present, possibly empty), and, only
 * when a helper sets them, {@code errors}, {@code current}, {@code stateAccepted} and {@code errorId}.
 * The properties appear as top-level members because the Boot-configured {@link ObjectMapper} carries
 * Spring's {@code ProblemDetailJacksonMixin}; a plain {@code new ObjectMapper()} would nest them under
 * {@code properties}, so this class always serializes with the injected mapper.
 *
 * <p>Example:
 * <pre>{@code
 * ProblemDetail p = problems.create(HttpStatus.UNPROCESSABLE_ENTITY, "DEM0502", List.of("Name"),
 *         "/api/customers/review");
 * problems.withErrors(p, List.of(problems.fieldProblem("name", "DEM0502", List.of("Name"))));
 * // {"type":"urn:customer-master:problem:DEM0502","title":"Unprocessable Entity","status":422,
 * //  "detail":"Name: Must not be blank","instance":"/api/customers/review","code":"DEM0502",
 * //  "args":["Name"],"errors":[{"field":"name","code":"DEM0502","message":"Name: Must not be blank"}]}
 * return problems.response(p);
 * }</pre>
 *
 * <p><b>Thread safety.</b> The bean holds only its two immutable collaborators, so it is safe to share
 * across request threads. Each {@code ProblemDetail} it returns is a new, request-local object.
 */
@Component
public class ProblemFactory {

    /** Prefix of every problem {@code type}; the catalog key follows, e.g. {@code ...:DEM1002}. */
    public static final String TYPE_PREFIX = "urn:customer-master:problem:";

    /** The media type of every error body. */
    public static final MediaType PROBLEM_JSON = MediaType.APPLICATION_PROBLEM_JSON;

    /** Property holding the catalog key. */
    static final String PROPERTY_CODE = "code";

    /** Property holding the substitution values, as strings. */
    static final String PROPERTY_ARGS = "args";

    /** Property holding the field errors; the first entry receives focus in the UI. */
    static final String PROPERTY_ERRORS = "errors";

    /** Property holding the record as now stored, on 409 DEM1002 only. */
    static final String PROPERTY_CURRENT = "current";

    /** Property holding the State accepted by a review before it failed. */
    static final String PROPERTY_STATE_ACCEPTED = "stateAccepted";

    /** Property holding the correlation id of a 500, also written to the ERROR log line. */
    static final String PROPERTY_ERROR_ID = "errorId";

    /** Upper bound on the cause chain walked by {@link #findSqlState(Throwable)}. */
    private static final int MAX_CAUSE_DEPTH = 20;

    /** The {@code Content-Type} header value written by {@link #write(HttpServletResponse, ProblemDetail)}. */
    private static final String PROBLEM_JSON_VALUE = MediaType.APPLICATION_PROBLEM_JSON_VALUE;

    /** Debug diagnostics only; error logging belongs to the callers, which hold the exception. */
    private static final Logger log = LoggerFactory.getLogger(ProblemFactory.class);

    /** Resolves every {@code code} to its text with the literal {@code {n}} substitution rule. */
    private final MessageCatalog catalog;

    /** The Boot-configured mapper; its {@code ProblemDetail} mixin puts the properties at top level. */
    private final ObjectMapper objectMapper;

    /**
     * Creates the factory.
     *
     * @param catalog the message catalog that resolves every {@code code} to its text
     * @param objectMapper the Boot-configured mapper, which carries Spring's {@code ProblemDetail} mixin
     */
    public ProblemFactory(MessageCatalog catalog, ObjectMapper objectMapper) {
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    /**
     * One {@code errors[]} item: the JSON property or query parameter at fault, the catalog key and the
     * resolved message. Field names are the JSON property names ({@code name}, {@code addr},
     * {@code city}, {@code state}, {@code zip}, {@code corpPhone}, {@code acctMgr}, {@code acctPhone},
     * {@code active}) or a query parameter such as {@code state} or {@code nameContains}.
     *
     * @param field the property or parameter name
     * @param code the catalog key
     * @param message the catalog text with the arguments substituted
     */
    public record FieldProblem(String field, String code, String message) {

        /**
         * Rejects missing members, so an {@code errors[]} item is always complete.
         *
         * @throws NullPointerException when any member is {@code null}
         */
        public FieldProblem {
            Objects.requireNonNull(field, "field");
            Objects.requireNonNull(code, "code");
            Objects.requireNonNull(message, "message");
        }
    }

    /**
     * Builds a problem for a catalog message.
     *
     * <p>The catalog is consulted first, so an unknown code fails with the catalog's
     * {@link IllegalArgumentException} before anything else is built; every code a caller passes is a
     * constant covered by tests, so such a failure is a programming error, never a user condition.
     *
     * @param status the HTTP status of the response
     * @param code the catalog key, for example {@code DEM1002} or {@code APP0400}
     * @param args the substitution values for {@code {0}}, {@code {1}}, ...; each is rendered with
     *     {@link String#valueOf(Object)} and a {@code null} value becomes {@code ""}; {@code null} or
     *     empty for none
     * @param instance the request path (Tomcat's already percent-encoded {@code getRequestURI()});
     *     {@code null} or blank leaves {@code instance} unset
     * @return a new problem with {@code type}, {@code title}, {@code status}, {@code detail},
     *     {@code instance} when usable, {@code code} and {@code args}
     * @throws NullPointerException when {@code status} is {@code null}
     * @throws IllegalArgumentException when {@code code} is {@code null} or not in the catalog
     */
    public ProblemDetail create(HttpStatusCode status, String code, @Nullable List<?> args,
            @Nullable String instance) {
        Objects.requireNonNull(status, "status");
        List<String> textArgs = toTextArgs(args);
        String detail = catalog.text(code, textArgs.toArray());

        ProblemDetail problem = ProblemDetail.forStatus(status);
        problem.setType(URI.create(TYPE_PREFIX + code));
        HttpStatus resolved = HttpStatus.resolve(status.value());
        if (resolved != null) {
            problem.setTitle(resolved.getReasonPhrase());
        }
        problem.setDetail(detail);
        URI instanceUri = toInstanceUri(instance);
        if (instanceUri != null) {
            problem.setInstance(instanceUri);
        }
        // ProblemDetail keeps its properties in a LinkedHashMap, so code precedes args and both precede
        // whatever the helpers add, in the order the caller adds it.
        problem.setProperty(PROPERTY_CODE, code);
        problem.setProperty(PROPERTY_ARGS, textArgs);
        return problem;
    }

    /**
     * Builds one {@code errors[]} item, resolving its message with the same argument rule as
     * {@link #create(HttpStatusCode, String, List, String)}.
     *
     * @param field the JSON property or query parameter name
     * @param code the catalog key
     * @param args the substitution values; {@code null} or empty for none
     * @return the item
     * @throws IllegalArgumentException when {@code code} is {@code null} or not in the catalog
     * @throws NullPointerException when {@code field} is {@code null}
     */
    public FieldProblem fieldProblem(String field, String code, @Nullable List<?> args) {
        String message = catalog.text(code, toTextArgs(args).toArray());
        return new FieldProblem(field, code, message);
    }

    /**
     * Adds the field errors. Their order is kept: the first entry is the field the UI highlights and
     * focuses, as the source put the cursor on the first field in error. An empty list adds nothing.
     *
     * @param problem the problem to extend
     * @param errors the field errors in reporting order; {@code null} or empty adds nothing
     * @return {@code problem}, for chaining
     * @throws NullPointerException when {@code problem} or an element of {@code errors} is {@code null}
     */
    public ProblemDetail withErrors(ProblemDetail problem, @Nullable List<FieldProblem> errors) {
        Objects.requireNonNull(problem, "problem");
        if (errors != null && !errors.isEmpty()) {
            problem.setProperty(PROPERTY_ERRORS, List.copyOf(errors));
        }
        return problem;
    }

    /**
     * Adds the customer as now stored, for 409 DEM1002, so the client can compare and refresh. The value
     * is typed {@code Object} so that this class never depends on the DTO package; it is serialized by
     * the same mapper as the API's success bodies, so it has the {@code CustomerResponse} shape.
     *
     * @param problem the problem to extend
     * @param current the stored record, normally a {@code CustomerResponse}
     * @return {@code problem}, for chaining
     * @throws NullPointerException when either argument is {@code null}
     */
    public ProblemDetail withCurrent(ProblemDetail problem, Object current) {
        Objects.requireNonNull(problem, "problem");
        problem.setProperty(PROPERTY_CURRENT, Objects.requireNonNull(current, "current"));
        return problem;
    }

    /**
     * Adds the State a review accepted before it failed, which the UI keeps as its working State. A
     * {@code null} value, meaning the failure came at or before the State rule, adds nothing, so the
     * member is absent rather than {@code null}.
     *
     * @param problem the problem to extend
     * @param stateAccepted the normalized State that passed the State rule, or {@code null}
     * @return {@code problem}, for chaining
     * @throws NullPointerException when {@code problem} is {@code null}
     */
    public ProblemDetail withStateAccepted(ProblemDetail problem, @Nullable String stateAccepted) {
        Objects.requireNonNull(problem, "problem");
        if (stateAccepted != null) {
            problem.setProperty(PROPERTY_STATE_ACCEPTED, stateAccepted);
        }
        return problem;
    }

    /**
     * Adds the correlation id of a 500. The caller writes the same id to its ERROR log line, which is
     * the only place the exception and its SQLSTATE are recorded.
     *
     * @param problem the problem to extend
     * @param errorId the id, normally from {@link #newErrorId()}
     * @return {@code problem}, for chaining
     * @throws NullPointerException when either argument is {@code null}
     */
    public ProblemDetail withErrorId(ProblemDetail problem, String errorId) {
        Objects.requireNonNull(problem, "problem");
        problem.setProperty(PROPERTY_ERROR_ID, Objects.requireNonNull(errorId, "errorId"));
        return problem;
    }

    /**
     * Returns a new, random correlation id for a 500 response and its log line.
     *
     * @return a random UUID in its canonical 36-character form
     */
    public static String newErrorId() {
        return UUID.randomUUID().toString();
    }

    /**
     * Finds the SQLSTATE behind a failure, for server-side logging only; it never goes into a body.
     * This is the counterpart of SQLProblem's {@code GET DIAGNOSTICS ... RETURNED_SQLSTATE}.
     *
     * <p>The cause chain is walked from {@code failure} outwards to its root, and the first
     * {@link SQLException} with a non-blank {@link SQLException#getSQLState()} wins, so a Spring
     * {@code DataAccessException} wrapping a driver exception yields the driver's state. The walk stops
     * at a cause already seen (a cyclic chain) and after 20 levels.
     *
     * @param failure the failure to inspect; may be {@code null}
     * @return the trimmed SQLSTATE, or empty when there is none
     */
    public static Optional<String> findSqlState(@Nullable Throwable failure) {
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        Throwable current = failure;
        for (int depth = 0; current != null && depth < MAX_CAUSE_DEPTH && seen.add(current); depth++) {
            if (current instanceof SQLException sqlException) {
                String state = sqlException.getSQLState();
                if (state != null && !state.isBlank()) {
                    return Optional.of(state.trim());
                }
            }
            current = current.getCause();
        }
        return Optional.empty();
    }


    /**
     * Wraps a problem in a response entity with no extra headers.
     *
     * @param problem the problem to send
     * @return the entity; see {@link #response(ProblemDetail, HttpHeaders)}
     */
    public ResponseEntity<Object> response(ProblemDetail problem) {
        return response(problem, HttpHeaders.EMPTY);
    }

    /**
     * Wraps a problem in a response entity for Spring MVC, keeping the headers the framework chose.
     *
     * <p>The given headers are copied, so headers such as {@code Allow} on a 405 or {@code Accept} on a
     * 415 survive; a {@code Content-Length} computed for some other body is dropped. The
     * {@code Content-Type} is then preset to {@code application/problem+json}. Spring MVC skips
     * {@code Accept} negotiation when the content type is preset, so a request that accepts only
     * {@code text/html}, or one that failed with 406 Not Acceptable, still receives the problem body
     * rather than a second 406 or an HTML page. The return type matches
     * {@code ResponseEntityExceptionHandler}.
     *
     * @param problem the problem to send; its {@code status} becomes the response status
     * @param headers headers to keep; {@code null} for none
     * @return the entity
     * @throws NullPointerException when {@code problem} is {@code null}
     */
    public ResponseEntity<Object> response(ProblemDetail problem, @Nullable HttpHeaders headers) {
        Objects.requireNonNull(problem, "problem");
        HttpHeaders responseHeaders = new HttpHeaders();
        if (headers != null) {
            responseHeaders.addAll(headers);
        }
        responseHeaders.remove(HttpHeaders.CONTENT_LENGTH);
        responseHeaders.setContentType(PROBLEM_JSON);
        return ResponseEntity.status(problem.getStatus()).headers(responseHeaders).body(problem);
    }

    /**
     * Writes a problem straight to the servlet response, for code that runs outside Spring MVC's return
     * value handling: the ERROR dispatch controller and the security entry point and access-denied
     * handler. Headers already set on the response, such as a {@code WWW-Authenticate} challenge, are
     * kept.
     *
     * <p>The body is serialized first, with the Boot-configured mapper, so a serialization failure leaves
     * the response untouched. Then any buffered content is discarded and the status,
     * {@code Content-Type: application/problem+json}, UTF-8 encoding and content length are set,
     * whatever the request's {@code Accept} header says. The stream is flushed but not closed; the
     * container owns it. A response that is already committed cannot change its status or headers, so
     * it is left as it is.
     *
     * @param response the servlet response
     * @param problem the problem to send; its {@code status} becomes the response status
     * @throws IOException when writing to the client fails
     * @throws NullPointerException when either argument is {@code null}
     */
    public void write(HttpServletResponse response, ProblemDetail problem) throws IOException {
        Objects.requireNonNull(response, "response");
        Objects.requireNonNull(problem, "problem");
        if (response.isCommitted()) {
            log.debug("Response already committed; problem {} {} not written",
                    problem.getStatus(), problem.getProperties() == null
                            ? "-" : problem.getProperties().get(PROPERTY_CODE));
            return;
        }
        // writeValueAsBytes returns the bytes without touching the servlet stream, so Jackson can
        // neither close nor half-write it.
        byte[] body = objectMapper.writeValueAsBytes(problem);
        response.resetBuffer();
        response.setStatus(problem.getStatus());
        response.setContentType(PROBLEM_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        ServletOutputStream out;
        try {
            out = response.getOutputStream();
        } catch (IllegalStateException writerInUse) {
            // Some earlier code obtained the writer, and the servlet API allows only one of the two.
            // The writer then encodes the text itself, so no byte count is declared.
            PrintWriter writer = response.getWriter();
            writer.write(new String(body, StandardCharsets.UTF_8));
            writer.flush();
            return;
        }
        response.setContentLength(body.length);
        out.write(body);
        out.flush();
    }

    /**
     * Renders substitution values as text: {@link String#valueOf(Object)} for each value, {@code ""} for
     * {@code null}. The catalog would render a {@code null} as {@code "null"}, which a user must never
     * read, and the {@code args} member must hold the same strings the {@code detail} shows.
     *
     * @param args the values; {@code null} for none
     * @return an unmodifiable list, empty when there are no values
     */
    private static List<String> toTextArgs(@Nullable List<?> args) {
        if (args == null || args.isEmpty()) {
            return List.of();
        }
        List<String> text = new ArrayList<>(args.size());
        for (Object arg : args) {
            text.add(arg == null ? "" : String.valueOf(arg));
        }
        return List.copyOf(text);
    }

    /**
     * Turns a request path into the {@code instance} URI without ever failing. Tomcat's
     * {@code getRequestURI()} is already percent-encoded, so it normally parses as it is; a path that
     * does not is quoted by the multi-argument {@link URI} constructor instead; a path that neither
     * accepts is left out, because an error response must never fail over its own {@code instance}.
     *
     * @param instance the request path; may be {@code null} or blank
     * @return the URI, or {@code null} when the path is blank or unusable
     */
    @Nullable
    private static URI toInstanceUri(@Nullable String instance) {
        if (instance == null || instance.isBlank()) {
            return null;
        }
        try {
            return URI.create(instance);
        } catch (IllegalArgumentException notEncoded) {
            try {
                return new URI(null, null, instance, null);
            } catch (URISyntaxException unusable) {
                // The path is user-controlled, so it is not echoed into the log.
                log.debug("Request path is not a usable URI; problem instance left unset");
                return null;
            }
        }
    }
}

