package com.democorp.customermaster.controller;

import java.util.Objects;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.Paths;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springdoc.core.utils.SpringDocAnnotationsUtils;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.stereotype.Component;

/**
 * Declares, on every operation of the OpenAPI document, the error responses all operations share, so
 * that no controller has to repeat them.
 *
 * <p><b>Why centrally.</b> {@link ApiExceptionHandler} and {@link ProblemErrorController} answer these
 * statuses for any route, but both are hidden from springdoc, which would otherwise derive generic
 * responses from the advice. The statuses are therefore added here, once, from the same status map:
 *
 * <table>
 *   <caption>Shared error responses</caption>
 *   <tr><th>Status</th><th>Code</th><th>Added to</th></tr>
 *   <tr><td>405</td><td>{@code APP0400}, keeping its own status</td><td>every operation</td></tr>
 *   <tr><td>406</td><td>{@code APP0400}, keeping its own status</td><td>every operation</td></tr>
 *   <tr><td>415</td><td>{@code APP0400}, keeping its own status</td>
 *       <td>every operation with a request body</td></tr>
 *   <tr><td>500</td><td>{@code DEM9999}, with {@code errorId}</td><td>every operation</td></tr>
 * </table>
 *
 * <p>Each response is {@code application/problem+json} with the {@value ProblemSchema#NAME} schema
 * described by {@link ProblemSchema}, like every error response a controller declares. A status an
 * operation already declares is left as the operation declares it.
 *
 * <p><b>Deterministic.</b> The document is the committed snapshot {@code OpenApiSnapshotIT} compares, so
 * the result depends only on the operations: the same responses with the same fixed descriptions, added
 * in a fixed order, and written with {@code springdoc.writer-with-order-by-keys}.
 *
 * <p><b>Web contexts only.</b> Like the controllers, the bean exists only in a servlet application
 * context; the test-data generator runs the same jar with no web server and builds no OpenAPI document.
 *
 * <p>The customizer holds no state and is thread-safe.
 */
@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
final class ProblemResponsesCustomizer implements OpenApiCustomizer {

    /** Response key of a request method the path does not support. */
    static final String METHOD_NOT_ALLOWED = "405";

    /** Response key of an {@code Accept} header the operation cannot satisfy. */
    static final String NOT_ACCEPTABLE = "406";

    /** Response key of a request body whose {@code Content-Type} the operation does not consume. */
    static final String UNSUPPORTED_MEDIA_TYPE = "415";

    /** Response key of an unexpected failure. */
    static final String INTERNAL_SERVER_ERROR = "500";

    /** Description of the shared 405 response. */
    static final String METHOD_NOT_ALLOWED_DESCRIPTION =
            "APP0400 (method not allowed; Allow lists the supported methods)";

    /** Description of the shared 406 response. */
    static final String NOT_ACCEPTABLE_DESCRIPTION = "APP0400 (not acceptable)";

    /** Description of the shared 415 response. */
    static final String UNSUPPORTED_MEDIA_TYPE_DESCRIPTION =
            "APP0400 (unsupported media type; Accept lists the supported type)";

    /** Description of the shared 500 response. */
    static final String INTERNAL_SERVER_ERROR_DESCRIPTION =
            "DEM9999 (unexpected failure; errorId matches the server's ERROR log line)";

    /**
     * Adds the shared error responses to every operation of the document.
     *
     * <p>The {@value ProblemSchema#NAME} schema is resolved into the document's components first, the way
     * springdoc resolves a schema an annotation names, so the responses added here never reference a
     * missing schema; a schema already registered by a controller's declaration is kept unchanged.
     *
     * @param openApi the document springdoc has built from the controllers
     * @throws NullPointerException when {@code openApi} is {@code null}
     * @throws IllegalStateException when springdoc resolves the schema without a reference, which would
     *     leave the added responses without a schema
     */
    @Override
    public void customise(OpenAPI openApi) {
        Objects.requireNonNull(openApi, "openApi");
        Paths paths = openApi.getPaths();
        if (paths == null || paths.isEmpty()) {
            return;
        }
        Components components = openApi.getComponents();
        if (components == null) {
            components = new Components();
            openApi.setComponents(components);
        }
        String problemRef = SpringDocAnnotationsUtils.extractSchema(
                components, ProblemSchema.class, null, null, openApi.getSpecVersion()).get$ref();
        if (problemRef == null) {
            throw new IllegalStateException("springdoc resolved " + ProblemSchema.NAME + " without a reference");
        }
        paths.values().forEach(pathItem ->
                pathItem.readOperations().forEach(operation -> addSharedResponses(operation, problemRef)));
    }

    /**
     * Adds the shared responses one operation does not declare itself.
     *
     * @param operation  the operation
     * @param problemRef the reference to the {@value ProblemSchema#NAME} schema
     */
    private static void addSharedResponses(Operation operation, String problemRef) {
        ApiResponses responses = operation.getResponses();
        if (responses == null) {
            responses = new ApiResponses();
            operation.setResponses(responses);
        }
        addIfAbsent(responses, METHOD_NOT_ALLOWED, METHOD_NOT_ALLOWED_DESCRIPTION, problemRef);
        addIfAbsent(responses, NOT_ACCEPTABLE, NOT_ACCEPTABLE_DESCRIPTION, problemRef);
        if (operation.getRequestBody() != null) {
            addIfAbsent(responses, UNSUPPORTED_MEDIA_TYPE, UNSUPPORTED_MEDIA_TYPE_DESCRIPTION, problemRef);
        }
        addIfAbsent(responses, INTERNAL_SERVER_ERROR, INTERNAL_SERVER_ERROR_DESCRIPTION, problemRef);
    }

    /**
     * Adds one problem response unless the operation already declares its status. Every response gets
     * its own model objects, so a later customizer changing one operation's response changes no other.
     *
     * @param responses   the operation's responses
     * @param status      the response key
     * @param description the response description
     * @param problemRef  the reference to the {@value ProblemSchema#NAME} schema
     */
    private static void addIfAbsent(ApiResponses responses, String status, String description,
            String problemRef) {
        if (responses.containsKey(status)) {
            return;
        }
        Content content = new Content().addMediaType(ProblemFactory.PROBLEM_JSON.toString(),
                new MediaType().schema(new Schema<>().$ref(problemRef)));
        responses.addApiResponse(status, new ApiResponse().description(description).content(content));
    }
}
