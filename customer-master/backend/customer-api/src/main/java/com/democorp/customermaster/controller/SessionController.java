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
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /api/session}: tells the browser who is signed in and which roles that user holds.
 *
 * <p><b>What it replaces.</b> On the IBM i, the screen mode was a letter the caller asserted: the
 * search program received {@code pParmType} ({@code I} inquiry, {@code M} maintenance, {@code S}
 * selection) as its first parameter ({@code 5250_Subfile/PMTCUSTR.SQLRPGLE}, lines 76-79), trusting
 * "a tested menu or some program that enforced security" to pass the right one. The detail window
 * showed the job's user profile in its header through the DDS {@code USER} keyword
 * ({@code 5250_Subfile/MTNCUSTD.DSPF}, record {@code SH_HDR}). The browser now derives its Inquiry or
 * Maintenance mode from the roles returned here, and shows the username returned here in the screen
 * header; it never takes a mode from a URL or a request parameter. Selection is not a role: it is the
 * customer picker's context, open to any signed-in user, so it never appears in the response.
 *
 * <p><b>Response.</b> {@code 200 {"username": "sales", "roles": ["MAINTENANCE"]}}. The roles are the
 * granted role names as {@link CurrentUser#roles()} reports them: without the {@code ROLE_} prefix and
 * without role-hierarchy expansion, so a maintenance user is {@code ["MAINTENANCE"]}, not
 * {@code ["MAINTENANCE", "INQUIRY"]}.
 *
 * <p><b>Authorization lives elsewhere.</b> {@code SecurityConfig} requires the {@code INQUIRY} role,
 * which {@code MAINTENANCE} implies, before the request reaches this controller, and answers a request
 * without valid credentials with 401 APP0401 problem+json. This class therefore performs no checks of
 * its own and carries no method-security annotation; the 401 response below only documents the
 * filter chain's answer in the OpenAPI document.
 *
 * <p><b>Web contexts only.</b> The controller exists only in a servlet application context. The
 * test-data generator runs the same jar with no web server, and creates no controller.
 *
 * <p>The OpenAPI annotations feed the committed springdoc snapshot
 * ({@code customer-master/openapi/customer-master-api.yaml}) and the frontend types generated from it,
 * so the operation id, summary and response declarations must stay fixed.
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
                    schema = @Schema(implementation = ProblemDetail.class)))
    public SessionResponse session() {
        return new SessionResponse(currentUser.name(), currentUser.roles());
    }
}
