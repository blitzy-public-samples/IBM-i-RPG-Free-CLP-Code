package com.democorp.customermaster.generator;

import com.democorp.customermaster.domain.ActiveStatus;
import com.democorp.customermaster.domain.Address;
import com.democorp.customermaster.domain.Customer;
import com.democorp.customermaster.domain.CustomerId;
import com.democorp.customermaster.domain.TextNormalizer;
import java.time.OffsetDateTime;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.NoSuchElementException;
import java.util.Objects;

/**
 * Builds the random customer rows of a test-data load, one LOADCUSTR row rule at a time.
 *
 * <p><b>What it replaces.</b> The {@code for nRecds = 1 to p_recds} loop of LOADCUSTR
 * [5250_Subfile/LOADCUSTR.SQLRPGLE:131-207], with its company-type and street-type arrays
 * [5250_Subfile/LOADCUSTR.SQLRPGLE:59-88]. The random building blocks ({@code Rand_Int},
 * {@code genWord}, {@code genPhone}) live in {@link NameGenerator}; the city/state/ZIP rows come from
 * {@link CszSource}; this class combines them into {@link Customer} rows exactly as the loop body
 * assigns {@code Fld}. Writing the rows ({@code insert into custmast values(:Fld)}) belongs to
 * {@code CustomerCopyWriter}, and the transaction, lock and sequence restart to {@code CustomerLoader}.
 *
 * <p><b>Row rules,</b> for the 1-based row number n (the source's {@code nRecds}):
 * <table>
 *   <caption>Column, rule and source lines</caption>
 *   <tr><th>Column</th><th>Rule</th><th>LOADCUSTR</th></tr>
 *   <tr><td>{@code custid}</td><td>the n-th successive ordinal from the start id, through
 *       {@link CustomerId#fromOrdinal(int)}; no base-36 arithmetic here</td><td>133-137</td></tr>
 *   <tr><td>{@code active}</td><td>{@code N} when n % 7 = 0, otherwise {@code Y}</td>
 *       <td>138-141</td></tr>
 *   <tr><td>{@code city}, {@code state}, {@code zip}</td><td>CSZ row {@code randInt(1, N)} of the N
 *       retained rows; city and state copied; ZIP the last five digits of the zero-padded
 *       integer</td><td>144-148</td></tr>
 *   <tr><td>{@code name}</td><td>words of 6..12 letters while the text with its trailing blank is at
 *       most {@code randInt(5, 28)} long; a company type for {@code randInt(1, 21) <= 12}; cut to
 *       40</td><td>151-165</td></tr>
 *   <tr><td>{@code addr}</td><td>a street number 1..5000 unless n % 4 = 0, words as for the name, a
 *       street type for {@code randInt(1, 28) <= 16}; cut to 40</td><td>168-183</td></tr>
 *   <tr><td>{@code corpphone}, {@code acctphone}</td><td>{@link NameGenerator#genPhone()}, in this
 *       order</td><td>186-187</td></tr>
 *   <tr><td>{@code acctmgr}</td><td>{@code X Y WORD} when n % 3 = 0, otherwise
 *       {@code WORD WORD}</td><td>190-198</td></tr>
 *   <tr><td>{@code chguser}</td><td>{@value #SYSTEM_USER}</td><td>200</td></tr>
 *   <tr><td>{@code chgtime}</td><td>the load time given to the constructor</td><td>135 (changed)</td></tr>
 *   <tr><td>{@code row_version}</td><td>0</td><td>none (new column)</td></tr>
 * </table>
 *
 * <p><b>Draw order.</b> Every unit value is consumed in the order the source calls {@code Rand_Int}:
 * the CSZ index, the name length limit, the name words, the company-type draw, the street length limit,
 * the street number (only when n % 4 &ne; 0), the street words, the street-type draw, the corporate
 * phone, the account phone, and the account-manager words. Nothing else draws, so a seeded
 * {@link NameGenerator} repeats a load exactly, and a constant unit source reaches every range endpoint
 * the tests assert. Reordering any step changes generated data.
 *
 * <p><b>Normalization.</b> Every data value passes {@link TextNormalizer#field(String)}, so output is
 * uppercase with no trailing blanks, as values written through the API are. Generated words are already
 * uppercase ASCII; the call strips the trailing blank a name or street without a type suffix keeps, and
 * the blank a 40-character cut can leave, as a {@code CHAR(40)} column ignored its padding. The CSZ state
 * is uppercased here, because {@link CszSource} keeps it as written in the file.
 *
 * <p><b>Intentional differences</b> (recorded in {@code docs/deviations-and-open-questions.md}):
 * <ul>
 *   <li>{@code chgtime} is the load time; the source left it at the cleared, lowest timestamp
 *       ({@code clear Fld}).</li>
 *   <li>{@code row_version} 0 is new.</li>
 *   <li>CSZ rows whose state is not in STATES were already dropped by {@link CszSource}, so every draw
 *       satisfies {@code custmast_state_fk}; no draw is rejected or repeated.</li>
 * </ul>
 *
 * <p><b>Thread safety and lifecycle.</b> This is a plain class, not a Spring bean: one load builds one
 * instance over its own {@link NameGenerator}, which is not thread-safe. Instances, and the iterators
 * {@link #generate(CustomerId, int)} returns, must be used by one thread at a time. The CSZ rows are
 * held as an immutable copy in file order, because the index draw depends on that order.
 *
 * <p>Example:
 * <pre>{@code
 * CustomerDataGenerator generator = new CustomerDataGenerator(
 *         NameGenerator.seeded(7), cszSource.load("classpath:generator/csz-sample.csv"),
 *         OffsetDateTime.now(clock).truncatedTo(ChronoUnit.MICROS));
 * Iterator<Customer> rows = generator.generate(CustomerId.parse("1001"), 1_000_000);
 * copyWriter.copy(connection, rows);   // rows 1001, 1002, ... built one at a time as COPY streams
 * }</pre>
 */
public final class CustomerDataGenerator {

    /**
     * LOADCUSTR's {@code companyType} array in source order, 12 entries
     * [5250_Subfile/LOADCUSTR.SQLRPGLE:59-70]; entry j is appended to a name when the type draw is j.
     */
    public static final List<String> COMPANY_TYPES = List.of(
            "INC", "LLC", "LLP", "COMPANY", "& SONS", "ET FILS",
            "PLC", "CORP", "LTD", "SOLE", "PARTNERS", "ASSOC");

    /**
     * LOADCUSTR's {@code streetType} array in source order, 16 entries
     * [5250_Subfile/LOADCUSTR.SQLRPGLE:73-88]; entry j is appended to a street when the type draw is j.
     */
    public static final List<String> STREET_TYPES = List.of(
            "STREET", "ST", "ROAD", "RD", "AVENUE", "AVE", "PLACE", "CIRCLE",
            "SQUARE", "HWY", "VISTA", "CALLE", "RANCH", "CRESCENT", "COURT", "WAY");

    /**
     * The fixed change-stamp identity of generated rows, as LOADCUSTR writes it
     * [5250_Subfile/LOADCUSTR.SQLRPGLE:200].
     */
    public static final String SYSTEM_USER = "*SYSTEM*";

    /** Width of {@code custmast.name varchar(40)}, the source's {@code CHAR(40)} cut. */
    public static final int NAME_MAX = 40;

    /** Width of {@code custmast.addr varchar(40)}, the source's {@code CHAR(40)} cut. */
    public static final int ADDR_MAX = 40;

    /**
     * Room the source leaves for a type suffix: {@code %len(Fld.Name) - 12} and
     * {@code %len(Fld.ADDR) - 12} [5250_Subfile/LOADCUSTR.SQLRPGLE:154,170].
     */
    private static final int TYPE_SUFFIX_ROOM = 12;

    /**
     * Upper bound of the word-length limit drawn for names and streets: 40 - 12 = 28, reached only at
     * r = 1. Name and address share it because both columns are 40 wide.
     */
    public static final int MAX_WORDS_LEN = NAME_MAX - TYPE_SUFFIX_ROOM;

    /** Lower bound of the word-length limit, {@code Rand_Int(5 : …)}. */
    private static final int MIN_WORDS_LEN = 5;

    /** Bounds of every name and street word, {@code genWord(5:11)}: 6..12 letters. */
    private static final int WORD_MIN = 5;

    private static final int WORD_MAX = 11;

    /**
     * Spread of the company-type draw, {@code %elem(companyType) * 1.8} = 21.6, truncated to 21 by
     * {@link NameGenerator#randInt(double, double)}: 12 of the 20 values drawn for r &lt; 1 add a type.
     */
    private static final double COMPANY_TYPE_SPREAD = 1.8;

    /**
     * Spread of the street-type draw, {@code %elem(streetType) * 1.75} = 28: 16 of the 27 values drawn for
     * r &lt; 1 add a type.
     */
    private static final double STREET_TYPE_SPREAD = 1.75;

    /** Highest street number, {@code Rand_Int(1:5000)}, reached only at r = 1. */
    private static final int STREET_NUMBER_MAX = 5000;

    /** Every 7th row is inactive, {@code %rem(nRecds : 7) = 0}. */
    private static final int INACTIVE_EVERY = 7;

    /** Every 4th row has no street number, {@code %rem(nRecds : 4) = 0}. */
    private static final int NO_STREET_NUMBER_EVERY = 4;

    /** Every 3rd row's account manager has two initials, {@code %rem(nRecds : 3) = 0}. */
    private static final int INITIALS_EVERY = 3;

    /** ZIP digits kept: {@code %subst(%editc(zip : 'X') : 6 : 5)} of the ten-digit edit. */
    private static final int ZIP_MODULUS = 100_000;

    /** The blank that separates words, and the only character {@code %trim} removes. */
    private static final char BLANK = ' ';

    /**
     * Initial capacity of the name and street work buffers. The source's work field is
     * {@code varchar(50)}; at these bounds the text never exceeds 49 characters (at most 41 before the
     * trim, then 40 + 1 + 8 with the longest type), so that field never truncates, no cut to 50 is
     * applied, and the buffer never grows.
     */
    private static final int WORK_CAPACITY = 50;

    private final NameGenerator names;

    private final List<CszSource.CszRow> csz;

    private final OffsetDateTime loadTime;

    /**
     * Creates a generator for one load.
     *
     * @param names    the random source; consumed only by this generator during the load
     * @param csz      the retained city/state/ZIP rows in file order, as {@link CszSource#load(String)}
     *                 returns them; copied, so later changes to the argument have no effect
     * @param loadTime the change time stamped on every row; the caller truncates it to microseconds,
     *                 the precision of {@code chgtime timestamptz(6)}
     * @throws NullPointerException     if an argument, or an element of {@code csz}, is {@code null}
     * @throws IllegalArgumentException if {@code csz} is empty
     */
    public CustomerDataGenerator(NameGenerator names, List<CszSource.CszRow> csz,
            OffsetDateTime loadTime) {
        this.names = Objects.requireNonNull(names, "names");
        Objects.requireNonNull(csz, "csz");
        this.loadTime = Objects.requireNonNull(loadTime, "loadTime");
        if (csz.isEmpty()) {
            throw new IllegalArgumentException("CSZ list is empty");
        }
        this.csz = List.copyOf(csz);
    }

    /**
     * Builds row {@code rowNumber} of a load, one pass of the LOADCUSTR loop body.
     *
     * <p>The row number selects the drawless rules (active every 7th row, no street number every 4th,
     * two initials every 3rd); the unit values are drawn in the order the class describes. The returned
     * customer carries {@code id}, the stamp {@value #SYSTEM_USER} at the load time, and version 0.
     *
     * @param rowNumber the 1-based position of the row in the load, the source's {@code nRecds}
     * @param id        the id of this row
     * @return the generated customer
     * @throws NullPointerException     if {@code id} is {@code null}
     * @throws IllegalArgumentException if {@code rowNumber} is less than 1
     */
    public Customer row(long rowNumber, CustomerId id) {
        Objects.requireNonNull(id, "id");
        if (rowNumber < 1) {
            throw new IllegalArgumentException("row number must be at least 1, was " + rowNumber);
        }

        // Fld.ACTIVE = 'Y'; 'N' when %rem(nRecds : 7) = 0. No draw.
        final String active = rowNumber % INACTIVE_EVERY == 0
                ? ActiveStatus.N.code()
                : ActiveStatus.Y.code();

        // csz_I = Rand_Int(1 : %elem(csz_a)): rows 1..N-1 uniformly, row N only at r = 1.
        final CszSource.CszRow place = csz.get(names.randInt(1, csz.size()) - 1);
        final String zip = String.format(Locale.ROOT, "%05d", place.zip() % ZIP_MODULUS);

        final String name = companyName();
        final String street = street(rowNumber % NO_STREET_NUMBER_EVERY);

        // Fld.CORPPHONE = genPhone(); Fld.ACCTPHONE = genPhone(); in this order.
        final String corpPhone = names.genPhone();
        final String acctPhone = names.genPhone();

        final String acctMgr = accountManager(rowNumber);

        final Address address = new Address(
                TextNormalizer.field(street),
                TextNormalizer.field(place.city()),
                TextNormalizer.field(place.state()),
                TextNormalizer.field(zip));
        return new Customer(id, TextNormalizer.field(name), address,
                TextNormalizer.field(corpPhone), TextNormalizer.field(acctMgr),
                TextNormalizer.field(acctPhone), active, loadTime, SYSTEM_USER, 0L);
    }

    /**
     * Returns the rows of a load, built lazily in order.
     *
     * <p>The i-th call of {@link Iterator#next()} (i = 1..count) returns
     * {@code row(i, CustomerId.fromOrdinal(start.toOrdinal() + i - 1))}. Nothing is generated up front
     * and no row is retained, so a 1,000,000-row load streams through COPY in constant memory.
     * {@link Iterator#hasNext()} draws nothing. The iterator shares this generator's random source:
     * draining two iterators of one generator interleaves their draws.
     *
     * @param start the id of the first row
     * @param count the number of rows, at least 1
     * @return an iterator over exactly {@code count} rows; {@link Iterator#remove()} is unsupported
     * @throws NullPointerException     if {@code start} is {@code null}
     * @throws IllegalArgumentException if {@code count} is less than 1, or the last id would lie beyond
     *                                  {@code 9999} (start ordinal + count &gt; {@link CustomerId#CAPACITY})
     */
    public Iterator<Customer> generate(CustomerId start, int count) {
        Objects.requireNonNull(start, "start");
        if (count < 1) {
            throw new IllegalArgumentException("count must be at least 1, was " + count);
        }
        final int startOrdinal = start.toOrdinal();
        if (startOrdinal + (long) count > CustomerId.CAPACITY) {
            throw new IllegalArgumentException("cannot generate " + count + " customers from "
                    + start.value() + ": only " + (CustomerId.CAPACITY - startOrdinal)
                    + " ids remain through 9999");
        }
        return new RowIterator(startOrdinal, count);
    }

    /**
     * The company name, LOADCUSTR lines 151-165.
     *
     * <p>Draws the word-length limit, then words until the text, trailing blank included, is longer
     * than the limit, then the company-type index. Without a type the work text keeps its trailing
     * blank, as the source assigns {@code wkStr} untrimmed; the caller's normalization removes it.
     *
     * @return the name, cut to {@value #NAME_MAX} characters
     */
    private String companyName() {
        // MaxL = Rand_Int(5 : %len(Fld.Name) - 12)
        final int maxLength = names.randInt(MIN_WORDS_LEN, MAX_WORDS_LEN);
        final StringBuilder work = new StringBuilder(WORK_CAPACITY);
        appendWords(work, maxLength);
        // j = Rand_Int(1 : (%elem(companyType) * 1.8)); the bound 21.6 truncates to 21.
        final int type = names.randInt(1, COMPANY_TYPES.size() * COMPANY_TYPE_SPREAD);
        return cut(withType(work, type, COMPANY_TYPES), NAME_MAX);
    }

    /**
     * The street line, LOADCUSTR lines 168-183.
     *
     * <p>The word-length limit is drawn first, before the street number, as in the source. The street
     * number has no leading zeros ({@code %trim(%editc(… : '3'))}) and counts toward the limit.
     *
     * @param rowRemainder {@code nRecds % 4}; 0 means no street number and no number draw
     * @return the street, cut to {@value #ADDR_MAX} characters
     */
    private String street(long rowRemainder) {
        // MaxL = Rand_Int(5 : %len(Fld.ADDR) - 12), drawn before the street number.
        final int maxLength = names.randInt(MIN_WORDS_LEN, MAX_WORDS_LEN);
        final StringBuilder work = new StringBuilder(WORK_CAPACITY);
        if (rowRemainder != 0) {
            work.append(names.randInt(1, STREET_NUMBER_MAX)).append(BLANK);
        }
        appendWords(work, maxLength);
        // j = Rand_Int(1 : (%elem(streetType) * 1.75)); the bound is exactly 28.
        final int type = names.randInt(1, STREET_TYPES.size() * STREET_TYPE_SPREAD);
        return cut(withType(work, type, STREET_TYPES), ADDR_MAX);
    }

    /**
     * The account manager, LOADCUSTR lines 190-198, its words drawn left to right.
     *
     * @param rowNumber the 1-based row number
     * @return {@code X Y WORD} (initials, initial, 4..10 letters) on every 3rd row, otherwise
     *         {@code WORD WORD} (4..6 letters, 6..10 letters)
     */
    private String accountManager(long rowNumber) {
        final StringBuilder work = new StringBuilder(WORK_CAPACITY);
        if (rowNumber % INITIALS_EVERY == 0) {
            work.append(names.genWord(1, 1)).append(BLANK);
            work.append(names.genWord(1, 1)).append(BLANK);
            work.append(names.genWord(4, 10));
        } else {
            work.append(names.genWord(3, 6)).append(BLANK);
            work.append(names.genWord(5, 9));
        }
        return work.toString();
    }

    /**
     * Appends one word and a blank, then more while the text is at most {@code maxLength} long:
     * {@code wkStr = wkStr + genWord(5:11) + ' '; dow %len(wkStr) <= MaxL; … enddo}.
     *
     * @param work      the work text, possibly holding a street number
     * @param maxLength the drawn limit
     */
    private void appendWords(StringBuilder work, int maxLength) {
        work.append(names.genWord(WORD_MIN, WORD_MAX)).append(BLANK);
        while (work.length() <= maxLength) {
            work.append(names.genWord(WORD_MIN, WORD_MAX)).append(BLANK);
        }
    }

    /**
     * Applies a type draw: {@code if j <= %elem(types); wkStr = %trim(wkStr) + ' ' + types(j)}.
     *
     * @param work  the work text
     * @param type  the drawn 1-based index, at least 1 for every unit value in [0, 1]
     * @param types the type list
     * @return the trimmed text plus the type when the index names one, otherwise the work text as is
     */
    private static String withType(CharSequence work, int type, List<String> types) {
        // Only the upper bound is tested, as in the source; an index below 1 cannot be drawn.
        if (type <= types.size()) {
            return trimBlanks(work) + BLANK + types.get(type - 1);
        }
        return work.toString();
    }

    /**
     * Removes leading and trailing blanks, and only blanks, as RPG {@code %trim} does by default.
     *
     * @param text the text
     * @return the text without surrounding blanks
     */
    private static String trimBlanks(CharSequence text) {
        int begin = 0;
        int end = text.length();
        while (begin < end && text.charAt(begin) == BLANK) {
            begin++;
        }
        while (end > begin && text.charAt(end - 1) == BLANK) {
            end--;
        }
        return text.subSequence(begin, end).toString();
    }

    /**
     * Keeps the first {@code max} characters, as an assignment to {@code CHAR(max)} truncates, counting
     * code points so a surrogate pair is never split. Generated text is ASCII, so this is the source's
     * character cut.
     *
     * @param text the text
     * @param max  the column width
     * @return the text, or its first {@code max} code points
     */
    private static String cut(String text, int max) {
        if (text.length() <= max) {
            return text;
        }
        final int codePoints = text.codePointCount(0, text.length());
        if (codePoints <= max) {
            return text;
        }
        return text.substring(0, text.offsetByCodePoints(0, max));
    }

    /**
     * The lazy iterator of {@link #generate(CustomerId, int)}: builds row i only when asked for it.
     */
    private final class RowIterator implements Iterator<Customer> {

        private final int startOrdinal;

        private final int count;

        /** Rows returned so far; the next row is number {@code produced + 1}. */
        private int produced;

        private RowIterator(int startOrdinal, int count) {
            this.startOrdinal = startOrdinal;
            this.count = count;
        }

        @Override
        public boolean hasNext() {
            return produced < count;
        }

        @Override
        public Customer next() {
            if (produced >= count) {
                throw new NoSuchElementException("all " + count + " rows have been generated");
            }
            final int rowIndex = produced;
            produced++;
            return row(rowIndex + 1L, CustomerId.fromOrdinal(startOrdinal + rowIndex));
        }
    }
}
