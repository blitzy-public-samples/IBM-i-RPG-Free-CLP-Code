package com.democorp.customermaster.controller;

import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy;

/**
 * The browser security policies the web API sends itself, in addition to Spring Security's default
 * headers: the two {@code Content-Security-Policy} values and the {@code Referrer-Policy}.
 *
 * <p><b>Who sends them.</b> {@code SecurityConfig}'s filter chain adds them to every response it handles:
 * {@link #SWAGGER_UI_CONTENT_SECURITY_POLICY} to the Swagger UI pages and resources, and
 * {@link #API_CONTENT_SECURITY_POLICY} to everything else, so each response carries exactly one policy.
 * {@link ProblemErrorReportValve} adds {@link #API_CONTENT_SECURITY_POLICY} and {@link #REFERRER_POLICY}
 * to the problem bodies of requests Tomcat's connector rejects, which never pass that chain. Both read
 * the values here, so the direct API port and the error-report valve can never drift apart.
 *
 * <p><b>Why in this package.</b> The security package already depends on this one for
 * {@link ProblemFactory}; keeping the policies here lets the valve use them without this package
 * depending on the security package.
 *
 * <p><b>Behind nginx.</b> The Compose {@code frontend} adds its own headers to an {@code /api/} answer
 * only where the API sent none, so the API's values reach the browser unchanged there too.
 */
public final class SecurityHeaderPolicies {

    /**
     * The policy of every response that is not part of the Swagger UI: the JSON and problem+json bodies of
     * the API, the Actuator and the OpenAPI document. Such a body is never a document a browser should
     * render, so it may load nothing ({@code default-src 'none'}) and no page may frame it
     * ({@code frame-ancestors 'none'}, which {@code default-src} does not cover).
     */
    public static final String API_CONTENT_SECURITY_POLICY = "default-src 'none'; frame-ancestors 'none'";

    /**
     * The policy of the Swagger UI under {@code /swagger-ui/**} and {@code /swagger-ui.html}: same-origin
     * scripts, styles, images and calls, plus {@code data:} images and one inline stylesheet known by its
     * hash, and nothing else. It is the policy the Compose {@code frontend} gives the SPA, with that hash
     * added.
     *
     * <p>Each source is what springdoc 2.9.1 and its swagger-ui 5 webjar load:
     * <ul>
     *   <li>{@code script-src 'self'}: {@code index.html} loads {@code swagger-ui-bundle.js},
     *       {@code swagger-ui-standalone-preset.js} and {@code swagger-initializer.js}, into which springdoc
     *       writes the {@code configUrl}; {@code oauth2-redirect.html} loads {@code oauth2-redirect.js}.
     *       None of these pages has an inline script, and the bundle builds a function from a string only
     *       as a fallback for an environment without {@code globalThis}, {@code self} or a native
     *       {@code Function.prototype.bind}, so neither {@code 'unsafe-inline'} nor {@code 'unsafe-eval'}
     *       is needed.</li>
     *   <li>{@code style-src 'self'}: {@code swagger-ui.css} and {@code index.css}. The one inline
     *       stylesheet is the Swagger logo's in the standalone layout's top bar:
     *       {@code swagger-ui-standalone-preset.js} renders it as an SVG {@code <style>} element holding
     *       {@code .logo_small_svg__cls-2{fill:#fff}.logo_small_svg__cls-3{fill:#85ea2d}}. Its SHA-256
     *       hash admits exactly that text and no other inline style; without it Chrome reports a
     *       {@code style-src} violation on every load and draws the logo black on the dark bar. When an
     *       upgrade changes that text, its new hash replaces this one. No page has a {@code style}
     *       attribute; React sets element styles through the CSS object model, which the policy does
     *       not restrict. The only {@code style} attribute the bundle can emit is the column alignment of
     *       a Markdown table in a description, and this API's descriptions have none.</li>
     *   <li>{@code img-src 'self' data:}: the favicons, and the icons {@code swagger-ui.css} embeds as
     *       {@code data:} URIs. The validator badge, an image from {@code validator.swagger.io}, never
     *       renders, because springdoc sets {@code validatorUrl} to empty.</li>
     *   <li>{@code connect-src 'self'}: {@code /v3/api-docs/swagger-config}, {@code /v3/api-docs} and the
     *       "Try it out" calls, all to this origin.</li>
     *   <li>{@code form-action 'self'}, {@code base-uri 'none'}, {@code object-src 'none'} and
     *       {@code frame-ancestors 'none'}: the UI submits no form elsewhere, sets no base URL, embeds no
     *       plugin and is never framed.</li>
     * </ul>
     * Everything else stays under {@code default-src 'none'}: no font, frame, worker or {@code blob:}
     * image is loaded, the last being needed only to preview an image response, which this API never
     * sends.
     */
    public static final String SWAGGER_UI_CONTENT_SECURITY_POLICY = "default-src 'none'; script-src 'self'; "
            + "style-src 'self' 'sha256-RL3ie0nH+Lzz2YNqQN83mnU0J1ot4QL7b99vMdIX99w='; img-src 'self' data:; "
            + "connect-src 'self'; object-src 'none'; base-uri 'none'; form-action 'self'; "
            + "frame-ancestors 'none'";

    /**
     * The {@code Referrer-Policy} of every response: {@code no-referrer}, so a page of this origin, the
     * Swagger UI included, never sends its URL to the resources it loads or the links it follows.
     */
    public static final ReferrerPolicy REFERRER_POLICY = ReferrerPolicy.NO_REFERRER;

    /** Never called: the class holds constants only. */
    private SecurityHeaderPolicies() {
        throw new AssertionError("SecurityHeaderPolicies holds constants only and is never instantiated");
    }
}
