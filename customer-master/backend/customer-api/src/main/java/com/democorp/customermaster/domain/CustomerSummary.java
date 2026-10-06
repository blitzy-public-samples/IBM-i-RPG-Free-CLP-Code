package com.democorp.customermaster.domain;

import java.util.Objects;

/**
 * One row of the customer search list: the columns a search page shows for a
 * customer, plus the key that opens its detail.
 *
 * <p>Replaces one record of the PMTCUSTD subfile, filled from the select list of
 * PMTCUSTR's {@code ItemCur} cursor ({@code NAME, CITY, STATE, ZIP, ACTIVE, CUSTID})
 * [5250_Subfile/PMTCUSTR.SQLRPGLE:208-223] into the subfile fields
 * [5250_Subfile/PMTCUSTD.DSPF:57-72]:
 *
 * <table>
 *   <caption>Source column, subfile field and component</caption>
 *   <tr><th>Component</th><th>ItemCur column</th><th>Subfile field</th></tr>
 *   <tr><td>{@code custId}</td><td>{@code CUSTID CHAR(4)}</td>
 *       <td>{@code SF_CUST_H 4}, hidden; the key options 2 and 5 open and option 1
 *       returns</td></tr>
 *   <tr><td>{@code name}</td><td>{@code NAME CHAR(40)}</td><td>{@code SF_NAME 40A}</td></tr>
 *   <tr><td>{@code city}</td><td>{@code CITY CHAR(20)}</td><td>{@code SF_CITY 20A}</td></tr>
 *   <tr><td>{@code state}</td><td>{@code STATE CHAR(2)}</td><td>{@code SF_STATE 2A}</td></tr>
 *   <tr><td>{@code zip5}</td><td>{@code ZIP CHAR(10)}</td><td>{@code SF_ZIP 5A}: only the
 *       first five characters are shown</td></tr>
 *   <tr><td>{@code active}</td><td>{@code ACTIVE CHAR(1)}</td><td>{@code SF_ACT_H 1},
 *       hidden; an inactive row is shown in red ({@code COLOR(RED)} under indicator 83)</td></tr>
 * </table>
 *
 * <p><b>Where the values come from.</b> {@code repository/CustomerSearchRepository}
 * builds each instance in its row mapper from
 * {@code SELECT custid, name, city, state, left(zip, 5) AS zip5, active}, so
 * {@code zip5} is already cut to five characters and this record never holds the full
 * ZIP. The values are stored column values, not filters or display text: they carry no
 * padding, because the target text columns are {@code varchar}, and they are exactly
 * what the keyset comparison {@code (name, city, state, custid)} reads back.
 *
 * <p><b>Who reads it.</b>
 * <ul>
 *   <li>{@code service/CustomerSearchService} builds the next-page cursor from the last
 *       row it serves: {@link #name()}, {@link #city()}, {@link #state()} and the
 *       4 characters of {@link #custId()}.</li>
 *   <li>{@code controller/dto/CustomerSummaryResponse} maps the six components, in this
 *       order, to the JSON list item {@code {custId, name, city, state, zip5, active}}.</li>
 * </ul>
 * This type holds no SQL, message text or serialization, and is not a mapped entity.
 *
 * <p><b>No {@code isActive()} convenience.</b> The record deliberately declares none.
 * Jackson treats a boolean {@code isActive()} as the getter of property
 * {@code active} and prefers it to the {@link #active()} accessor, so the
 * {@code Y}/{@code N} flag would serialize as {@code true}/{@code false}. Callers test
 * {@code "Y".equals(row.active())}; an inactive row ({@code N}) is the one the list
 * shows in red, as the subfile did under indicator 83.
 *
 * <p>Example, seed customer {@code AAAD}, whose stored ZIP is {@code 15762-0001}:
 * <pre>{@code
 * var row = new CustomerSummary(CustomerId.parse("AAAD"), "NIBH L'LOR COMPANY",
 *         "AUBURN", "ME", "15762", "Y");
 * row.custId().value(); // "AAAD"
 * row.active();         // "Y"
 * }</pre>
 *
 * <p>Instances are immutable and therefore thread-safe.
 *
 * @param custId the customer id, never {@code null}
 * @param name   stored customer name, at most 40 characters
 * @param city   stored city, at most 20 characters
 * @param state  stored 2-character state code
 * @param zip5   the first five characters of the stored ZIP
 * @param active stored active flag, {@code Y} or {@code N} (the {@code custmast_active_ck}
 *               CHECK constraint)
 */
public record CustomerSummary(
        CustomerId custId,
        String name,
        String city,
        String state,
        String zip5,
        String active) {

    /**
     * Requires the key. Every row the search returns has one, and the next-page cursor
     * and the row actions depend on it; a missing id is a programming error.
     *
     * @throws NullPointerException if {@code custId} is {@code null}
     */
    public CustomerSummary {
        Objects.requireNonNull(custId, "custId");
    }
}
