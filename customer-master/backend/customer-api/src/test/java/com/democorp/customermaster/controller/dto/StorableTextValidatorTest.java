package com.democorp.customermaster.controller.dto;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.lang.reflect.Constructor;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Specifies {@link StorableText} and {@link StorableTextValidator}: a request text value is rejected
 * when it contains U+0000 (NUL), which a PostgreSQL text column cannot store, and accepted otherwise,
 * {@code null} included.
 *
 * <p>Two levels are covered. The validator alone decides on single values. Hibernate Validator, the
 * provider Spring MVC uses for {@code @Valid} bodies, then checks the three customer request records:
 * each of their nine text components rejects a NUL with exactly one {@code StorableText} violation on
 * that property, {@code null} passes so the business rules still see absent fields, and
 * {@code version} and {@code purpose} are not constrained by it. The simple name {@code StorableText}
 * is the constraint code {@code ApiExceptionHandler} phrases as "contains a character that cannot be
 * stored".
 *
 * <p>Strings with a NUL are written with the octal escape {@code \0}, never followed by an octal
 * digit. Pure JUnit 5 and AssertJ: no Spring context, no database, no Docker.
 */
@DisplayName("StorableText: request text must not contain U+0000")
final class StorableTextValidatorTest {

    /** The nine text components every customer request record declares, in JSON order. */
    private static final List<String> TEXT_COMPONENTS = List.of("name", "addr", "city", "state", "zip",
            "corpPhone", "acctMgr", "acctPhone", "active");

    /** One provider for the whole class; closed in {@link #closeValidator()}. */
    private static ValidatorFactory factory;

    /** The validator built by {@link #factory}. */
    private static Validator validator;

    /** The validator under test; stateless, so one instance serves every case. */
    private final StorableTextValidator storable = new StorableTextValidator();

    @BeforeAll
    static void openValidator() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void closeValidator() {
        factory.close();
    }

    @Test
    @DisplayName("null is valid, so an absent field still reaches the business rules")
    void nullIsValid() {
        assertThat(storable.isValid(null, null)).isTrue();
    }

    @ParameterizedTest(name = "[{index}] valid: {0}")
    @ValueSource(strings = {"", " ", "ACME INC", "NIBH L'LOR COMPANY", "\\NUNC\\", "STRAßE", "É", "\t",
        "A\1B", "\u007F", "\uD83D\uDE00", "0", "\uFFFF"})
    @DisplayName("text without U+0000 is valid, other control characters included")
    void textWithoutNulIsValid(String value) {
        assertThat(storable.isValid(value, null)).isTrue();
    }

    @ParameterizedTest(name = "[{index}] invalid at {1} of {2}")
    @MethodSource("nulBearing")
    @DisplayName("text containing U+0000 anywhere is invalid")
    void textWithNulIsInvalid(String value, int position, int length) {
        assertThat(value.indexOf(StorableTextValidator.NUL)).isEqualTo(position);
        assertThat(value).hasSize(length);
        assertThat(storable.isValid(value, null)).isFalse();
    }

    static Stream<Arguments> nulBearing() {
        return Stream.of(
                Arguments.of("\0", 0, 1),
                Arguments.of("AB\0C", 2, 4),
                Arguments.of("\0ABC", 0, 4),
                Arguments.of("ABC\0", 3, 4),
                Arguments.of("\uD83D\uDE00\0", 2, 3),
                Arguments.of("A\0\0B", 1, 4));
    }

    @Test
    @DisplayName("any CharSequence is checked, not only String")
    void checksAnyCharSequence() {
        assertThat(storable.isValid(new StringBuilder("AB").append(StorableTextValidator.NUL), null))
                .isFalse();
        assertThat(storable.isValid(new StringBuilder("AB"), null)).isTrue();
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(classes = {CustomerFields.class, CustomerUpdateRequest.class, ReviewRequest.class})
    @DisplayName("exactly the nine text components of each request record carry StorableText")
    void annotatesExactlyTheNineTextComponents(Class<? extends Record> type) throws NoSuchFieldException {
        List<String> annotated = new ArrayList<>();
        for (RecordComponent component : type.getRecordComponents()) {
            if (type.getDeclaredField(component.getName()).isAnnotationPresent(StorableText.class)) {
                annotated.add(component.getName());
            }
        }
        assertThat(annotated).containsExactlyElementsOf(TEXT_COMPONENTS);
    }

    @ParameterizedTest(name = "[{index}] {0}.{1}")
    @MethodSource("recordProperties")
    @DisplayName("a NUL in one text component is exactly one StorableText violation on that property")
    void rejectsNulPerProperty(Class<? extends Record> type, String property) throws ReflectiveOperationException {
        // One character, so it is within every column size, active's 1 included: only the NUL fails.
        Record request = build(type, property, "\0");

        Set<ConstraintViolation<Record>> violations = validator.validate(request);

        assertThat(violations).singleElement().satisfies(violation -> {
            assertThat(violation.getPropertyPath()).hasToString(property);
            assertThat(violation.getConstraintDescriptor().getAnnotation().annotationType())
                    .isEqualTo(StorableText.class);
            assertThat(violation.getConstraintDescriptor().getAnnotation().annotationType().getSimpleName())
                    .isEqualTo("StorableText");
            assertThat(violation.getMessage()).isEqualTo("contains a character that cannot be stored");
        });
    }

    @ParameterizedTest(name = "[{index}] {0}.{1}")
    @MethodSource("recordProperties")
    @DisplayName("an ordinary value in one text component raises no violation")
    void acceptsOrdinaryValuePerProperty(Class<? extends Record> type, String property)
            throws ReflectiveOperationException {
        assertThat(validator.validate(build(type, property, "Y"))).isEmpty();
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(classes = {CustomerFields.class, CustomerUpdateRequest.class, ReviewRequest.class})
    @DisplayName("all text components null raises no violation, so blank and absent stay business rules")
    void acceptsAllNullText(Class<? extends Record> type) throws ReflectiveOperationException {
        assertThat(validator.validate(build(type, null, null))).isEmpty();
    }

    @Test
    @DisplayName("a value that is both too long and contains NUL reports both constraints")
    void reportsSizeAndStorableTextTogether() {
        CustomerFields fields = new CustomerFields("A".repeat(40) + StorableTextValidator.NUL, null, null,
                null, null, null, null, null, null);

        assertThat(validator.validate(fields))
                .extracting(violation -> violation.getConstraintDescriptor().getAnnotation().annotationType()
                        .getSimpleName())
                .containsExactlyInAnyOrder("Size", "StorableText");
    }

    static Stream<Arguments> recordProperties() {
        return Stream.of(CustomerFields.class, CustomerUpdateRequest.class, ReviewRequest.class)
                .flatMap(type -> TEXT_COMPONENTS.stream().map(property -> Arguments.of(type, property)));
    }

    /**
     * Builds a request record through its canonical constructor: every text component {@code null}
     * except {@code property}, which gets {@code value}; a {@code Long} component ({@code version})
     * gets {@code 0} and an enum component ({@code purpose}) its first constant, so their own
     * {@code @NotNull} is satisfied.
     *
     * @param type     the record type
     * @param property the text component to set; {@code null} to leave every text component null
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
