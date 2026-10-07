package com.democorp.customermaster.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.democorp.customermaster.controller.dto.CustomerFields;
import com.democorp.customermaster.controller.dto.CustomerUpdateRequest;
import com.democorp.customermaster.controller.dto.ReviewRequest;
import com.democorp.customermaster.service.CustomerMaintenanceService.Purpose;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * Specifies {@link StrictJsonBindingConfig}: with its customizer, the {@code ObjectMapper} Spring Boot
 * builds through {@code Jackson2ObjectMapperBuilder} binds a {@code String} only from a JSON string or
 * {@code null}, and changes nothing else.
 *
 * <p><b>Scenarios.</b> A JSON integral number, fraction, exponent or boolean sent for any of the nine
 * text fields of {@link CustomerFields}, {@link ReviewRequest} or {@link CustomerUpdateRequest} fails with
 * a {@link MismatchedInputException} whose path is exactly that property, the shape
 * {@code ApiExceptionHandler} answers with 400 APP0400 on the field; an object or array fails the same
 * way. A JSON string binds as sent, the empty string included, and JSON {@code null} and an absent member
 * bind {@code null}. {@code version} keeps the {@code application.yml} rules: an integral number binds, a
 * string, fraction, exponent or boolean fails. An unknown property is still rejected. Without the class,
 * the same mapper binds {@code 123} as {@code "123"}: the defect the class exists to prevent.
 *
 * <p>The runner holds {@link JacksonAutoConfiguration} and, but for the control, the class under test,
 * with the application's own {@code application.yml} loaded by
 * {@link ConfigDataApplicationContextInitializer}, so the {@code spring.jackson} settings are the shipped
 * ones. No web server, database or Docker; this class declares no configuration class or stereotype,
 * because test classes are on the classpath when an {@code ApplicationContextRunner} runs the
 * application's component scan.
 */
@DisplayName("StrictJsonBindingConfig: a String binds only from a JSON string or null")
class StrictJsonBindingConfigTest {

    /** The nine text fields of every customer request body, in JSON order. */
    private static final List<String> TEXT_FIELDS = List.of("name", "addr", "city", "state", "zip",
            "corpPhone", "acctMgr", "acctPhone", "active");

    /** A valid JSON string for each text field. */
    private static final Map<String, String> VALID = Map.of(
            "name", "\"acme inc\"",
            "addr", "\"1 main st\"",
            "city", "\"auburn\"",
            "state", "\"me\"",
            "zip", "\"04210\"",
            "corpPhone", "\"(207) 555-0100\"",
            "acctMgr", "\"jane doe\"",
            "acctPhone", "\"(207) 555-0101\"",
            "active", "\"Y\"");

    /** JSON numbers and booleans, each of which Jackson would otherwise turn into its text. */
    private static final List<String> NON_TEXT_SCALARS = List.of("123", "0", "-1", "1.5", "1e3", "true", "false");

    /** The application's Jackson auto-configuration over the shipped {@code application.yml}. */
    private static final ApplicationContextRunner WITHOUT_CONFIG = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class));

    /** {@link #WITHOUT_CONFIG} plus the class under test, as component scanning registers it. */
    private static final ApplicationContextRunner WITH_CONFIG =
            WITHOUT_CONFIG.withUserConfiguration(StrictJsonBindingConfig.class);

    /**
     * The mapper {@link #WITH_CONFIG} builds, taken once: it stays usable after its context closes, and
     * one context per case would make the 189 field cases slow.
     */
    private static ObjectMapper strictMapper;

    @BeforeAll
    static void buildMapper() {
        strictMapper = mapperOf(WITH_CONFIG);
    }

    /** The three maintenance request bodies around their nine text fields. */
    private enum Body {
        /** {@code POST /api/customers}. */
        ADD(CustomerFields.class, "", ""),
        /** {@code POST /api/customers/review}. */
        REVIEW(ReviewRequest.class, "\"purpose\":\"ADD\",", ""),
        /** {@code PUT /api/customers/{custId}}. */
        UPDATE(CustomerUpdateRequest.class, "", ",\"version\":0");

        /** The record the body binds to. */
        private final Class<?> type;

        /** The members before the text fields, with their trailing comma. */
        private final String before;

        /** The members after the text fields, with their leading comma. */
        private final String after;

        Body(Class<?> type, String before, String after) {
            this.type = type;
            this.before = before;
            this.after = after;
        }

        /**
         * Returns this body with valid text fields, except {@code field}, sent as {@code valueJson}.
         *
         * @param field the field to replace
         * @param valueJson the JSON text sent for it
         * @return the JSON object
         */
        String with(String field, String valueJson) {
            return "{" + before + textMembers(field, valueJson) + after + "}";
        }
    }

    @Test
    @DisplayName("the customizer is a bean named strictTextualCoercionCustomizer")
    void registersTheCustomizer() {
        WITH_CONFIG.run(context -> assertThat(context)
                .hasNotFailed()
                .getBean("strictTextualCoercionCustomizer")
                .isInstanceOf(Jackson2ObjectMapperBuilderCustomizer.class));
    }

    @ParameterizedTest(name = "{0} {1}: {2}")
    @DisplayName("a JSON number or boolean for a text field fails on exactly that field")
    @MethodSource("nonTextScalarPerFieldAndBody")
    void numberOrBooleanForATextFieldFails(Body body, String field, String valueJson) {
        assertFailsOn(body.with(field, valueJson), body.type, field);
    }

    static Stream<Arguments> nonTextScalarPerFieldAndBody() {
        return Stream.of(Body.values()).flatMap(body -> TEXT_FIELDS.stream()
                .flatMap(field -> NON_TEXT_SCALARS.stream().map(value -> Arguments.of(body, field, value))));
    }

    @ParameterizedTest(name = "name {0}")
    @DisplayName("an object or an array for a text field still fails on that field")
    @ValueSource(strings = {"{}", "{\"x\":\"y\"}", "[]", "[\"acme\"]"})
    void objectOrArrayForATextFieldFails(String valueJson) {
        assertFailsOn(Body.ADD.with("name", valueJson), CustomerFields.class, "name");
    }

    @Test
    @DisplayName("a JSON string binds as sent, the empty string and blanks included")
    void stringsBindAsSent() {
        CustomerFields digits = read(strictMapper, Body.ADD.with("name", "\"123\""), CustomerFields.class);
        assertThat(digits.name()).isEqualTo("123");
        assertThat(digits.active()).isEqualTo("Y");
        assertThat(read(strictMapper, Body.ADD.with("name", "\"\""), CustomerFields.class).name()).isEmpty();
        assertThat(read(strictMapper, Body.ADD.with("active", "\" \""), CustomerFields.class).active())
                .isEqualTo(" ");
        assertThat(read(strictMapper, Body.ADD.with("zip", "\" 04210 \""), CustomerFields.class).zip())
                .isEqualTo(" 04210 ");
    }

    @Test
    @DisplayName("JSON null and an absent member bind null")
    void nullAndAbsentBindNull() {
        assertThat(read(strictMapper, Body.REVIEW.with("name", "null"), ReviewRequest.class).name()).isNull();
        assertThat(read(strictMapper, Body.UPDATE.with("active", "null"), CustomerUpdateRequest.class).active())
                .isNull();
        ReviewRequest sparse = read(strictMapper, "{\"purpose\":\"ADD\",\"name\":\"acme inc\"}", ReviewRequest.class);
        assertThat(sparse.purpose()).isEqualTo(Purpose.ADD);
        assertThat(sparse.name()).isEqualTo("acme inc");
        assertThat(sparse.addr()).isNull();
        assertThat(sparse.active()).isNull();
        assertThat(read(strictMapper, "{}", CustomerFields.class))
                .isEqualTo(new CustomerFields(null, null, null, null, null, null, null, null, null));
    }

    @ParameterizedTest(name = "version {0}")
    @DisplayName("an integral version still binds")
    @ValueSource(longs = {0L, 7L, 9_007_199_254_740_993L})
    void integralVersionBinds(long version) {
        CustomerUpdateRequest request =
                read(strictMapper, updateWithVersion(Long.toString(version)), CustomerUpdateRequest.class);
        assertThat(request.version()).isEqualTo(version);
    }

    @ParameterizedTest(name = "version {0}")
    @DisplayName("a version that is not an exact integral number still fails on version")
    @ValueSource(strings = {"\"0\"", "0.9", "1.0", "1e0", "true", "false", "{}", "[0]"})
    void nonIntegralVersionStillFails(String versionJson) {
        assertFailsOn(updateWithVersion(versionJson), CustomerUpdateRequest.class, "version");
    }

    @Test
    @DisplayName("an unknown property is still rejected")
    void unknownPropertyIsStillRejected() {
        assertThatThrownBy(() -> strictMapper.readValue("{\"custId\":\"ZZZZ\",\"name\":\"acme inc\"}",
                CustomerFields.class)).isInstanceOf(UnrecognizedPropertyException.class);
    }

    @Test
    @DisplayName("control: without the class, the shipped settings bind 123 as \"123\"")
    void withoutTheClassANumberBecomesText() {
        WITHOUT_CONFIG.run(context -> assertThat(context).hasNotFailed()
                .doesNotHaveBean(StrictJsonBindingConfig.class)
                .doesNotHaveBean("strictTextualCoercionCustomizer"));
        ObjectMapper lenient = mapperOf(WITHOUT_CONFIG);

        assertThat(read(lenient, Body.ADD.with("name", "123"), CustomerFields.class).name()).isEqualTo("123");
        assertThat(read(lenient, Body.ADD.with("active", "true"), CustomerFields.class).active()).isEqualTo("true");
    }

    /**
     * Starts a runner's context and returns the {@code ObjectMapper} Spring Boot built in it.
     *
     * @param runner the runner
     * @return the mapper, still usable after the context has closed
     */
    private static ObjectMapper mapperOf(ApplicationContextRunner runner) {
        AtomicReference<ObjectMapper> built = new AtomicReference<>();
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            built.set(context.getBean(ObjectMapper.class));
        });
        return built.get();
    }

    /**
     * Returns an update body with valid text fields and {@code version} sent as {@code versionJson}.
     *
     * @param versionJson the JSON text sent as {@code version}
     * @return the JSON object
     */
    private static String updateWithVersion(String versionJson) {
        return "{" + textMembers("name", VALID.get("name")) + ",\"version\":" + versionJson + "}";
    }

    /**
     * Returns the nine text fields as JSON members, each with its {@link #VALID} value except
     * {@code field}, which is sent as {@code valueJson}.
     *
     * @param field the field to replace
     * @param valueJson the JSON text sent for it
     * @return the members, comma-separated, in JSON order
     */
    private static String textMembers(String field, String valueJson) {
        return TEXT_FIELDS.stream()
                .map(name -> "\"" + name + "\":" + (name.equals(field) ? valueJson : VALID.get(name)))
                .collect(Collectors.joining(","));
    }

    /**
     * Asserts that a body fails to bind with a {@link MismatchedInputException} whose path is exactly
     * one property.
     *
     * @param json the body
     * @param type the record it binds to
     * @param field the property the failure must name
     */
    private static void assertFailsOn(String json, Class<?> type, String field) {
        assertThatThrownBy(() -> strictMapper.readValue(json, type))
                .as(json)
                .isInstanceOf(MismatchedInputException.class)
                .satisfies(failure -> assertThat(((JsonMappingException) failure).getPath())
                        .extracting(JsonMappingException.Reference::getFieldName)
                        .containsExactly(field));
    }

    /**
     * Binds a body that must bind.
     *
     * @param mapper the mapper
     * @param json the body
     * @param type the record it binds to
     * @param <T> the record type
     * @return the bound record
     */
    private static <T> T read(ObjectMapper mapper, String json, Class<T> type) {
        try {
            return mapper.readValue(json, type);
        } catch (Exception e) {
            throw new AssertionError("Cannot bind " + json, e);
        }
    }
}
