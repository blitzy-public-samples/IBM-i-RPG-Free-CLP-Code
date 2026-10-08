package com.democorp.customermaster.controller.dto;

import com.democorp.customermaster.domain.SearchPage;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.Objects;

/**
 * One page of customer search results, the 200 body of {@code GET /api/customers} and the
 * counterpart of one load of the PMTCUSTD subfile [5250_Subfile/PMTCUSTR.SQLRPGLE:532-600].
 *
 * <p>{@code nextCursor} and {@code notice} are explicit nullable members: the bottom page sends
 * {@code "nextCursor": null} and a page without a message sends {@code "notice": null}, so the
 * client tells "no next page" and "no message" from an explicit value rather than from a missing
 * member. {@code controller/DtoSchemaCustomizer} publishes {@code notice} as a {@link Notice} or
 * {@code null}, a form swagger-core cannot derive from an annotation. {@link #items()} is never
 * {@code null} and is an unmodifiable copy.
 *
 * <p>{@code service/CustomerSearchService} decides every value. This record never builds, decodes
 * or inspects the cursor, which stays opaque.
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
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        List<CustomerSummaryResponse> items,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, types = {"string", "null"})
        String nextCursor,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        boolean limitReached,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
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
