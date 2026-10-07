package com.democorp.customermaster.config;

import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.type.LogicalType;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Makes the application's Jackson mapper bind a {@code String} only from a JSON string or
 * {@code null}: a JSON number or boolean sent for a text property is rejected instead of being
 * converted to its text.
 *
 * <p><b>Why a class and not a property.</b> {@code spring.jackson.mapper.allow-coercion-of-scalars:
 * false} in {@code application.yml} stops one direction only, a JSON string read as a number or
 * boolean (a {@code version} of {@code "5"}), because Jackson applies that switch to numeric, boolean
 * and date targets alone. For text targets ({@link LogicalType#Textual}) Jackson keeps its default
 * action, {@link CoercionAction#TryConvert}, so {@code {"name":123}} would bind {@code "123"},
 * {@code 1.5} {@code "1.5"} and {@code true} {@code "true"}, and an add would store them. Spring Boot
 * offers no {@code spring.jackson} property for Jackson's coercion settings, so this class sets them
 * on the mapper itself.
 *
 * <p><b>What a client sees.</b> The nine text fields of {@code CustomerFields},
 * {@code ReviewRequest} and {@code CustomerUpdateRequest} are the application's only {@code String}
 * properties of a JSON request body. A JSON number, integral or not, or a boolean sent for one of them
 * now fails deserialization with Jackson's {@code InvalidFormatException}, a
 * {@code MismatchedInputException} whose path names the property. Spring reports it as
 * {@code HttpMessageNotReadableException}, which {@code ApiExceptionHandler} answers with 400
 * APP0400 "Request is not valid: name has an invalid value" and one {@code errors[]} entry on that
 * property, before any service runs: nothing is stored and no customer id is allocated.
 *
 * <p><b>What does not change.</b>
 * <ul>
 *   <li>A JSON string binds as sent. An empty string ({@link CoercionInputShape#EmptyString}) is
 *       left at Jackson's default and stays {@code ""}, so a blank field still reaches
 *       {@code CustomerValidator} as a business-rule failure (422 DEM0501, DEM0502 or DEM0503).</li>
 *   <li>JSON {@code null} and an absent member still bind {@code null}, so an add or {@code ADD}
 *       review without {@code active} still defaults it to {@code Y}.</li>
 *   <li>An object or an array sent for a text property already failed, and still does.</li>
 *   <li>Numeric and boolean targets, such as {@code version}, keep the {@code spring.jackson}
 *       settings of {@code application.yml}; only the text target is configured here.</li>
 *   <li>Serialization and the springdoc schema are untouched: coercion settings apply only when
 *       a value is read.</li>
 * </ul>
 *
 * <p><b>How it is applied.</b> The bean is a {@link Jackson2ObjectMapperBuilderCustomizer}, which
 * Spring Boot applies to the {@code Jackson2ObjectMapperBuilder} it builds the application's
 * {@code ObjectMapper} from. The settings are registered through
 * {@code Jackson2ObjectMapperBuilder.postConfigurer}, which runs on each mapper the builder
 * creates, after the {@code spring.jackson} properties have been applied. The MVC message converter
 * that reads request bodies uses that mapper.
 *
 * <p>The class carries no profile or condition: the bean exists in the web context, in the
 * {@code test} profile and in the {@code generator} profile, which reads no request body. It is
 * found by component scanning from {@code CustomerMasterApplication}.
 *
 * <p>Example:
 * <pre>{@code
 * // POST /api/customers {"name":123,"addr":"1 main st", ...}
 * // 400 {"code":"APP0400","detail":"Request is not valid: name has an invalid value",
 * //      "errors":[{"field":"name","code":"APP0400",
 * //                 "message":"Request is not valid: name has an invalid value"}], ...}
 * }</pre>
 */
@Configuration(proxyBeanMethods = false)
public class StrictJsonBindingConfig {

    /**
     * Creates the configuration. It holds no state.
     */
    public StrictJsonBindingConfig() {
        // Stateless: the bean method builds the customizer from constants only.
    }

    /**
     * The customizer that makes a JSON integral number, fractional number or boolean fail
     * ({@link CoercionAction#Fail}) wherever the mapper binds text ({@link LogicalType#Textual}).
     * Every other coercion keeps the mapper's configuration. The bean name is
     * {@code strictTextualCoercionCustomizer}.
     *
     * @return the customizer
     */
    @Bean
    public Jackson2ObjectMapperBuilderCustomizer strictTextualCoercionCustomizer() {
        return builder -> builder.postConfigurer(mapper -> mapper.coercionConfigFor(LogicalType.Textual)
                .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail));
    }
}
