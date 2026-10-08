package com.democorp.customermaster.controller.dto;

import com.democorp.customermaster.domain.SearchPage;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * An informational message on the wire, {@code {code, message}}: the {@code notice} member of
 * {@code SearchResponse} (DEM0002, DEM0006) and {@code ReviewResponse} (DEM0000, DEM0009).
 *
 * <p>{@code code} is the message catalog key, and {@code message} is the text
 * {@link com.democorp.customermaster.messages.MessageCatalog} has already resolved and
 * substituted; this record performs no lookup or formatting. When there is no notice, the owning
 * response holds {@code null}, which is serialized as {@code "notice": null}.
 *
 * @param code    the message catalog key, for example {@code "DEM0002"}
 * @param message the resolved catalog text for {@code code}
 */
public record Notice(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String code,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String message) {

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
