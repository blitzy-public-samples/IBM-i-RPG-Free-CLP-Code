package com.democorp.customermaster.security;

import com.democorp.customermaster.controller.ProblemFactory;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.hierarchicalroles.RoleHierarchy;
import org.springframework.security.access.hierarchicalroles.RoleHierarchyImpl;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;

/**
 * The security configuration of the Customer Master web API: stateless HTTP Basic authentication
 * against the configured users, the two roles and their hierarchy, the request authorization rules,
 * and the {@code application/problem+json} bodies of 401 and 403.
 *
 * <p><b>What it replaces.</b> The IBM i application authenticated nobody. PMTCUSTR took its mode as a
 * caller-asserted parameter ({@code pParmType char(1)}: I, M or S) and passed MTNCUSTR a function
 * code ({@code pMaintain char(1)}); PMTCUSTR itself notes that in production it "would be called from
 * a tested menu or some program that enforced security". Here that security is enforced by the API,
 * not only by the user interface: {@code I} becomes {@link Role#INQUIRY}, {@code M} becomes
 * {@link Role#MAINTENANCE}, and {@code S} is not a role but the customer picker context, open to every
 * authenticated user, because Selection only reads customers and returns an id.
 *
 * <p><b>Authorization rules</b>, evaluated in this order; the first match decides:
 * <table>
 *   <caption>Request rules</caption>
 *   <tr><th>Request</th><th>Required</th></tr>
 *   <tr><td>any ERROR dispatch (the container's forward to {@code /error})</td><td>none</td></tr>
 *   <tr><td>{@code GET} {@link #PUBLIC_GET_PATHS}: messages, health, API docs, Swagger UI</td>
 *       <td>none</td></tr>
 *   <tr><td>{@code GET} {@link #INQUIRY_GET_PATHS}: session, customer search and detail, states</td>
 *       <td>{@code INQUIRY}</td></tr>
 *   <tr><td>{@code POST} {@link #MAINTENANCE_POST_PATHS} and {@code PUT}
 *       {@link #MAINTENANCE_PUT_PATHS}: review, add, change</td><td>{@code MAINTENANCE}</td></tr>
 *   <tr><td>any other request</td><td>authenticated; an unknown path then answers 404</td></tr>
 * </table>
 * {@code MAINTENANCE} implies {@code INQUIRY} through {@link #roleHierarchy()}, so a maintenance user
 * passes every inquiry rule while an inquiry user never passes a maintenance rule.
 *
 * <p><b>ERROR dispatch.</b> Exceptions thrown outside a controller, and {@code sendError} calls, are
 * forwarded by the container to {@code /error}, which {@code ProblemErrorController} answers. Spring
 * Security 6 authorizes every dispatcher type, so that forward is permitted explicitly; otherwise it
 * would be rejected in turn and replace the original status. A direct {@code REQUEST} to
 * {@code /error} is not an ERROR dispatch: it falls under "any other request", so an anonymous one is
 * answered 401 {@code APP0401} and {@code /error} is never public.
 *
 * <p><b>Stateless.</b> No HTTP session is created or read, and every request carries its own
 * credentials. CSRF protection is therefore disabled: no cookie or session carries authority, so a
 * forged cross-site request has nothing to ride on. There is no CORS configuration, because the
 * browser reaches the API only from the same origin (nginx in Compose, the Vite proxy in development).
 * Logout and the request cache are disabled too: there is no session to end and no login page to
 * return to, and the default logout filter would otherwise answer {@code POST /logout} with a redirect
 * rather than letting an unknown path reach the 404 of the MVC layer. The default security headers
 * stay in place.
 *
 * <p><b>401 and 403 bodies.</b> One {@link ProblemAuthenticationEntryPoint} serves both the Basic
 * filter (missing or bad credentials) and the exception translation filter (an anonymous request to a
 * protected path), so the two answer identically: 401 {@code APP0401} "Sign in required.". A
 * {@code WWW-Authenticate: Basic realm="customer-master"} challenge is added only when the request
 * carries no {@value #X_REQUESTED_WITH} header: the SPA always sends
 * {@code X-Requested-With: XMLHttpRequest}, so the browser never shows its native sign-in prompt,
 * while curl and k6 still receive a challenge. An authenticated user without the required role gets
 * 403 {@code APP0403} from {@link ProblemAccessDeniedHandler}. Both bodies are built and written by
 * {@link ProblemFactory} only, and carry no exception message, class name or attempted username.
 * Because the Basic filter runs before authorization, bad credentials sent to a public endpoint are
 * still answered 401; the SPA therefore fetches {@code /api/messages} without credentials.
 *
 * <p><b>Registration and the generator.</b> This class is the only registrar of
 * {@link UsersProperties}; the application declares no {@code @ConfigurationPropertiesScan}. It exists
 * only in a servlet web application ({@link ConditionalOnWebApplication}), so in the generator profile
 * ({@code spring.main.web-application-type: none}) it is skipped entirely: no filter chain, no
 * dependency on Spring MVC's {@code HandlerMappingIntrospector}, and no users bound or validated, so
 * invalid {@code customer-master.security.*} values cannot stop a data load. No class outside this
 * conditional configuration may depend on {@link UsersProperties} or on the beans declared here.
 *
 * <p><b>Users.</b> They come only from {@link UsersProperties}, bound from the environment; this class
 * contains no username, password or default user. Because a {@code UserDetailsService} bean is
 * declared, Spring Boot's {@code UserDetailsServiceAutoConfiguration} backs off and never generates
 * or logs a password.
 */
@Configuration(proxyBeanMethods = false)
@EnableWebSecurity
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@EnableConfigurationProperties(UsersProperties.class)
public class SecurityConfig {

    /** Realm named in the HTTP Basic challenge. */
    static final String REALM = "customer-master";

    /** Value of the {@code WWW-Authenticate} header sent with a 401 to a non-SPA client. */
    static final String BASIC_CHALLENGE = "Basic realm=\"" + REALM + "\"";

    /** Header the SPA sends on every call; its presence suppresses the Basic challenge. */
    static final String X_REQUESTED_WITH = "X-Requested-With";

    /** Catalog key of 401: "Sign in required.". */
    static final String CODE_UNAUTHORIZED = "APP0401";

    /** Catalog key of 403: "You are not authorized to perform this action.". */
    static final String CODE_FORBIDDEN = "APP0403";

    /**
     * BCrypt cost factor of the in-memory password hashes: 2<sup>4</sup> rounds, about a millisecond per
     * check.
     *
     * <p>HTTP Basic is stateless, so every request re-verifies its password against the hash. Spring's
     * default cost of 10 takes roughly 50 to 100 ms per check, which alone would break the search
     * performance thresholds: get-by-id p95 at most 50 ms, search p95 at most 250 ms, and the k6 run's
     * p95 under 300 ms. The hashes are never stored: they exist only in memory, derived at startup from
     * the secrets the environment supplies, so a low cost factor weakens no data at rest. It departs
     * from the usual BCrypt default deliberately; raising it trades request latency for nothing.
     */
    private static final int BCRYPT_STRENGTH = 4;

    /** {@code GET} paths open to anonymous callers: the message catalog, health probes and API docs. */
    static final List<String> PUBLIC_GET_PATHS = List.of(
            "/api/messages",
            "/actuator/health",
            "/actuator/health/**",
            "/v3/api-docs",
            "/v3/api-docs/**",
            "/v3/api-docs.yaml",
            "/swagger-ui/**",
            "/swagger-ui.html");

    /** {@code GET} paths that need {@code INQUIRY} (or {@code MAINTENANCE}, through the hierarchy). */
    static final List<String> INQUIRY_GET_PATHS = List.of(
            "/api/session",
            "/api/customers",
            "/api/customers/*",
            "/api/states");

    /** {@code POST} paths that need {@code MAINTENANCE}: review and add. */
    static final List<String> MAINTENANCE_POST_PATHS = List.of(
            "/api/customers/review",
            "/api/customers");

    /** {@code PUT} paths that need {@code MAINTENANCE}: change one customer. */
    static final List<String> MAINTENANCE_PUT_PATHS = List.of(
            "/api/customers/*");

    /** Startup summary and debug diagnostics; never a password, a username or a request path. */
    private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);

    /**
     * Creates the configuration. Spring instantiates it; it holds no state.
     */
    public SecurityConfig() {
        // All collaborators arrive as bean-method parameters.
    }

    /**
     * The password encoder of the in-memory user store and of HTTP Basic verification.
     *
     * @return a BCrypt encoder with cost {@value #BCRYPT_STRENGTH}; see {@link #BCRYPT_STRENGTH} for why
     *     the cost is low
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(BCRYPT_STRENGTH);
    }

    /**
     * The user store: one Spring Security user per configured {@link UsersProperties.User}, its password
     * BCrypt-encoded once, here, at startup, and its single role granted as the authority
     * {@code ROLE_INQUIRY} or {@code ROLE_MAINTENANCE}.
     *
     * <p>{@link UsersProperties} has already been validated when this method runs: at least one user,
     * unique usernames of 1 to 18 allowed characters, never {@code *SYSTEM*}, a non-blank password of
     * at most {@value UsersProperties#MAX_PASSWORD_BYTES} UTF-8 bytes, the most BCrypt encodes, and a
     * role each. The store returns the configured username, which {@code CurrentUser} stamps into
     * {@code chguser}. Only the number of users per role is logged, never a username or a password.
     *
     * @param usersProperties the validated users from {@code customer-master.security.users}
     * @param passwordEncoder the encoder from {@link #passwordEncoder()}
     * @return the in-memory store holding every configured user
     * @throws IllegalStateException when no users list is bound, which validation normally reports first
     */
    @Bean
    public InMemoryUserDetailsManager userDetailsService(UsersProperties usersProperties,
            PasswordEncoder passwordEncoder) {
        Objects.requireNonNull(usersProperties, "usersProperties");
        Objects.requireNonNull(passwordEncoder, "passwordEncoder");
        List<UsersProperties.User> configured = usersProperties.users();
        if (configured == null || configured.isEmpty()) {
            throw new IllegalStateException(
                    "No users configured under " + UsersProperties.PREFIX + ".users");
        }
        List<UserDetails> users = new ArrayList<>(configured.size());
        Map<Role, Integer> usersPerRole = new EnumMap<>(Role.class);
        for (UsersProperties.User configuredUser : configured) {
            users.add(User.withUsername(configuredUser.username())
                    .password(passwordEncoder.encode(configuredUser.password()))
                    .roles(configuredUser.role().name())
                    .build());
            usersPerRole.merge(configuredUser.role(), 1, Integer::sum);
        }
        log.info("HTTP Basic user store loaded with {} user(s) by role: {}", users.size(), usersPerRole);
        return new InMemoryUserDetailsManager(users);
    }

    /**
     * The role hierarchy: {@code MAINTENANCE} implies {@code INQUIRY}, and nothing implies
     * {@code MAINTENANCE}.
     *
     * <p>{@code authorizeHttpRequests} picks this bean up, so {@code hasRole("INQUIRY")} admits a
     * maintenance user, who is configured with that one role only. {@code CurrentUser.roles()} still
     * reports the role as granted, {@code ["MAINTENANCE"]}, from which the browser derives its mode.
     * The method is static so the hierarchy is available before this configuration is instantiated, as
     * Spring Security recommends for infrastructure beans.
     *
     * @return the hierarchy with the default {@code ROLE_} prefix
     */
    @Bean
    public static RoleHierarchy roleHierarchy() {
        return RoleHierarchyImpl.withDefaultRolePrefix()
                .role(Role.MAINTENANCE.name()).implies(Role.INQUIRY.name())
                .build();
    }

    /**
     * The one security filter chain of the web API, applying the rules described on this class.
     *
     * @param http the builder Spring Security provides for this chain
     * @param problems the factory that builds and writes the 401 and 403 problem bodies
     * @return the chain
     * @throws Exception when Spring Security cannot build the chain
     */
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, ProblemFactory problems)
            throws Exception {
        // One instance for both filters, so missing or bad credentials and an anonymous request to a
        // protected path produce the same 401.
        AuthenticationEntryPoint entryPoint = new ProblemAuthenticationEntryPoint(problems);
        AccessDeniedHandler accessDenied = new ProblemAccessDeniedHandler(problems);

        http
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .httpBasic(basic -> basic.authenticationEntryPoint(entryPoint))
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(entryPoint)
                        .accessDeniedHandler(accessDenied))
                .authorizeHttpRequests(requests -> requests
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        .requestMatchers(HttpMethod.GET, paths(PUBLIC_GET_PATHS)).permitAll()
                        .requestMatchers(HttpMethod.GET, paths(INQUIRY_GET_PATHS))
                                .hasRole(Role.INQUIRY.name())
                        .requestMatchers(HttpMethod.POST, paths(MAINTENANCE_POST_PATHS))
                                .hasRole(Role.MAINTENANCE.name())
                        .requestMatchers(HttpMethod.PUT, paths(MAINTENANCE_PUT_PATHS))
                                .hasRole(Role.MAINTENANCE.name())
                        .anyRequest().authenticated());
        return http.build();
    }

    /**
     * Copies a path list into the array the matcher registry takes, so the shared constant can never
     * be changed through it.
     *
     * @param paths the path patterns
     * @return a new array holding them in order
     */
    private static String[] paths(List<String> paths) {
        return paths.toArray(String[]::new);
    }

    /**
     * Answers an unauthenticated request with 401 {@code APP0401} problem+json, adding the Basic
     * challenge only for clients that are not the SPA.
     *
     * <p>Invoked by the Basic authentication filter when credentials are malformed, unknown or wrong,
     * and by the exception translation filter when an anonymous request reaches a protected path. The
     * challenge header is set before the body is written; {@link ProblemFactory#write} keeps it. The
     * body names neither the failure nor the attempted username.
     */
    static final class ProblemAuthenticationEntryPoint implements AuthenticationEntryPoint {

        /** Builds and writes the problem body. */
        private final ProblemFactory problems;

        /**
         * Creates the entry point.
         *
         * @param problems the factory that builds and writes the problem body
         * @throws NullPointerException when {@code problems} is {@code null}
         */
        ProblemAuthenticationEntryPoint(ProblemFactory problems) {
            this.problems = Objects.requireNonNull(problems, "problems");
        }

        /**
         * Writes the 401 response.
         *
         * @param request the request that failed authentication
         * @param response the response to write
         * @param authException why authentication failed; logged by type at debug level only
         * @throws IOException when writing to the client fails
         */
        @Override
        public void commence(HttpServletRequest request, HttpServletResponse response,
                AuthenticationException authException) throws IOException {
            boolean challenge = request.getHeader(X_REQUESTED_WITH) == null;
            if (challenge) {
                response.setHeader(HttpHeaders.WWW_AUTHENTICATE, BASIC_CHALLENGE);
            }
            if (log.isDebugEnabled()) {
                log.debug("401 {} for {} request (challenge sent: {}, cause: {})", CODE_UNAUTHORIZED,
                        request.getMethod(), challenge,
                        authException == null ? "-" : authException.getClass().getSimpleName());
            }
            problems.write(response, problems.create(HttpStatus.UNAUTHORIZED, CODE_UNAUTHORIZED,
                    List.of(), request.getRequestURI()));
        }
    }

    /**
     * Answers an authenticated request whose role is insufficient with 403 {@code APP0403}
     * problem+json, for example an {@code INQUIRY} user calling {@code POST /api/customers}.
     *
     * <p>An anonymous request never arrives here: the exception translation filter sends it to the
     * entry point instead, so it receives 401. The body names neither the user nor the rule.
     */
    static final class ProblemAccessDeniedHandler implements AccessDeniedHandler {

        /** Builds and writes the problem body. */
        private final ProblemFactory problems;

        /**
         * Creates the handler.
         *
         * @param problems the factory that builds and writes the problem body
         * @throws NullPointerException when {@code problems} is {@code null}
         */
        ProblemAccessDeniedHandler(ProblemFactory problems) {
            this.problems = Objects.requireNonNull(problems, "problems");
        }

        /**
         * Writes the 403 response.
         *
         * @param request the request that was denied
         * @param response the response to write
         * @param accessDeniedException the denial; never echoed into the body
         * @throws IOException when writing to the client fails
         */
        @Override
        public void handle(HttpServletRequest request, HttpServletResponse response,
                AccessDeniedException accessDeniedException) throws IOException {
            log.debug("403 {} for {} request", CODE_FORBIDDEN, request.getMethod());
            problems.write(response, problems.create(HttpStatus.FORBIDDEN, CODE_FORBIDDEN,
                    List.of(), request.getRequestURI()));
        }
    }
}
