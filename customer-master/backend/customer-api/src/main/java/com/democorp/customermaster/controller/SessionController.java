package com.democorp.customermaster.controller;

import java.util.Objects;

import com.democorp.customermaster.controller.dto.SessionResponse;
import com.democorp.customermaster.security.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /api/session}: the signed-in username and the roles granted to that user.
 *
 * <p>The browser derives its Inquiry or Maintenance mode and the screen header's username from this
 * response, never from a URL or a request parameter. It replaces the caller-asserted mode letter
 * {@code pParmType} ({@code 5250_Subfile/PMTCUSTR.SQLRPGLE}, lines 76-79) and the header's DDS
 * {@code USER} keyword ({@code 5250_Subfile/MTNCUSTD.DSPF}, record {@code SH_HDR}).
 *
 * <p><b>Roles.</b> The roles are as {@link CurrentUser#roles()} reports them: granted, not expanded
 * through the role hierarchy, so a maintenance user is {@code ["MAINTENANCE"]}. Selection is not a
 * role and never appears in the list.
 *
 * <p><b>Authorization.</b> The {@code INQUIRY} requirement and the 401 APP0401 answer belong to the
 * filter chain of {@link com.democorp.customermaster.security.SecurityConfig SecurityConfig}. This
 * class checks nothing, and its 401 declaration only documents that answer.
 */
@RestController
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@Tag(name = "Session")
@SecurityRequirement(name = "basicAuth")
public class SessionController {

    /** Accessor of the authenticated principal bound to the request thread. */
    private final CurrentUser currentUser;

    /**
     * Creates the controller.
     *
     * @param currentUser accessor of the authenticated principal; must not be {@code null}
     * @throws NullPointerException if {@code currentUser} is {@code null}
     */
    public SessionController(CurrentUser currentUser) {
        this.currentUser = Objects.requireNonNull(currentUser, "currentUser");
    }

    /**
     * Returns the signed-in user's name and granted roles.
     *
     * <p>The name is the same value the maintenance service stamps into {@code chguser}, so the header
     * the browser shows and the change stamp it later displays name the same user.
     *
     * @return the authenticated principal's username and its granted role names
     */
    @GetMapping(value = "/api/session", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "getSession", summary = "Signed-in user and roles")
    @ApiResponse(
            responseCode = "200",
            content = @Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = SessionResponse.class)))
    @ApiResponse(
            responseCode = "401",
            description = "APP0401",
            content = @Content(
                    mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemSchema.class)))
    public SessionResponse session() {
        return new SessionResponse(currentUser.name(), currentUser.roles());
    }
}
