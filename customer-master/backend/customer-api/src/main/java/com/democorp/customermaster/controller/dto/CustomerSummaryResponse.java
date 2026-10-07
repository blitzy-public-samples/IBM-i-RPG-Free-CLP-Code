package com.democorp.customermaster.controller.dto;

import com.democorp.customermaster.domain.CustomerSummary;
import java.util.Objects;

/**
 * One item of {@code SearchResponse.items}, the JSON row of {@code GET /api/customers}:
 * {@code {custId, name, city, state, zip5, active}}.
 *
 * <p>Replaces one record of the PMTCUSTD subfile [5250_Subfile/PMTCUSTD.DSPF:57-72],
 * which PMTCUSTR filled from the select list of its {@code ItemCur} cursor
 * [5250_Subfile/PMTCUSTR.SQLRPGLE:208-223]:
 *
 * <table>
 *   <caption>Subfile field and JSON property</caption>
 *   <tr><th>Property</th><th>Subfile field</th><th>Content</th></tr>
 *   <tr><td>{@code custId}</td><td>{@code SF_CUST_H 4}, hidden</td>
 *       <td>The 4-character base-36 id that options 2 and 5 open and option 1 returns</td></tr>
 *   <tr><td>{@code name}</td><td>{@code SF_NAME 40A}</td><td>Stored name, at most 40 characters</td></tr>
 *   <tr><td>{@code city}</td><td>{@code SF_CITY 20A}</td><td>Stored city, at most 20 characters</td></tr>
 *   <tr><td>{@code state}</td><td>{@code SF_STATE 2A}</td><td>Stored 2-character state code</td></tr>
 *   <tr><td>{@code zip5}</td><td>{@code SF_ZIP 5A}</td><td>The first five characters of the
 *       stored ZIP</td></tr>
 *   <tr><td>{@code active}</td><td>{@code SF_ACT_H 1}, hidden</td><td>{@code Y} or {@code N};
 *       the list shows an {@code N} row in red, as the subfile did with {@code COLOR(RED)}
 *       under indicator 83</td></tr>
 * </table>
 *
 * <p>The component order is the JSON and OpenAPI property order, so it must not change
 * without regenerating the committed OpenAPI snapshot and the frontend's
 * {@code src/api/schema.d.ts}. The input option field {@code SF_OPT} has no counterpart:
 * the browser owns the per-row option.
 *
 * <p>Every property is a string. {@code custId} is the id's text form, not an object, and
 * {@code active} stays the stored {@code Y}/{@code N} flag rather than a boolean, so the
 * client sees the same values as the database and the detail endpoints. The record has no
 * {@code isActive()} method for the same reason: Jackson would prefer a boolean
 * {@code isActive()} over {@link #active()} and serialize the flag as {@code true}/{@code false}.
 *
 * <p>The record carries values only. It holds no serialization, validation or OpenAPI
 * annotations, because it is a response body that is never deserialized from a client.
 *
 * <p>Example, seed customer {@code AAAD}:
 * <pre>{@code
 * CustomerSummaryResponse.from(new CustomerSummary(CustomerId.parse("AAAD"),
 *         "NIBH L'LOR COMPANY", "AUBURN", "ME", "15762", "Y"));
 * // serializes as
 * // {"custId":"AAAD","name":"NIBH L'LOR COMPANY","city":"AUBURN","state":"ME",
 * //  "zip5":"15762","active":"Y"}
 * }</pre>
 *
 * <p>Instances are immutable and therefore thread-safe.
 *
 * @param custId the 4-character customer id, for example {@code "AAAD"}
 * @param name   stored customer name
 * @param city   stored city
 * @param state  stored 2-character state code
 * @param zip5   the first five characters of the stored ZIP
 * @param active stored active flag, {@code "Y"} or {@code "N"}
 */
public record CustomerSummaryResponse(
        String custId,
        String name,
        String city,
        String state,
        String zip5,
        String active) {

    /**
     * Maps one search row to its JSON item, component for component.
     *
     * <p>Values pass through unchanged. {@code zip5} is already the first five ZIP
     * characters, cut by the search query's {@code left(zip, 5)}, so it is not recomputed
     * here. No value is trimmed, padded or reformatted, so the client sees exactly what is
     * stored.
     *
     * @param summary the search row; its {@code custId} is never {@code null}
     * @return the list item
     * @throws NullPointerException if {@code summary} is {@code null}
     */
    public static CustomerSummaryResponse from(CustomerSummary summary) {
        Objects.requireNonNull(summary, "summary");
        return new CustomerSummaryResponse(
                summary.custId().value(),
                summary.name(),
                summary.city(),
                summary.state(),
                summary.zip5(),
                summary.active());
    }
}
