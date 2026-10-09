package com.democorp.customermaster.controller;

import com.democorp.customermaster.messages.MessageCatalog;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Map;
import java.util.Objects;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /api/messages}: the whole message catalog, so the browser bundles no message text. It
 * replaces the {@code CUSTMSGF} message file ({@code 5250_Subfile/CRTMSGF.CLLE}).
 *
 * <p><b>Response.</b> The body is {@link MessageCatalog#all()} as is: message id to raw template, in
 * catalog file order, with {@code {n}} placeholders unsubstituted, so the client applies the same
 * literal substitution rule as the server. Jackson writes a map in its iteration order, so the wire
 * keeps the file order.
 *
 * <p><b>Access.</b> The endpoint is public: the catalog is needed before sign-in and holds no customer
 * data, so no {@code basicAuth} security requirement is declared. An anonymous request is always
 * answered 200, but invalid or malformed Basic credentials are still answered 401 APP0401, because the
 * Basic filter runs before the public rule of
 * {@link com.democorp.customermaster.security.SecurityConfig SecurityConfig}. The SPA therefore
 * fetches the catalog without credentials.
 */
@RestController
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@Tag(name = "Messages")
public class MessageController {

    private final MessageCatalog catalog;

    /**
     * Creates the controller over the application's single message catalog.
     *
     * @param catalog the catalog whose texts are served; must not be {@code null}
     * @throws NullPointerException when {@code catalog} is {@code null}
     */
    public MessageController(MessageCatalog catalog) {
        this.catalog = Objects.requireNonNull(catalog, "catalog");
    }

    /**
     * Returns every message id with its raw text, in catalog order.
     *
     * @return the catalog's unmodifiable map of message id to template, placeholders unsubstituted
     */
    @Operation(operationId = "getMessages", summary = "Message catalog keyed by message id")
    @ApiResponse(
            responseCode = "200",
            content = @Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(type = "object", additionalPropertiesSchema = String.class)))
    @ApiResponse(
            responseCode = "401",
            description = "APP0401 (only when invalid Basic credentials are supplied; an anonymous request"
                    + " is answered 200)",
            content = @Content(
                    mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemSchema.class)))
    @GetMapping(value = "/api/messages", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, String> messages() {
        return catalog.all();
    }
}
