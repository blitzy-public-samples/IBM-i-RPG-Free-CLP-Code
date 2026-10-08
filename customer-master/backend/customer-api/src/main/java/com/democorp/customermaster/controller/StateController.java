package com.democorp.customermaster.controller;

import java.util.List;
import java.util.Objects;

import com.democorp.customermaster.controller.dto.StateResponse;
import com.democorp.customermaster.service.StateService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /api/states}: the stateless USA state list behind the browser's {@code StatePicker},
 * which replaces the PMTSTATER window and its returned code {@code pState}
 * ({@code 5250_Subfile/PMTSTATER.SQLRPGLE}, lines 52-54).
 *
 * <p><b>Layering.</b> Filtering, sort order and their validation, including 400 APP0400 on
 * {@code nameContains} and {@code sort}, are {@link StateService#list(String, String)}'s rules; the
 * controller only delegates and maps the result to {@link StateResponse}.
 *
 * <p><b>Why {@code sort} is a {@code String}.</b> Binding it to an enum would let a type-conversion
 * failure decide the error for an unknown value; keeping it a string leaves the one rule, and its
 * APP0400 problem naming the field {@code sort}, with the service. The OpenAPI document still lists the
 * two allowed values.
 *
 * <p><b>Width in code points.</b> {@code nameContains} publishes its 10-character width only as the
 * OpenAPI {@code maxLength}: a Bean Validation {@code @Size} would count UTF-16 units and reject a
 * 10-character fragment that holds supplementary characters.
 *
 * <p><b>No constraints, no {@code @Validated}.</b> Both request rules are the service's, so no
 * parameter carries a Bean Validation constraint. Spring Framework 6.2 validates a constrained handler
 * parameter itself and raises {@code HandlerMethodValidationException}, which
 * {@link ApiExceptionHandler} turns into APP0400; {@code @Validated} would switch to AOP method
 * validation, whose {@code ConstraintViolationException} would bypass that mapping.
 */
@RestController
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@Tag(name = "States")
@SecurityRequirement(name = "basicAuth")
public class StateController {

    /**
     * Width of the "Name Contains" filter field {@code SC_NAME} in PMTSTATED, in characters; published
     * as the OpenAPI {@code maxLength} of {@code nameContains} and enforced, in code points, by
     * {@link StateService}.
     */
    static final int NAME_CONTAINS_MAX_LENGTH = 10;

    /** The state list and its filter and sort rules. */
    private final StateService stateService;

    /**
     * Creates the controller.
     *
     * @param stateService the service that filters and orders the states; must not be {@code null}
     * @throws NullPointerException if {@code stateService} is {@code null}
     */
    public StateController(StateService stateService) {
        this.stateService = Objects.requireNonNull(stateService, "stateService");
    }

    /**
     * Lists the states whose name contains {@code nameContains}, in the requested order.
     *
     * <p>Example: {@code GET /api/states?nameContains=car} returns North Carolina and South Carolina by
     * name; {@code GET /api/states?sort=code} returns all 58 rows by code.
     *
     * @param nameContains the "Name Contains" fragment as typed; {@code null} or blank lists every state
     * @param sort {@code name} or {@code code}; anything else is rejected with 400 APP0400
     * @return the matching states, each with its code and stored name; empty when none matches
     */
    @GetMapping(value = "/api/states", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "listStates", summary = "USA states, optionally filtered by name")
    @ApiResponse(
            responseCode = "200",
            content = @Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    array = @ArraySchema(schema = @Schema(implementation = StateResponse.class))))
    @ApiResponse(
            responseCode = "400",
            description = "APP0400",
            content = @Content(
                    mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemSchema.class)))
    @ApiResponse(
            responseCode = "401",
            description = "APP0401",
            content = @Content(
                    mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemSchema.class)))
    public List<StateResponse> list(
            // Width documented, not constrained: @Size counts UTF-16 units, while the 10-character
            // limit counts code points; the service enforces it and answers APP0400 on the field.
            @Parameter(
                    description = "Case-insensitive name fragment, at most 10 characters",
                    schema = @Schema(maxLength = NAME_CONTAINS_MAX_LENGTH))
            @RequestParam(required = false)
            String nameContains,
            @Parameter(
                    description = "Order of the list: by name (default) or by code",
                    schema = @Schema(allowableValues = {"name", "code"}, defaultValue = "name"))
            @RequestParam(defaultValue = "name")
            String sort) {
        return stateService.list(nameContains, sort).stream()
                .map(StateResponse::from)
                .toList();
    }
}
