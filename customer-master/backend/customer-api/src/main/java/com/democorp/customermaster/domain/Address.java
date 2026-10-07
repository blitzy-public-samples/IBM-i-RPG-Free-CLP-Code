package com.democorp.customermaster.domain;

import org.springframework.data.relational.core.mapping.Column;

/**
 * The postal address of a customer: the street, city, state and ZIP columns of the
 * customer master, embedded in {@code Customer}.
 *
 * <p>Gives the four address fields one type. They are exactly the fields the USPS
 * variant's {@code Edit_Address} reads into its request and overwrites from the
 * standardized response [USPS_Address/MTNCUSTR.SQLRPGLE:471-499], over the CUSTMAST
 * columns of [5250_Subfile/Custmast2.sql:12-15]:
 *
 * <table>
 *   <caption>Source column, V3 column and component</caption>
 *   <tr><th>Component</th><th>Custmast2.sql</th><th>V3 column</th>
 *       <th>Edit_Address</th></tr>
 *   <tr><td>{@code addr}</td><td>{@code Addr CHAR(40)}</td>
 *       <td>{@code addr varchar(40)}</td>
 *       <td>sent as {@code AdrIn.Address2}; replaced by {@code AdrOut.Address2}</td></tr>
 *   <tr><td>{@code city}</td><td>{@code City CHAR(20)}</td>
 *       <td>{@code city varchar(20)}</td>
 *       <td>sent as {@code AdrIn.City}; replaced by {@code AdrOut.City}</td></tr>
 *   <tr><td>{@code state}</td><td>{@code State CHAR(2)}</td>
 *       <td>{@code state char(2)}, foreign key to {@code states}</td>
 *       <td>sent as {@code AdrIn.State}; replaced by {@code AdrOut.State}</td></tr>
 *   <tr><td>{@code zip}</td><td>{@code Zip CHAR(10)}</td>
 *       <td>{@code zip varchar(10)}</td>
 *       <td>first five characters sent as {@code AdrIn.Zip5}; replaced by
 *       {@code Zip5-Zip4}, or {@code Zip5} alone when Zip4 is blank</td></tr>
 * </table>
 *
 * <p><b>Column names.</b> Every component names its V3 column explicitly with
 * {@link Column}, as every mapped type in this package does, so the mapping never
 * depends on the default {@code NamingStrategy}. {@code Customer} embeds this record
 * with {@code @Embedded.Empty}, whose prefix is empty, so the SQL Spring Data JDBC
 * generates reads and writes {@code addr}, {@code city}, {@code state} and {@code zip}:
 * the same names the handwritten SQL of {@code CustomerSearchRepository} and the
 * {@code COPY} column list of {@code CustomerCopyWriter} use.
 *
 * <p><b>Values are taken as given.</b> The record validates and normalizes nothing,
 * and accepts {@code null} components:
 * <ul>
 *   <li>Values written through the API arrive already normalized by
 *       {@link TextNormalizer#field(String)} in {@code CustomerMaintenanceService},
 *       and values read from the database are the stored values.</li>
 *   <li>A draft built from a request may be incomplete until
 *       {@code CustomerValidator} runs, so an absent field stays {@code null} here
 *       and is reported by the field rules (DEM0502, DEM0503), not by this type.</li>
 *   <li>The Edit_Address mapping, which cuts the street to 30 characters, the city to
 *       20 and composes the ZIP, belongs to {@code AddressStandardizationService}.</li>
 * </ul>
 *
 * <p><b>Persistent properties.</b> Only the four record components are mapped.
 * {@link #zip5()} is a derived value with no backing field, so Spring Data does not
 * treat it as a property, and its name does not follow the getter convention, so
 * Jackson does not serialize it either. No other derived accessor is declared.
 *
 * <p>Example, seed customer {@code AAAD} as stored by the V5 seed, whose street keeps
 * the source's mixed case:
 * <pre>{@code
 * Address a = new Address("P.O. Box 103,  9218 Vivamus Avenue", "AUBURN", "ME",
 *         "15762-0001");
 * a.zip5();                    // "15762"
 * a.withZip("06371").zip();    // "06371"; a is unchanged
 * }</pre>
 *
 * <p>Instances are immutable and therefore thread-safe.
 *
 * @param addr  the street line, at most 40 characters as stored
 * @param city  the city, at most 20 characters as stored
 * @param state the 2-character state code
 * @param zip   the ZIP code, at most 10 characters: five digits, or ZIP+4 as
 *              {@code 12345-6789}; no format is enforced, as in the source
 */
public record Address(
        @Column("addr") String addr,
        @Column("city") String city,
        @Column("state") String state,
        @Column("zip") String zip) {

    /** Characters the list shows and Edit_Address sends of the ZIP. */
    private static final int ZIP5_LENGTH = 5;

    /**
     * The first five characters of {@link #zip()}: {@code %subst(SD_ZIP:1:5)} in
     * Edit_Address [USPS_Address/MTNCUSTR.SQLRPGLE:475], the value the list shows in
     * {@code SF_ZIP 5A} [5250_Subfile/PMTCUSTD.DSPF:71], and what the search SQL
     * computes as {@code left(zip, 5)}.
     *
     * <p>Characters are counted as code points, as PostgreSQL's {@code left} counts
     * them, so a surrogate pair is never split. A ZIP shorter than five characters is
     * returned whole, and a {@code null} ZIP gives {@code ""}.
     *
     * <pre>{@code
     * new Address("1 MAIN", "X", "CA", "06371-1234").zip5(); // "06371"
     * new Address(null, null, null, "123").zip5();          // "123"
     * new Address(null, null, null, null).zip5();           // ""
     * }</pre>
     *
     * @return at most the first five characters of the ZIP, never {@code null}
     */
    public String zip5() {
        if (zip == null) {
            return "";
        }
        if (zip.codePointCount(0, zip.length()) <= ZIP5_LENGTH) {
            return zip;
        }
        return zip.substring(0, zip.offsetByCodePoints(0, ZIP5_LENGTH));
    }

    /**
     * Returns a copy with the street line replaced.
     *
     * @param addr the new street line, taken as given
     * @return a new {@code Address}; this instance is unchanged
     */
    public Address withAddr(String addr) {
        return new Address(addr, city, state, zip);
    }

    /**
     * Returns a copy with the city replaced.
     *
     * @param city the new city, taken as given
     * @return a new {@code Address}; this instance is unchanged
     */
    public Address withCity(String city) {
        return new Address(addr, city, state, zip);
    }

    /**
     * Returns a copy with the state code replaced.
     *
     * @param state the new 2-character state code, taken as given
     * @return a new {@code Address}; this instance is unchanged
     */
    public Address withState(String state) {
        return new Address(addr, city, state, zip);
    }

    /**
     * Returns a copy with the ZIP code replaced.
     *
     * @param zip the new ZIP code, taken as given
     * @return a new {@code Address}; this instance is unchanged
     */
    public Address withZip(String zip) {
        return new Address(addr, city, state, zip);
    }
}
