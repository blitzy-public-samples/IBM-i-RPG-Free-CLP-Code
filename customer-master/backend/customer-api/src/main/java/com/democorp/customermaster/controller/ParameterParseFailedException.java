package com.democorp.customermaster.controller;

import java.io.Serial;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.lang.Nullable;
import org.springframework.web.ErrorResponseException;

/**
 * The request parameters the servlet container failed to parse, raised by
 * {@link ParameterParseFailureInterceptor} before any handler binds them, and answered by
 * {@link ApiExceptionHandler} like every other framework failure.
 *
 * <p><b>Why.</b> Tomcat parses the query string, and a form body, the first time a parameter is read.
 * A parameter whose name or value holds a {@code %} that is not followed by two hexadecimal digits
 * cannot be decoded: Tomcat logs it at INFO, drops it and carries on, so without this exception
 * {@code GET /api/customers?cursor=%%%} would bind no cursor and answer 200 with the first page instead
 * of the 400 {@code APP0400} of a malformed cursor, and {@code name=ZQ%} would answer the unfiltered list.
 *
 * <p><b>What it carries.</b>
 * <ul>
 *   <li>The status, the one Tomcat's own {@code FailedRequestFilter} sends for the same failure: 413 for
 *       a form body over the container's size limit, 500 for an I/O failure while reading the body,
 *       which is not the client's fault, and 400 for everything else.</li>
 *   <li>For a parameter that could not be decoded, the decoded name of that query parameter when it
 *       can be identified, which {@link ApiExceptionHandler} reports as the reason
 *       {@code cursor has an invalid value} and as {@code errors[0].field}; {@code null} when its name
 *       itself is malformed or the failure lies in a form body, which yields
 *       {@code parameter has an invalid value} with no {@code errors}.</li>
 *   <li>For a failure of the parameters as a whole (a parameter without a name, too many parameters, a
 *       body too large or unreadable), a fixed English reason instead.</li>
 * </ul>
 * Neither the value the client sent nor any text of the container travels: the exception has no cause
 * and no message of its own beyond its status.
 *
 * <p><b>Why {@link ErrorResponseException}.</b> {@code ResponseEntityExceptionHandler} routes it, with
 * its own status, to {@link ApiExceptionHandler#handleExceptionInternal}, the one path that turns a
 * framework 4xx into {@code APP0400} and a 5xx into 500 {@code DEM9999}. Unlike
 * {@code ServletRequestBindingException}, whose status is fixed at 400, it takes the status per
 * instance.
 *
 * <p>Instances are immutable after construction and safe to share between threads.
 */
final class ParameterParseFailedException extends ErrorResponseException {

    /** Serialization version of this exception type. */
    @Serial
    private static final long serialVersionUID = 1L;

    /** The decoded name of the undecodable query parameter; {@code null} when unknown or not applicable. */
    @Nullable
    private final String parameterName;

    /** The fixed reason of a failure of the parameters as a whole; {@code null} for an undecodable one. */
    @Nullable
    private final String reason;

    /**
     * Creates the exception.
     *
     * @param status the status to answer with
     * @param parameterName the decoded name of the undecodable query parameter; may be {@code null}
     * @param reason the fixed reason of a failure of the parameters as a whole; {@code null} for an
     *     undecodable parameter
     */
    private ParameterParseFailedException(HttpStatus status, @Nullable String parameterName,
            @Nullable String reason) {
        super(status);
        this.parameterName = parameterName;
        this.reason = reason;
    }

    /**
     * Creates the 400 of a parameter Tomcat could not decode.
     *
     * @param parameterName the decoded name of the query parameter at fault, or {@code null} when its
     *     name is itself malformed or the failure is not in the query string
     * @return the exception
     */
    static ParameterParseFailedException undecodable(@Nullable String parameterName) {
        return new ParameterParseFailedException(HttpStatus.BAD_REQUEST, parameterName, null);
    }

    /**
     * Creates the exception of a failure of the parameters as a whole.
     *
     * @param status the status to answer with
     * @param reason the fixed English reason, the {@code {0}} of {@code APP0400}; never a client value
     * @return the exception
     * @throws NullPointerException when an argument is {@code null}
     */
    static ParameterParseFailedException rejected(HttpStatus status, String reason) {
        return new ParameterParseFailedException(Objects.requireNonNull(status, "status"), null,
                Objects.requireNonNull(reason, "reason"));
    }

    /**
     * Returns the decoded name of the query parameter that could not be decoded.
     *
     * @return the name, or {@code null} when it is unknown or the failure concerns the parameters as a
     *     whole
     */
    @Nullable
    String parameterName() {
        return parameterName;
    }

    /**
     * Returns the fixed reason of a failure of the parameters as a whole.
     *
     * @return the reason, or {@code null} when one parameter could not be decoded, which
     *     {@link ApiExceptionHandler} phrases on {@link #parameterName()}
     */
    @Nullable
    String reason() {
        return reason;
    }
}
