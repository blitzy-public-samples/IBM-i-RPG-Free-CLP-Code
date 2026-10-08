package com.democorp.customermaster.controller;

import java.util.Objects;
import org.apache.catalina.Container;
import org.apache.catalina.Context;
import org.apache.catalina.Pipeline;
import org.apache.catalina.Valve;
import org.apache.catalina.core.StandardHost;
import org.apache.catalina.valves.ErrorReportValve;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.web.embedded.tomcat.ConfigurableTomcatWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;

/**
 * Replaces the embedded Tomcat host's error-report valve with {@link ProblemErrorReportValve}, so the
 * errors the container reports itself, above all requests its connector rejects before any application
 * code runs, are answered with {@code application/problem+json} rather than Tomcat's HTML page.
 *
 * <p><b>Why a replacement.</b> Spring Boot's own Tomcat customizer adds a plain {@link ErrorReportValve},
 * with the report and server information hidden, to the host's pipeline through a context customizer, and
 * Tomcat's {@code StandardHost} adds a default one when it starts unless a valve of the class named by
 * its {@code errorReportValveClass} is already in the pipeline. Both render the HTML
 * "HTTP Status 400 – Bad Request" page. This customizer therefore registers a context customizer of its
 * own that runs after Boot's ({@link #getOrder()} is {@link Ordered#LOWEST_PRECEDENCE}, Boot's is 0): it
 * removes every {@link ErrorReportValve} from the host's pipeline, names
 * {@link ProblemErrorReportValve} as the host's error-report valve class, and adds one instance of it, so
 * the host starts with exactly that valve.
 *
 * <p><b>Lazy collaborators.</b> The web server is built before the application's singletons, so the
 * valve receives {@link ObjectProvider}s and looks up {@link ProblemErrorController} and
 * {@link ProblemFactory} only when it reports an error; building the server initializes neither.
 *
 * <p>The web-application condition keeps this customizer, and with it the valve, out of the generator's
 * non-web context. It holds only the two providers, so one instance serves the whole application.
 */
@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
final class ProblemErrorReportValveCustomizer
        implements WebServerFactoryCustomizer<ConfigurableTomcatWebServerFactory>, Ordered {

    /** The provider of the controller whose status map builds every body. */
    private final ObjectProvider<ProblemErrorController> errorController;

    /** The provider of the factory that writes every body. */
    private final ObjectProvider<ProblemFactory> problemFactory;

    /**
     * Creates the customizer.
     *
     * @param errorController the provider of the ERROR dispatch controller
     * @param problemFactory the provider of the problem factory
     * @throws NullPointerException when either provider is {@code null}
     */
    ProblemErrorReportValveCustomizer(ObjectProvider<ProblemErrorController> errorController,
            ObjectProvider<ProblemFactory> problemFactory) {
        this.errorController = Objects.requireNonNull(errorController, "errorController");
        this.problemFactory = Objects.requireNonNull(problemFactory, "problemFactory");
    }

    /**
     * Registers the context customizer that installs {@link ProblemErrorReportValve} on the host.
     *
     * @param factory the Tomcat web server factory
     */
    @Override
    public void customize(ConfigurableTomcatWebServerFactory factory) {
        factory.addContextCustomizers(this::installOnHost);
    }

    /**
     * Runs after every Spring Boot customizer, so Boot's valve is already in the host's pipeline.
     *
     * @return {@link Ordered#LOWEST_PRECEDENCE}
     */
    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }

    /**
     * Puts {@link ProblemErrorReportValve} in place of every error-report valve of the context's host.
     *
     * @param context the application's context, already a child of its host
     * @throws IllegalStateException when the context has no parent container
     */
    private void installOnHost(Context context) {
        Container host = context.getParent();
        if (host == null) {
            throw new IllegalStateException("Tomcat context has no host; error-report valve not installed");
        }
        Pipeline pipeline = host.getPipeline();
        for (Valve valve : pipeline.getValves()) {
            if (valve instanceof ErrorReportValve) {
                pipeline.removeValve(valve);
            }
        }
        if (host instanceof StandardHost standardHost) {
            // StandardHost adds its own valve at start unless one of exactly this class is present.
            standardHost.setErrorReportValveClass(ProblemErrorReportValve.class.getName());
        }
        pipeline.addValve(new ProblemErrorReportValve(errorController, problemFactory));
    }
}
