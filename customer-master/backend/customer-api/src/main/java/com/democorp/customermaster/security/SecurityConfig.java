package com.democorp.customermaster.security;

import com.democorp.customermaster.controller.ProblemFactory;
import com.democorp.customermaster.controller.SecurityHeaderPolicies;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.lang.Nullable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.hierarchicalroles.RoleHierarchy;
import org.springframework.security.access.hierarchicalroles.RoleHierarchyImpl;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.ObjectPostProcessor;
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
import org.springframework.security.web.authentication.AuthenticationConverter;
import org.springframework.security.web.authentication.www.BasicAuthenticationConverter;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;
import org.springframework.security.web.header.writers.ContentSecurityPolicyHeaderWriter;
import org.springframework.security.web.header.writers.DelegatingRequestMatcherHeaderWriter;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.NegatedRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.util.function.SingletonSupplier;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.DefaultCorsProcessor;
import org.springframework.web.servlet.handler.AbstractHandlerMapping;

/**
 * The security configuration of the Customer Master web API: stateless HTTP Basic authentication
 * against the configured users, the two roles and their hierarchy, the request authorization rules,
 * and the {@code application/problem+json} bodies of 401 and 403. The API, not only the user interface,
 * enforces the roles; {@link Role} records how the source's caller-asserted modes became them.
 *
 * <p><b>Authorization rules</b>, evaluated in this order; the first match decides:
 * <ol>
 *   <li>any ERROR dispatch: permitted;</li>
 *   <li>{@code GET} {@link #PUBLIC_GET_PATHS}: permitted;</li>
 *   <li>{@code GET} {@link #INQUIRY_GET_PATHS}: {@code INQUIRY};</li>
 *   <li>{@code POST} {@link #MAINTENANCE_POST_PATHS} and {@code PUT} {@link #MAINTENANCE_PUT_PATHS}:
 *       {@code MAINTENANCE};</li>
 *   <li>any other request: authenticated; an unknown path then answers 404.</li>
 * </ol>
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
 * browser reaches the API only from the same origin (nginx in Compose, the Vite proxy in development),
 * so no cross-origin request is ever granted. The cross-origin requests Spring MVC itself rejects are
 * answered 403 {@code APP0403} problem+json by {@link ProblemCorsProcessor}, which
 * {@link #problemCorsProcessorInstaller(ObjectProvider)} installs on every handler mapping. An
 * anonymous preflight never gets that far: it is answered 401 like any anonymous request to a
 * protected path. Logout and the request cache are disabled too: there is no session to end and no
 * login page to return to, and the default logout filter would otherwise answer {@code POST /logout}
 * with a redirect rather than letting an unknown path reach the 404 of the MVC layer.
 *
 * <p><b>Security headers.</b> Spring Security's default headers stay in place:
 * {@code X-Content-Type-Options: nosniff}, {@code X-XSS-Protection: 0}, the no-store
 * {@code Cache-Control} set (unless the handler chose its own), HSTS on secure requests only, and
 * {@code X-Frame-Options: DENY}. The chain adds two of its own to every response, its 401 and 403
 * included: {@code Referrer-Policy: no-referrer}, and exactly one {@code Content-Security-Policy}, the
 * Swagger UI's {@link SecurityHeaderPolicies#SWAGGER_UI_CONTENT_SECURITY_POLICY} for
 * {@link #SWAGGER_UI_PATHS} and the strict {@link SecurityHeaderPolicies#API_CONTENT_SECURITY_POLICY}
 * for everything else. No writer replaces a header already set, and the headers are written while the
 * request's own dispatch runs, so a response that the ERROR dispatch to {@code /error} finishes keeps
 * the policy of the path the client asked for; only a request the firewall rejects first meets the
 * writers on that dispatch, and gets the strict policy. A request Tomcat's connector rejects never
 * reaches this chain; {@code ProblemErrorReportValve} adds the same headers to its answer.
 *
 * <p><b>401 and 403 bodies.</b> One {@link ProblemAuthenticationEntryPoint} answers missing or bad
 * credentials and an anonymous request to a protected path alike: 401 {@code APP0401}. A
 * {@code WWW-Authenticate: Basic realm="customer-master"} challenge is added only when the request
 * carries no {@value #X_REQUESTED_WITH} header: the SPA always sends
 * {@code X-Requested-With: XMLHttpRequest}, so the browser never shows its native sign-in prompt,
 * while curl and k6 still receive a challenge. An authenticated user without the required role gets
 * 403 {@code APP0403} from {@link ProblemAccessDeniedHandler}. Both bodies are built and written by
 * {@link ProblemFactory} only, and carry no exception message, class name or attempted username.
 * Because the Basic filter runs before authorization, bad credentials sent to a public endpoint are
 * still answered 401; the SPA therefore fetches {@code /api/messages} without credentials.
 *
 * <p><b>Failed sign-ins.</b> Credentials that were sent and rejected, whether wrong, unknown or
 * malformed, are logged at WARN, one line per request, with the code, the method, whether a challenge
 * was sent and the type of the failure; an anonymous 401 is logged at debug level only. The API keeps
 * no per-caller state between requests, so it counts no failures and locks out no user or client:
 * throttling repeated attempts is the job of the Compose {@code frontend}'s nginx, the only browser
 * ingress, which limits the rate of credentialed {@code /api/} requests per client address and answers
 * a flood beyond its burst with 429.
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

    /**
     * The Swagger UI's paths, public under {@link #PUBLIC_GET_PATHS}: its pages and resources, and the
     * {@code springdoc.swagger-ui.path} that redirects to them. Only these responses get the Swagger UI's
     * {@code Content-Security-Policy}; every other response gets the strict API policy.
     */
    static final List<String> SWAGGER_UI_PATHS = List.of(
            "/swagger-ui/**",
            "/swagger-ui.html");

    /**
     * Startup summary, the WARN line of each rejected sign-in and debug diagnostics; never a password,
     * a username, a request path or query, a header value or a client address.
     */
    private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);

    /**
     * Creates the configuration. Spring instantiates it; it holds no state.
     */
    public SecurityConfig() {
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
        RequestMatcher swaggerUi = swaggerUiRequests();

        http
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                // The defaults stay; the Swagger UI matcher and its negation never both match, so each
                // response gets exactly one Content-Security-Policy.
                .headers(headers -> headers
                        .referrerPolicy(referrer -> referrer.policy(SecurityHeaderPolicies.REFERRER_POLICY))
                        .addHeaderWriter(new DelegatingRequestMatcherHeaderWriter(swaggerUi,
                                new ContentSecurityPolicyHeaderWriter(
                                        SecurityHeaderPolicies.SWAGGER_UI_CONTENT_SECURITY_POLICY)))
                        .addHeaderWriter(new DelegatingRequestMatcherHeaderWriter(
                                new NegatedRequestMatcher(swaggerUi),
                                new ContentSecurityPolicyHeaderWriter(
                                        SecurityHeaderPolicies.API_CONTENT_SECURITY_POLICY))))
                // The strict converter turns a Basic header without its space separator, and a
                // password longer than BCrypt verifies, into the same 401 as bad credentials.
                .httpBasic(basic -> basic
                        .authenticationEntryPoint(entryPoint)
                        .withObjectPostProcessor(new ObjectPostProcessor<BasicAuthenticationFilter>() {
                            @Override
                            public <O extends BasicAuthenticationFilter> O postProcess(O filter) {
                                filter.setAuthenticationConverter(new StrictBasicAuthenticationConverter());
                                return filter;
                            }
                        }))
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
     * Matches a request for the Swagger UI, any method: one of {@link #SWAGGER_UI_PATHS}, matched as
     * Spring MVC matches paths.
     *
     * @return a matcher that matches when any of those paths does
     */
    private static RequestMatcher swaggerUiRequests() {
        PathPatternRequestMatcher.Builder pathPatterns = PathPatternRequestMatcher.withDefaults();
        List<RequestMatcher> matchers = new ArrayList<>(SWAGGER_UI_PATHS.size());
        for (String path : SWAGGER_UI_PATHS) {
            matchers.add(pathPatterns.matcher(path));
        }
        return new OrRequestMatcher(matchers);
    }

    /**
     * Installs one {@link ProblemCorsProcessor} on every Spring MVC handler mapping, so a cross-origin
     * request Spring rejects is answered 403 {@code APP0403} problem+json whichever mapping found its
     * handler: the controllers, the Actuator endpoints, the API docs and the Swagger UI resources alike.
     *
     * <p>The method is static and its only parameter is an {@link ObjectProvider}, so registering the
     * post-processor instantiates neither this configuration nor {@link ProblemFactory}: no bean is
     * created early, and none misses the post-processing of the others. The factory is resolved on the
     * first rejection and then reused.
     *
     * @param problems the factory that builds and writes the 403 problem body, resolved lazily
     * @return the post-processor that sets the processor on each handler mapping
     */
    @Bean
    public static BeanPostProcessor problemCorsProcessorInstaller(ObjectProvider<ProblemFactory> problems) {
        return new CorsProcessorInstaller(
                new ProblemCorsProcessor(SingletonSupplier.of(problems::getObject)));
    }

    /**
     * Answers an unauthenticated request with 401 {@code APP0401} problem+json, adding the Basic
     * challenge only for clients that are not the SPA.
     *
     * <p>Invoked by the Basic authentication filter when credentials are malformed, unknown or wrong,
     * and by the exception translation filter when an anonymous request reaches a protected path. The
     * challenge header is set before the body is written; {@link ProblemFactory#write} keeps it. The
     * body names neither the failure nor the attempted username.
     *
     * <p><b>Logging.</b> Credentials that were sent and rejected, any failure other than
     * {@link InsufficientAuthenticationException} (a {@link BadCredentialsException} from the user store
     * or from {@link StrictBasicAuthenticationConverter} included), write one WARN line carrying the
     * code, the method, whether a challenge was sent and the failure's simple class name, so repeated
     * guessing shows in the operational log. An anonymous request, which the exception translation
     * filter reports as {@link InsufficientAuthenticationException}, is routine for the SPA's sign-in
     * and is logged at debug level only. No line carries the username, the password, the path, the
     * query, a header value or the client address. The entry point counts nothing and delays nothing,
     * as the API keeps no per-caller state; the Compose {@code frontend}'s nginx throttles clients.
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
         * @param authException why authentication failed, logged by type only: at WARN when credentials
         *     were sent and rejected, at debug level for an anonymous request or when absent
         * @throws IOException when writing to the client fails
         */
        @Override
        public void commence(HttpServletRequest request, HttpServletResponse response,
                AuthenticationException authException) throws IOException {
            boolean challenge = request.getHeader(X_REQUESTED_WITH) == null;
            if (challenge) {
                response.setHeader(HttpHeaders.WWW_AUTHENTICATE, BASIC_CHALLENGE);
            }
            if (authException != null && !(authException instanceof InsufficientAuthenticationException)) {
                log.warn("Authentication failed: 401 {} for {} request (challenge sent: {}, cause: {})",
                        CODE_UNAUTHORIZED, request.getMethod(), challenge,
                        authException.getClass().getSimpleName());
            } else if (log.isDebugEnabled()) {
                log.debug("401 {} for {} request (challenge sent: {}, cause: {})", CODE_UNAUTHORIZED,
                        request.getMethod(), challenge,
                        authException == null ? "-" : authException.getClass().getSimpleName());
            }
            problems.write(response, problems.create(HttpStatus.UNAUTHORIZED, CODE_UNAUTHORIZED,
                    List.of(), request.getRequestURI()));
        }
    }

    /**
     * Reads HTTP Basic credentials through Spring's {@link BasicAuthenticationConverter} but rejects,
     * with {@link BadCredentialsException}, two inputs Spring accepts, so the Basic filter answers them
     * 401 {@code APP0401} through {@link ProblemAuthenticationEntryPoint}:
     * <ul>
     *   <li>a header starting with {@code Basic} that does not continue with a space, such as
     *       {@code Basic!<token>} or {@code Basic<TAB><token>}: Spring decodes whatever follows the sixth
     *       character, so a malformed header would still sign in;</li>
     *   <li>a password over {@value UsersProperties#MAX_PASSWORD_BYTES} UTF-8 bytes: BCrypt verifies only
     *       that many, so a longer password would match on its prefix.</li>
     * </ul>
     * Everything else stays Spring's: no header or another scheme yields {@code null} and the request
     * stays anonymous, the scheme name is case-insensitive, and an empty, undecodable or colon-less
     * token is rejected. No exception message carries the username or the password. The only state is
     * the stateless delegate, so one instance serves every request.
     */
    static final class StrictBasicAuthenticationConverter implements AuthenticationConverter {

        /** The scheme name, matched case-insensitively. */
        private static final String SCHEME = "Basic";

        /** Decodes the token as UTF-8 and attaches the web authentication details. */
        private final BasicAuthenticationConverter delegate;

        /** Creates the converter over Spring's default Basic decoding. */
        StrictBasicAuthenticationConverter() {
            this.delegate = new BasicAuthenticationConverter();
        }

        /**
         * Reads the Basic credentials of one request.
         *
         * @param request the request
         * @return the unauthenticated username and password, or {@code null} when the request carries no
         *     {@code Authorization} header or one of another scheme
         * @throws BadCredentialsException when the header is Basic but malformed, or the password is
         *     longer than {@value UsersProperties#MAX_PASSWORD_BYTES} UTF-8 bytes
         */
        @Override
        @Nullable
        public UsernamePasswordAuthenticationToken convert(HttpServletRequest request) {
            String header = request.getHeader(HttpHeaders.AUTHORIZATION);
            if (header == null) {
                return null;
            }
            String trimmed = header.trim();
            if (!trimmed.regionMatches(true, 0, SCHEME, 0, SCHEME.length())) {
                return null;
            }
            if (trimmed.length() > SCHEME.length() && trimmed.charAt(SCHEME.length()) != ' ') {
                throw new BadCredentialsException("Basic authentication scheme is not followed by a space");
            }
            UsernamePasswordAuthenticationToken token = delegate.convert(request);
            if (token != null && token.getCredentials() instanceof String password
                    && password.getBytes(StandardCharsets.UTF_8).length > UsersProperties.MAX_PASSWORD_BYTES) {
                throw new BadCredentialsException("Basic authentication password is too long for BCrypt");
            }
            return token;
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

    /**
     * Decides cross-origin requests exactly as Spring's {@link DefaultCorsProcessor} does, but answers
     * each rejection with 403 {@code APP0403} problem+json instead of the plain-text body
     * {@code Invalid CORS request}.
     *
     * <p>Every decision stays the default one. {@code Vary: Origin}, {@code Access-Control-Request-Method}
     * and {@code Access-Control-Request-Headers} are added to each response the processor sees. A
     * request without an {@code Origin} header, or from the API's own origin, passes untouched. With no
     * CORS configuration, which this application never declares, a cross-origin preflight is rejected and
     * any other cross-origin request passes without an {@code Access-Control-Allow-*} header. A request
     * whose {@code Origin} cannot be parsed is rejected as well.
     *
     * <p>Only the rejection's body changes. {@link #rejectRequest(ServerHttpResponse)} receives no
     * request, so it keeps the 403 status and writes nothing; {@link #processRequest} learns of the
     * rejection from the {@code false} the default processor returns for it, and then writes the problem
     * with the request path as {@code instance}. The headers already set, the {@code Vary} and security
     * headers included, are kept. The body names neither the origin nor the requested method or headers.
     *
     * <p>One instance serves every handler mapping and request thread; it holds only the supplier of the
     * factory.
     */
    static final class ProblemCorsProcessor extends DefaultCorsProcessor {

        /** Supplies the factory that builds and writes the problem body; called only on a rejection. */
        private final Supplier<ProblemFactory> problems;

        /**
         * Creates the processor.
         *
         * @param problems supplies the factory that builds and writes the problem body
         * @throws NullPointerException when {@code problems} is {@code null}
         */
        ProblemCorsProcessor(Supplier<ProblemFactory> problems) {
            this.problems = Objects.requireNonNull(problems, "problems");
        }

        /**
         * Applies the default CORS decision and, when it rejects the request, writes the 403 response.
         *
         * @param config the CORS configuration of the matched handler; {@code null} when it has none,
         *     which is always the case here
         * @param request the request being checked
         * @param response the response, still uncommitted when the request is rejected
         * @return {@code false} when the request was rejected and answered, {@code true} when it may
         *     proceed
         * @throws IOException when writing to the client fails
         * @throws NullPointerException when the supplier yields no factory
         */
        @Override
        public boolean processRequest(@Nullable CorsConfiguration config, HttpServletRequest request,
                HttpServletResponse response) throws IOException {
            if (super.processRequest(config, request, response)) {
                return true;
            }
            log.debug("403 {} for rejected CORS {} request", CODE_FORBIDDEN, request.getMethod());
            ProblemFactory factory = Objects.requireNonNull(problems.get(), "problems");
            factory.write(response, factory.create(HttpStatus.FORBIDDEN, CODE_FORBIDDEN,
                    List.of(), request.getRequestURI()));
            return false;
        }

        /**
         * Keeps the default rejection status, 403, and leaves the body to {@link #processRequest},
         * which knows the request path. Nothing is written or flushed, so the response stays
         * uncommitted for the problem body.
         *
         * @param response the response of the rejected request
         */
        @Override
        protected void rejectRequest(ServerHttpResponse response) {
            response.setStatusCode(HttpStatus.FORBIDDEN);
        }
    }

    /**
     * Sets the shared {@link ProblemCorsProcessor} on each {@link AbstractHandlerMapping} bean before it
     * is initialized, replacing the {@link DefaultCorsProcessor} every mapping creates for itself. Every
     * other bean passes through unchanged, and no bean is wrapped or replaced.
     */
    static final class CorsProcessorInstaller implements BeanPostProcessor {

        /** The processor every handler mapping shares. */
        private final ProblemCorsProcessor processor;

        /**
         * Creates the post-processor.
         *
         * @param processor the processor to install on every handler mapping
         * @throws NullPointerException when {@code processor} is {@code null}
         */
        CorsProcessorInstaller(ProblemCorsProcessor processor) {
            this.processor = Objects.requireNonNull(processor, "processor");
        }

        /**
         * Installs the processor when {@code bean} is a handler mapping.
         *
         * @param bean the new bean instance, before its initialization callbacks
         * @param beanName the name of the bean
         * @return {@code bean} itself
         */
        @Override
        public Object postProcessBeforeInitialization(Object bean, String beanName) {
            if (bean instanceof AbstractHandlerMapping mapping) {
                mapping.setCorsProcessor(processor);
            }
            return bean;
        }
    }
}
