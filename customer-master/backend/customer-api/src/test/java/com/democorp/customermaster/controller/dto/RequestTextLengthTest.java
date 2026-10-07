package com.democorp.customermaster.controller.dto;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import jakarta.validation.constraints.Size;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.hibernate.validator.constraints.CodePointLength;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Specifies the length limit of the nine text components of {@link CustomerFields},
 * {@link ReviewRequest} and {@link CustomerUpdateRequest}: each is its column width (the DDS field
 * length) counted in characters (code points), as PostgreSQL's {@code varchar(n)} and {@code char(n)}
 * count them, never in UTF-16 units.
 *
 * <p>A value at the width passes even when made of supplementary characters such as U+1F600, each two
 * UTF-16 units; one more character is exactly one {@code CodePointLength} violation on that property,
 * the constraint code {@code ApiExceptionHandler} phrases as "is too long".
 *
 * <p>Pure JUnit 5 and AssertJ over Hibernate Validator, the provider Spring MVC uses for {@code @Valid}
 * bodies: no Spring context, no database, no Docker.
 */
@DisplayName("Request text components: the column width, counted in code points")
final class RequestTextLengthTest {

    /** The nine text components in JSON order, each with its column width in characters. */
    private static final Map<String, Integer> WIDTHS = widths();

    /** U+1F600, one code point held in two UTF-16 units. */
    private static final String EMOJI = new String(Character.toChars(0x1F600));

    /** One provider for the whole class; closed in {@link #closeValidator()}. */
    private static ValidatorFactory factory;

    /** The validator built by {@link #factory}. */
    private static Validator validator;

    @BeforeAll
    static void openValidator() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void closeValidator() {
        factory.close();
    }

    @ParameterizedTest(name = "[{index}] {0}.{1}")
    @MethodSource("recordProperties")
    @DisplayName("each text component carries CodePointLength of its column width and no Size")
    void declaresTheColumnWidthInCodePoints(Class<? extends Record> type, String property)
            throws NoSuchFieldException {
        Field field = type.getDeclaredField(property);

        assertThat(field.getAnnotation(CodePointLength.class)).isNotNull().satisfies(length -> {
            assertThat(length.min()).isZero();
            assertThat(length.max()).isEqualTo(WIDTHS.get(property));
            assertThat(length.normalizationStrategy()).isEqualTo(CodePointLength.NormalizationStrategy.NONE);
        });
        assertThat(field.getAnnotation(Size.class)).isNull();
    }

    @ParameterizedTest(name = "[{index}] {0}.{1}")
    @MethodSource("recordProperties")
    @DisplayName("the width in supplementary characters, twice as many UTF-16 units, is valid")
    void acceptsTheWidthInSupplementaryCharacters(Class<? extends Record> type, String property)
            throws ReflectiveOperationException {
        String value = EMOJI.repeat(WIDTHS.get(property));
        assertThat(value).hasSize(2 * WIDTHS.get(property));

        assertThat(validator.validate(build(type, property, value))).isEmpty();
    }

    @ParameterizedTest(name = "[{index}] {0}.{1}")
    @MethodSource("recordProperties")
    @DisplayName("the width in characters ending in a supplementary one, one UTF-16 unit over, is valid")
    void acceptsTheWidthEndingInASupplementaryCharacter(Class<? extends Record> type, String property)
            throws ReflectiveOperationException {
        String value = "A".repeat(WIDTHS.get(property) - 1) + EMOJI;

        assertThat(validator.validate(build(type, property, value))).isEmpty();
    }

    @ParameterizedTest(name = "[{index}] {0}.{1}")
    @MethodSource("recordProperties")
    @DisplayName("one character over the width is exactly one CodePointLength violation on the property")
    void rejectsOneCharacterOverTheWidth(Class<? extends Record> type, String property)
            throws ReflectiveOperationException {
        for (String value : new String[] {"A".repeat(WIDTHS.get(property) + 1),
            "A".repeat(WIDTHS.get(property)) + EMOJI, EMOJI.repeat(WIDTHS.get(property) + 1)}) {
            Set<ConstraintViolation<Record>> violations = validator.validate(build(type, property, value));

            assertThat(violations).as(value).singleElement().satisfies(violation -> {
                assertThat(violation.getPropertyPath()).hasToString(property);
                assertThat(violation.getConstraintDescriptor().getAnnotation().annotationType()
                        .getSimpleName()).isEqualTo("CodePointLength");
            });
        }
    }

    static Stream<Arguments> recordProperties() {
        return Stream.of(CustomerFields.class, CustomerUpdateRequest.class, ReviewRequest.class)
                .flatMap(type -> WIDTHS.keySet().stream().map(property -> Arguments.of(type, property)));
    }

    /**
     * Returns the column widths of the nine text components, the MTNCUSTD field lengths.
     *
     * @return an unmodifiable map in JSON order
     */
    private static Map<String, Integer> widths() {
        Map<String, Integer> widths = new LinkedHashMap<>();
        widths.put("name", 40);
        widths.put("addr", 40);
        widths.put("city", 20);
        widths.put("state", 2);
        widths.put("zip", 10);
        widths.put("corpPhone", 20);
        widths.put("acctMgr", 40);
        widths.put("acctPhone", 20);
        widths.put("active", 1);
        return Collections.unmodifiableMap(widths);
    }

    /**
     * Builds a request record through its canonical constructor: every text component {@code null}
     * except {@code property}, which gets {@code value}; a {@code Long} component ({@code version})
     * gets {@code 0} and an enum component ({@code purpose}) its first constant, so their own
     * {@code @NotNull} is satisfied.
     *
     * @param type     the record type
     * @param property the text component to set
     * @param value    the value of {@code property}
     * @return the record
     * @throws ReflectiveOperationException if the canonical constructor cannot be invoked
     */
    private static Record build(Class<? extends Record> type, String property, String value)
            throws ReflectiveOperationException {
        RecordComponent[] components = type.getRecordComponents();
        Class<?>[] parameterTypes = Arrays.stream(components).map(RecordComponent::getType)
                .toArray(Class<?>[]::new);
        Object[] arguments = new Object[components.length];
        for (int i = 0; i < components.length; i++) {
            Class<?> componentType = components[i].getType();
            if (componentType == Long.class) {
                arguments[i] = 0L;
            } else if (componentType.isEnum()) {
                arguments[i] = componentType.getEnumConstants()[0];
            } else if (components[i].getName().equals(property)) {
                arguments[i] = value;
            }
        }
        Constructor<? extends Record> canonical = type.getDeclaredConstructor(parameterTypes);
        return canonical.newInstance(arguments);
    }
}
