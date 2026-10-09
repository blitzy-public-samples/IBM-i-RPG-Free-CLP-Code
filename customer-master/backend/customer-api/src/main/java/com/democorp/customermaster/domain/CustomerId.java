package com.democorp.customermaster.domain;

import java.util.Comparator;
import java.util.regex.Pattern;

/**
 * A 4-character base-36 customer id, the {@code custid} key of the customer master,
 * and the one codec that converts it to and from its ordinal.
 *
 * <p>Replaces the BASE36ADD service procedure [BASE36/SRV_BASE36.RPGLE:29-55] and the
 * CUSTNEXT key format [5250_Subfile/CRTDTAARA.clle:7-8]. Every consumer (the id
 * allocator, the data generator, the JDBC converters, the controllers and the tests)
 * goes through this type; none re-implements base-36 arithmetic.
 *
 * <p><b>Digit alphabet.</b> BASE36ADD's own {@code FROM} string
 * [BASE36/SRV_BASE36.RPGLE:38], most significant digit first:
 * <pre>
 *   A B C ... Y Z 0 1 ... 8 9
 *   0 1 2 ... 24 25 26 27 ... 34 35
 * </pre>
 * The ordinal of {@code c0 c1 c2 c3} is {@code d(c0)*36^3 + d(c1)*36^2 + d(c2)*36 + d(c3)},
 * so {@code AAAA} is 0 and {@code 9999} is {@value #MAX_ORDINAL}.
 *
 * <p><b>Increment.</b> {@link #successor()} equals BASE36ADD for every id below
 * {@code 9999}: adding one to the ordinal is BASE36ADD's "increment the rightmost
 * position and carry left on rollover" [BASE36/SRV_BASE36.RPGLE:42-53], for example
 * {@code 1009 -> 101A}, {@code 101Z -> 1010}, {@code AAAZ -> AAA0},
 * {@code EEEE -> EEEF}.
 *
 * <p><b>Ordering.</b> {@link #compareTo(CustomerId)} and {@link #ORDER} compare
 * ordinals, which is BASE36ADD's ascending sequence and the EBCDIC raw sorting order
 * it follows: letters before digits [BASE36/SRV_BASE36.RPGLE:15-16,37]. So allocation
 * order equals sort order: {@code AAAZ < AAA0}, {@code 101A < 1010},
 * {@code ZZZZ < 0000}. The database reproduces this order through the collation
 * {@code customer_sort} (migration V1) on {@code custid}. The 4-character strings must
 * never be compared directly: ASCII and Unicode order put digits before letters.
 * Because id and ordinal correspond one to one, the natural ordering is consistent
 * with {@link #equals(Object)}.
 *
 * <p><b>Notable ordinals.</b>
 * <table>
 *   <caption>Ids the application relies on</caption>
 *   <tr><th>Id</th><th>Ordinal</th><th>Role</th></tr>
 *   <tr><td>{@code AAAA}</td><td>0</td><td>{@link #MIN_ORDINAL}; generator start for
 *       counts too large to start at {@code 1001}</td></tr>
 *   <tr><td>{@code AAAB}..{@code AAIM}</td><td>1..300</td><td>The 300 seed rows</td></tr>
 *   <tr><td>{@code EEEE}</td><td>191,956</td><td>CUSTNEXT's initial value
 *       [5250_Subfile/CRTDTAARA.clle:7-8]</td></tr>
 *   <tr><td>{@code EEEF}</td><td>191,957</td><td>First interactive id, because AddRecd
 *       increments CUSTNEXT before using it [5250_Subfile/MTNCUSTR.SQLRPGLE:549-555];
 *       the {@code START WITH} of {@code custmast_id_seq} (migration V4)</td></tr>
 *   <tr><td>{@code 1001}</td><td>1,294,371</td><td>LOADCUSTR's and the generator's
 *       default first id; {@code CAPACITY - 1,294,371 = 385,245} ids remain from it</td></tr>
 *   <tr><td>{@code 9999}</td><td>1,679,615</td><td>{@link #MAX_ORDINAL}; the sequence's
 *       {@code MAXVALUE}</td></tr>
 * </table>
 *
 * <p>Example:
 * <pre>{@code
 * CustomerId id = CustomerId.fromOrdinal(191_957); // EEEF, the sequence start
 * id.successor().value();                           // "EEEG"
 * CustomerId.parse("1001").toOrdinal();             // 1_294_371
 * CustomerId.parse("101A").compareTo(CustomerId.parse("1010")) < 0; // true
 * }</pre>
 *
 * <p>Instances are immutable and therefore thread-safe. The only way to obtain one is
 * {@link #parse(String)} or {@link #fromOrdinal(int)}, so every instance holds a valid
 * id.
 */
public final class CustomerId implements Comparable<CustomerId> {

    /** Number of characters in every customer id. */
    public static final int LENGTH = 4;

    /** Ordinal of the lowest id, {@code AAAA}. */
    public static final int MIN_ORDINAL = 0;

    /** Ordinal of the highest id, {@code 9999}: 36^4 - 1. */
    public static final int MAX_ORDINAL = 1_679_615;

    /** Number of distinct ids, 36^4. */
    public static final int CAPACITY = 1_679_616;

    /**
     * The order of the natural ordering: by ordinal, letters before digits. Consumers
     * that need a {@link Comparator} (sorting, test assertions against
     * {@code ORDER BY custid} under {@code customer_sort}) use this constant.
     */
    public static final Comparator<CustomerId> ORDER = Comparator.naturalOrder();

    /**
     * BASE36ADD's digit alphabet in ascending digit value [BASE36/SRV_BASE36.RPGLE:38]:
     * the index of a character is its digit value.
     */
    private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";

    /** Number base, the size of {@link #ALPHABET}. */
    private static final int RADIX = 36;

    /** The id format; identical to the {@code custmast_custid_ck} CHECK of migration V3. */
    private static final Pattern FORMAT = Pattern.compile("^[A-Z0-9]{4}$");

    /** Longest prefix of a rejected input that an error message repeats. */
    private static final int MAX_ECHO = 16;

    /** The 4 characters of the id, always matching {@link #FORMAT}. */
    private final String value;

    /**
     * Wraps text the factories have already validated or built.
     *
     * @param value 4 characters matching {@link #FORMAT}
     */
    private CustomerId(String value) {
        this.value = value;
    }

    /**
     * Parses the text form of an id. The text must already be exactly 4 characters of
     * {@code A-Z} or {@code 0-9}: nothing is trimmed and no case is folded, so
     * {@code "aaab"}, {@code "AAA"}, {@code "AAAAA"}, {@code " AAA"} and {@code "AA-A"}
     * are all rejected. Callers that accept user input (path variables, cursors)
     * validate through this method and map the exception to their own error response.
     *
     * @param text the 4-character id
     * @return the id
     * @throws IllegalArgumentException if {@code text} is {@code null} or does not match
     *                                  {@code ^[A-Z0-9]{4}$}
     */
    public static CustomerId parse(String text) {
        if (text == null) {
            throw new IllegalArgumentException("customer id must not be null");
        }
        if (!FORMAT.matcher(text).matches()) {
            throw new IllegalArgumentException(
                    "customer id must be 4 characters A-Z or 0-9, was '" + echo(text) + "'");
        }
        return new CustomerId(text);
    }

    /**
     * Converts an ordinal to its id, filling the 4 digits from the right by repeated
     * division by 36. Leading positions take digit 0, {@code A}, so ordinal 35 is
     * {@code AAA9} and ordinal 36 is {@code AABA}.
     *
     * @param ordinal a value from {@link #MIN_ORDINAL} to {@link #MAX_ORDINAL}
     * @return the id with that ordinal
     * @throws IllegalArgumentException if {@code ordinal} is outside that range
     */
    public static CustomerId fromOrdinal(int ordinal) {
        if (ordinal < MIN_ORDINAL || ordinal > MAX_ORDINAL) {
            throw new IllegalArgumentException("customer id ordinal must be "
                    + MIN_ORDINAL + ".." + MAX_ORDINAL + ", was " + ordinal);
        }
        char[] digits = new char[LENGTH];
        int remaining = ordinal;
        for (int position = LENGTH - 1; position >= 0; position--) {
            digits[position] = ALPHABET.charAt(remaining % RADIX);
            remaining /= RADIX;
        }
        return new CustomerId(new String(digits));
    }

    /**
     * Returns the ordinal of this id: the sum of each character's digit value times
     * 36 raised to its distance from the rightmost position.
     *
     * @return a value from {@link #MIN_ORDINAL} to {@link #MAX_ORDINAL}
     */
    public int toOrdinal() {
        int ordinal = 0;
        for (int position = 0; position < LENGTH; position++) {
            ordinal = ordinal * RADIX + ALPHABET.indexOf(value.charAt(position));
        }
        return ordinal;
    }

    /**
     * Whether an id follows this one, which holds for every id except {@code 9999}.
     *
     * @return {@code false} only for the highest id
     */
    public boolean hasSuccessor() {
        return toOrdinal() < MAX_ORDINAL;
    }

    /**
     * Returns the next id, as BASE36ADD would compute it.
     *
     * <p>Exhaustion is a corrected source defect. BASE36ADD rolls {@code 9999} over to
     * {@code AAAA} and leaves the limit to its caller [BASE36/SRV_BASE36.RPGLE:9-13],
     * and AddRecd never checks it, so the source reissues ids. This codec has no
     * successor for {@code 9999} instead, matching the {@code NO CYCLE} sequence whose
     * exhaustion the API reports as 503 APP0503.
     *
     * @return the id whose ordinal is one higher
     * @throws IllegalStateException if this id is {@code 9999}
     */
    public CustomerId successor() {
        int ordinal = toOrdinal();
        if (ordinal == MAX_ORDINAL) {
            throw new IllegalStateException("no successor after 9999");
        }
        return fromOrdinal(ordinal + 1);
    }

    /**
     * Returns the 4 characters of the id, the form stored in {@code custid}, sent in
     * JSON and written to logs.
     *
     * @return the id text, for example {@code "EEEF"}
     */
    public String value() {
        return value;
    }

    /**
     * Compares by ordinal: letters before digits, as BASE36ADD allocates and as the
     * {@code customer_sort} collation orders {@code custid}.
     *
     * @param other the id to compare with
     * @return a negative number, zero or a positive number as this id sorts before,
     *         equal to or after {@code other}
     */
    @Override
    public int compareTo(CustomerId other) {
        return Integer.compare(toOrdinal(), other.toOrdinal());
    }

    @Override
    public boolean equals(Object other) {
        return this == other || other instanceof CustomerId that && value.equals(that.value);
    }

    @Override
    public int hashCode() {
        return value.hashCode();
    }

    /**
     * Returns the bare 4 characters, the same as {@link #value()}.
     *
     * @return the id text
     */
    @Override
    public String toString() {
        return value;
    }

    /**
     * Shortens rejected input before it is repeated in an exception message.
     *
     * @param text the rejected input, not {@code null}
     * @return {@code text} itself, or its first {@value #MAX_ECHO} characters plus
     *         {@code "..."}
     */
    private static String echo(String text) {
        return text.length() <= MAX_ECHO ? text : text.substring(0, MAX_ECHO) + "...";
    }
}
