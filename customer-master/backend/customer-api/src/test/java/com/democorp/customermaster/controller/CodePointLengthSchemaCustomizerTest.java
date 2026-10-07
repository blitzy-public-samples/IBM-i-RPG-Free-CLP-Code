package com.democorp.customermaster.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.democorp.customermaster.controller.dto.CustomerFields;
import com.democorp.customermaster.controller.dto.CustomerUpdateRequest;
import com.democorp.customermaster.controller.dto.ReviewRequest;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.media.JsonSchema;
import io.swagger.v3.oas.models.media.Schema;
import java.lang.reflect.RecordComponent;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Specifies {@link CodePointLengthSchemaCustomizer}: the {@code @CodePointLength} limits of the three
 * customer request records are published as {@code minLength: 0} and {@code maxLength: <width>} on
 * their text properties, the keywords {@code @Size} produced, and nothing else changes.
 *
 * <p>The documents are built by hand in the shape springdoc publishes (OpenAPI 3.1: a text property is
 * {@code type: [string, "null"]}); the published document as a whole is compared with the committed
 * snapshot elsewhere. Pure JUnit 5 and AssertJ: no Spring context.
 */
@DisplayName("CodePointLengthSchemaCustomizer: @CodePointLength as minLength and maxLength")
final class CodePointLengthSchemaCustomizerTest {

    /** The column widths of the nine text properties. */
    private static final Map<String, Integer> WIDTHS = Map.of("name", 40, "addr", 40, "city", 20,
            "state", 2, "zip", 10, "corpPhone", 20, "acctMgr", 40, "acctPhone", 20, "active", 1);

    /** The customizer under test; stateless. */
    private final CodePointLengthSchemaCustomizer customizer = new CodePointLengthSchemaCustomizer();

    @Test
    @DisplayName("every text property of the three records gets minLength 0 and its column width")
    void publishesTheWidthOfEveryTextProperty() {
        OpenAPI openApi = document();

        customizer.customise(openApi);

        for (Class<? extends Record> dto : CodePointLengthSchemaCustomizer.REQUEST_RECORDS) {
            Map<String, Schema> properties = schemaOf(openApi, dto).getProperties();
            WIDTHS.forEach((name, width) -> {
                assertThat(properties.get(name).getMinLength()).as(dto.getSimpleName() + "." + name).isZero();
                assertThat(properties.get(name).getMaxLength()).as(dto.getSimpleName() + "." + name)
                        .isEqualTo(width);
            });
        }
    }

    @Test
    @DisplayName("purpose and version, which carry no CodePointLength, are left unchanged")
    void leavesUnconstrainedPropertiesUnchanged() {
        OpenAPI openApi = document();

        customizer.customise(openApi);

        Schema<?> purpose = schemaOf(openApi, ReviewRequest.class).getProperties().get("purpose");
        Schema<?> version = schemaOf(openApi, CustomerUpdateRequest.class).getProperties().get("version");
        assertThat(purpose.getMinLength()).isNull();
        assertThat(purpose.getMaxLength()).isNull();
        assertThat(version.getMinLength()).isNull();
        assertThat(version.getMaxLength()).isNull();
    }

    @Test
    @DisplayName("applying it twice yields the same document")
    void isIdempotent() {
        OpenAPI once = document();
        customizer.customise(once);
        OpenAPI twice = document();
        customizer.customise(twice);
        customizer.customise(twice);

        assertThat(twice).isEqualTo(once);
    }

    @Test
    @DisplayName("a document without components or without the records is left unchanged")
    void leavesOtherDocumentsUnchanged() {
        OpenAPI empty = new OpenAPI();
        customizer.customise(empty);
        assertThat(empty).isEqualTo(new OpenAPI());

        OpenAPI unrelated = new OpenAPI().components(new Components().addSchemas("Notice", new JsonSchema()));
        customizer.customise(unrelated);
        assertThat(unrelated).isEqualTo(
                new OpenAPI().components(new Components().addSchemas("Notice", new JsonSchema())));
    }

    @Test
    @DisplayName("a record schema without an annotated property fails rather than publish a stale contract")
    void failsOnAMissingProperty() {
        OpenAPI openApi = document();
        schemaOf(openApi, CustomerFields.class).getProperties().remove("city");

        assertThatThrownBy(() -> customizer.customise(openApi))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("CustomerFields is published without its 'city' property");
    }

    /**
     * Builds a document holding the three records' schemas as springdoc publishes them before the
     * customizer runs: every text property a string or {@code null} without length, {@code purpose} a
     * string and {@code version} an integer.
     *
     * @return a new document
     */
    private static OpenAPI document() {
        Components components = new Components();
        for (Class<? extends Record> dto : CodePointLengthSchemaCustomizer.REQUEST_RECORDS) {
            JsonSchema owner = new JsonSchema();
            owner.addType("object");
            for (RecordComponent component : dto.getRecordComponents()) {
                JsonSchema property = new JsonSchema();
                if (component.getType() == String.class) {
                    property.addType("string");
                    property.addType("null");
                } else if (component.getType() == Long.class) {
                    property.addType("integer");
                } else {
                    property.addType("string");
                }
                owner.addProperty(component.getName(), property);
            }
            components.addSchemas(dto.getSimpleName(), owner);
        }
        return new OpenAPI().components(components);
    }

    private static Schema<?> schemaOf(OpenAPI openApi, Class<? extends Record> dto) {
        return openApi.getComponents().getSchemas().get(dto.getSimpleName());
    }
}
