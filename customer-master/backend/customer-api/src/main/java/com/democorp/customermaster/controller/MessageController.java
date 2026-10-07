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
 * Serves the whole message catalog: {@code GET /api/messages}.
 *
 * <p><b>What it replaces.</b> On the IBM i, the CL program {@code CRTMSGF} created the message file
 * {@code CUSTMSGF} with 17 {@code ADDMSGD} descriptions, and the screens never held a message text of
 * their own: {@code SndMsgPgmQ} in the {@code SRV_MSG} service program sent only a message id and its
 * substitution data, and the system resolved the text from the message file into the message subfile.
 * The browser keeps that separation. It bundles no message text; its message catalog provider fetches
 * this map once at application start, before sign-in, and formats from it every message it shows,
 * including the ones it raises itself (DEM0003, DEM0004 and DEM0005).
 *
 * <p><b>Response.</b> One JSON object keyed by message id, holding the raw templates exactly as
 * {@link MessageCatalog#all()} returns them, in catalog file order and with {@code {n}} placeholders
 * unsubstituted, so the client applies the same literal substitution rule as the server:
 * <pre>{@code
 * GET /api/messages
 * 200 {"DEM0000": "Press Enter to update. F12 to Cancel.", "DEM0002": "No records match ...", ...,
 *      "DEM0004": "{0} is not a valid option at this time.", ..., "APP0503": "No customer ids are left. Contact IT."}
 * }</pre>
 *
 * <p><b>No logic here.</b> The controller neither filters, reorders nor caches: which texts exist and
 * how they read is decided by the catalog file alone, and the map is immutable, so it is returned as
 * is. Jackson writes a map in its iteration order, which keeps the file order on the wire.
 *
 * <p><b>Access.</b> The endpoint is public: the catalog is needed to render the sign-in page's own
 * messages before any credentials exist, and it holds no customer data. The security configuration
 * permits it anonymously, so, unlike the other controllers, no {@code basicAuth} security requirement is
 * declared for the OpenAPI document. Its one error response of its own is a conditional 401
 * {@code APP0401}: the Basic authentication filter runs before the anonymous permission is checked, so
 * a request that voluntarily sends invalid or malformed Basic credentials is still rejected, as the
 * security configuration documents. The body is the usual problem+json, and the
 * {@code WWW-Authenticate} challenge is added only when {@code X-Requested-With} is absent. An
 * anonymous request is always answered 200, which is why the SPA fetches the catalog without
 * credentials.
 *
 * <p><b>Web context only.</b> The generator runs the same jar with no web server; the condition keeps
 * this controller out of that context.
 *
 * <p>Thread safety: stateless apart from the immutable catalog reference, so one instance serves every
 * request thread.
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
