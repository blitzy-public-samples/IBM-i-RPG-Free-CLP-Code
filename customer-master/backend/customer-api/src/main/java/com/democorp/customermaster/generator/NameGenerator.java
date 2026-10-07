package com.democorp.customermaster.generator;

import java.util.Locale;
import java.util.Objects;
import java.util.SplittableRandom;
import java.util.function.DoubleSupplier;
import java.util.random.RandomGenerator;

/**
 * Random building blocks for generated customer rows: bounded integers, pseudo
 * words and phone numbers.
 *
 * <p>This class replaces three IBM i procedures and keeps their arithmetic:
 * <ul>
 *   <li>{@code Rand_Int} of the SRV_RANDOM service program
 *       ({@code Service_Pgms/SRV_RANDOM.SQLRPGLE}, prototype
 *       {@code Copy_Mbrs/SRV_RAND_P.RPGLE}) becomes {@link #randInt(int, int)};</li>
 *   <li>LOADCUSTR's {@code genWord} becomes {@link #genWord(int, int)};</li>
 *   <li>LOADCUSTR's {@code genPhone} becomes {@link #genPhone()}.</li>
 * </ul>
 *
 * <h2>Random policy</h2>
 * <p>{@code Rand_Int} scales Db2 {@code RANDOM()}, which returns a value r with
 * 0 &le; r &le; 1, as {@code %int(r * (p_High - p_Low) + p_Low)}. In exact
 * arithmetic the result is {@code low..high-1} uniformly for r &lt; 1, and
 * {@code high} only when r is exactly 1, which the source documents as
 * "p_High is much less frequently returned". The expression is evaluated in
 * IEEE double, as the source's {@code float(8)} is, so for some ranges an r
 * within a few ulps of 1 also rounds up to {@code high}, for example (5, 11) at
 * {@code Math.nextDown(1.0)}; {@link #randInt(int, int)} gives the details.
 * Both halves are kept on purpose:
 * <ul>
 *   <li>The unit source is <em>inclusive</em> of 1. {@link #inclusiveUnit(RandomGenerator)}
 *       draws k/2<sup>53</sup> for k in 0..2<sup>53</sup>, so r = 1 is reachable and
 *       rare. {@link RandomGenerator#nextDouble()} and PostgreSQL {@code random()}
 *       cover [0, 1) and omit the exact r = 1 endpoint, so {@code high} would be
 *       unreachable in every range where only r = 1 yields it, such as the
 *       generator's ranges (5, 28), (1, 21), (1, 28), (1, 5000), (100, 900),
 *       (1, 998), (1, 9900) and (1, 45). Neither is used.</li>
 *   <li>The scaling truncates toward zero, as {@code %int} does, through a plain
 *       {@code (int)} cast of the whole expression.</li>
 * </ul>
 *
 * <h2>Determinism</h2>
 * <p>Every public method consumes unit values in a fixed, documented order, so
 * instances built by {@link #seeded(long)} with the same seed yield the same
 * sequence of draws, words and phone numbers on the same Java runtime,
 * including in separate runs of the generator, each in its own JVM process.
 * The generator's {@code --seed} option relies on this to make a load
 * reproducible on the project's pinned runtime. The promise stops at that
 * runtime: the JDK specifies {@link SplittableRandom}'s repeatability only for
 * the same seed within the same program, not a fixed algorithm across JDK
 * implementations or releases, so a different Java runtime may produce a
 * different sequence for the same seed.
 *
 * <h2>Thread safety</h2>
 * <p>Instances are <strong>not</strong> thread-safe. The JDK generators behind
 * {@link #seeded(long)} and {@link #random()} are not, and a shared instance
 * would interleave draws and destroy the reproducible order. Use one instance
 * per load.
 *
 * <p>Example:
 * <pre>{@code
 * NameGenerator names = NameGenerator.seeded(7);
 * int maxLength = names.randInt(5, 40 - 12);   // 5..28, 28 only at r = 1
 * String word = names.genWord(5, 11);          // 6..12 letters
 * String phone = names.genPhone();             // e.g. "(415) 007-0420"
 * }</pre>
 */
public final class NameGenerator {

    /**
     * Straight alphabet of LOADCUSTR's {@code genWord}, copied character for
     * character: 28 letters, with {@code I} and {@code Z} doubled. Index 28 (the
     * final {@code Z}) is drawn only at r = 1; {@code Z} also sits at index 27.
     */
    public static final String ALPHA = "ABCDEFGHIIJKLMNOPQRSTUVWXYZZ";

    /**
     * Vowel-biased alphabet of LOADCUSTR's {@code genWord}, copied character for
     * character: 45 letters. Index 45 (the final {@code Z}) is drawn only at
     * r = 1; {@code Z} also sits at index 44.
     */
    public static final String V_ALPHA = "AAAAABCDEEEEEFGHIIIIIJKLMNOOOOOPQRSTUUUVWXYZZ";

    /** Number of distinct unit values below 1: the double mantissa resolution, 2^53. */
    private static final long UNIT_STEPS = 1L << 53;

    /** 2^-53, the spacing between consecutive unit values. */
    private static final double UNIT_SCALE = 0x1.0p-53;

    /**
     * Initial capacity of the {@link #genWord(int, int)} buffer. The source's bounds
     * produce at most 12 letters, so 16 leaves spare room and the builder never grows
     * for them; wider caller bounds still grow it.
     */
    private static final int WORD_CAPACITY = 16;

    private final DoubleSupplier unit;

    /**
     * Creates a generator over the given unit source.
     *
     * @param unit supplier of r with 0 &le; r &le; 1 <em>inclusive</em>; each
     *             {@link #randInt(int, int)} call draws exactly one value. Tests
     *             inject constant suppliers such as {@code () -> 0.0},
     *             {@code () -> 1.0} or {@code () -> Math.nextDown(1.0)} to reach
     *             the range endpoints.
     * @throws NullPointerException if {@code unit} is {@code null}
     */
    public NameGenerator(DoubleSupplier unit) {
        this.unit = Objects.requireNonNull(unit, "unit");
    }

    /**
     * Builds an inclusive unit source over a JDK random generator, standing in
     * for Db2 {@code RANDOM()}.
     *
     * <p>Each value is {@code k * 2^-53} for a uniformly drawn k in
     * 0..2<sup>53</sup> inclusive, so 0 and 1 are both reachable and 1 is as rare
     * as any other single value. The multiplication is exact in IEEE double.
     *
     * @param generator the source of randomness; not shared with other users
     *                  while the returned supplier is in use
     * @return a supplier of r with 0 &le; r &le; 1
     * @throws NullPointerException if {@code generator} is {@code null}
     */
    public static DoubleSupplier inclusiveUnit(RandomGenerator generator) {
        Objects.requireNonNull(generator, "generator");
        return () -> generator.nextLong(0, UNIT_STEPS + 1) * UNIT_SCALE;
    }

    /**
     * Creates a reproducible generator. Two instances built with the same seed
     * produce identical sequences on the same Java runtime, in one JVM process
     * or in separate ones, which is what makes a {@code --seed} load repeatable.
     * The JDK specifies {@link SplittableRandom}'s repeatability only for the
     * same seed within the same program and does not fix its algorithm across
     * JDK implementations or releases, so another Java runtime may produce a
     * different sequence for the same seed.
     *
     * @param seed the seed given by the generator's {@code --seed} option
     * @return a generator whose output, for a given Java runtime, depends only
     *         on {@code seed}
     */
    public static NameGenerator seeded(long seed) {
        return new NameGenerator(inclusiveUnit(new SplittableRandom(seed)));
    }

    /**
     * Creates a generator with an unpredictable seed, used when no
     * {@code --seed} option is given.
     *
     * @return a generator whose output differs from run to run
     */
    public static NameGenerator random() {
        return new NameGenerator(inclusiveUnit(new SplittableRandom()));
    }

    /**
     * Returns a pseudo-random integer with {@code low <= value <= high}, as
     * {@code Rand_Int} does.
     *
     * <p>Exactly one unit value r is drawn per call, even when
     * {@code low == high}, and the result is {@code (int) (r * (high - low) + low)}:
     * truncation toward zero, as {@code %int} truncates. It is
     * {@code low..high-1} uniformly for r &lt; 1 and {@code high} only at r = 1.
     * The expression is evaluated in IEEE double, as the source's
     * {@code float(8)} is, so for some ranges an r within a few ulps of 1 rounds
     * up to {@code high} in the final addition (for example (5, 11) at
     * {@code Math.nextDown(1.0)}), exactly as the source computes it; for the
     * generator's ranges (5, 28), (1, 21), (1, 28), (1, 5000), (100, 900),
     * (1, 998), (1, 9900) and (1, 45), {@code Math.nextDown(1.0)} yields
     * {@code high - 1}. Like the source, which states "No validity checking on
     * the parameters", the bounds are not validated.
     *
     * @param low  lowest value of the range
     * @param high highest value of the range, returned only when r = 1 or when
     *             r rounds up to it as described above
     * @return the scaled, truncated value
     */
    public int randInt(int low, int high) {
        double r = unit.getAsDouble();
        // Kept in the source's exact form: %int((rf * (p_High - p_Low) + p_Low)).
        // The whole sum is truncated toward zero, as %int truncates it. Writing
        // low + (int) (r * (high - low)) truncates only part of it and differs
        // whenever the sum is negative; Math.floor and Math.round round in other
        // directions. Either rewrite would change generated data.
        return (int) (r * (high - low) + low);
    }

    /**
     * Fractional-bound overload of {@link #randInt(int, int)}.
     *
     * <p>{@code Rand_Int} declares its bounds {@code int(10) value}, so a
     * fractional argument such as LOADCUSTR's {@code %elem(companyType) * 1.8}
     * is truncated on entry: 21.6 becomes 21, and {@code 16 * 1.75} = 28.0
     * becomes 28.
     *
     * @param low  lowest value of the range, truncated toward zero
     * @param high highest value of the range, truncated toward zero
     * @return {@code randInt((int) low, (int) high)}
     */
    public int randInt(double low, double high) {
        return randInt((int) low, (int) high);
    }

    /**
     * Generates an uppercase pseudo word, as LOADCUSTR's {@code genWord}.
     *
     * <p>Unit values are drawn in exactly this order:
     * <ol>
     *   <li>one {@link #ALPHA} letter, index {@code randInt(1, 28)};</li>
     *   <li>unless {@code min} or {@code max} is 1, one {@link #V_ALPHA} letter,
     *       index {@code randInt(1, 45)};</li>
     *   <li>the target length {@code tgtL = randInt(min, max) - 2}, drawn in every
     *       case, so {@code genWord(1, 1)} also consumes this value;</li>
     *   <li>for {@code j = 1, 3, 5, ...} while {@code j <= tgtL}, one ALPHA letter
     *       followed by one V_ALPHA letter.</li>
     * </ol>
     *
     * <p>Resulting lengths for the source's calls: {@code genWord(5, 11)} gives
     * 6..12, {@code genWord(4, 10)} 4..10, {@code genWord(3, 6)} 4..6,
     * {@code genWord(5, 9)} 6..10 and {@code genWord(1, 1)} exactly 1. The word
     * is returned without padding; the source's {@code varchar(30)} result never
     * needs a cut at these bounds.
     *
     * @param min lower bound of the target length
     * @param max upper bound of the target length
     * @return the generated word
     */
    public String genWord(int min, int max) {
        StringBuilder word = new StringBuilder(WORD_CAPACITY);
        word.append(alphaLetter());
        if (min != 1 && max != 1) {
            word.append(vowelBiasedLetter());
        }
        int tgtL = randInt(min, max) - 2;
        for (int j = 1; j <= tgtL; j += 2) {
            word.append(alphaLetter());
            word.append(vowelBiasedLetter());
        }
        return word.toString();
    }

    /**
     * Generates a phone number shaped {@code (999) 999-9999}, as LOADCUSTR's
     * {@code genPhone}.
     *
     * <p>Three unit values are drawn in order: the area code
     * {@code randInt(100, 900)}, the exchange {@code randInt(1, 998)} and the line
     * {@code randInt(1, 9900)}. The exchange and line are zero-padded to three and
     * four digits, as {@code %editc(... : 'X')} pads them, for example
     * {@code (415) 007-0420}. The result always matches
     * {@code ^\(\d{3}\) \d{3}-\d{4}$}.
     *
     * @return the generated phone number, 14 characters long
     */
    public String genPhone() {
        int area = randInt(100, 900);
        int exchange = randInt(1, 998);
        int line = randInt(1, 9900);
        return String.format(Locale.ROOT, "(%03d) %03d-%04d", area, exchange, line);
    }

    /** Draws one letter of {@link #ALPHA} by its 1-based index, as {@code %subst(Alpha : Rand_Int(1 : %len(Alpha)) : 1)}. */
    private char alphaLetter() {
        return ALPHA.charAt(randInt(1, ALPHA.length()) - 1);
    }

    /** Draws one letter of {@link #V_ALPHA} by its 1-based index, as {@code %subst(vAlpha : Rand_Int(1 : %len(vAlpha)) : 1)}. */
    private char vowelBiasedLetter() {
        return V_ALPHA.charAt(randInt(1, V_ALPHA.length()) - 1);
    }
}
