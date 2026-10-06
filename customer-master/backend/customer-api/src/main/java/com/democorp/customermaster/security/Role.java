package com.democorp.customermaster.security;

/**
 * The application roles that authorize access to the Customer Master API.
 *
 * <p>The IBM i application had no authentication. Its mode was a one-letter parameter asserted by
 * whoever called the program: PMTCUSTR's first parameter {@code pParmType char(1)} (I, M or S) and
 * MTNCUSTR's function code {@code pMaintain char(1)}. PMTCUSTR itself notes that in production it
 * "would be called from a tested menu or some program that enforced security". The target enforces
 * that security in the API: each user is configured with exactly one role, and the request rules in
 * {@code SecurityConfig} decide what that role may do.
 *
 * <p>Mapping of the source mode letters:
 * <ul>
 *   <li>{@code I} (Inquiry, for the general user population) becomes {@link #INQUIRY}.</li>
 *   <li>{@code M} (Maintenance, for Sales) becomes {@link #MAINTENANCE}.</li>
 *   <li>{@code S} (Selection) is deliberately <em>not</em> a role. It is the customer picker context
 *       of the user interface, open to every authenticated user, because Selection only reads
 *       customers and returns an id to the calling screen.</li>
 * </ul>
 *
 * <p>{@link #MAINTENANCE} implies {@link #INQUIRY}. That implication is declared once, by the
 * {@code RoleHierarchy} bean in {@code SecurityConfig}, and is not modelled in this enum, so a
 * maintenance user passes every inquiry rule without being configured with two roles.
 *
 * <p>The constant names are an external contract and must not be renamed or prefixed:
 * <ul>
 *   <li>they are the values bound from configuration,
 *       {@code customer-master.security.users[n].role: INQUIRY|MAINTENANCE};</li>
 *   <li>{@code SecurityConfig} turns them into the Spring Security authorities
 *       {@code ROLE_INQUIRY} and {@code ROLE_MAINTENANCE} through {@code User.roles(role.name())},
 *       which adds the {@code ROLE_} prefix itself;</li>
 *   <li>they appear verbatim in the {@code roles} array of {@code GET /api/session}, from which the
 *       browser derives its mode, and in the committed OpenAPI snapshot.</li>
 * </ul>
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
