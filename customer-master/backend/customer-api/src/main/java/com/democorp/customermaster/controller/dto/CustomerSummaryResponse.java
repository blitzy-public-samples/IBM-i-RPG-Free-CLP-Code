package com.democorp.customermaster.controller.dto;

import com.democorp.customermaster.domain.CustomerSummary;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Objects;

/**
 * One item of {@code SearchResponse.items}, the JSON row of {@code GET /api/customers} and the
 * counterpart of one PMTCUSTD subfile record [5250_Subfile/PMTCUSTD.DSPF:57-72].
 *
 * <p>Every property is a string. {@code custId} is the id's text form, {@code zip5} holds the first
 * five ZIP characters as the search query cuts them, and {@code active} stays the stored
 * {@code Y}/{@code N} flag rather than a boolean, so the client sees the same values as the
 * database and the detail endpoints. The record must not gain an {@code isActive()} method:
 * Jackson would prefer a boolean {@code isActive()} over {@link #active()} and serialize the flag
 * as {@code true}/{@code false}.
 *
 * @param custId the 4-character customer id, for example {@code "AAAD"}
 * @param name   stored customer name
 * @param city   stored city
 * @param state  stored 2-character state code
 * @param zip5   the first five characters of the stored ZIP
 * @param active stored active flag, {@code "Y"} or {@code "N"}
 */
public record CustomerSummaryResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String custId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String name,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String city,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String state,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String zip5,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String active) {

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
