package com.democorp.customermaster.controller.dto;

import com.democorp.customermaster.security.Role;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * Body of {@code GET /api/session}: the signed-in user and the roles granted to that user.
 *
 * <p>The roles are reported exactly as granted: role names without the {@code ROLE_} prefix and
 * without role-hierarchy expansion, so a maintenance user is reported as {@code ["MAINTENANCE"]},
 * not {@code ["MAINTENANCE", "INQUIRY"]}. Selection is not a role and never appears here; it is the
 * customer picker's context, available to any signed-in user. The UI derives its Inquiry or
 * Maintenance mode from {@link #roles()} alone, where the search program took the caller-asserted
 * {@code pParmType} [5250_Subfile/PMTCUSTR.SQLRPGLE:76-79], and the API enforces the same roles on
 * every request.
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
