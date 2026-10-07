package com.democorp.customermaster.domain;

import java.util.List;
import java.util.Objects;

/**
 * One page of customer search results: the rows served, whether another page
 * follows, whether the row cap was reached, and the notice to show the user.
 *
 * <p>Replaces one load of the PMTCUSTD subfile together with its end indicator and
 * the informational messages PMTCUSTR sends to the message subfile:
 * <ul>
 *   <li>{@code SflFirstPage} sends DEM0002 "No records match the selection criteria"
 *       when the first fetch finds no row [5250_Subfile/PMTCUSTR.SQLRPGLE:532-545],
 *       [5250_Subfile/CRTMSGF.CLLE:14];</li>
 *   <li>{@code SflFillPage} writes up to one page of rows, reads one extra row ahead
 *       to decide whether more follow, and stops with DEM0006 "Too many records. Change
 *       the selection criteria." when it writes row 9,999 (MAXSFLRECDS)
 *       [5250_Subfile/PMTCUSTR.SQLRPGLE:562-600], [5250_Subfile/CRTMSGF.CLLE:22];</li>
 *   <li>the PageDown branch repeats DEM0006 once the cap is reached
 *       [5250_Subfile/PMTCUSTR.SQLRPGLE:287-297].</li>
 * </ul>
 * The server keeps no open cursor between requests, so the "More..."/"Bottom"
 * indicator becomes {@link #nextCursor()}: present while another page follows,
 * {@code null} at the bottom of the list.
 *
 * <table>
 *   <caption>Component, source counterpart and meaning</caption>
 *   <tr><th>Component</th><th>Source</th><th>Meaning here</th></tr>
 *   <tr><td>{@code items}</td><td>The subfile records written by one
 *       {@code SflFillPage}</td>
 *       <td>The rows of this page in {@code name, city, state, custid} order; never
 *       {@code null}, empty when nothing matched</td></tr>
 *   <tr><td>{@code nextCursor}</td><td>{@code SFLEND(*MORE)} off, with the SQL cursor
 *       still open</td>
 *       <td>Opaque position of the next page, or {@code null} when no page follows or
 *       the cap was reached</td></tr>
 *   <tr><td>{@code limitReached}</td><td>{@code SflRRN = MAXSFLRECDS}</td>
 *       <td>{@code true} on the page that brings the rows served to 9,999</td></tr>
 *   <tr><td>{@code notice}</td><td>{@code SndSflMsg('DEM0002')} or
 *       {@code SndSflMsg('DEM0006')}</td>
 *       <td>{@code null}, DEM0002 on an empty first page, or DEM0006 with
 *       {@code limitReached}</td></tr>
 * </table>
 *
 * <p><b>Division of work.</b> This type carries values only. Each responsibility
 * below stays with the class named:
 * <ul>
 *   <li>{@code service/CustomerSearchService} decides every component: it cuts the
 *       page at the 9,999-row cap, encodes the base64url JSON cursor
 *       {@code {name, city, state, custid, served}} from the last row it serves,
 *       leaves {@code nextCursor} {@code null} on the capped page, and resolves the
 *       notice text through {@code messages/MessageCatalog}.</li>
 *   <li>{@code controller/dto/SearchResponse} maps this record to the wire shape
 *       {@code {items, nextCursor, limitReached, notice}}, and
 *       {@code controller/dto/Notice} maps the {@link Notice}.</li>
 * </ul>
 * No SQL, catalog lookup or serialization lives here, so the domain package depends
 * on the JDK and its sibling types only.
 *
 * <p>Example, the response to a search that matches nothing:
 * <pre>{@code
 * var page = SearchPage.empty(new SearchPage.Notice("DEM0002",
 *         "No records match the selection criteria"));
 * page.items();        // []
 * page.nextCursor();   // null
 * page.limitReached(); // false
 * }</pre>
 *
 * <p>Instances are immutable and therefore thread-safe: {@link #items()} is an
 * unmodifiable copy, so adding to or removing from it throws
 * {@link UnsupportedOperationException}.
 *
 * @param items        rows of this page; {@code null} is stored as an empty list, and
 *                     any other list is copied into an unmodifiable list
 * @param nextCursor   opaque cursor of the next page, as encoded by the search
 *                     service, or {@code null} when no page follows or the cap was
 *                     reached
 * @param limitReached {@code true} when this page brings the rows served to the
 *                     9,999-row cap; the page then has no {@code nextCursor} and
 *                     carries notice DEM0006
 * @param notice       the informational message for this page, or {@code null}
 */
public record SearchPage(
        List<CustomerSummary> items,
        String nextCursor,
        boolean limitReached,
        Notice notice) {

    /**
     * Stores the rows as an unmodifiable copy, so neither the caller's list nor a
     * consumer of {@link #items()} can change a page after it is built. A
     * {@code null} list becomes an empty one, so consumers never test for
     * {@code null} and an empty result serializes as {@code []}.
     *
     * @throws NullPointerException if {@code items} contains a {@code null} row
     */
    public SearchPage {
        items = items == null ? List.of() : List.copyOf(items);
    }

    /**
     * A page with no rows, no next page and the cap not reached: the result of a
     * first page that matched nothing, which carries DEM0002, or of a later page
     * that came back empty because the data changed, which carries no notice.
     *
     * @param notice the notice to show, or {@code null} for none
     * @return an empty page carrying {@code notice}
     */
    public static SearchPage empty(Notice notice) {
        return new SearchPage(List.of(), null, false, notice);
    }

    /**
     * An informational message for the user: a message catalog key and its text,
     * already resolved and substituted by {@code messages/MessageCatalog} in the
     * service that raises it.
     *
     * <p>Replaces a CUSTMSGF message sent to the message subfile by
     * {@code SndSflMsg}. It is the one domain notice type, used for the search
     * notices DEM0002 and DEM0006 and for the review confirmations DEM0000
     * "Press Enter to update. F12 to Cancel." and DEM0009 "Press Enter to add. Press
     * F12 to cancel" returned by {@code CustomerMaintenanceService.review}.
     * Controllers map it to {@code controller/dto/Notice}, which is {@code {code,
     * message}} on the wire.
     *
     * @param code    the catalog key, for example {@code DEM0002}; never {@code null}
     * @param message the resolved catalog text for {@code code}
     */
    public record Notice(String code, String message) {

        /**
         * Requires the catalog key, which the client uses to identify the message; a
         * missing key is a programming error.
         *
         * @throws NullPointerException if {@code code} is {@code null}
         */
        public Notice {
            Objects.requireNonNull(code, "code");
        }
    }
}
