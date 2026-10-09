package com.democorp.customermaster.security;

/**
 * The application roles that authorize access to the Customer Master API.
 *
 * <p>The IBM i application authenticated nobody. Its mode was a one-letter parameter asserted by the
 * caller: PMTCUSTR's {@code pParmType char(1)} (I, M or S) [5250_Subfile/PMTCUSTR.SQLRPGLE:76-79] and
 * MTNCUSTR's function code {@code pMaintain char(1)} [5250_Subfile/MTNCUSTR.SQLRPGLE:45-48]. PMTCUSTR
 * notes that in production it "would be called from a tested menu or some program that enforced
 * security" [5250_Subfile/PMTCUSTR.SQLRPGLE:230-231]. The target enforces that security in the API:
 * each user is configured with exactly one role, and the request rules in {@code SecurityConfig}
 * decide what that role may do. The mode letters map as follows [5250_Subfile/README.md:33-40]:
 * <ul>
 *   <li>{@code I} (Inquiry, for the general user population) becomes {@link #INQUIRY}.</li>
 *   <li>{@code M} (Maintenance, for Sales) becomes {@link #MAINTENANCE}.</li>
 *   <li>{@code S} (Selection) is deliberately <em>not</em> a role. It is the customer picker context
 *       of the user interface, open to every authenticated user, because Selection only reads
 *       customers and returns an id to the calling screen.</li>
 * </ul>
 *
 * <p>{@link #MAINTENANCE} implies {@link #INQUIRY}. That implication is declared once, by the
 * {@code RoleHierarchy} bean in {@code SecurityConfig}, not in this enum, so a maintenance user passes
 * every inquiry rule with one configured role.
 *
 * <p>The constant names are an external contract and must not be renamed or prefixed. They are the
 * values of {@code customer-master.security.users[n].role}; {@code SecurityConfig} grants them as the
 * authorities {@code ROLE_INQUIRY} and {@code ROLE_MAINTENANCE} through {@code User.roles(role.name())},
 * which adds the prefix itself; and they appear verbatim in the {@code roles} of
 * {@code GET /api/session}, from which the browser derives its mode, and in the committed OpenAPI
 * snapshot.
 *
 * <p>The enum carries no fields and no framework annotations, so it is usable in every context,
 * including the non-web generator profile in which no security configuration exists.
 */
public enum Role {

    /**
     * Read-only access, replacing source mode {@code I}: search the customer list, display a
     * customer, list states and read the caller's own session. Also granted to every
     * {@link #MAINTENANCE} user through the role hierarchy in {@code SecurityConfig}.
     */
    INQUIRY,

    /**
     * Read and write access, replacing source mode {@code M} (option 2=Edit and F6=Add): everything
     * {@link #INQUIRY} permits, plus reviewing, adding and changing customers.
     */
    MAINTENANCE
}
