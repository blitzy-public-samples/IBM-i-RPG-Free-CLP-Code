package com.democorp.customermaster.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.democorp.customermaster.controller.ProblemFactory;
import com.democorp.customermaster.messages.MessageCatalog;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.beans.factory.support.BeanDefinitionBuilder;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.json.ProblemDetailJacksonMixin;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsProcessor;
import org.springframework.web.servlet.handler.AbstractHandlerMapping;
import org.springframework.web.servlet.handler.SimpleUrlHandlerMapping;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * Specifies {@link SecurityConfig.ProblemCorsProcessor}, the CORS processor of every Spring MVC handler
 * mapping, and {@link SecurityConfig#problemCorsProcessorInstaller(ObjectProvider)}, which installs it.
 *
 * <p><b>Rejections.</b> A cross-origin preflight without CORS configuration, a request whose
 * {@code Origin} cannot be parsed, and a preflight a configuration refuses are each answered 403
 * {@code APP0403} {@code application/problem+json} with the request path as {@code instance}, never
 * Spring's plain-text {@code Invalid CORS request}. The {@code Vary} headers and headers set earlier
 * survive; no {@code Access-Control-*} header is added, and the body echoes no origin.
 *
 * <p><b>Decisions kept.</b> A request without {@code Origin}, a same-origin preflight and a
 * cross-origin request that is not a preflight pass exactly as with Spring's default processor; a
 * configuration that allows the origin is still honoured, although the application declares none.
 *
 * <p><b>Installation.</b> The installer sets one shared processor on every handler mapping, passes other
 * beans through, and resolves {@link ProblemFactory} only when a request is first rejected. Outside a
 * servlet web application, as in the generator profile, neither {@code SecurityConfig} nor the
 * installer exists.
 *
 * <p>Pure JUnit 5 and AssertJ over Spring's servlet mocks and an {@link ApplicationContextRunner}: no
 * web server, no database, no Docker. The
 * factory is the real one, with the real catalog and a mapper carrying Spring's {@code ProblemDetail}
 * mixin, as Boot configures it.
 */
@DisplayName("ProblemCorsProcessor: rejected cross-origin requests answer 403 APP0403 problem+json")
final class ProblemCorsProcessorTest {

    /** An origin other than the mock request's own {@code http://localhost}. */
    private static final String FOREIGN_ORIGIN = "https://example.org";

    /** The mock request's own origin: scheme {@code http}, host {@code localhost}, default port 80. */
    private static final String OWN_ORIGIN = "http://localhost";

    /** The request path, expected back as {@code instance}. */
    private static final String PATH = "/api/customers";

    /** The {@code Vary} values Spring's default processor adds to every response it sees. */
    private static final List<String> VARY = List.of(HttpHeaders.ORIGIN,
            HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS);

    /** The name prefix of every CORS response header. */
    private static final String CORS_HEADER_PREFIX = "Access-Control-";

    /** The members of the 403 body, in order. */
    private static final List<String> MEMBERS =
            List.of("type", "title", "status", "detail", "instance", "code", "args");

    /** A mapper carrying Spring's {@code ProblemDetail} mixin, so properties are top-level members. */
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .addMixIn(ProblemDetail.class, ProblemDetailJacksonMixin.class);

    /** The real factory, with the real catalog. */
    private final ProblemFactory problems = new ProblemFactory(new MessageCatalog(), MAPPER);

    /** Counts how often the processor asks for the factory. */
    private final AtomicInteger factoryLookups = new AtomicInteger();

    /** The processor under test. */
    private final SecurityConfig.ProblemCorsProcessor processor = new SecurityConfig.ProblemCorsProcessor(
            () -> {
                factoryLookups.incrementAndGet();
                return problems;
            });

    @Test
    void crossOriginPreflightWithoutConfigurationIsAnswered403App0403ProblemJson() throws IOException {
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setHeader("X-Frame-Options", "DENY");
        response.setHeader("X-Content-Type-Options", "nosniff");

        boolean accepted = processor.processRequest(null, preflight(FOREIGN_ORIGIN), response);

        assertThat(accepted).isFalse();
        assertForbiddenProblem(response);
        assertThat(response.getHeader("X-Frame-Options")).isEqualTo("DENY");
        assertThat(response.getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(factoryLookups).hasValue(1);
    }

    @ParameterizedTest(name = "Origin <{0}>")
    @ValueSource(strings = {"http://example.org:abc", "http://exa mple.org"})
    void requestWithAnUnparsableOriginIsAnswered403App0403ProblemJson(String origin) throws IOException {
        MockHttpServletRequest request = new MockHttpServletRequest(HttpMethod.GET.name(), PATH);
        request.addHeader(HttpHeaders.ORIGIN, origin);
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean accepted = processor.processRequest(null, request, response);

        assertThat(accepted).isFalse();
        assertForbiddenProblem(response);
        assertThat(response.getContentAsString()).doesNotContain(origin);
    }

    @Test
    void preflightRefusedByAConfigurationIsAnswered403App0403ProblemJson() throws IOException {
        CorsConfiguration config = new CorsConfiguration();
        config.addAllowedOrigin("https://allowed.example");
        config.addAllowedMethod(HttpMethod.GET);
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean accepted = processor.processRequest(config, preflight(FOREIGN_ORIGIN), response);

        assertThat(accepted).isFalse();
        assertForbiddenProblem(response);
    }

    @Test
    void sameOriginPreflightPassesUntouched() throws IOException {
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean accepted = processor.processRequest(null, preflight(OWN_ORIGIN), response);

        assertThat(accepted).isTrue();
        assertPassedUntouched(response);
    }

    @Test
    void requestWithoutOriginPassesUntouched() throws IOException {
        MockHttpServletRequest request = new MockHttpServletRequest(HttpMethod.OPTIONS.name(), PATH);
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean accepted = processor.processRequest(null, request, response);

        assertThat(accepted).isTrue();
        assertPassedUntouched(response);
    }

    @Test
    void crossOriginRequestThatIsNotAPreflightPassesWithoutAnyGrant() throws IOException {
        MockHttpServletRequest request = new MockHttpServletRequest(HttpMethod.GET.name(), PATH);
        request.addHeader(HttpHeaders.ORIGIN, FOREIGN_ORIGIN);
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean accepted = processor.processRequest(null, request, response);

        assertThat(accepted).isTrue();
        assertPassedUntouched(response);
    }

    @Test
    void originAllowedByAConfigurationIsStillGrantedAsByTheDefaultProcessor() throws IOException {
        CorsConfiguration config = new CorsConfiguration();
        config.addAllowedOrigin(FOREIGN_ORIGIN);
        config.addAllowedMethod(HttpMethod.GET);
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean accepted = processor.processRequest(config, preflight(FOREIGN_ORIGIN), response);

        assertThat(accepted).isTrue();
        assertThat(response.getStatus()).isEqualTo(HttpStatus.OK.value());
        assertThat(response.getHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN)).isEqualTo(FOREIGN_ORIGIN);
        assertThat(response.getContentAsByteArray()).isEmpty();
        assertThat(factoryLookups).hasValue(0);
    }

    @Test
    void rejectsAMissingFactorySupplier() {
        assertThatNullPointerException()
                .isThrownBy(() -> new SecurityConfig.ProblemCorsProcessor(null))
                .withMessage("problems");
    }

    @Test
    void installerSetsOneSharedProcessorOnEveryHandlerMappingAndPassesOtherBeansThrough() {
        BeanPostProcessor installer =
                SecurityConfig.problemCorsProcessorInstaller(provider(new AtomicInteger()));
        AbstractHandlerMapping requestMappings = new RequestMappingHandlerMapping();
        AbstractHandlerMapping urlMappings = new SimpleUrlHandlerMapping();
        Object other = new Object();

        assertThat(installer.postProcessBeforeInitialization(requestMappings, "requestMappingHandlerMapping"))
                .isSameAs(requestMappings);
        assertThat(installer.postProcessBeforeInitialization(urlMappings, "resourceHandlerMapping"))
                .isSameAs(urlMappings);
        assertThat(installer.postProcessBeforeInitialization(other, "other")).isSameAs(other);
        assertThat(installer.postProcessAfterInitialization(other, "other")).isSameAs(other);

        assertThat(requestMappings.getCorsProcessor())
                .isInstanceOf(SecurityConfig.ProblemCorsProcessor.class)
                .isSameAs(urlMappings.getCorsProcessor());
    }

    @Test
    void installerResolvesTheFactoryOnlyOnTheFirstRejection() throws IOException {
        AtomicInteger created = new AtomicInteger();
        BeanPostProcessor installer = SecurityConfig.problemCorsProcessorInstaller(provider(created));
        AbstractHandlerMapping mapping = new SimpleUrlHandlerMapping();
        installer.postProcessBeforeInitialization(mapping, "resourceHandlerMapping");
        CorsProcessor installed = mapping.getCorsProcessor();

        assertThat(installed.processRequest(null, preflight(OWN_ORIGIN), new MockHttpServletResponse()))
                .isTrue();
        assertThat(created).as("no rejection yet, so no factory").hasValue(0);

        MockHttpServletResponse first = new MockHttpServletResponse();
        MockHttpServletResponse second = new MockHttpServletResponse();
        assertThat(installed.processRequest(null, preflight(FOREIGN_ORIGIN), first)).isFalse();
        assertThat(installed.processRequest(null, preflight(FOREIGN_ORIGIN), second)).isFalse();

        assertThat(created).as("the factory is resolved once and reused").hasValue(1);
        assertForbiddenProblem(first);
        assertForbiddenProblem(second);
    }

    @Test
    void installerIsAbsentOutsideAServletWebApplicationAsInTheGeneratorProfile() {
        new ApplicationContextRunner()
                .withUserConfiguration(SecurityConfig.class)
                .run(context -> assertThat(context)
                        .hasNotFailed()
                        .doesNotHaveBean(SecurityConfig.class)
                        .doesNotHaveBean("problemCorsProcessorInstaller")
                        .doesNotHaveBean(SecurityConfig.CorsProcessorInstaller.class));
    }

    /**
     * Builds a cross-origin-style preflight: {@code OPTIONS} with {@code Origin} and
     * {@code Access-Control-Request-Method: GET}.
     *
     * @param origin the {@code Origin} header value
     * @return the request for {@value #PATH}
     */
    private static MockHttpServletRequest preflight(String origin) {
        MockHttpServletRequest request = new MockHttpServletRequest(HttpMethod.OPTIONS.name(), PATH);
        request.addHeader(HttpHeaders.ORIGIN, origin);
        request.addHeader(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, HttpMethod.GET.name());
        return request;
    }

    /**
     * Returns a provider of a {@link ProblemFactory} bean whose creation is counted, as the
     * application context hands the installer one.
     *
     * @param created incremented each time the bean is created
     * @return the provider
     */
    private ObjectProvider<ProblemFactory> provider(AtomicInteger created) {
        DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();
        Supplier<ProblemFactory> creation = () -> {
            created.incrementAndGet();
            return new ProblemFactory(new MessageCatalog(), MAPPER);
        };
        beanFactory.registerBeanDefinition("problemFactory",
                BeanDefinitionBuilder.genericBeanDefinition(ProblemFactory.class, creation)
                        .getBeanDefinition());
        return beanFactory.getBeanProvider(ProblemFactory.class);
    }

    /**
     * Asserts the 403 {@code APP0403} problem response of a rejected request for {@value #PATH}.
     *
     * @param response the response the processor wrote
     * @throws IOException if the body is not JSON
     */
    private static void assertForbiddenProblem(MockHttpServletResponse response) throws IOException {
        assertThat(response.getStatus()).isEqualTo(HttpStatus.FORBIDDEN.value());
        assertThat(response.getContentType()).isEqualTo("application/problem+json;charset=UTF-8");
        assertThat(response.getContentLength()).isEqualTo(response.getContentAsByteArray().length);
        JsonNode body = MAPPER.readTree(response.getContentAsByteArray());
        assertThat(body.properties()).extracting(Map.Entry::getKey).containsExactlyElementsOf(MEMBERS);
        assertThat(body.path("type").asText()).isEqualTo("urn:customer-master:problem:APP0403");
        assertThat(body.path("title").asText()).isEqualTo("Forbidden");
        assertThat(body.path("status").asInt()).isEqualTo(403);
        assertThat(body.path("detail").asText()).isEqualTo("You are not authorized to perform this action.");
        assertThat(body.path("instance").asText()).isEqualTo(PATH);
        assertThat(body.path("code").asText()).isEqualTo("APP0403");
        assertThat(body.path("args").isArray()).isTrue();
        assertThat(body.path("args")).isEmpty();
        assertThat(response.getContentAsString())
                .doesNotContain("Invalid CORS request")
                .doesNotContain("example.org");
        assertThat(response.getHeaders(HttpHeaders.VARY)).containsExactlyElementsOf(VARY);
        assertNoAccessControlHeader(response);
    }

    /**
     * Asserts a response the processor let through: status 200, no body, no content type, the
     * default {@code Vary} headers and no {@code Access-Control-*} header.
     *
     * @param response the response after processing
     */
    private void assertPassedUntouched(MockHttpServletResponse response) {
        assertThat(response.getStatus()).isEqualTo(HttpStatus.OK.value());
        assertThat(response.getContentAsByteArray()).isEmpty();
        assertThat(response.getContentType()).isNull();
        assertThat(response.isCommitted()).isFalse();
        assertThat(response.getHeaders(HttpHeaders.VARY)).containsExactlyElementsOf(VARY);
        assertNoAccessControlHeader(response);
        assertThat(factoryLookups).hasValue(0);
    }

    /**
     * Asserts that no CORS response header was written, so nothing was granted.
     *
     * @param response the response after processing
     */
    private static void assertNoAccessControlHeader(MockHttpServletResponse response) {
        assertThat(response.getHeaderNames())
                .noneMatch(name -> name.regionMatches(
                        true, 0, CORS_HEADER_PREFIX, 0, CORS_HEADER_PREFIX.length()));
    }
}
