package com.democorp.customermaster.generator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.democorp.customermaster.domain.Customer;
import com.democorp.customermaster.domain.CustomerId;
import com.democorp.customermaster.generator.CszSource.CszRow;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.TreeSet;
import java.util.function.DoubleSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Specifies the LOADCUSTR row rules carried by {@link CustomerDataGenerator} and the {@code Rand_Int},
 * {@code genWord} and {@code genPhone} arithmetic carried by {@link NameGenerator}.
 *
 * <p><b>What the source does.</b> LOADCUSTR's {@code for nRecds = 1 to p_recds} loop builds each row from
 * a city/state/ZIP draw, a company name, a street line, two phones and an account manager
 * [5250_Subfile/LOADCUSTR.SQLRPGLE:131-207], with {@code genWord} and {@code genPhone} below it
 * [5250_Subfile/LOADCUSTR.SQLRPGLE:213-256]. Every random value comes from {@code Rand_Int}, which scales
 * Db2 {@code RANDOM()} (0 &le; r &le; 1) as {@code %int(rf * (p_High - p_Low) + p_Low)}, so its range is
 * inclusive and {@code p_High} is drawn only when r = 1 [Service_Pgms/SRV_RANDOM.SQLRPGLE:8-33]. Its bounds
 * are {@code int(10) value}, so a fractional argument truncates on entry [Copy_Mbrs/SRV_RAND_P.RPGLE:3-6].
 * Row ids follow BASE36ADD from {@code 1001} [5250_Subfile/LOADCUSTR.SQLRPGLE:133-137],
 * [BASE36/SRV_BASE36.RPGLE:29-55].
 *
 * <p><b>How the tests reach every endpoint.</b> {@link NameGenerator} takes its unit source as a
 * {@link DoubleSupplier}, so constant suppliers {@code () -> 0.0}, {@code () -> 1.0} and
 * {@code () -> Math.nextDown(1.0)} put every {@code Rand_Int} call at {@code low}, {@code high} or
 * {@code high - 1}. The exact rows asserted at r = 0 and r = 1 were traced by hand through
 * LOADCUSTR lines 144-198 and SRV_RANDOM line 31; they are the specification, not a snapshot of the
 * implementation. Statistical rules (every 7th row inactive, every 4th row without a street number,
 * every 3rd account manager with initials) run over a seeded load of {@value #SEEDED_ROWS} rows,
 * 25 times the least common multiple of 3, 4 and 7, so every combination of the three remainders occurs.
 *
 * <p><b>Floating-point caveat.</b> The expression is evaluated in IEEE double, as the source's
 * {@code float(8)} is, so for a few ranges {@code Math.nextDown(1.0)} rounds up to {@code high}: (1, 3)
 * and (5, 11) do. No test therefore draws a CSZ row or a {@code genWord(5, 11)} length at
 * {@code Math.nextDown(1.0)}; the {@code high - 1} property is asserted only for the generator's own
 * ranges, for which it holds.
 *
 * <p><b>Out of this class.</b> Inserting a generated load against {@code custmast_state_fk} needs a
 * database and is covered by {@code CustomerGeneratorIT}; here every generated state is shown to come
 * from the CSZ list the generator was given, which {@code CszSource} has already filtered to STATES.
 *
 * <p>Pure JUnit 5 and AssertJ: no Spring context, no database, no Docker, no file I/O.
 */
@DisplayName("CustomerDataGenerator and NameGenerator: LOADCUSTR row rules and Rand_Int arithmetic")
final class CustomerDataGeneratorTest {

    /** The change time given to every generator under test. */
    private static final OffsetDateTime LOAD_TIME = OffsetDateTime.of(2026, 10, 5, 12, 0, 0, 0, ZoneOffset.UTC);

    /** First CSZ row, drawn at r = 0; its ZIP 501 exercises the zero padding. */
    private static final CszRow FIRST = new CszRow(501, "STANDARD", "FIRSTVILLE", "NY");

    /** Middle CSZ row, drawn for r &lt; 1 together with {@link #FIRST}. */
    private static final CszRow MIDDLE = new CszRow(10001, "STANDARD", "MIDTOWN", "NY");

    /** Last CSZ row, drawn only at r = 1. */
    private static final CszRow LAST = new CszRow(75001, "STANDARD", "LASTBURG", "TX");

    /** Three distinguishable CSZ rows in file order. */
    private static final List<CszRow> THREE_ROWS = List.of(FIRST, MIDDLE, LAST);

    /** LOADCUSTR's first id, {@code varCUSTID = '1001'} [5250_Subfile/LOADCUSTR.SQLRPGLE:133]. */
    private static final CustomerId START = CustomerId.parse("1001");

    /** Ordinal of {@code 1001}: digits 1, 0, 0, 1 are 27, 26, 26, 27 in BASE36ADD's alphabet. */
    private static final int START_ORDINAL = 27 * 36 * 36 * 36 + 26 * 36 * 36 + 26 * 36 + 27;

    /** Fixed seed of the statistical tests. */
    private static final long SEED = 20_261_005L;

    /** Rows of a seeded load: 25 x lcm(3, 4, 7). */
    private static final int SEEDED_ROWS = 2_100;

    /** Calls per seeded {@code genWord}/{@code genPhone} distribution test. */
    private static final int SEEDED_CALLS = 2_000;

    /** The largest unit value below 1. */
    private static final double JUST_BELOW_ONE = Math.nextDown(1.0);

    /**
     * The {@code Rand_Int} ranges LOADCUSTR uses: name and street length limit (5, 28), company type
     * (1, 21), street type (1, 28), street number (1, 5000), the three phone parts, and the vowel-biased
     * alphabet index (1, 45).
     */
    private static final String GENERATOR_RANGES = """
            5, 28
            1, 21
            1, 28
            1, 5000
            100, 900
            1, 998
            1, 9900
            1, 45
            """;

    /** {@code genPhone} shape {@code (999) 999-9999} [5250_Subfile/LOADCUSTR.SQLRPGLE:243-256]. */
    private static final Pattern PHONE = Pattern.compile("^\\((\\d{3})\\) (\\d{3})-(\\d{4})$");

    /** A street number without leading zeros, then a blank ({@code %trim(%editc(… : '3'))}). */
    private static final Pattern STREET_NUMBER = Pattern.compile("^([1-9]\\d{0,3}) ");

    /** Name words, then an optional company type, which may hold {@code &}; no blank at either end. */
    private static final Pattern NAME_SHAPE = Pattern.compile("^[A-Z]+( [A-Z&]+)*$");

    /** An optional street number, words, then an optional street type; no blank at either end. */
    private static final Pattern ADDR_SHAPE = Pattern.compile("^([1-9]\\d{0,3} )?[A-Z]+( [A-Z]+)*$");

    /** Account manager on every 3rd row: {@code genWord(1:1) genWord(1:1) genWord(4:10)}. */
    private static final Pattern ACCT_MGR_INITIALS = Pattern.compile("^[A-Z] [A-Z] [A-Z]{4,10}$");

    /** Account manager on the other rows: {@code genWord(3:6) genWord(5:9)}. */
    private static final Pattern ACCT_MGR_WORDS = Pattern.compile("^[A-Z]{4,6} [A-Z]{6,10}$");

    /** Every generated word: uppercase letters of the two alphabets. */
    private static final Pattern WORD = Pattern.compile("^[A-Z]+$");

    /** Twelve Z letters: one {@code genWord(5, 11)} at r = 1. */
    private static final String Z12 = "Z".repeat(12);

    /**
     * A name generator whose every draw is {@code r}.
     *
     * @param r the constant unit value
     * @return the generator
     */
    private static NameGenerator constant(double r) {
        return new NameGenerator(() -> r);
    }

    /**
     * A data generator over a constant unit value and the three-row CSZ list.
     *
     * @param r the constant unit value
     * @return the generator
     */
    private static CustomerDataGenerator constantRows(double r) {
        return new CustomerDataGenerator(constant(r), THREE_ROWS, LOAD_TIME);
    }

    /**
     * Generates a seeded load of {@value #SEEDED_ROWS} rows from {@code 1001}; element i is row i + 1.
     *
     * @param csz the CSZ rows
     * @return the rows in load order
     */
    private static List<Customer> seededRows(List<CszRow> csz) {
        return drain(new CustomerDataGenerator(NameGenerator.seeded(SEED), csz, LOAD_TIME)
                .generate(START, SEEDED_ROWS));
    }

    /**
     * Collects every remaining element of an iterator.
     *
     * @param rows the iterator
     * @return the elements in order
     */
    private static List<Customer> drain(Iterator<Customer> rows) {
        List<Customer> out = new ArrayList<>();
        rows.forEachRemaining(out::add);
        return out;
    }

    /**
     * The unit value that makes {@code randInt(min, max)} return {@code t}: the middle of t's interval for
     * t &lt; max, and exactly 1 for t = max.
     *
     * @param min the lower bound
     * @param max the upper bound
     * @param t   the wanted result, {@code min <= t <= max}
     * @return the unit value
     */
    private static double unitFor(int min, int max, int t) {
        return t == max ? 1.0 : (t - min + 0.5) / (max - min);
    }

    /**
     * Asserts a phone's shape and the {@code Rand_Int} ranges of its three parts.
     *
     * @param softly the soft assertions collector
     * @param phone  the phone text
     * @param label  what the phone is, for the failure message
     */
    private static void assertPhone(SoftAssertions softly, String phone, String label) {
        Matcher m = PHONE.matcher(phone);
        softly.assertThat(m.matches()).as("%s %s matches (999) 999-9999", label, phone).isTrue();
        if (m.matches()) {
            softly.assertThat(Integer.parseInt(m.group(1))).as("%s area code", label).isBetween(100, 900);
            softly.assertThat(Integer.parseInt(m.group(2))).as("%s exchange", label).isBetween(1, 998);
            softly.assertThat(Integer.parseInt(m.group(3))).as("%s line", label).isBetween(1, 9900);
        }
    }

    /** A unit source that counts its draws and delegates the values. */
    private static final class CountingUnit implements DoubleSupplier {

        private final DoubleSupplier delegate;

        private long draws;

        CountingUnit(DoubleSupplier delegate) {
            this.delegate = delegate;
        }

        @Override
        public double getAsDouble() {
            draws++;
            return delegate.getAsDouble();
        }

        long draws() {
            return draws;
        }
    }

    /** A unit source that returns the scripted values in order, then {@code rest} for every later draw. */
    private static final class ScriptedUnit implements DoubleSupplier {

        private final double[] script;

        private final double rest;

        private int next;

        ScriptedUnit(double rest, double... script) {
            this.rest = rest;
            this.script = Arrays.copyOf(script, script.length);
        }

        @Override
        public double getAsDouble() {
            return next < script.length ? script[next++] : rest;
        }
    }

    /** {@code Rand_Int} [Service_Pgms/SRV_RANDOM.SQLRPGLE:21-33] as {@link NameGenerator#randInt(int, int)}. */
    @Nested
    @DisplayName("randInt: Rand_Int's inclusive, truncating scale")
    class RandInt {

        @ParameterizedTest(name = "({0}, {1}) at r = 0 is {0}")
        @CsvSource(textBlock = GENERATOR_RANGES)
        void zeroYieldsLow(int low, int high) {
            assertThat(constant(0.0).randInt(low, high)).isEqualTo(low);
        }

        @ParameterizedTest(name = "({0}, {1}) at r = 1 is {1}")
        @CsvSource(textBlock = GENERATOR_RANGES)
        void oneYieldsHigh(int low, int high) {
            assertThat(constant(1.0).randInt(low, high)).isEqualTo(high);
        }

        @ParameterizedTest(name = "({0}, {1}) just below r = 1 is {1} - 1")
        @CsvSource(textBlock = GENERATOR_RANGES)
        void justBelowOneYieldsHighMinusOne(int low, int high) {
            assertThat(constant(JUST_BELOW_ONE).randInt(low, high)).isEqualTo(high - 1);
        }

        @Test
        void truncatesInsteadOfRounding() {
            // 0.75 * (3 - 1) + 1 = 2.5: %int keeps 2, rounding would give 3 (or 2 by half-even).
            assertThat(constant(0.75).randInt(1, 3)).isEqualTo(2);
            // 0.99 * (2 - 1) + 1 = 1.99: truncated to 1.
            assertThat(constant(0.99).randInt(1, 2)).isEqualTo(1);
        }

        @Test
        void truncatesNegativeSumsTowardZeroAsPercentIntDoes() {
            // 0.75 * (-2 - -4) + -4 = -2.5: %int truncates toward zero to -2; floor would give -3.
            assertThat(constant(0.75).randInt(-4, -2)).isEqualTo(-2);
        }

        @Test
        void companyTypeBoundOf21Point6TruncatesTo21() {
            // j = Rand_Int(1 : %elem(companyType) * 1.8) [5250_Subfile/LOADCUSTR.SQLRPGLE:161]: int(10) value.
            double bound = CustomerDataGenerator.COMPANY_TYPES.size() * 1.8;
            assertThat(constant(1.0).randInt(1, bound)).isEqualTo(21);
            assertThat(constant(0.0).randInt(1, bound)).isEqualTo(1);
        }

        @Test
        void streetTypeBoundIsExactly28() {
            // j = Rand_Int(1 : %elem(streetType) * 1.75) [5250_Subfile/LOADCUSTR.SQLRPGLE:179].
            double bound = CustomerDataGenerator.STREET_TYPES.size() * 1.75;
            assertThat(constant(1.0).randInt(1, bound)).isEqualTo(28);
            assertThat(constant(0.0).randInt(1, bound)).isEqualTo(1);
        }

        @Test
        void drawsExactlyOneUnitValuePerCallEvenForAOneValueRange() {
            // Rand_Int runs "set :rf = random()" on every call, whatever its bounds.
            CountingUnit unit = new CountingUnit(() -> 0.5);
            NameGenerator names = new NameGenerator(unit);

            names.randInt(1, 5000);
            names.randInt(3, 3);
            names.randInt(1.0, 21.6);

            assertThat(unit.draws()).isEqualTo(3);
        }

        @Test
        void seededInclusiveUnitStaysWithinZeroAndOne() {
            DoubleSupplier unit = NameGenerator.inclusiveUnit(new SplittableRandom(SEED));
            for (int i = 0; i < SEEDED_CALLS; i++) {
                assertThat(unit.getAsDouble()).isBetween(0.0, 1.0);
            }
        }

        @Test
        void rejectsANullUnitSource() {
            assertThatNullPointerException().isThrownBy(() -> new NameGenerator(null));
        }
    }

    /** The two alphabets of {@code genWord} [5250_Subfile/LOADCUSTR.SQLRPGLE:220-224]. */
    @Nested
    @DisplayName("ALPHA and V_ALPHA: genWord's alphabets copied character for character")
    class Alphabets {

        @Test
        void alphaIsTheSourceLiteralWithTheFinalZAtIndex28() {
            assertThat(NameGenerator.ALPHA).isEqualTo("ABCDEFGHIIJKLMNOPQRSTUVWXYZZ").hasSize(28);
            assertThat(NameGenerator.ALPHA.charAt(28 - 1)).isEqualTo('Z');
        }

        @Test
        void vowelBiasedAlphaIsTheSourceLiteralWithTheFinalZAtIndex45() {
            assertThat(NameGenerator.V_ALPHA)
                    .isEqualTo("AAAAABCDEEEEEFGHIIIIIJKLMNOOOOOPQRSTUUUVWXYZZ")
                    .hasSize(45);
            assertThat(NameGenerator.V_ALPHA.charAt(45 - 1)).isEqualTo('Z');
        }

        @Test
        void atROneBothLetterDrawsPickTheFinalIndex() {
            NameGenerator names = constant(1.0);
            assertThat(names.randInt(1, NameGenerator.ALPHA.length())).isEqualTo(28);
            assertThat(names.randInt(1, NameGenerator.V_ALPHA.length())).isEqualTo(45);
        }
    }

    /** {@code genWord} [5250_Subfile/LOADCUSTR.SQLRPGLE:214-240]. */
    @Nested
    @DisplayName("genWord: first letters, then letter pairs for j = 1 by 2 to TgtL")
    class GenWord {

        /**
         * Length for each {@code randInt(min, max)} result t: two leading letters (one when min or max is 1),
         * plus one pair per {@code j = 1, 3, 5, …} up to {@code TgtL = t - 2}.
         */
        @ParameterizedTest(name = "genWord({0}, {1}) with Rand_Int = {2} has {3} letters")
        @CsvSource(textBlock = """
                5, 11,  5,  6
                5, 11,  6,  6
                5, 11,  7,  8
                5, 11,  8,  8
                5, 11,  9, 10
                5, 11, 10, 10
                5, 11, 11, 12
                4, 10,  4,  4
                4, 10,  5,  6
                4, 10,  6,  6
                4, 10,  7,  8
                4, 10,  8,  8
                4, 10,  9, 10
                4, 10, 10, 10
                3,  6,  3,  4
                3,  6,  4,  4
                3,  6,  5,  6
                3,  6,  6,  6
                5,  9,  5,  6
                5,  9,  6,  6
                5,  9,  7,  8
                5,  9,  8,  8
                5,  9,  9, 10
                1,  1,  1,  1
                """)
        void lengthFollowsTheSourceLoop(int min, int max, int t, int expectedLength) {
            NameGenerator names = constant(unitFor(min, max, t));
            assertThat(names.randInt(min, max)).as("unit value reaches Rand_Int = %d", t).isEqualTo(t);

            assertThat(names.genWord(min, max)).hasSize(expectedLength).matches(WORD);
        }

        @ParameterizedTest(name = "genWord({0}, {1}) at r = 1 is {2}")
        @CsvSource(textBlock = """
                5, 11, ZZZZZZZZZZZZ
                4, 10, ZZZZZZZZZZ
                3,  6, ZZZZZZ
                5,  9, ZZZZZZZZZZ
                1,  1, Z
                """)
        void atROneEveryLetterIsTheFinalZAndTheLengthIsTheLongest(int min, int max, String expected) {
            assertThat(constant(1.0).genWord(min, max)).isEqualTo(expected);
        }

        @ParameterizedTest(name = "genWord({0}, {1}) at r = 0 is {2}")
        @CsvSource(textBlock = """
                5, 11, AAAAAA
                4, 10, AAAA
                3,  6, AAAA
                5,  9, AAAAAA
                1,  1, A
                """)
        void atRZeroEveryLetterIsTheFirstAAndTheLengthIsTheShortest(int min, int max, String expected) {
            assertThat(constant(0.0).genWord(min, max)).isEqualTo(expected);
        }

        /**
         * For r &lt; 1 the top {@code Rand_Int} value is not drawn, so the longest length of (5, 11) (12) and
         * of (5, 9) (10) appears only at r = 1, which the endpoint test covers; (4, 10) and (3, 6) reach their
         * longest length one value below the top, at t = 9 and t = 5.
         */
        @ParameterizedTest(name = "seeded genWord({0}, {1}) lengths are {2}")
        @CsvSource(textBlock = """
                5, 11, 6 8 10
                4, 10, 4 6 8 10
                3,  6, 4 6
                5,  9, 6 8
                1,  1, 1
                """)
        void seededLengthsCoverEveryValueBelowROne(int min, int max, String expectedLengths) {
            NameGenerator names = NameGenerator.seeded(SEED);
            Set<Integer> lengths = new TreeSet<>();
            for (int i = 0; i < SEEDED_CALLS; i++) {
                String word = names.genWord(min, max);
                assertThat(word).matches(WORD);
                lengths.add(word.length());
            }
            Set<Integer> expected = Arrays.stream(expectedLengths.split(" "))
                    .map(Integer::valueOf)
                    .collect(Collectors.toCollection(TreeSet::new));

            assertThat(lengths).isEqualTo(expected);
        }

        /**
         * Draw counts of the source procedure: one Alpha index, one vAlpha index unless MinL or MaxL is 1,
         * one TgtL, and two per loop pass.
         */
        @ParameterizedTest(name = "genWord({0}, {1}) at r = {2} draws {3} values")
        @CsvSource(textBlock = """
                1,  1, 0.0,  2
                1,  1, 1.0,  2
                3,  6, 0.0,  5
                5, 11, 0.0,  7
                5, 11, 1.0, 13
                4, 10, 1.0, 11
                """)
        void drawsOneValuePerSourceRandIntCall(int min, int max, double r, long expectedDraws) {
            CountingUnit unit = new CountingUnit(() -> r);

            new NameGenerator(unit).genWord(min, max);

            assertThat(unit.draws()).isEqualTo(expectedDraws);
        }
    }

    /** {@code genPhone} [5250_Subfile/LOADCUSTR.SQLRPGLE:243-256]. */
    @Nested
    @DisplayName("genPhone: (Rand_Int(100:900)) Rand_Int(1:998)-Rand_Int(1:9900), zero-padded")
    class GenPhone {

        @Test
        void atRZeroEveryPartIsItsLowAndZeroPadded() {
            assertThat(constant(0.0).genPhone()).isEqualTo("(100) 001-0001");
        }

        @Test
        void atROneEveryPartIsItsHigh() {
            assertThat(constant(1.0).genPhone()).isEqualTo("(900) 998-9900");
        }

        @Test
        void justBelowROneEveryPartIsOneBelowItsHigh() {
            assertThat(constant(JUST_BELOW_ONE).genPhone()).isEqualTo("(899) 997-9899");
        }

        @Test
        void seededPhonesKeepTheShapeAndTheRanges() {
            NameGenerator names = NameGenerator.seeded(SEED);
            SoftAssertions.assertSoftly(softly -> {
                for (int i = 0; i < SEEDED_CALLS; i++) {
                    assertPhone(softly, names.genPhone(), "phone " + i);
                }
            });
        }

        @Test
        void drawsAreaThenExchangeThenLine() {
            NameGenerator names = new NameGenerator(new ScriptedUnit(0.0, 1.0, 0.0, 1.0));
            assertThat(names.genPhone()).isEqualTo("(900) 001-9900");
        }
    }

    /**
     * Exact rows at the range endpoints, traced through the loop body [5250_Subfile/LOADCUSTR.SQLRPGLE:131-198].
     *
     * <p>At r = 1 every limit is its maximum: name and street length limit 28, words of 12 letters, so the
     * name loop stops at three words (13, 26, 39 characters) and the street loop at two after
     * {@code "5000 "} (5, 18, 31); the type draws are 21 and 28, above 12 and 16, so no type is appended.
     * At r = 0 the limit is 5, one 6-letter word passes it, and the type draws are 1: {@code INC},
     * {@code STREET}.
     */
    @Nested
    @DisplayName("row: exact rows at r = 0 and r = 1")
    class EndpointRows {

        @Test
        void atROneRowOneTakesEveryMaximum() {
            Customer row = constantRows(1.0).row(1, START);

            assertThat(row.custId()).isEqualTo(START);
            assertThat(row.active()).isEqualTo("Y");
            assertThat(row.address().city()).isEqualTo("LASTBURG");
            assertThat(row.address().state()).isEqualTo("TX");
            assertThat(row.address().zip()).isEqualTo("75001");
            assertThat(row.name()).isEqualTo(Z12 + " " + Z12 + " " + Z12).hasSize(38);
            assertThat(row.address().addr()).isEqualTo("5000 " + Z12 + " " + Z12);
            assertThat(row.corpPhone()).isEqualTo("(900) 998-9900");
            assertThat(row.acctPhone()).isEqualTo("(900) 998-9900");
            assertThat(row.acctMgr()).isEqualTo("ZZZZZZ ZZZZZZZZZZ");
        }

        @Test
        void atROneNoCompanyTypeAndNoStreetTypeIsAppended() {
            Customer row = constantRows(1.0).row(1, START);

            assertThat(CustomerDataGenerator.COMPANY_TYPES)
                    .noneMatch(type -> row.name().endsWith(" " + type));
            assertThat(CustomerDataGenerator.STREET_TYPES)
                    .noneMatch(type -> row.address().addr().endsWith(" " + type));
        }

        @Test
        void atROneRowFourHasNoStreetNumber() {
            assertThat(constantRows(1.0).row(4, START).address().addr())
                    .isEqualTo(Z12 + " " + Z12 + " " + Z12);
        }

        @Test
        void atROneRowThreeHasAnAccountManagerWithInitials() {
            assertThat(constantRows(1.0).row(3, START).acctMgr()).isEqualTo("Z Z ZZZZZZZZZZ");
        }

        @Test
        void atROneRowSevenIsInactive() {
            assertThat(constantRows(1.0).row(7, START).active()).isEqualTo("N");
        }

        @Test
        void atRZeroRowOneTakesEveryMinimum() {
            Customer row = constantRows(0.0).row(1, START);

            assertThat(row.active()).isEqualTo("Y");
            assertThat(row.address().city()).isEqualTo("FIRSTVILLE");
            assertThat(row.address().state()).isEqualTo("NY");
            assertThat(row.address().zip()).isEqualTo("00501");
            assertThat(row.name()).isEqualTo("AAAAAA INC");
            assertThat(row.address().addr()).isEqualTo("1 AAAAAA STREET");
            assertThat(row.corpPhone()).isEqualTo("(100) 001-0001");
            assertThat(row.acctPhone()).isEqualTo("(100) 001-0001");
            assertThat(row.acctMgr()).isEqualTo("AAAA AAAAAA");
        }

        @Test
        void atRZeroRowFourHasNoStreetNumber() {
            assertThat(constantRows(0.0).row(4, START).address().addr()).isEqualTo("AAAAAA STREET");
        }

        @Test
        void atRZeroRowThreeHasAnAccountManagerWithInitials() {
            assertThat(constantRows(0.0).row(3, START).acctMgr()).isEqualTo("A A AAAA");
        }

        @Test
        void atROneEveryRowDrawsTheLastCszRow() {
            List<Customer> rows = drain(constantRows(1.0).generate(START, 50));

            assertThat(rows).hasSize(50).allSatisfy(row -> {
                assertThat(row.address().city()).isEqualTo(LAST.city());
                assertThat(row.address().state()).isEqualTo(LAST.state());
                assertThat(row.address().zip()).isEqualTo("75001");
            });
        }

        @Test
        void atRZeroEveryRowDrawsTheFirstCszRow() {
            List<Customer> rows = drain(constantRows(0.0).generate(START, 50));

            assertThat(rows).hasSize(50).allSatisfy(row -> {
                assertThat(row.address().city()).isEqualTo(FIRST.city());
                assertThat(row.address().state()).isEqualTo(FIRST.state());
                assertThat(row.address().zip()).isEqualTo("00501");
            });
        }

        @Test
        void theCszIndexIsTheFirstDrawOfARow() {
            // csz_I = Rand_Int(1 : %elem(csz_a)) runs before every other draw [5250_Subfile/LOADCUSTR.SQLRPGLE:144].
            NameGenerator names = new NameGenerator(new ScriptedUnit(0.0, 1.0));
            Customer row = new CustomerDataGenerator(names, THREE_ROWS, LOAD_TIME).row(1, START);

            assertThat(row.address().city()).isEqualTo(LAST.city());
            assertThat(row.name()).isEqualTo("AAAAAA INC");
            assertThat(row.address().addr()).isEqualTo("1 AAAAAA STREET");
        }

        @Test
        void theStreetLengthLimitIsDrawnBeforeTheStreetNumber() {
            // MaxL (line 170) precedes Rand_Int(1:5000) (line 172). Draws 1-10 are the CSZ index and the name;
            // draw 11 is the street limit (0 -> 5) and draw 12 the number (1 -> 5000). Drawn the other way
            // round, the number would be 1 and the limit 28, giving a four-word street.
            double[] script = new double[12];
            script[11] = 1.0;
            NameGenerator names = new NameGenerator(new ScriptedUnit(0.0, script));
            Customer row = new CustomerDataGenerator(names, THREE_ROWS, LOAD_TIME).row(1, START);

            assertThat(row.name()).isEqualTo("AAAAAA INC");
            assertThat(row.address().addr()).isEqualTo("5000 AAAAAA STREET");
        }

        /**
         * One unit value per {@code Rand_Int} call of the loop body, counted on the source. At r = 0, row 1:
         * CSZ 1; name 1 + 7 + 1; street 1 + 1 + 7 + 1; phones 6; account manager 5 + 7. Row 3 replaces the
         * account manager's 12 draws with 2 + 2 + 5; row 4 has no street-number draw. At r = 1, row 1: CSZ 1;
         * name 1 + 3 x 13 + 1; street 1 + 1 + 2 x 13 + 1; phones 6; account manager 7 + 11.
         */
        @ParameterizedTest(name = "row {0} at r = {1} draws {2} values")
        @CsvSource(textBlock = """
                1, 0.0, 38
                3, 0.0, 35
                4, 0.0, 37
                1, 1.0, 95
                """)
        void drawsOneValuePerSourceRandIntCall(long rowNumber, double r, long expectedDraws) {
            CountingUnit unit = new CountingUnit(() -> r);
            CustomerDataGenerator generator =
                    new CustomerDataGenerator(new NameGenerator(unit), THREE_ROWS, LOAD_TIME);

            generator.row(rowNumber, START);

            assertThat(unit.draws()).isEqualTo(expectedDraws);
        }
    }

    /** Row rules over a seeded load [5250_Subfile/LOADCUSTR.SQLRPGLE:138-200]. */
    @Nested
    @DisplayName("row rules over a seeded load of 2,100 rows")
    class SeededRowRules {

        @Test
        void activeIsNExactlyOnEverySeventhRow() {
            List<Customer> rows = seededRows(THREE_ROWS);
            SoftAssertions.assertSoftly(softly -> {
                for (int i = 0; i < rows.size(); i++) {
                    long n = i + 1L;
                    softly.assertThat(rows.get(i).active()).as("active of row %d", n)
                            .isEqualTo(n % 7 == 0 ? "N" : "Y");
                }
            });
        }

        @Test
        void streetNumberIsPresentExactlyWhenTheRowIsNotAMultipleOfFour() {
            List<Customer> rows = seededRows(THREE_ROWS);
            SoftAssertions.assertSoftly(softly -> {
                for (int i = 0; i < rows.size(); i++) {
                    long n = i + 1L;
                    String addr = rows.get(i).address().addr();
                    Matcher number = STREET_NUMBER.matcher(addr);
                    boolean hasNumber = number.find();
                    if (n % 4 != 0) {
                        softly.assertThat(hasNumber).as("row %d street %s has a number", n, addr).isTrue();
                        if (hasNumber) {
                            softly.assertThat(Integer.parseInt(number.group(1)))
                                    .as("row %d street number", n).isBetween(1, 5000);
                        }
                    } else {
                        softly.assertThat(addr).as("row %d street starts with a letter", n).matches("^[A-Z].*");
                    }
                }
            });
        }

        @Test
        void bothPhonesKeepTheShapeAndTheRanges() {
            List<Customer> rows = seededRows(THREE_ROWS);
            SoftAssertions.assertSoftly(softly -> {
                for (int i = 0; i < rows.size(); i++) {
                    assertPhone(softly, rows.get(i).corpPhone(), "row " + (i + 1) + " corporate phone");
                    assertPhone(softly, rows.get(i).acctPhone(), "row " + (i + 1) + " account phone");
                }
            });
        }

        @Test
        void nameAndStreetFitFortyCharactersWithNoSurroundingBlank() {
            List<Customer> rows = seededRows(THREE_ROWS);
            SoftAssertions.assertSoftly(softly -> {
                for (int i = 0; i < rows.size(); i++) {
                    Customer row = rows.get(i);
                    softly.assertThat(row.name()).as("row %d name", i + 1)
                            .hasSizeLessThanOrEqualTo(CustomerDataGenerator.NAME_MAX)
                            .matches(NAME_SHAPE);
                    softly.assertThat(row.address().addr()).as("row %d street", i + 1)
                            .hasSizeLessThanOrEqualTo(CustomerDataGenerator.ADDR_MAX)
                            .matches(ADDR_SHAPE);
                }
            });
        }

        @Test
        void companyAndStreetTypesAreAppendedToSomeRowsAndNotToOthers() {
            List<Customer> rows = seededRows(THREE_ROWS);

            assertThat(rows).anyMatch(row -> CustomerDataGenerator.COMPANY_TYPES.stream()
                            .anyMatch(type -> row.name().endsWith(" " + type)))
                    .anyMatch(row -> CustomerDataGenerator.COMPANY_TYPES.stream()
                            .noneMatch(type -> row.name().endsWith(" " + type)))
                    .anyMatch(row -> CustomerDataGenerator.STREET_TYPES.stream()
                            .anyMatch(type -> row.address().addr().endsWith(" " + type)))
                    .anyMatch(row -> CustomerDataGenerator.STREET_TYPES.stream()
                            .noneMatch(type -> row.address().addr().endsWith(" " + type)));
        }

        @Test
        void accountManagerHasInitialsExactlyOnEveryThirdRow() {
            List<Customer> rows = seededRows(THREE_ROWS);
            SoftAssertions.assertSoftly(softly -> {
                for (int i = 0; i < rows.size(); i++) {
                    long n = i + 1L;
                    softly.assertThat(rows.get(i).acctMgr()).as("account manager of row %d", n)
                            .matches(n % 3 == 0 ? ACCT_MGR_INITIALS : ACCT_MGR_WORDS);
                }
            });
        }

        @Test
        void cityStateAndZipAlwaysComeFromOneListRow() {
            List<Customer> rows = seededRows(THREE_ROWS);
            Set<List<String>> expected = THREE_ROWS.stream()
                    .map(csz -> List.of(csz.city(), csz.state(),
                            String.format(Locale.ROOT, "%05d", csz.zip() % 100_000)))
                    .collect(Collectors.toSet());

            assertThat(rows).allSatisfy(row -> {
                assertThat(row.address().zip()).matches("^\\d{5}$");
                assertThat(List.of(row.address().city(), row.address().state(), row.address().zip()))
                        .isIn(expected);
            });
        }

        @Test
        void theLastCszRowIsNeverDrawnBelowROne() {
            // Rand_Int(1 : %elem(csz_a)) yields rows 1..N-1 for r < 1 and row N only at r = 1.
            Set<String> cities = seededRows(THREE_ROWS).stream()
                    .map(row -> row.address().city())
                    .collect(Collectors.toSet());

            assertThat(cities).containsExactlyInAnyOrder(FIRST.city(), MIDDLE.city());
        }

        @Test
        void aSingleRowListZeroPadsEveryZip() {
            // wk10 = %editc(zip : 'X'); Fld.ZIP = %subst(wk10 : 6 : 5) [5250_Subfile/LOADCUSTR.SQLRPGLE:147-148].
            assertThat(seededRows(List.of(FIRST))).allSatisfy(row -> {
                assertThat(row.address().zip()).isEqualTo("00501");
                assertThat(row.address().city()).isEqualTo("FIRSTVILLE");
                assertThat(row.address().state()).isEqualTo("NY");
            });
        }

        @Test
        void zipKeepsTheLastFiveDigitsOfALongerNumber() {
            CszRow sixDigits = new CszRow(1_234_567, "STANDARD", "LONGZIP", "CA");
            Customer row =
                    new CustomerDataGenerator(constant(0.0), List.of(sixDigits), LOAD_TIME).row(1, START);

            assertThat(row.address().zip()).isEqualTo("34567");
        }

        @Test
        void everyRowCarriesTheSystemStampAtTheLoadTimeAndVersionZero() {
            assertThat(seededRows(THREE_ROWS)).allSatisfy(row -> {
                assertThat(row.chgUser()).isEqualTo("*SYSTEM*").isEqualTo(CustomerDataGenerator.SYSTEM_USER);
                assertThat(row.chgTime()).isEqualTo(LOAD_TIME);
                assertThat(row.rowVersion()).isZero();
            });
        }
    }

    /** {@link CustomerDataGenerator#generate(CustomerId, int)}: ids, laziness and reproducibility. */
    @Nested
    @DisplayName("generate: successive ids, built lazily, reproducible by seed")
    class Generate {

        @Test
        void startIdOf1001HasOrdinal1294371() {
            assertThat(START.toOrdinal()).isEqualTo(START_ORDINAL).isEqualTo(1_294_371);
        }

        @Test
        void yieldsExactlyCountRowsWithSuccessiveOrdinals() {
            Iterator<Customer> rows = constantRows(0.0).generate(START, 50);
            List<CustomerId> ids = new ArrayList<>();
            for (int i = 0; i < 50; i++) {
                assertThat(rows.hasNext()).as("row %d is available", i + 1).isTrue();
                ids.add(rows.next().custId());
            }

            assertThat(rows.hasNext()).isFalse();
            for (int i = 0; i < ids.size(); i++) {
                assertThat(ids.get(i)).as("id of row %d", i + 1)
                        .isEqualTo(CustomerId.fromOrdinal(START_ORDINAL + i));
            }
        }

        @Test
        void idsFollowBase36AddFrom1001() {
            // varCUSTID = BASE36ADD(varCUSTID) per row: 1001 .. 1009, then 9 rolls to A with a carry.
            List<String> ids = drain(constantRows(0.0).generate(START, 12)).stream()
                    .map(row -> row.custId().value())
                    .toList();

            assertThat(ids).containsExactly(
                    "1001", "1002", "1003", "1004", "1005", "1006", "1007", "1008", "1009",
                    "101A", "101B", "101C");
            for (int i = 1; i < ids.size(); i++) {
                assertThat(CustomerId.parse(ids.get(i - 1)).successor().value()).isEqualTo(ids.get(i));
            }
        }

        @Test
        void throwsNoSuchElementAfterTheLastRow() {
            Iterator<Customer> rows = constantRows(0.0).generate(START, 1);
            rows.next();

            assertThat(rows.hasNext()).isFalse();
            assertThatThrownBy(rows::next).isInstanceOf(NoSuchElementException.class);
        }

        @Test
        void drawsNothingUntilARowIsRequested() {
            CountingUnit unit = new CountingUnit(() -> 0.0);
            CustomerDataGenerator generator =
                    new CustomerDataGenerator(new NameGenerator(unit), THREE_ROWS, LOAD_TIME);

            // Every id left from 1001 through 9999: building them up front would draw millions of values.
            Iterator<Customer> rows = generator.generate(START, CustomerId.CAPACITY - START_ORDINAL);
            assertThat(rows.hasNext()).isTrue();
            assertThat(unit.draws()).as("draws before next()").isZero();

            rows.next();
            assertThat(unit.draws()).as("draws for row 1 at r = 0").isEqualTo(38);
        }

        @Test
        void theIthRowEqualsRowIWithTheIthIdOfATwinGenerator() {
            List<Customer> generated = seededLoad(SEED, 30);
            CustomerDataGenerator twin =
                    new CustomerDataGenerator(NameGenerator.seeded(SEED), THREE_ROWS, LOAD_TIME);
            List<Customer> built = new ArrayList<>();
            for (int i = 1; i <= 30; i++) {
                built.add(twin.row(i, CustomerId.fromOrdinal(START_ORDINAL + i - 1)));
            }

            assertThat(generated).isEqualTo(built);
        }

        @Test
        void theSameSeedReproducesTheLoad() {
            // Customer.equals compares all ten properties: name, street, phones and account manager included.
            List<Customer> first = seededLoad(7, 200);
            List<Customer> second = seededLoad(7, 200);

            assertThat(second).hasSize(200).isEqualTo(first);
        }

        @Test
        void differentSeedsProduceDifferentLoads() {
            List<String> seven = seededLoad(7, 200).stream().map(Customer::name).toList();
            List<String> eight = seededLoad(8, 200).stream().map(Customer::name).toList();

            assertThat(eight).isNotEqualTo(seven);
        }

        /**
         * Generates {@code count} rows from {@code 1001} over the three-row list with a seeded generator.
         *
         * @param seed  the seed
         * @param count the row count
         * @return the rows in load order
         */
        private List<Customer> seededLoad(long seed, int count) {
            return drain(new CustomerDataGenerator(NameGenerator.seeded(seed), THREE_ROWS, LOAD_TIME)
                    .generate(START, count));
        }

        @Test
        void aLoadMayEndExactlyAt9999() {
            List<Customer> rows = drain(constantRows(0.0).generate(CustomerId.parse("9999"), 1));

            assertThat(rows).singleElement()
                    .satisfies(row -> assertThat(row.custId().value()).isEqualTo("9999"));
        }

        @Test
        void rejectsALoadBeyond9999() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> constantRows(0.0).generate(CustomerId.parse("9999"), 2));
        }

        @Test
        void rejectsACountBelowOne() {
            assertThatIllegalArgumentException().isThrownBy(() -> constantRows(0.0).generate(START, 0));
        }

        @Test
        void rejectsANullStartId() {
            assertThatNullPointerException().isThrownBy(() -> constantRows(0.0).generate(null, 1));
        }
    }

    /** Constructor and {@link CustomerDataGenerator#row(long, CustomerId)} argument checks. */
    @Nested
    @DisplayName("construction and row arguments")
    class Construction {

        @Test
        void rejectsAnEmptyCszList() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new CustomerDataGenerator(constant(0.0), List.of(), LOAD_TIME));
        }

        @Test
        void rejectsNullArguments() {
            assertThatNullPointerException()
                    .isThrownBy(() -> new CustomerDataGenerator(null, THREE_ROWS, LOAD_TIME));
            assertThatNullPointerException()
                    .isThrownBy(() -> new CustomerDataGenerator(constant(0.0), null, LOAD_TIME));
            assertThatNullPointerException()
                    .isThrownBy(() -> new CustomerDataGenerator(constant(0.0), THREE_ROWS, null));
        }

        @Test
        void copiesTheCszListSoLaterChangesHaveNoEffect() {
            List<CszRow> csz = new ArrayList<>(THREE_ROWS);
            CustomerDataGenerator generator = new CustomerDataGenerator(constant(0.0), csz, LOAD_TIME);
            csz.set(0, LAST);

            assertThat(generator.row(1, START).address().city()).isEqualTo(FIRST.city());
        }

        @Test
        void rejectsARowNumberBelowOne() {
            assertThatIllegalArgumentException().isThrownBy(() -> constantRows(0.0).row(0, START));
        }

        @Test
        void rejectsANullRowId() {
            assertThatNullPointerException().isThrownBy(() -> constantRows(0.0).row(1, null));
        }
    }
}
