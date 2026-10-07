package com.democorp.customermaster.controller.dto;

import com.democorp.customermaster.domain.SearchPage;

/**
 * An informational message on the wire: {@code {code, message}}, the {@code notice} member of
 * {@code SearchResponse} and {@code ReviewResponse}.
 *
 * <p>Replaces a CUSTMSGF informational message that the IBM i programs sent to the message
 * subfile with {@code SndSflMsg} [5250_Subfile/CRTMSGF.CLLE:12-29]:
 *
 * <table>
 *   <caption>Notice codes and where the source raised them</caption>
 *   <tr><th>Code</th><th>Text</th><th>Source</th><th>Carried by</th></tr>
 *   <tr><td>{@code DEM0002}</td><td>No records match the selection criteria</td>
 *       <td>{@code SflFirstPage} [5250_Subfile/PMTCUSTR.SQLRPGLE:540]</td>
 *       <td>{@code GET /api/customers}, empty first page</td></tr>
 *   <tr><td>{@code DEM0006}</td><td>Too many records. Change the selection criteria.</td>
 *       <td>{@code SflFillPage} and PageDown [5250_Subfile/PMTCUSTR.SQLRPGLE:291,590]</td>
 *       <td>{@code GET /api/customers}, the page that reaches 9,999 rows</td></tr>
 *   <tr><td>{@code DEM0000}</td><td>Press Enter to update. F12 to Cancel.</td>
 *       <td>Edit confirmation [5250_Subfile/MTNCUSTR.SQLRPGLE:225]</td>
 *       <td>{@code POST /api/customers/review}, purpose {@code EDIT}</td></tr>
 *   <tr><td>{@code DEM0009}</td><td>Press Enter to add. Press F12 to cancel</td>
 *       <td>Add confirmation [5250_Subfile/MTNCUSTR.SQLRPGLE:277]</td>
 *       <td>{@code POST /api/customers/review}, purpose {@code ADD}</td></tr>
 * </table>
 *
 * <p><b>Values only.</b> {@code code} is the message catalog key, and {@code message} is the
 * catalog text that {@code messages/MessageCatalog} has already resolved and substituted in the
 * service that raised the notice. This record performs no lookup, substitution or formatting, so
 * the client shows exactly the text the server resolved and can still identify the message by
 * its key.
 *
 * <p>The component order is the JSON and OpenAPI property order, so it must not change without
 * regenerating the committed OpenAPI snapshot and the frontend's {@code src/api/schema.d.ts}. The
 * record carries no serialization or OpenAPI annotations: a page or review without a notice is
 * represented by the owning response holding {@code null}, which serializes as
 * {@code "notice": null}.
 *
 * <p>Example, the notice of a search that matches nothing:
 * <pre>{@code
 * Notice.of(new SearchPage.Notice("DEM0002", "No records match the selection criteria"));
 * // serializes as
 * // {"code":"DEM0002","message":"No records match the selection criteria"}
 * }</pre>
 *
 * <p>Instances are immutable and therefore thread-safe.
 *
 * @param code    the message catalog key, for example {@code "DEM0002"}
 * @param message the resolved catalog text for {@code code}
 */
public record Notice(String code, String message) {

    /**
     * Maps the domain notice to its wire form, member for member.
     *
     * <p>The domain {@link SearchPage.Notice} is the one notice type the services produce, for the
     * search notices DEM0002 and DEM0006 and for the review confirmations DEM0000 and DEM0009, so
     * this single factory serves both {@code SearchResponse} and {@code ReviewResponse}.
     *
     * @param n the domain notice, or {@code null} when there is none
     * @return the wire notice, or {@code null} when {@code n} is {@code null}, so the owning
     *         response serializes {@code "notice": null}
     */
    public static Notice of(SearchPage.Notice n) {
        if (n == null) {
            return null;
        }
        return new Notice(n.code(), n.message());
    }
}
