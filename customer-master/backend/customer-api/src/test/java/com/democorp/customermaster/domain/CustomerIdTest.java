package com.democorp.customermaster.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Specifies {@link CustomerId}, the codec that replaces the BASE36ADD service procedure
 * [BASE36/SRV_BASE36.RPGLE:29-55] and the CUSTNEXT data area [5250_Subfile/CRTDTAARA.clle:1-9].
 *
 * <p><b>What the source does.</b> BASE36ADD translates the rightmost character through
 * {@code FROM 'ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789'} / {@code TO 'BCDEFGHIJKLMNOPQRSTUVWXYZ0123456789A'}
 * and carries left while the translated character is {@code A}, the roll-over mark
 * [BASE36/SRV_BASE36.RPGLE:37-54]. Its values ascend in the EBCDIC raw sorting sequence, letters
 * before digits [BASE36/SRV_BASE36.RPGLE:15-16], [BASE36/README.MD]. CRTDTAARA seeds CUSTNEXT with
 * {@code EEEE}, and AddRecd increments before it inserts, so the first interactive id is {@code EEEF}
 * [5250_Subfile/MTNCUSTR.SQLRPGLE:548-557].
 *
 * <p><b>What the target keeps and changes.</b> {@code successor()} reproduces BASE36ADD for every id
 * below {@code 9999}; the ordinal ({@code A..Z} = 0..25, {@code 0..9} = 26..35, most significant digit
 * first) defines both allocation order and sort order. BASE36ADD's roll-over from {@code 9999} to
 * {@code AAAA} is a corrected defect: the codec has no successor for {@code 9999}, matching the
 * {@code NO CYCLE} sequence {@code custmast_id_seq}.
 *
 * <p><b>Reference model.</b> {@link #base36Add(String)} re-states BASE36ADD's translate-and-carry loop
 * literally, character by character, so the exhaustive test compares the codec with the source algorithm
 * rather than with the codec's own arithmetic.
 *
 * <p><b>Encoding.</b> Non-ASCII characters are written as {@code \}{@code uXXXX} escapes, so the test
 * does not depend on the source-file encoding.
 *
 * <p>Pure JUnit 5 and AssertJ: no Spring context, no database, no Docker.
 */
@DisplayName("CustomerId: base-36 codec replacing BASE36ADD and CUSTNEXT")
final class CustomerIdTest {

    /** BASE36ADD's {@code FROM} constant: ascending digit values of one position [BASE36/SRV_BASE36.RPGLE:38]. */
    private static final String BASE36ADD_FROM = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";

    /** BASE36ADD's {@code TO} constant: each {@code FROM} character's successor [BASE36/SRV_BASE36.RPGLE:39]. */
    private static final String BASE36ADD_TO = "BCDEFGHIJKLMNOPQRSTUVWXYZ0123456789A";

    /** The id format, identical to the {@code custmast_custid_ck} CHECK constraint of migration V3. */
    private static final Pattern ID_FORMAT = Pattern.compile("^[A-Z0-9]{4}$");

    private static final int MAX_REPORTED = 10;

    /**
     * BASE36ADD exactly as the source codes it [BASE36/SRV_BASE36.RPGLE:41-54]: translate the
     * rightmost position through {@code %xlate(FROM:TO)}; when the result is the last character of
     * {@code TO} ({@code A}) the position has rolled over, so move one position left and repeat. A value
     * of all {@code 9}s therefore rolls over to all {@code A}s.
     *
     * @param value the id to increment, 4 characters of {@code A-Z0-9}
     * @return the incremented id, as the source returns it
     */
    private static String base36Add(String value) {
        char[] work = value.toCharArray();
        char rolledOver = BASE36ADD_TO.charAt(BASE36ADD_TO.length() - 1);
        for (int inx = work.length - 1; inx >= 0; inx--) {
            work[inx] = BASE36ADD_TO.charAt(BASE36ADD_FROM.indexOf(work[inx]));
            if (work[inx] != rolledOver) {
                break;
            }
        }
        return new String(work);
    }

    @Nested
    @DisplayName("Constants: 4 base-36 digits give 36^4 ids")
    final class Constants {

        @Test
        @DisplayName("MIN_ORDINAL 0, MAX_ORDINAL 1,679,615, CAPACITY 1,679,616 = 36^4 = MAX_ORDINAL + 1")
        void ordinalRangeIsFourBase36Digits() {
            assertThat(CustomerId.LENGTH).isEqualTo(4);
            assertThat(CustomerId.MIN_ORDINAL).isZero();
            assertThat(CustomerId.MAX_ORDINAL).isEqualTo(1_679_615);
            assertThat(CustomerId.CAPACITY).isEqualTo(1_679_616);
            assertThat(CustomerId.CAPACITY).isEqualTo(CustomerId.MAX_ORDINAL + 1);
            assertThat(CustomerId.CAPACITY).isEqualTo(36 * 36 * 36 * 36);
        }

        @Test
        @DisplayName("LOADCUSTR start 1001 leaves 385,245 ids, the generator's automatic AAAA threshold")
        void idsFromLoadcustrStart() {
            assertThat(CustomerId.CAPACITY - CustomerId.parse("1001").toOrdinal()).isEqualTo(385_245);
        }
    }

    @Nested
    @DisplayName("BASE36ADD: successor() increments the rightmost position and carries left")
    final class Successor {

        @ParameterizedTest(name = "BASE36ADD: {0} \u2192 {1}")
        @CsvSource({
            "1009, 101A",
            "101Z, 1010",
            "AAAZ, AAA0",
            "EEEE, EEEF",
            "1001, 1002",
            "AAAA, AAAB",
            "AZ99, A0AA",
            "9998, 9999",
        })
        @DisplayName("BASE36ADD vectors [BASE36/SRV_BASE36.RPGLE:29-55]")
        void successorMatchesBase36AddVector(String in, String out) {
            CustomerId id = CustomerId.parse(in);

            assertThat(id.hasSuccessor()).isTrue();
            assertThat(id.successor()).isEqualTo(CustomerId.parse(out));
            assertThat(id.successor().value()).isEqualTo(out);
            assertThat(id.successor()).isEqualTo(CustomerId.fromOrdinal(id.toOrdinal() + 1));
            assertThat(base36Add(in)).as("reference model of BASE36ADD").isEqualTo(out);
        }

        @Test
        @DisplayName("BASE36ADD: successor() equals the source's translate-and-carry for every id AAAA..9998")
        void successorEqualsBase36AddOverTheWholeIdSpace() {
            List<String> mismatches = new ArrayList<>();
            CustomerId current = CustomerId.fromOrdinal(CustomerId.MIN_ORDINAL);
            for (int ordinal = CustomerId.MIN_ORDINAL; ordinal < CustomerId.MAX_ORDINAL; ordinal++) {
                CustomerId next = current.successor();
                String expected = base36Add(current.value());
                boolean consistent = current.toOrdinal() == ordinal
                        && current.hasSuccessor()
                        && next.value().equals(expected)
                        && next.toOrdinal() == ordinal + 1
                        && current.compareTo(next) < 0
                        && CustomerId.ORDER.compare(current, next) < 0;
                if (!consistent && mismatches.size() < MAX_REPORTED) {
                    mismatches.add(current.value() + " (ordinal " + ordinal + ") -> " + next.value()
                            + ", BASE36ADD gives " + expected);
                }
                current = next;
            }

            assertThat(mismatches).isEmpty();
            assertThat(current.value()).isEqualTo("9999");
            assertThat(current.toOrdinal()).isEqualTo(CustomerId.MAX_ORDINAL);
        }

        @Test
        @DisplayName("CUSTNEXT EEEE + BASE36ADD gives EEEF, the first interactive id [CRTDTAARA.clle, MTNCUSTR AddRecd]")
        void firstInteractiveIdFollowsCustnextInitialValue() {
            CustomerId custnext = CustomerId.parse("EEEE");

            assertThat(custnext.successor().value()).isEqualTo("EEEF");
            assertThat(custnext.successor().toOrdinal()).isEqualTo(191_957);
        }
    }

    @Nested
    @DisplayName("Ordinals: A..Z = 0..25, 0..9 = 26..35, most significant digit first")
    final class Ordinals {

        @ParameterizedTest(name = "{0} \u2194 ordinal {1}")
        @CsvSource({
            // MIN_ORDINAL; the generator start for counts above 385,245.
            "AAAA, 0",
            // First and last seed ids (V5 re-keys the seed rows 1..300).
            "AAAB, 1",
            "AAAZ, 25",
            "AAA0, 26",
            "AAIM, 300",
            // GeneratorProcessIT start id.
            "B000, 81314",
            // CUSTNEXT initial value and the custmast_id_seq START WITH.
            "EEEE, 191956",
            "EEEF, 191957",
            // Last all-letter id and first all-digit id: letters before digits.
            "ZZZZ, 1199725",
            "0000, 1247714",
            // LOADCUSTR start id and the 101A/1010 pair whose ASCII order is reversed.
            "1001, 1294371",
            "101A, 1294380",
            "1010, 1294406",
            "9990, 1679606",
            // MAX_ORDINAL; the sequence MAXVALUE.
            "9999, 1679615",
            // Carry boundaries of each digit position.
            "AAA9, 35",
            "AABA, 36",
            "AA99, 1295",
            "ABAA, 1296",
            "A999, 46655",
            "BAAA, 46656",
            "9998, 1679614",
        })
        @DisplayName("Ordinal table")
        void idAndOrdinalCorrespond(String id, int ordinal) {
            assertThat(CustomerId.parse(id).toOrdinal()).isEqualTo(ordinal);
            assertThat(CustomerId.fromOrdinal(ordinal).value()).isEqualTo(id);
        }

        @ParameterizedTest(name = "round trip of ordinal {0}")
        @ValueSource(ints = {
            0, 1, 25, 26, 35, 36, 1_295, 1_296, 46_655, 46_656,
            191_956, 191_957, 1_294_371, 1_679_614, 1_679_615,
        })
        @DisplayName("Round trip at the boundary ordinals")
        void roundTripAtBoundaries(int ordinal) {
            CustomerId id = CustomerId.fromOrdinal(ordinal);

            assertThat(id.toOrdinal()).isEqualTo(ordinal);
            assertThat(CustomerId.parse(id.value())).isEqualTo(id);
            assertThat(id.value()).hasSize(CustomerId.LENGTH).matches(ID_FORMAT);
        }

        @Test
        @DisplayName("fromOrdinal(MIN_ORDINAL) is AAAA and fromOrdinal(MAX_ORDINAL) is 9999")
        void extremesOfTheIdSpace() {
            assertThat(CustomerId.fromOrdinal(0).value()).isEqualTo("AAAA");
            assertThat(CustomerId.fromOrdinal(CustomerId.MIN_ORDINAL).value()).isEqualTo("AAAA");
            assertThat(CustomerId.fromOrdinal(CustomerId.MAX_ORDINAL).value()).isEqualTo("9999");
        }

        @Test
        @DisplayName("Every ordinal 0..1,679,615 round-trips to a distinct 4-character id of the V3 format")
        void everyOrdinalRoundTrips() {
            List<String> mismatches = new ArrayList<>();
            for (int ordinal = CustomerId.MIN_ORDINAL; ordinal <= CustomerId.MAX_ORDINAL; ordinal++) {
                CustomerId id = CustomerId.fromOrdinal(ordinal);
                String value = id.value();
                boolean consistent = id.toOrdinal() == ordinal
                        && ID_FORMAT.matcher(value).matches()
                        && CustomerId.parse(value).equals(id);
                if (!consistent && mismatches.size() < MAX_REPORTED) {
                    mismatches.add("ordinal " + ordinal + " -> " + value);
                }
            }

            assertThat(mismatches).isEmpty();
        }
    }

    @Nested
    @DisplayName("Exhaustion: 9999 has no successor (BASE36ADD's roll-over to AAAA is corrected)")
    final class Exhaustion {

        @Test
        @DisplayName("BASE36ADD rolls 9999 over to AAAA [BASE36/SRV_BASE36.RPGLE:9-13]; the codec refuses")
        void highestIdHasNoSuccessor() {
            CustomerId highest = CustomerId.parse("9999");

            assertThat(base36Add("9999")).as("source roll-over").isEqualTo("AAAA");
            assertThat(highest.hasSuccessor()).isFalse();
            assertThatIllegalStateException()
                    .isThrownBy(highest::successor)
                    .withMessageContaining("9999");
        }

        @Test
        @DisplayName("fromOrdinal(MAX_ORDINAL) has no successor either")
        void maxOrdinalHasNoSuccessor() {
            CustomerId highest = CustomerId.fromOrdinal(CustomerId.MAX_ORDINAL);

            assertThat(highest.hasSuccessor()).isFalse();
            assertThatIllegalStateException().isThrownBy(highest::successor);
        }

        @Test
        @DisplayName("9998 is the last id with a successor, and that successor is 9999")
        void lastIdWithSuccessor() {
            CustomerId penultimate = CustomerId.parse("9998");

            assertThat(penultimate.hasSuccessor()).isTrue();
            assertThat(penultimate.successor()).isEqualTo(CustomerId.parse("9999"));
        }
    }

    @Nested
    @DisplayName("Rejection: only 4 characters of A-Z or 0-9, only ordinals 0..1,679,615")
    final class Rejection {

        @ParameterizedTest(name = "fromOrdinal({0}) is rejected")
        @ValueSource(ints = {-1, 1_679_616, Integer.MAX_VALUE, Integer.MIN_VALUE})
        @DisplayName("Ordinals outside MIN_ORDINAL..MAX_ORDINAL")
        void ordinalOutOfRangeIsRejected(int ordinal) {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> CustomerId.fromOrdinal(ordinal))
                    .withMessageContaining(String.valueOf(ordinal));
        }

        /*
         * Lowercase is rejected: the codec folds no case and trims nothing, because normalization belongs
         * to TextNormalizer. "\u00c4AAA" is U+00C4 LATIN CAPITAL LETTER A WITH DIAERESIS (outside A-Z),
         * "\uff21AAA" is U+FF21 FULLWIDTH LATIN CAPITAL LETTER A, "\u0661AAA" is U+0661 ARABIC-INDIC DIGIT
         * ONE (outside 0-9), and "AAAA\n" checks that a trailing line terminator is not accepted.
         */
        @ParameterizedTest(name = "parse(\"{0}\") is rejected")
        @ValueSource(strings = {
            "abc1", "AAA", "AAAAA", "AA-A", "", "    ", "AAA ", " AAA", "aaaa", "\u00c4AAA",
            "\uff21AAA", "\u0661AAA", "AAAA\n",
        })
        @DisplayName("Text not matching ^[A-Z0-9]{4}$")
        void malformedTextIsRejected(String text) {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> CustomerId.parse(text))
                    .withMessageContaining("'" + text + "'");
        }

        @ParameterizedTest(name = "parse(null) is rejected")
        @NullSource
        @DisplayName("Null text")
        void nullTextIsRejected(String text) {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> CustomerId.parse(text))
                    .withMessageContaining("null");
        }

        @Test
        @DisplayName("Over-long text is rejected without being repeated in full")
        void overLongTextIsRejectedWithBoundedMessage() {
            String text = "A".repeat(1_000);

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> CustomerId.parse(text))
                    .withMessageNotContaining(text);
        }
    }

    @Nested
    @DisplayName("Ordering: letters before digits, the EBCDIC raw sequence [BASE36/SRV_BASE36.RPGLE:15-16]")
    final class Ordering {

        @Test
        @DisplayName("101A sorts before 1010 and ZZZZ before 0000, by compareTo and by ORDER")
        void lettersSortBeforeDigits() {
            assertThat(CustomerId.parse("101A").compareTo(CustomerId.parse("1010"))).isNegative();
            assertThat(CustomerId.ORDER.compare(CustomerId.parse("101A"), CustomerId.parse("1010"))).isNegative();
            assertThat(CustomerId.parse("ZZZZ").compareTo(CustomerId.parse("0000"))).isNegative();
            assertThat(CustomerId.ORDER.compare(CustomerId.parse("ZZZZ"), CustomerId.parse("0000"))).isNegative();
        }

        @ParameterizedTest(name = "{0} < {1}, although ASCII puts {1} first")
        @CsvSource({
            "ZAAA, 0AAA",
            "AZAA, A0AA",
            "AAZA, AA0A",
            "AAAZ, AAA0",
        })
        @DisplayName("Z sorts before 0 in every digit position")
        void letterBeforeDigitInEveryPosition(String letter, String digit) {
            assertThat(CustomerId.parse(letter)).isLessThan(CustomerId.parse(digit));
            assertThat(CustomerId.ORDER.compare(CustomerId.parse(letter), CustomerId.parse(digit))).isNegative();
            assertThat(letter.compareTo(digit)).as("plain String order").isPositive();
        }

        @Test
        @DisplayName("ORDER and the natural order sort AAAA, AAAZ, AAA0, ZZZZ, 0000, 101A, 1010, 9999")
        void sortOrderMatchesCustomerSortCollation() {
            List<String> ids = List.of("1010", "0000", "AAA0", "ZZZZ", "AAAZ", "101A", "AAAA", "9999");

            List<String> byOrder = ids.stream()
                    .map(CustomerId::parse)
                    .sorted(CustomerId.ORDER)
                    .map(CustomerId::value)
                    .toList();
            List<String> byNaturalOrder = ids.stream()
                    .map(CustomerId::parse)
                    .sorted()
                    .map(CustomerId::value)
                    .toList();

            assertThat(byOrder).containsExactly("AAAA", "AAAZ", "AAA0", "ZZZZ", "0000", "101A", "1010", "9999");
            assertThat(byNaturalOrder).isEqualTo(byOrder);
            assertThat(ids.stream().sorted().toList())
                    .as("plain String (ASCII) order, which puts digits first and must not be used")
                    .containsExactly("0000", "1010", "101A", "9999", "AAA0", "AAAA", "AAAZ", "ZZZZ");
        }

        @ParameterizedTest(name = "{0} sorts before its successor {1}")
        @CsvSource({
            "1009, 101A",
            "101Z, 1010",
            "AAAZ, AAA0",
            "EEEE, EEEF",
            "1001, 1002",
            "AAAA, AAAB",
            "AZ99, A0AA",
        })
        @DisplayName("Allocation order equals sort order")
        void allocationOrderEqualsSortOrder(String in, String out) {
            CustomerId id = CustomerId.parse(in);
            CustomerId next = id.successor();

            assertThat(next.value()).isEqualTo(out);
            assertThat(id.compareTo(next)).isNegative();
            assertThat(next.compareTo(id)).isPositive();
            assertThat(CustomerId.ORDER.compare(id, next)).isNegative();
            assertThat(CustomerId.ORDER.compare(next, id)).isPositive();
        }

        @Test
        @DisplayName("String order contradicts allocation order for 101A and 1010")
        void asciiOrderContradictsAllocationOrder() {
            assertThat("101A".compareTo("1010")).isPositive();
            assertThat(CustomerId.parse("101Z").successor().value()).isEqualTo("1010");
            assertThat(CustomerId.parse("101A").toOrdinal()).isLessThan(CustomerId.parse("1010").toOrdinal());
        }

        @Test
        @DisplayName("Equal ids compare as 0 and agree on equals and hashCode, whichever factory built them")
        void equalIdsAreConsistent() {
            CustomerId first = CustomerId.parse("EEEF");
            CustomerId second = CustomerId.parse("EEEF");
            CustomerId fromOrdinal = CustomerId.fromOrdinal(191_957);

            assertThat(first).isNotSameAs(second);
            assertThat(first.compareTo(second)).isZero();
            assertThat(CustomerId.ORDER.compare(first, second)).isZero();
            assertThat(first).isEqualTo(second).isEqualTo(fromOrdinal);
            assertThat(first.hashCode()).isEqualTo(second.hashCode()).isEqualTo(fromOrdinal.hashCode());
            assertThat(first).isNotEqualTo(CustomerId.parse("EEEG"));
            assertThat(first.equals("EEEF")).as("never equal to its text").isFalse();
            assertThat(first.equals(null)).isFalse();
        }
    }

    @Nested
    @DisplayName("Accessors: value() and toString() are the bare 4 characters")
    final class Accessors {

        @Test
        @DisplayName("value() and toString() of EEEF are \"EEEF\"")
        void valueAndToStringAreTheIdText() {
            CustomerId id = CustomerId.parse("EEEF");

            assertThat(id.value()).isEqualTo("EEEF").hasSize(CustomerId.LENGTH);
            assertThat(id.toString()).isEqualTo("EEEF").hasSize(CustomerId.LENGTH);
        }
    }
}
