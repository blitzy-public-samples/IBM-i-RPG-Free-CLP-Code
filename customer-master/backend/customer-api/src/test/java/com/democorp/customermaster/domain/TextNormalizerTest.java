package com.democorp.customermaster.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.util.Locale;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Specifies {@link TextNormalizer}, the server-side twin of the 5250 terminal's uppercase translation.
 *
 * <p>No input field of PMTCUSTD ({@code SC_NAME 13A}, {@code SC_CITY 13A}, {@code SC_STATE 2A}) or
 * MTNCUSTD (the nine customer fields) declares {@code CHECK(LC)}, so the workstation uppercased every
 * keyed character, and PMTCUSTR trimmed both ends of a filter with {@code %trim(SC_NAME)} before
 * appending its wildcard. The target makes both a server rule:
 * <ul>
 *   <li>{@code field(s)} removes trailing whitespace (CHAR padding parity) and keeps leading characters;</li>
 *   <li>{@code filter(s)} trims both ends;</li>
 *   <li>both map {@code null} to {@code ""} and apply the length-preserving uppercase rule: a code point
 *       takes its full uppercase mapping under {@link Locale#ROOT} only when that mapping is exactly one
 *       code point, and is otherwise kept.</li>
 * </ul>
 *
 * <p><b>Cross-folder parity.</b> {@code frontend/src/components/upperField.ts} applies the same rule as
 * the user types, and its {@code upperField.test.ts} uses the parity table below case for case (the client
 * never trims, which {@link TextNormalizer#upper(String)} mirrors). Change a row here only together with
 * that suite.
 *
 * <p><b>Encoding.</b> Every non-ASCII literal is written as a {@code \}{@code uXXXX} escape and described
 * by its Unicode name in a comment, so the test does not depend on the source-file encoding. Parameterized
 * display names print the decoded characters at run time.
 *
 * <p>Pure JUnit 5 and AssertJ: no Spring context, no database, no Docker. The class is {@link Isolated}
 * because one test changes the JVM-wide default locale, then restores the general, DISPLAY and FORMAT
 * defaults.
 */
@DisplayName("TextNormalizer: length-preserving uppercase with source trimming")
@Isolated
final class TextNormalizerTest {

    /**
     * The shared parity table, identical to {@code upperField.test.ts}. None of the inputs carries a
     * blank, so {@code field}, {@code filter} and {@code upper} must all return the expected value.
     */
    static Stream<Arguments> parityTable() {
        return Stream.of(
                Arguments.of("abc", "ABC"),
                // U+00DF LATIN SMALL LETTER SHARP S: full mapping "SS" is two code points, so it is kept.
                Arguments.of("\u00df", "\u00df"),
                // U+00E9 LATIN SMALL LETTER E WITH ACUTE becomes U+00C9 LATIN CAPITAL LETTER E WITH ACUTE.
                Arguments.of("\u00e9", "\u00c9"),
                // U+01C6 LATIN SMALL LETTER DZ WITH CARON becomes U+01C4, its uppercase form,
                // not the titlecase U+01C5.
                Arguments.of("\u01c6", "\u01c4"),
                // U+1F80 GREEK SMALL LETTER ALPHA WITH PSILI AND YPOGEGRAMMENI: full mapping is the two code
                // points U+1F08 U+0399, so it is kept (the simple mapping U+1F88 must not be used).
                Arguments.of("\u1f80", "\u1f80"));
    }

    @ParameterizedTest(name = "[{index}] field(\"{0}\") = \"{1}\"")
    @MethodSource("parityTable")
    @DisplayName("field applies the shared uppercase rule of the parity table")
    void fieldAppliesParityTable(String input, String expected) {
        assertLengthPreservingResult(TextNormalizer.field(input), input, expected);
    }

    @ParameterizedTest(name = "[{index}] filter(\"{0}\") = \"{1}\"")
    @MethodSource("parityTable")
    @DisplayName("filter applies the shared uppercase rule of the parity table")
    void filterAppliesParityTable(String input, String expected) {
        assertLengthPreservingResult(TextNormalizer.filter(input), input, expected);
    }

    @ParameterizedTest(name = "[{index}] upper(\"{0}\") = \"{1}\"")
    @MethodSource("parityTable")
    @DisplayName("upper, the non-trimming twin of upperField, applies the parity table")
    void upperAppliesParityTable(String input, String expected) {
        assertLengthPreservingResult(TextNormalizer.upper(input), input, expected);
    }

    @Test
    @DisplayName("plain String.toUpperCase is not the rule: it expands \u00df to SS")
    void plainUppercasingWouldExpandSharpS() {
        // Negative control: the whole-string full mapping turns U+00DF into two characters, which would
        // change the length the 5250 field and the browser input both preserve.
        final String plain = "\u00df".toUpperCase(Locale.ROOT);

        assertThat(plain).isEqualTo("SS");
        assertThat(TextNormalizer.field("\u00df")).isNotEqualTo(plain).isEqualTo("\u00df");
    }

    @Test
    @DisplayName("\u1f80 is kept, not replaced by its simple mapping \u1f88")
    void greekAlphaIsKeptRatherThanSimplyMapped() {
        // Character.toUpperCase(int) is the simple mapping, which the rule must not use.
        assertThat(Character.toUpperCase(0x1f80)).isEqualTo(0x1f88);
        // The full mapping is two code points, U+1F08 U+0399, so the rule keeps the original.
        assertThat("\u1f80".toUpperCase(Locale.ROOT)).isEqualTo("\u1f08\u0399");

        assertThat(TextNormalizer.field("\u1f80")).isEqualTo("\u1f80").isNotEqualTo("\u1f88");
        assertThat(TextNormalizer.filter("\u1f80")).isEqualTo("\u1f80").isNotEqualTo("\u1f88");
    }

    @Test
    @DisplayName("\u01c6 becomes uppercase \u01c4, never titlecase \u01c5")
    void dzDigraphTakesUppercaseNotTitlecase() {
        // Contrast: the titlecase form exists and is a different code point.
        assertThat(Character.toTitleCase(0x01c6)).isEqualTo(0x01c5);

        assertThat(TextNormalizer.field("\u01c6")).isEqualTo("\u01c4").isNotEqualTo("\u01c5");
        assertThat(TextNormalizer.filter("\u01c6")).isEqualTo("\u01c4").isNotEqualTo("\u01c5");
    }

    @Test
    @DisplayName("a 40-character mixed-case field keeps its 40 characters")
    void fortyCharacterFieldKeepsItsLength() {
        // "Strasse" spelt with U+00DF, U+00E9, U+01C6 and U+1F80 among ASCII words: the full width of
        // SD_NAME and SD_ADDR (40) in MTNCUSTD.
        final String input = "Stra\u00dfe \u00e9 \u01c6 \u1f80 Mixed Case Company Name abc";
        assertThat(input.length()).as("guard: input UTF-16 length").isEqualTo(40);
        assertThat(input.codePointCount(0, input.length())).as("guard: input code points").isEqualTo(40);

        final String result = TextNormalizer.field(input);

        assertThat(result).isEqualTo("STRA\u00dfE \u00c9 \u01c4 \u1f80 MIXED CASE COMPANY NAME ABC");
        assertThat(result).hasSize(40);
        assertThat(result.codePointCount(0, result.length())).isEqualTo(40);
        // Contrast: plain uppercasing expands U+00DF and U+1F80 and would overflow the 40-character column.
        assertThat(input.toUpperCase(Locale.ROOT)).hasSize(42);
    }

    @Test
    @DisplayName("a 13-character filter keeps its 13 characters")
    void thirteenCharacterFilterKeepsItsLength() {
        // The full width of SC_NAME and SC_CITY (13) in PMTCUSTD, whose limit drives the search-pattern cut.
        final String input = "stra\u00dfe \u1f80 city";
        assertThat(input.length()).as("guard: input UTF-16 length").isEqualTo(13);
        assertThat(input.codePointCount(0, input.length())).as("guard: input code points").isEqualTo(13);

        final String result = TextNormalizer.filter(input);

        assertThat(result).isEqualTo("STRA\u00dfE \u1f80 CITY");
        assertThat(result).hasSize(13);
        assertThat(result.codePointCount(0, result.length())).isEqualTo(13);
        // Contrast: plain uppercasing would give 15 characters and push the filter past the 13-character cut.
        assertThat(input.toUpperCase(Locale.ROOT)).hasSize(15);
    }

    @Test
    @DisplayName("the FormField example stra\u00dfe becomes STRA\u00dfE with six characters")
    void formFieldExampleKeepsSixCharacters() {
        final String result = TextNormalizer.field("stra\u00dfe");

        assertThat(result).isEqualTo("STRA\u00dfE");
        assertThat(result).hasSize(6);
    }

    @Test
    @DisplayName("a supplementary code point maps by code point and keeps its length")
    void supplementaryCodePointMapsByCodePoint() {
        // U+10428 DESERET SMALL LETTER LONG I (surrogate pair) has the single-code-point uppercase
        // U+10400 DESERET CAPITAL LETTER LONG I, so it is mapped and stays two UTF-16 units.
        final String result = TextNormalizer.field("a\ud801\udc28b");

        assertThat(result).isEqualTo("A\ud801\udc00B");
        assertThat(result).hasSize(4);
        assertThat(result.codePointCount(0, result.length())).isEqualTo(3);
    }

    @Test
    @DisplayName("an unpaired surrogate passes through unchanged")
    void unpairedSurrogateIsKept() {
        // A lone high surrogate (U+D800) has no case mapping; it must neither throw nor change.
        assertThat(TextNormalizer.field("ab\ud800")).isEqualTo("AB\ud800");
        assertThat(TextNormalizer.filter("\ud800cd")).isEqualTo("\ud800CD");
    }

    @Test
    @DisplayName("field removes trailing blanks and keeps leading blanks")
    void fieldRemovesTrailingBlanksAndKeepsLeadingBlanks() {
        assertThat(TextNormalizer.field("  abc  ")).isEqualTo("  ABC");
    }

    @Test
    @DisplayName("filter trims blanks from both ends, as %trim(SC_NAME) does")
    void filterTrimsBothEnds() {
        assertThat(TextNormalizer.filter("  abc  ")).isEqualTo("ABC");
    }

    @Test
    @DisplayName("unpadded input gives the same result from field and filter")
    void unpaddedInputIsTheSameThroughFieldAndFilter() {
        assertThat(TextNormalizer.field("abc")).isEqualTo("ABC");
        assertThat(TextNormalizer.filter("abc")).isEqualTo("ABC");
    }

    @Test
    @DisplayName("inner blanks are kept by field and filter")
    void innerBlanksAreKept() {
        assertThat(TextNormalizer.field("a  b ")).isEqualTo("A  B");
        assertThat(TextNormalizer.filter(" a  b ")).isEqualTo("A  B");
    }

    @Test
    @DisplayName("blank-only input normalizes to the empty string")
    void blankOnlyInputBecomesEmpty() {
        assertThat(TextNormalizer.field("   ")).isEmpty();
        assertThat(TextNormalizer.filter("   ")).isEmpty();
    }

    @Test
    @DisplayName("empty input stays empty")
    void emptyInputStaysEmpty() {
        assertThat(TextNormalizer.field("")).isEmpty();
        assertThat(TextNormalizer.filter("")).isEmpty();
        assertThat(TextNormalizer.upper("")).isEmpty();
    }

    @Test
    @DisplayName("tabs count as whitespace, as TextNormalizer documents (Character.isWhitespace)")
    void tabsAreWhitespace() {
        assertThat(TextNormalizer.field("\tabc\t")).isEqualTo("\tABC");
        assertThat(TextNormalizer.filter("\tabc\t")).isEqualTo("ABC");
    }

    @Test
    @DisplayName("upper, like upperField, keeps leading and trailing blanks")
    void upperNeverTrims() {
        assertThat(TextNormalizer.upper("  abc  ")).isEqualTo("  ABC  ");
    }

    @Test
    @DisplayName("null normalizes to the empty string without throwing")
    void nullBecomesEmptyString() {
        assertThatCode(() -> TextNormalizer.field(null)).doesNotThrowAnyException();
        assertThatCode(() -> TextNormalizer.filter(null)).doesNotThrowAnyException();

        assertThat(TextNormalizer.field(null)).isNotNull().isEmpty();
        assertThat(TextNormalizer.filter(null)).isNotNull().isEmpty();
        assertThat(TextNormalizer.upper(null)).isNotNull().isEmpty();
    }

    @ParameterizedTest(name = "[{index}] normalizing \"{0}\" twice changes nothing more")
    @MethodSource("parityTable")
    @DisplayName("normalization is idempotent and leaves normalized values unchanged")
    void normalizationIsIdempotent(String input, String expected) {
        final String once = TextNormalizer.field(input);
        assertThat(TextNormalizer.field(once)).isEqualTo(once);

        final String filtered = TextNormalizer.filter(input);
        assertThat(TextNormalizer.filter(filtered)).isEqualTo(filtered);

        assertThat(TextNormalizer.field(expected)).isEqualTo(expected);
        assertThat(TextNormalizer.filter(expected)).isEqualTo(expected);
    }

    @Test
    @DisplayName("ASCII digits, punctuation and backslashes pass through unchanged")
    void digitsAndPunctuationPassThrough() {
        // The apostrophe and backslashes come from the seed names NIBH L'LOR COMPANY and URNA \NUNC\ COMPANY.
        final String value = "O'BRIEN & SONS, \\NUNC\\ 123";

        assertThat(TextNormalizer.field(value)).isEqualTo(value);
        assertThat(TextNormalizer.filter(value)).isEqualTo(value);
    }

    @Test
    @DisplayName("the default locale never changes the result (Turkish dotted capital I)")
    void defaultLocaleIsNotUsed() {
        final Locale originalDefault = Locale.getDefault();
        final Locale originalDisplay = Locale.getDefault(Locale.Category.DISPLAY);
        final Locale originalFormat = Locale.getDefault(Locale.Category.FORMAT);
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            // Contrast: under a Turkish default locale plain uppercasing maps i to U+0130 (dotted capital I).
            assertThat("i".toUpperCase()).isEqualTo("\u0130");

            assertThat(TextNormalizer.field("istanbul")).isEqualTo("ISTANBUL");
            assertThat(TextNormalizer.filter(" i ")).isEqualTo("I");
        } finally {
            // The general setter also overwrites the DISPLAY and FORMAT defaults, so it runs first and the
            // two category defaults are then put back individually.
            Locale.setDefault(originalDefault);
            Locale.setDefault(Locale.Category.DISPLAY, originalDisplay);
            Locale.setDefault(Locale.Category.FORMAT, originalFormat);
        }
    }

    /**
     * Asserts the expected value, an unchanged UTF-16 length and an unchanged code-point count, the three
     * properties both parity suites check for every row.
     */
    private static void assertLengthPreservingResult(String actual, String input, String expected) {
        assertThat(actual).isEqualTo(expected);
        assertThat(actual.length()).as("UTF-16 length of %s", expected).isEqualTo(input.length());
        assertThat(actual.codePointCount(0, actual.length()))
                .as("code-point count of %s", expected)
                .isEqualTo(input.codePointCount(0, input.length()));
    }
}
