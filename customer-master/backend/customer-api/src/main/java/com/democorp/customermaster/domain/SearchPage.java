package com.democorp.customermaster.domain;

import java.util.List;
import java.util.Objects;

/**
 * One page of customer search results: the rows served, whether another page
 * follows, whether the row cap was reached, and the notice to show the user.
 *
 * <p>Replaces one load of the PMTCUSTD subfile together with its end indicator and
 * the informational messages PMTCUSTR sends to the message subfile: DEM0002 when the
 * first fetch finds no row [5250_Subfile/PMTCUSTR.SQLRPGLE:532-545], and DEM0006 when
 * row 9,999 is written, repeated on PageDown once that cap is reached
 * [5250_Subfile/PMTCUSTR.SQLRPGLE:562-600,287-297], [5250_Subfile/CRTMSGF.CLLE:14,22].
 * The server keeps no open cursor between requests, so the "More..."/"Bottom"
 * indicator becomes {@link #nextCursor()}: present while another page follows,
 * {@code null} at the bottom of the list or on the capped page.
 *
 * <p>A page with {@code limitReached} has no {@code nextCursor} and carries notice
 * DEM0006. This record states that invariant; {@code service/CustomerSearchService},
 * which decides every component, enforces it.
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
