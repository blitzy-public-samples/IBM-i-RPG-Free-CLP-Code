package com.democorp.customermaster.controller;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.ServletException;
import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.catalina.connector.Request;
import org.apache.catalina.connector.Response;
import org.apache.catalina.valves.ErrorReportValve;
import org.apache.coyote.ActionCode;
import org.apache.tomcat.util.ExceptionUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ProblemDetail;
import org.springframework.lang.Nullable;
import org.springframework.security.web.header.HeaderWriter;
import org.springframework.security.web.header.writers.CacheControlHeadersWriter;
import org.springframework.security.web.header.writers.ContentSecurityPolicyHeaderWriter;
import org.springframework.security.web.header.writers.HstsHeaderWriter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.security.web.header.writers.XContentTypeOptionsHeaderWriter;
import org.springframework.security.web.header.writers.XXssProtectionHeaderWriter;
import org.springframework.security.web.header.writers.frameoptions.XFrameOptionsHeaderWriter;
import org.springframework.security.web.header.writers.frameoptions.XFrameOptionsHeaderWriter.XFrameOptionsMode;

/**
 * Tomcat's error-report valve for this application: it answers every error the servlet container reports
 * itself with the same {@code application/problem+json} body {@link ProblemErrorController} sends, instead
 * of Tomcat's HTML "HTTP Status 400 – Bad Request" page or an empty body.
 *
 * <p><b>Why it exists.</b> Some requests are rejected by Tomcat's connector before any filter, servlet or
 * Spring component runs, so they never reach the ERROR dispatch to {@code /error}:
 * <ul>
 *   <li>a request line or header block over the connector's {@code max-http-request-header-size} (8 KB),
 *       such as a 9,000-character {@code cursor}, {@code name} or {@code nameContains}, or a 20 KB
 *       header;</li>
 *   <li>a request target with a character the HTTP grammar forbids, such as a raw <code>|</code> or
 *       <code>&#123;</code>, and a method that is not a valid token, such as {@code GE(T};</li>
 *   <li>a path Tomcat refuses to decode or normalize: an encoded {@code /} ({@code %2F}), {@code \}
 *       ({@code %5C}) or NUL ({@code %00}), or an invalid escape such as {@code %ZZ} or {@code %G1};</li>
 *   <li>{@code TRACE}, which the connector answers 405 with an {@code Allow} header.</li>
 * </ul>
 * Each of them has already marked the response as in error when the request enters the host's pipeline.
 * A path rejection also leaves the request without a context, so the host never forwards it to
 * {@code /error}; a {@code TRACE} keeps its context, and its forward to {@code /error} would itself be
 * rejected by the security firewall, which refuses the {@code TRACE} method, leaving an empty 400.
 *
 * <p><b>What it does.</b> A request whose response is already in error when this valve is entered, and
 * which is not an asynchronous dispatch, was rejected by the connector: no application code has run and
 * none should, so the valve reports the error at once without passing the request on. Every other request
 * goes down the pipeline as with Tomcat's own valve, and an error still unreported when it returns, with
 * nothing written yet, is reported the same way; errors the application answers itself, through
 * {@code /error}, the MVC exception handler or the security handlers, have their body written by then and
 * are left alone.
 *
 * <p><b>The body.</b> It comes from {@link ProblemErrorController#problemFor(Object, Throwable, String)},
 * the single status map of the ERROR dispatch, so a connector rejection is answered exactly as a
 * {@code sendError} of the same status would be: a 4xx keeps its status with {@code APP0400} and the
 * reason that map gives it ("Request is not valid: bad request", or "method not allowed" for the 405 of
 * {@code TRACE}), 401 and 403 become {@code APP0401} and {@code APP0403}, and anything else becomes 500
 * {@code DEM9999} with an {@code errorId} logged at ERROR. {@code instance} is the request path as the
 * connector read it, and is left out when there is none or it is not a usable URI. The body is written by
 * {@link ProblemFactory#write}, which keeps headers already set, such as the connector's {@code Allow}. No
 * server name, version, exception message or class name reaches the body or the headers.
 *
 * <p><b>Security headers.</b> These responses never pass the security filter chain, so the valve adds the
 * headers that chain adds to every other response, through the same Spring Security writers: its default
 * {@code X-Content-Type-Options: nosniff}, {@code X-XSS-Protection: 0},
 * {@code Cache-Control: no-cache, no-store, max-age=0, must-revalidate} with {@code Pragma: no-cache} and
 * {@code Expires: 0}, {@code Strict-Transport-Security} on secure requests only, and
 * {@code X-Frame-Options: DENY}, then the chain's own strict
 * {@code Content-Security-Policy} ({@link SecurityHeaderPolicies#API_CONTENT_SECURITY_POLICY}) and
 * {@code Referrer-Policy} ({@link SecurityHeaderPolicies#REFERRER_POLICY}), read from the same constants.
 * The body is problem+json, never a Swagger UI page, so the strict policy applies whatever the path. Each
 * writer leaves a header that is already present unchanged.
 *
 * <p><b>Fail-safe.</b> The controller and the factory are looked up when an error is reported, not when
 * the web server is built, so building the server initializes no application bean early. When either
 * cannot be had, which can happen only while the application context starts or closes, the response keeps
 * its status with no body, never Tomcat's HTML page. The valve never throws: a failure while reporting is
 * logged at DEBUG and the response is left as it stands, as Tomcat's own valve does.
 *
 * <p><b>Installation and thread safety.</b> {@link ProblemErrorReportValveCustomizer} puts this valve in
 * place of every other error-report valve on the host. One instance serves every request thread: its
 * fields are final and thread-safe, and every request is handled with request-local values.
 */
final class ProblemErrorReportValve extends ErrorReportValve {

    /** Lowest status that is an error; anything below is never reported. */
    private static final int MIN_ERROR_STATUS = 400;

    /**
     * The writers of the security headers, in the order of the slots Spring Security's header
     * configuration gives them: its defaults, then the content security and referrer policies the
     * security filter chain adds. Each is stateless once built, so the list is shared by every request.
     */
    private static final List<HeaderWriter> SECURITY_HEADER_WRITERS = List.of(
            new XContentTypeOptionsHeaderWriter(),
            new XXssProtectionHeaderWriter(),
            new CacheControlHeadersWriter(),
            new HstsHeaderWriter(),
            new XFrameOptionsHeaderWriter(XFrameOptionsMode.DENY),
            new ContentSecurityPolicyHeaderWriter(SecurityHeaderPolicies.API_CONTENT_SECURITY_POLICY),
            new ReferrerPolicyHeaderWriter(SecurityHeaderPolicies.REFERRER_POLICY));

    private static final Logger log = LoggerFactory.getLogger(ProblemErrorReportValve.class);

    /** The ERROR dispatch controller, whose status map builds every body; resolved per report. */
    private final ObjectProvider<ProblemErrorController> errorController;

    /** The single writer of problem bodies; resolved per report. */
    private final ObjectProvider<ProblemFactory> problemFactory;

    /**
     * Creates the valve.
     *
     * @param errorController the provider of the controller whose status map builds the body
     * @param problemFactory the provider of the factory that writes the body
     * @throws NullPointerException when either provider is {@code null}
     */
    ProblemErrorReportValve(ObjectProvider<ProblemErrorController> errorController,
            ObjectProvider<ProblemFactory> problemFactory) {
        super();
        this.errorController = Objects.requireNonNull(errorController, "errorController");
        this.problemFactory = Objects.requireNonNull(problemFactory, "problemFactory");
        setShowReport(false);
        setShowServerInfo(false);
    }

    /**
     * Reports a request the connector has already rejected at once, and hands every other request to the
     * rest of the pipeline and Tomcat's error-report handling, which ends in {@link #report}.
     *
     * @param request the request
     * @param response the response
     * @throws IOException when the rest of the pipeline fails with it
     * @throws ServletException when the rest of the pipeline fails with it
     */
    @Override
    public void invoke(Request request, Response response) throws IOException, ServletException {
        if (!response.isError() || request.isAsync()) {
            super.invoke(request, response);
            return;
        }
        // The connector called sendError (or marked the response in error) before the pipeline started,
        // which suspended the response; lift that so the problem body can be written.
        response.setSuspended(false);
        try {
            report(request, response, failureOf(request));
        } catch (Throwable reportFailure) {
            ExceptionUtils.handleThrowable(reportFailure);
            log.debug("Container error report not written", reportFailure);
        }
    }

    /**
     * Writes the problem body of an error the container reports, with the security headers.
     *
     * <p>Like Tomcat's own valve it does nothing for a status below 400, for a response that already has
     * content or whose error was already reported, and when the connection no longer allows I/O.
     *
     * @param request the request
     * @param response the response, whose status is the error to report
     * @param throwable the failure recorded on the request; {@code null} when there is none
     */
    @Override
    protected void report(Request request, Response response, @Nullable Throwable throwable) {
        int status = response.getStatus();
        if (status < MIN_ERROR_STATUS || response.getContentWritten() > 0 || !response.setErrorReported()) {
            return;
        }
        AtomicBoolean ioAllowed = new AtomicBoolean(false);
        response.getCoyoteResponse().action(ActionCode.IS_IO_ALLOWED, ioAllowed);
        if (!ioAllowed.get()) {
            return;
        }
        ProblemErrorController controller = resolve(errorController);
        ProblemFactory factory = resolve(problemFactory);
        if (controller == null || factory == null) {
            log.warn("Error report without body: application context unavailable status={}", status);
            return;
        }
        ProblemDetail problem = controller.problemFor(status, throwable, request.getRequestURI());
        try {
            for (HeaderWriter writer : SECURITY_HEADER_WRITERS) {
                writer.writeHeaders(request, response);
            }
            factory.write(response, problem);
        } catch (IOException | RuntimeException writeFailure) {
            // The client went away or the response can no longer take a body; nothing more can be sent.
            log.debug("Container error report not written status={}", status, writeFailure);
        }
    }

    /**
     * Returns the failure the connector or the container recorded on the request.
     *
     * @param request the request
     * @return the {@link RequestDispatcher#ERROR_EXCEPTION} attribute, or {@code null} when it is absent
     *     or not a {@link Throwable}
     */
    @Nullable
    private static Throwable failureOf(Request request) {
        return request.getAttribute(RequestDispatcher.ERROR_EXCEPTION) instanceof Throwable failure
                ? failure : null;
    }

    /**
     * Looks up an application bean without ever failing.
     *
     * @param provider the bean's provider
     * @param <T> the bean type
     * @return the bean, or {@code null} when it is not defined or cannot be obtained, as while the
     *     application context closes
     */
    @Nullable
    private static <T> T resolve(ObjectProvider<T> provider) {
        try {
            return provider.getIfAvailable();
        } catch (BeansException | IllegalStateException unavailable) {
            log.debug("Error report bean unavailable", unavailable);
            return null;
        }
    }
}
