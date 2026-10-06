package com.democorp.customermaster.security;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.SequencedCollection;
import java.util.stream.Stream;

import org.springframework.security.authentication.AuthenticationTrustResolver;
import org.springframework.security.authentication.AuthenticationTrustResolverImpl;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * The authenticated principal of the current request: who is signed in, and with which role.
 *
 * <p><b>What it replaces.</b> The IBM i maintenance program stamped every write with the job's user
 * profile. MTNCUSTR reads {@code CURR_USER} from its program status data structure (PSDS position 358)
 * and assigns it to {@code CHGUSER} both when it adds a customer ({@code AddRecd}) and inside the
 * conditional {@code UPDATE CUSTMAST ... CHGUSER = :CURR_USER} ({@code UpdateRecd}); the detail window
 * printed the same profile through the DDS {@code USER} keyword, and the column itself defaulted to
 * {@code USER} ({@code ChgUser varchar(18) not null DEFAULT USER} in {@code Custmast2.sql}). The target
 * has no job user, so the identity comes from authentication instead: the HTTP Basic principal that
 * Spring Security has bound to the request thread.
 *
 * <p><b>The only source of {@code chguser}.</b> {@link #name()} is the one value written into
 * {@code custmast.chguser} by the application. It is never the database role (the column default
 * {@code CURRENT_USER} serves only inserts made outside the application), and never a request field
 * (unknown properties such as {@code chgUser} are rejected before they reach a service).
 * {@code CustomerMaintenanceService} writes it in the same {@code INSERT} or {@code UPDATE} statement as
 * the customer data and repeats it in the {@code customer.write action=... user=...} log line after the
 * commit; {@code SessionController} returns it, with {@link #roles()}, as
 * {@code {"username": "sales", "roles": ["MAINTENANCE"]}}. For HTTP Basic the name is the configured
 * username (1 to 18 characters, validated by {@code UsersProperties}, so it always fits the
 * {@code varchar(18)} column), whatever case the client typed, because the user details service returns
 * the stored user.
 *
 * <p><b>Roles are reported as granted, not as implied.</b> {@link #roles()} lists the
 * {@link Role} names behind the principal's {@code ROLE_} authorities without expanding the role
 * hierarchy, so a maintenance user is {@code ["MAINTENANCE"]}, not {@code ["MAINTENANCE", "INQUIRY"]}.
 * The browser derives its Inquiry or Maintenance mode from that list, while the hierarchy in
 * {@code SecurityConfig} still lets a maintenance user pass every inquiry rule.
 *
 * <p><b>An authentication must be bound.</b> Both methods throw {@link IllegalStateException} when the
 * current thread holds no authentication, an anonymous one, or one that is not authenticated. A request
 * that reaches a service has always been authenticated by the filter chain, so the exception signals a
 * programming error, never a user error, and is answered as 500 DEM9999. Code that runs outside a
 * request thread must bind a {@link SecurityContext} on its own thread before it calls a service that
 * uses this class; for example, a test that adds customers from worker threads sets
 * {@code SecurityContextHolder.setContext(...)} in each worker. The test-data generator never calls this
 * class: it stamps the fixed system identity {@code *SYSTEM*}, as LOADCUSTR did.
 *
 * <p><b>Context independence.</b> The bean has no state and no dependencies, and it imports nothing from
 * the web or controller layers. It is therefore created in every application context, including the
 * non-web generator profile, in which no security configuration exists, so services that inject it start
 * there as well. It reads the thread-bound {@link SecurityContextHolder} on every call, and is safe to
 * share between threads.
 *
 * <p>Usage, inside a request:
 * <pre>{@code
 * String stamp = currentUser.name();          // "sales"
 * List<String> roles = currentUser.roles();   // ["MAINTENANCE"]
 * }</pre>
 */
@Component
public class CurrentUser {

    /** Prefix Spring Security puts in front of a role name to form its authority. */
    static final String ROLE_PREFIX = "ROLE_";

    /** Message of the exception raised when no usable authentication is bound to the thread. */
    static final String NO_AUTHENTICATED_USER = "No authenticated user";

    /** Recognizes anonymous authentications; stateless, so one shared instance serves every call. */
    private static final AuthenticationTrustResolver TRUST_RESOLVER = new AuthenticationTrustResolverImpl();

    /**
     * Creates the accessor. It takes no dependencies, so the bean can be created in any application
     * context, web or not.
     */
    public CurrentUser() {
        // Stateless: every call reads the security context bound to the calling thread.
    }

    /**
     * Returns the authenticated principal's name, the value stamped into {@code chguser}.
     *
     * @return the configured username of the signed-in user; never {@code null} or blank
     * @throws IllegalStateException if no authenticated, non-anonymous user is bound to the current
     *                               thread, or if that authentication carries no name
     */
    public String name() {
        String name = authenticated().getName();
        if (name == null || name.isBlank()) {
            // A blank name would hide the change stamp, so it is refused rather than written.
            throw new IllegalStateException("Authenticated user has no name");
        }
        return name;
    }

    /**
     * Returns the role names granted to the authenticated principal, without the {@code ROLE_} prefix
     * and without role-hierarchy expansion.
     *
     * <p>Only authorities that start with {@code ROLE_} and carry a name after it are reported; any
     * other authority is ignored. Names keep the order in which the authentication lists its
     * authorities, and a repeated name is reported once. When the authentication holds its authorities
     * in a collection without a defined order, the names are sorted, so the result is still
     * deterministic.
     *
     * @return the granted role names, for example {@code ["INQUIRY"]} or {@code ["MAINTENANCE"]};
     *         unmodifiable, never {@code null}, and empty when no role is granted
     * @throws IllegalStateException if no authenticated, non-anonymous user is bound to the current
     *                               thread
     */
    public List<String> roles() {
        Collection<? extends GrantedAuthority> authorities = authenticated().getAuthorities();
        if (authorities == null || authorities.isEmpty()) {
            return List.of();
        }
        Stream<String> names = authorities.stream()
                .filter(Objects::nonNull)
                .map(GrantedAuthority::getAuthority)
                .filter(authority -> authority != null
                        && authority.length() > ROLE_PREFIX.length()
                        && authority.startsWith(ROLE_PREFIX))
                .map(authority -> authority.substring(ROLE_PREFIX.length()))
                .distinct();
        if (!(authorities instanceof SequencedCollection<?>)) {
            // A plain Set has no encounter order; sorting keeps the response stable between calls.
            names = names.sorted(Comparator.naturalOrder());
        }
        return names.toList();
    }

    /**
     * Returns the authentication bound to the current thread, provided it identifies a signed-in user.
     *
     * @return the current, authenticated, non-anonymous authentication
     * @throws IllegalStateException if the security context is missing, holds no authentication, holds
     *                               an anonymous authentication, or holds one that is not authenticated
     */
    private Authentication authenticated() {
        SecurityContext context = SecurityContextHolder.getContext();
        Authentication authentication = context == null ? null : context.getAuthentication();
        if (authentication == null
                || TRUST_RESOLVER.isAnonymous(authentication)
                || !authentication.isAuthenticated()) {
            throw new IllegalStateException(NO_AUTHENTICATED_USER);
        }
        return authentication;
    }
}
