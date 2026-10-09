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
 * when it contains an unpaired surrogate, which the driver would store as {@code ?}, a control
 * character (category Cc, U+0000 NUL included, which a PostgreSQL text column cannot store), a format
 * character (category Cf: the bidirectional controls, the zero-width characters, the soft hyphen and
 * the supplementary tag characters), U+2028 or U+2029, or a noncharacter, and accepted otherwise,
 * {@code null}, ordinary letters, marks and symbols, U+00A0, U+FFFD, private-use characters and
 * well-formed surrogate pairs included. Every accepted single code point is an XML 1.0 {@code Char},
 * so no accepted value can make the USPS request document malformed.
 *
 * <p>Two levels are covered. The validator alone decides on single values: each unstorable code point
 * is tried at the start, in the middle and at the end of a value. Hibernate Validator, the provider
 * Spring MVC uses for {@code @Valid} bodies, then checks the three customer request records: each of
 * their nine text components rejects a NUL, an unpaired surrogate, a control character and a
 * bidirectional control with exactly one {@code StorableText} violation on that property, {@code null}
 * passes so the business rules still see absent fields, and {@code version} and {@code purpose} are
 * not constrained by it. The simple name {@code StorableText} is the constraint code
 * {@code ApiExceptionHandler} phrases as "contains a character that cannot be stored".
 *
 * <p>Strings with a NUL are written with the octal escape {@code \0}, never followed by an octal
 * digit. Display names omit the values and name code points as {@code U+XXXX}: most of them are not
 * legal XML characters in the test report. Pure JUnit 5 and AssertJ: no Spring context, no database,
 * no Docker.
 */
@DisplayName("StorableText: request text must hold no unpaired surrogate, control, format, separator or noncharacter")
final class StorableTextValidatorTest {

    /** The nine text components every customer request record declares, in JSON order. */
    private static final List<String> TEXT_COMPONENTS = List.of("name", "addr", "city", "state", "zip",
            "corpPhone", "acctMgr", "acctPhone", "active");

    /** U+0000, which a PostgreSQL text column cannot store. */
    private static final char NUL = '\0';

    /**
     * Every class of unstorable code point, with the code points the reports and the rule name: C0 and
     * C1 controls and DEL (TAB, LF and CR included), the bidirectional controls, the zero-width and
     * other format characters (the supplementary tag characters included), the line and paragraph
     * separators, and the noncharacters of the U+FDD0 block and at the end of planes 0, 1 and 16.
     */
    private static final int[] UNSTORABLE_CODE_POINTS = {
        // Controls (Cc), the XML 1.0-illegal ones first.
        0x0000, 0x0001, 0x0007, 0x0008, 0x000B, 0x000C, 0x000E, 0x001F, 0x0009, 0x000A, 0x000D,
        0x007F, 0x0085, 0x009F,
        // Bidirectional controls (Cf).
        0x061C, 0x200E, 0x200F, 0x202A, 0x202B, 0x202C, 0x202D, 0x202E, 0x2066, 0x2067, 0x2068, 0x2069,
        // Zero-width and other format characters (Cf), supplementary tags included.
        0x00AD, 0x200B, 0x200C, 0x200D, 0x2060, 0x2064, 0xFEFF, 0xE0001, 0xE0020, 0xE007F,
        // Line and paragraph separators (Zl, Zp).
        0x2028, 0x2029,
        // Noncharacters.
        0xFDD0, 0xFDEF, 0xFFFE, 0xFFFF, 0x1FFFE, 0x1FFFF, 0x10FFFE, 0x10FFFF};

    /** A high surrogate, the first half of a supplementary character. */
    private static final char HIGH = '\uD800';

    /** A low surrogate, the second half of a supplementary character. */
    private static final char LOW = '\uDC00';

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

    @ParameterizedTest(name = "valid case {index}")
    @ValueSource(strings = {"", " ", "ACME INC", "NIBH L'LOR COMPANY", "\\NUNC\\", "STRAßE", "É", "ß",
        "\uD83D\uDE00", "0", "\uD800\uDC00", "\uDBFF\uDFFD", "A\uD83D\uDE00B\uD83D\uDE00", "N\u00A0A",
        "\u00A0", "\uFFFD", "\uE000", "\uF8FF", "E\u0301", "\u05D0\u05D1", "\u0627\u0644", "~!@#$%^&*()",
        "\uFFFC", "\uFDCF", "\uFDF0", "\uFFFD\uD83D\uDE00", "\uDB80\uDC00"})
    @DisplayName("text of letters, marks, symbols, spaces, U+FFFD, private use and pairs is valid")
    void storableTextIsValid(String value) {
        assertThat(storable.isValid(value, null)).isTrue();
    }

    @ParameterizedTest(name = "[{index}] {0} at the {1}")
    @MethodSource("unstorableCharacters")
    @DisplayName("text with a control, format, separator or noncharacter code point anywhere is invalid")
    void textWithUnstorableCodePointIsInvalid(String codePoint, String position, String value) {
        assertThat(storable.isValid(value, null)).as("%s at the %s", codePoint, position).isFalse();
    }

    static Stream<Arguments> unstorableCharacters() {
        return Arrays.stream(UNSTORABLE_CODE_POINTS).boxed().flatMap(codePoint -> {
            String character = Character.toString(codePoint);
            String label = String.format("U+%04X", codePoint);
            return Stream.of(
                    Arguments.of(label, "start", character + "ABC"),
                    Arguments.of(label, "middle", "AB" + character + "C"),
                    Arguments.of(label, "end", "ABC" + character),
                    Arguments.of(label, "whole value", character));
        });
    }

    @Test
    @DisplayName("the supplementary code points of the list are judged as one character each, not as units")
    void supplementaryCodePointsAreJudgedWhole() {
        // U+E0001 is a format character only as the code point its pair encodes: read unit by unit, it
        // is a well-formed surrogate pair, so its rejection shows the category of the code point is read.
        assertThat(Character.getType(0xE0001)).isEqualTo(Character.FORMAT);
        assertThat(storable.isValid("A\uDB40\uDC01B", null)).isFalse();
        // The units of a valid supplementary character next to it are still read as a pair.
        assertThat(storable.isValid("A\uDB40\uDD00B", null)).as("U+E0100, a variation selector (Mn)").isTrue();
        assertThat(storable.isValid("A\uDBFF\uDFFFB", null)).as("U+10FFFF, a noncharacter").isFalse();
        assertThat(storable.isValid("A\uDBFF\uDFFDB", null)).as("U+10FFFD, private use").isTrue();
    }

    @Test
    @DisplayName("every valid single code point is an XML 1.0 Char, and every lone surrogate unit is invalid")
    void everyValidCodePointIsAnXmlChar() {
        List<String> notXml = new ArrayList<>();
        for (int codePoint = 0; codePoint <= Character.MAX_CODE_POINT; codePoint++) {
            if (storable.isValid(Character.toString(codePoint), null) && !isXmlChar(codePoint)) {
                notXml.add(String.format("U+%04X", codePoint));
            }
        }
        assertThat(notXml).as("valid code points outside the XML 1.0 Char production").isEmpty();
        for (int unit = Character.MIN_SURROGATE; unit <= Character.MAX_SURROGATE; unit++) {
            assertThat(storable.isValid(String.valueOf((char) unit), null)).as("lone unit U+%04X", unit).isFalse();
        }
    }

    /**
     * The XML 1.0 {@code Char} production, written independently of the validator:
     * {@code #x9 | #xA | #xD | [#x20-#xD7FF] | [#xE000-#xFFFD] | [#x10000-#x10FFFF]}.
     *
     * @param codePoint the code point
     * @return whether an XML 1.0 document may hold it as character data
     */
    private static boolean isXmlChar(int codePoint) {
        return codePoint == 0x9 || codePoint == 0xA || codePoint == 0xD
                || (codePoint >= 0x20 && codePoint <= 0xD7FF)
                || (codePoint >= 0xE000 && codePoint <= 0xFFFD)
                || (codePoint >= 0x10000 && codePoint <= 0x10FFFF);
    }

    @ParameterizedTest(name = "[{index}] invalid: {1}")
    @MethodSource("unpairedSurrogates")
    @DisplayName("text with an unpaired surrogate anywhere is invalid")
    void textWithUnpairedSurrogateIsInvalid(String value, String description) {
        assertThat(storable.isValid(value, null)).as(description).isFalse();
    }

    static Stream<Arguments> unpairedSurrogates() {
        String pair = "\uD83D\uDE00";
        return Stream.of(
                Arguments.of("" + HIGH, "a lone high surrogate"),
                Arguments.of("" + LOW, "a lone low surrogate"),
                Arguments.of(HIGH + "ABC", "a high surrogate at the start"),
                Arguments.of("QA " + HIGH + "X", "a high surrogate in the middle"),
                Arguments.of("ABC" + HIGH, "a high surrogate at the end"),
                Arguments.of(LOW + "ABC", "a low surrogate at the start"),
                Arguments.of("AB" + LOW + "C", "a low surrogate in the middle"),
                Arguments.of("ABC" + LOW, "a low surrogate at the end"),
                Arguments.of("" + LOW + HIGH, "a reversed pair"),
                Arguments.of("" + HIGH + HIGH, "two high surrogates"),
                Arguments.of("" + HIGH + HIGH + LOW, "a high surrogate before a pair"),
                Arguments.of(pair + LOW, "a low surrogate after a pair"),
                Arguments.of(pair + HIGH, "a high surrogate after a pair"));
    }

    @ParameterizedTest(name = "[{index}] invalid at {1} of {2}")
    @MethodSource("nulBearing")
    @DisplayName("text containing U+0000 anywhere is invalid")
    void textWithNulIsInvalid(String value, int position, int length) {
        assertThat(value.indexOf(NUL)).isEqualTo(position);
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
        assertThat(storable.isValid(new StringBuilder("AB").append(NUL), null)).isFalse();
        assertThat(storable.isValid(new StringBuilder("AB"), null)).isTrue();
        assertThat(storable.isValid(new StringBuilder("AB").append(HIGH), null)).isFalse();
        assertThat(storable.isValid(new StringBuilder("AB").append(HIGH).append(LOW), null)).isTrue();
        assertThat(storable.isValid(new StringBuilder("AB").append('\u0001'), null)).isFalse();
        assertThat(storable.isValid(new StringBuilder("AB").append('\u202E'), null)).isFalse();
        assertThat(storable.isValid(new StringBuilder("AB").appendCodePoint(0xE0001), null)).isFalse();
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
    @DisplayName("an unpaired surrogate in one text component is exactly one StorableText violation there")
    void rejectsUnpairedSurrogatePerProperty(Class<? extends Record> type, String property)
            throws ReflectiveOperationException {
        // One code point, so it is within every column size, active's 1 included: only StorableText fails.
        Record request = build(type, property, String.valueOf(HIGH));

        Set<ConstraintViolation<Record>> violations = validator.validate(request);

        assertThat(violations).singleElement().satisfies(violation -> {
            assertThat(violation.getPropertyPath()).hasToString(property);
            assertThat(violation.getConstraintDescriptor().getAnnotation().annotationType())
                    .isEqualTo(StorableText.class);
            assertThat(violation.getMessage()).isEqualTo("contains a character that cannot be stored");
        });
    }

    @ParameterizedTest(name = "[{index}] {0}.{1} with {2}")
    @MethodSource("recordPropertiesWithControlAndBidi")
    @DisplayName("a control or a bidirectional control in one text component is exactly one StorableText violation")
    void rejectsControlAndBidiPerProperty(Class<? extends Record> type, String property, String codePoint,
            String value) throws ReflectiveOperationException {
        // One code point, so it is within every column size, active's 1 included: only StorableText fails.
        Record request = build(type, property, value);

        Set<ConstraintViolation<Record>> violations = validator.validate(request);

        assertThat(violations).as(codePoint).singleElement().satisfies(violation -> {
            assertThat(violation.getPropertyPath()).hasToString(property);
            assertThat(violation.getConstraintDescriptor().getAnnotation().annotationType())
                    .isEqualTo(StorableText.class);
            assertThat(violation.getMessage()).isEqualTo("contains a character that cannot be stored");
        });
    }

    static Stream<Arguments> recordPropertiesWithControlAndBidi() {
        return recordProperties().flatMap(arguments -> Stream.of(
                Arguments.of(arguments.get()[0], arguments.get()[1], "U+0001", "\u0001"),
                Arguments.of(arguments.get()[0], arguments.get()[1], "U+202E", "\u202E")));
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
    void reportsLengthAndStorableTextTogether() {
        CustomerFields fields = new CustomerFields("A".repeat(40) + NUL, null, null,
                null, null, null, null, null, null);

        assertThat(validator.validate(fields))
                .extracting(violation -> violation.getConstraintDescriptor().getAnnotation().annotationType()
                        .getSimpleName())
                .containsExactlyInAnyOrder("CodePointLength", "StorableText");
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
