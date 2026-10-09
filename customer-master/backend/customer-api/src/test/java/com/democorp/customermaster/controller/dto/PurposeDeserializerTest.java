package com.democorp.customermaster.controller.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.democorp.customermaster.service.CustomerMaintenanceService.Purpose;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Specifies {@link PurposeDeserializer} through {@link ReviewRequest}: {@code purpose} binds only from a
 * JSON string exactly equal to {@code "ADD"} or {@code "EDIT"}; every other value fails with a
 * {@link MismatchedInputException} whose path ends at {@code purpose}, and JSON {@code null} or an
 * absent member binds {@code null} for {@code @NotNull} to reject.
 *
 * <p>The mapper is built with Jackson's most lenient enum and coercion switches turned on, so a pass
 * shows that the deserializer, not the application's {@code spring.jackson} settings, decides. Tab and
 * line break are sent as the JSON escapes {@code \t} and {@code \n}. Pure JUnit 5, AssertJ and Jackson:
 * no Spring context, no database, no Docker.
 */
@DisplayName("PurposeDeserializer: purpose is exactly \"ADD\" or \"EDIT\"")
final class PurposeDeserializerTest {

    /** The eight data members of a review body other than {@code purpose}; all optional for binding. */
    private static final String FIELDS = "\"name\":\"acme inc\",\"addr\":\"1 main st\",\"city\":\"auburn\","
            + "\"state\":\"me\",\"zip\":\"04210\",\"corpPhone\":\"(207) 555-0100\",\"acctMgr\":\"jane doe\","
            + "\"acctPhone\":\"(207) 555-0101\"";

    /** A mapper that would accept trimmed, case-insensitive, ordinal and single-element-array enums. */
    private static final ObjectMapper LENIENT = JsonMapper.builder()
            .enable(MapperFeature.ACCEPT_CASE_INSENSITIVE_ENUMS)
            .enable(DeserializationFeature.UNWRAP_SINGLE_VALUE_ARRAYS)
            .enable(DeserializationFeature.READ_UNKNOWN_ENUM_VALUES_AS_NULL)
            .disable(DeserializationFeature.FAIL_ON_NUMBERS_FOR_ENUMS)
            .build();

    @Test
    @DisplayName("\"ADD\" binds ADD and \"EDIT\" binds EDIT")
    void exactNamesBind() throws Exception {
        assertThat(read("\"ADD\"").purpose()).isEqualTo(Purpose.ADD);
        assertThat(read("\"EDIT\"").purpose()).isEqualTo(Purpose.EDIT);
    }

    @Test
    @DisplayName("JSON null and an absent member bind null")
    void nullAndAbsentBindNull() throws Exception {
        assertThat(read("null").purpose()).isNull();
        assertThat(LENIENT.readValue("{" + FIELDS + "}", ReviewRequest.class).purpose()).isNull();
    }

    @ParameterizedTest(name = "purpose {0}")
    @DisplayName("any other value fails on purpose")
    @ValueSource(strings = {"\" ADD\"", "\"ADD \"", "\"\\tEDIT\"", "\"EDIT\\n\"", "\"Add\"", "\"add\"",
        "\"edit\"", "\"\"", "\" \"", "\"DELETE\"", "\"0\"", "\"1\"", "0", "1", "1.5", "true", "false", "{}",
        "[\"ADD\"]", "[]"})
    void otherValuesFailOnPurpose(String purposeJson) {
        assertThatThrownBy(() -> read(purposeJson))
                .isInstanceOf(MismatchedInputException.class)
                .satisfies(failure -> assertThat(((JsonMappingException) failure).getPath())
                        .extracting(JsonMappingException.Reference::getFieldName)
                        .containsExactly("purpose"));
    }

    /**
     * Reads a review body whose {@code purpose} member is the given JSON text.
     *
     * @param purposeJson the JSON text of {@code purpose}
     * @return the bound request
     * @throws Exception the binding failure
     */
    private static ReviewRequest read(String purposeJson) throws Exception {
        return LENIENT.readValue("{\"purpose\":" + purposeJson + "," + FIELDS + "}", ReviewRequest.class);
    }
}
