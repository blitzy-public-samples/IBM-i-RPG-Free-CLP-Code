package com.democorp.customermaster.controller.dto;

import com.democorp.customermaster.security.Role;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * Body of {@code GET /api/session}: the signed-in user and the roles granted to that user.
 *
 * <p>Replaces two IBM i mechanisms of the Customer Master screens:
 * <ul>
 *   <li>the DDS {@code USER} keyword, which printed the job's user profile in the header of the
 *       detail window ({@code 5250_Subfile/MTNCUSTD.DSPF}); the browser now shows {@link #username()};</li>
 *   <li>the caller-asserted mode parameter {@code pParmType} ({@code I}, {@code M} or {@code S}) of
 *       the search program ({@code 5250_Subfile/PMTCUSTR.SQLRPGLE}, lines 76-79). The UI derives its
 *       Inquiry or Maintenance mode from {@link #roles()} alone, never from a URL or a request
 *       parameter, and the API enforces the same roles on every request.</li>
 * </ul>
 *
 * <p>Serialized as {@code {"username": "sales", "roles": ["MAINTENANCE"]}}.
 *
 * <p>The record carries values only; it computes nothing. The controller passes the authenticated
 * principal's name and its granted roles exactly as the security layer reports them: role names
 * without the {@code ROLE_} prefix and without role-hierarchy expansion, so a maintenance user is
 * reported as {@code ["MAINTENANCE"]}, not {@code ["MAINTENANCE", "INQUIRY"]}. Selection mode is not a
 * role and never appears here; it is the customer picker's context, available to any signed-in user.
 *
 * <p>The {@code @Schema} and {@code @ArraySchema} annotations are documentation only: they publish
 * both members as {@code required} and each role as one of the {@link Role} constant names, the
 * only values a configured user can be granted. The wire type stays a list of strings.
 *
 * @param username the authenticated principal's name, the same value stamped into {@code chguser}
 * @param roles    the granted role names, for example {@code ["INQUIRY"]} or {@code ["MAINTENANCE"]};
 *                 never {@code null} and unmodifiable
 */
public record SessionResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        String username,
        @ArraySchema(
                arraySchema = @Schema(requiredMode = Schema.RequiredMode.REQUIRED),
                schema = @Schema(implementation = Role.class))
        List<String> roles) {

    /**
     * Normalizes {@code roles}: {@code null} becomes an empty list, and any other list is copied into
     * an unmodifiable list, so the response can never be changed after construction.
     *
     * @throws NullPointerException if {@code roles} contains a {@code null} element
     */
    public SessionResponse {
        roles = roles == null ? List.of() : List.copyOf(roles);
    }
}
