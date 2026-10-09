package com.democorp.customermaster.controller;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Registers {@link ParameterParseFailureInterceptor} for every handler mapping Spring MVC configures,
 * the controllers, the static resources and the springdoc endpoints alike, so no handler binds the
 * parameters of a request whose parameters Tomcat failed to parse.
 *
 * <p>Registration through {@link WebMvcConfigurer} keeps Spring Boot's MVC auto-configuration in
 * place, and a handler interceptor, unlike a servlet filter, runs after the security filter chain and
 * hands its exception to the same {@code HandlerExceptionResolver} chain, and with it to
 * {@link ApiExceptionHandler}, as a handler does.
 *
 * <p>The web-application condition keeps it out of the generator's non-web context. It holds no
 * state.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class ParameterParseFailureWebConfig implements WebMvcConfigurer {

    /** Creates the configuration. It holds no state. */
    public ParameterParseFailureWebConfig() {
        // Stateless: the interceptor is created when the registry asks for it.
    }

    /**
     * Adds the parameter parse check to every request Spring MVC dispatches.
     *
     * @param registry the interceptor registry of the MVC configuration
     */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new ParameterParseFailureInterceptor());
    }
}
