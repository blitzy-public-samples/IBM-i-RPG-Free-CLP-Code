package com.democorp.customermaster.domain;

import java.util.Objects;

/**
 * One row of the customer search list: the columns a search page shows for a
 * customer, plus the key that opens its detail.
 *
 * <p>Replaces one record of the PMTCUSTD subfile, filled from the select list of
 * PMTCUSTR's {@code ItemCur} cursor ({@code NAME, CITY, STATE, ZIP, ACTIVE, CUSTID})
 * [5250_Subfile/PMTCUSTR.SQLRPGLE:208-223] into the subfile fields
 * [5250_Subfile/PMTCUSTD.DSPF:57-72].
 *
 * <p><b>Where the values come from.</b> {@code repository/CustomerSearchRepository}
 * selects {@code left(zip, 5) AS zip5}, so {@code zip5} is already cut to five
 * characters and this record never holds the full ZIP. The values are stored column
 * values, not filters or display text. The target text columns are {@code varchar},
 * which adds no automatic padding, and values written through
 * {@link TextNormalizer#field(String)} carry no trailing blanks, because it strips them.
 * They are exactly what the keyset comparison {@code (name, city, state, custid)} reads
 * back, so they must not be trimmed or padded again.
 *
 * <p><b>No {@code isActive()} convenience.</b> The record deliberately declares none.
 * Jackson treats a boolean {@code isActive()} as the getter of property
 * {@code active} and prefers it to the {@link #active()} accessor, so the
 * {@code Y}/{@code N} flag would serialize as {@code true}/{@code false}. Callers test
 * {@code "Y".equals(row.active())}; an inactive row ({@code N}) is the one the list
 * shows in red, as the subfile did under indicator 83.
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
