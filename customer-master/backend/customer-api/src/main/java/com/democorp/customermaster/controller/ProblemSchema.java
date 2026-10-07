package com.democorp.customermaster.controller;

import java.util.List;

import com.democorp.customermaster.controller.dto.CustomerResponse;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * The OpenAPI description of the {@code application/problem+json} body of every error response, the
 * schema {@value #NAME} that each error response of the controllers and of
 * {@link ProblemResponsesCustomizer} references.
 *
 * <p><b>Documentation only.</b> The body itself is a Spring {@code ProblemDetail} built by
 * {@link ProblemFactory} and serialized through Boot's {@code ProblemDetailJacksonMixin}, which writes its
 * properties as top-level members. Referencing {@code ProblemDetail} in the annotations would publish
 * Spring's generic shape instead, with a nested {@code properties} object the wire never carries and
 * without {@code code}, {@code args} and the conditional members. This record lists the members exactly
 * as the factory writes them, so springdoc can describe them; no code constructs it, and no response
 * returns it.
 *
 * <p><b>Kept in step with the factory.</b> Each member mirrors one part of
 * {@link ProblemFactory#create}, {@link ProblemFactory#withErrors}, {@link ProblemFactory#withCurrent},
 * {@link ProblemFactory#withStateAccepted} or {@link ProblemFactory#withErrorId}, and {@link FieldError}
 * mirrors {@link ProblemFactory.FieldProblem}. A change to the body changes this record in the same
 * commit, followed by the committed OpenAPI snapshot and the frontend types generated from it.
 *
 * <p>Example, the 409 of a stale update:
 * <pre>{@code
 * {"type":"urn:customer-master:problem:DEM1002","title":"Conflict","status":409,
 *  "detail":"Someone else changed record. Review data.","instance":"/api/customers/EEEF",
 *  "code":"DEM1002","args":[],"current":{"custId":"EEEF", ..., "version":1}}
 * }</pre>
 *
 * @param type          {@code urn:customer-master:problem:} followed by the catalog key
 * @param title         the HTTP reason phrase of {@code status}
 * @param status        the HTTP status of the response
 * @param detail        the catalog text with {@code args} substituted
 * @param instance      the request path; absent only when the path is not a usable URI reference
 * @param code          the catalog key
 * @param args          the substitution values as strings; always present, empty when there are none
 * @param errors        the fields at fault, the first one to receive focus; absent when there are none
 * @param current       409 {@code DEM1002} only: the customer as now stored, including its version
 * @param stateAccepted review 422 and 502 only: the normalized State, when the State rule passed
 * @param errorId       500 {@code DEM9999} only: the correlation id also written to the ERROR log line
 */
@Schema(
        name = ProblemSchema.NAME,
        description = "RFC 9457 problem: the body of every error response. type, title, status, detail, "
                + "code and args are always present; the other members only in the cases their "
                + "descriptions name.")
record ProblemSchema(
        @Schema(
                requiredMode = Schema.RequiredMode.REQUIRED,
                format = "uri",
                description = "urn:customer-master:problem: followed by the catalog key",
                example = "urn:customer-master:problem:DEM1002")
        String type,
        @Schema(
                requiredMode = Schema.RequiredMode.REQUIRED,
                description = "The HTTP reason phrase of status",
                example = "Conflict")
        String title,
        @Schema(
                requiredMode = Schema.RequiredMode.REQUIRED,
                description = "The HTTP status of the response",
                example = "409")
        int status,
        @Schema(
                requiredMode = Schema.RequiredMode.REQUIRED,
                description = "The catalog text of code with args substituted",
                example = "Someone else changed record. Review data.")
        String detail,
        @Schema(
                format = "uri-reference",
                description = "The request path; absent only when the path is not a usable URI reference",
                example = "/api/customers/EEEF")
        String instance,
        @Schema(
                requiredMode = Schema.RequiredMode.REQUIRED,
                description = "The catalog key, for example APP0400, DEM0502 or DEM1002",
                example = "DEM1002")
        String code,
        @Schema(
                requiredMode = Schema.RequiredMode.REQUIRED,
                description = "The values substituted into the catalog text, as strings; empty when there "
                        + "are none")
        List<String> args,
        @ArraySchema(
                arraySchema = @Schema(
                        description = "The fields at fault; the first entry receives focus. Present when the "
                                + "failure names a request property or parameter: 400 APP0400 on a body "
                                + "property or a path or query parameter, 400 DEM0007 on state, 422 DEM0501, "
                                + "DEM0502 or DEM0503 on the field whose rule failed, and 422 DEM9898 on "
                                + "addr, city, state and zip, in that order. Absent when it names none, as "
                                + "for a malformed body, an unknown property, content after the JSON value, "
                                + "an unknown route, 405, 406, 415, 401, 403, 404 DEM0599, 409, 502, 503 "
                                + "and 500"),
                minItems = 1)
        List<FieldError> errors,
        @Schema(description = "409 DEM1002 only: the customer as now stored, including its version")
        CustomerResponse current,
        @Schema(
                description = "Failures of POST /api/customers/review only (422 and 502): the normalized "
                        + "State, present when the State rule passed before the failure and absent when the "
                        + "failure is at the State rule or before it",
                example = "ME")
        String stateAccepted,
        @Schema(
                format = "uuid",
                description = "500 DEM9999 only: the correlation id, also written to the server's ERROR log "
                        + "line")
        String errorId) {

    /** The schema name under {@code components.schemas}, referenced by every error response. */
    static final String NAME = "Problem";

    /**
     * One {@code errors[]} item, mirroring {@link ProblemFactory.FieldProblem}, whose members are never
     * {@code null}.
     *
     * @param field   the request body's JSON property, or the path or query parameter, at fault
     * @param code    the catalog key
     * @param message the catalog text with the arguments substituted
     */
    @Schema(name = "FieldError", description = "One field at fault in a problem's errors")
    record FieldError(
            @Schema(
                    requiredMode = Schema.RequiredMode.REQUIRED,
                    description = "The request body's JSON property (name, addr, city, state, zip, "
                            + "corpPhone, acctMgr, acctPhone, active; version on update; purpose on "
                            + "review) or the path or query parameter (custId; name, city, state, "
                            + "includeInactive, size, cursor on search; nameContains, sort on the state "
                            + "list) at fault",
                    example = "name")
            String field,
            @Schema(
                    requiredMode = Schema.RequiredMode.REQUIRED,
                    description = "The catalog key",
                    example = "DEM0502")
            String code,
            @Schema(
                    requiredMode = Schema.RequiredMode.REQUIRED,
                    description = "The catalog text with the arguments substituted",
                    example = "Name: Must not be blank")
            String message) {
    }
}
