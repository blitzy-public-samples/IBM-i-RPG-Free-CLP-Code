package com.democorp.customermaster.controller.dto;

import com.democorp.customermaster.domain.SearchPage;
import java.util.List;
import java.util.Objects;

/**
 * One page of customer search results on the wire, the 200 body of
 * {@code GET /api/customers}:
 * {@code {items: [{custId, name, city, state, zip5, active}], nextCursor, limitReached, notice}}.
 *
 * <p>Replaces one load of the PMTCUSTD subfile and the indicators and messages that went with
 * it [5250_Subfile/PMTCUSTR.SQLRPGLE:180,287-297,532-600], [5250_Subfile/PMTCUSTD.DSPF:77-88]:
 *
 * <table>
 *   <caption>Component, source counterpart and wire meaning</caption>
 *   <tr><th>Property</th><th>Source</th><th>Content</th></tr>
 *   <tr><td>{@code items}</td><td>The subfile records {@code SflFillPage} writes, at most
 *       {@code SFLPAG(0012)} per page</td>
 *       <td>The rows of this page as {@link CustomerSummaryResponse}; never {@code null}, and
 *       {@code []} when nothing matched</td></tr>
 *   <tr><td>{@code nextCursor}</td><td>{@code SFLEND(*MORE)} under indicator 97, set from
 *       {@code EofData}: "More..." while the SQL cursor still holds rows, "Bottom" at the
 *       end</td>
 *       <td>The opaque cursor of the next page, or {@code null} when no page follows or the cap
 *       was reached</td></tr>
 *   <tr><td>{@code limitReached}</td><td>{@code SflRRN = MAXSFLRECDS} (9,999)</td>
 *       <td>{@code true} on the page that brings the rows served to 9,999; that page has no
 *       {@code nextCursor} and carries DEM0006</td></tr>
 *   <tr><td>{@code notice}</td><td>{@code SndSflMsg('DEM0002')} on an empty first page,
 *       {@code SndSflMsg('DEM0006')} at the cap</td>
 *       <td>A {@link Notice}, or {@code null} when the page carries no message</td></tr>
 * </table>
 *
 * <p><b>Values only.</b> {@code service/CustomerSearchService} decides every value: it cuts the
 * page at the cap, encodes the base64url cursor and resolves the notice text through the message
 * catalog. This record copies those values into the wire shape and never builds, decodes or
 * inspects the cursor, so the cursor stays opaque to every layer above the service.
 *
 * <p><b>Nulls are part of the contract.</b> The record carries no serialization or OpenAPI
 * annotations, in particular no {@code @JsonInclude(NON_NULL)}: the bottom page serializes
 * {@code "nextCursor": null} and a page without a message serializes {@code "notice": null}, so
 * the client tells "no next page" and "no message" from an explicit value rather than from a
 * missing member. The component order is the JSON and OpenAPI property order, so it must not
 * change without regenerating the committed OpenAPI snapshot and the frontend's
 * {@code src/api/schema.d.ts}.
 *
 * <p>Example, a search that matches nothing:
 * <pre>{@code
 * SearchResponse.from(SearchPage.empty(new SearchPage.Notice("DEM0002",
 *         "No records match the selection criteria")));
 * // serializes as
 * // {"items":[],"nextCursor":null,"limitReached":false,
 * //  "notice":{"code":"DEM0002","message":"No records match the selection criteria"}}
 * }</pre>
 *
 * <p>Instances are immutable and therefore thread-safe: {@link #items()} is an unmodifiable
 * list.
 *
 * @param items        rows of this page; {@code null} is stored as an empty list, and any other
 *                     list is copied into an unmodifiable list
 * @param nextCursor   opaque cursor of the next page, as encoded by the search service, or
 *                     {@code null} when no page follows or the 9,999-row cap was reached
 * @param limitReached {@code true} when this page brings the rows served to the 9,999-row cap
 * @param notice       the informational message for this page (DEM0002 or DEM0006), or
 *                     {@code null} for none
 */
public record SearchResponse(
        List<CustomerSummaryResponse> items,
        String nextCursor,
        boolean limitReached,
        Notice notice) {

    /**
     * Stores the rows as an unmodifiable copy, so a response cannot change after it is built,
     * and turns a {@code null} list into an empty one, so an empty result always serializes as
     * {@code []} and never as {@code null}.
     *
     * @throws NullPointerException if {@code items} contains a {@code null} row
     */
    public SearchResponse {
        items = items == null ? List.of() : List.copyOf(items);
    }

    /**
     * Maps a domain search page to its wire form, component for component.
     *
     * <p>Each row is mapped by {@link CustomerSummaryResponse#from}, the cursor and the cap flag
     * pass through unchanged, and the notice is mapped by {@link Notice#of}, which keeps a
     * missing notice {@code null}. A page whose row list is {@code null} yields {@code []}.
     *
     * @param p the page returned by the search service
     * @return the response body for that page
     * @throws NullPointerException if {@code p} is {@code null} or holds a {@code null} row
     */
    public static SearchResponse from(SearchPage p) {
        Objects.requireNonNull(p, "page");
        List<CustomerSummaryResponse> items = p.items() == null
                ? List.of()
                : p.items().stream().map(CustomerSummaryResponse::from).toList();
        return new SearchResponse(items, p.nextCursor(), p.limitReached(), Notice.of(p.notice()));
    }
}
