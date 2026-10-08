package com.democorp.customermaster.controller.dto;

import com.democorp.customermaster.domain.Customer;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.Objects;

/**
 * One customer as stored: the body of {@code GET /api/customers/{custId}} (200),
 * {@code POST /api/customers} (201) and {@code PUT /api/customers/{custId}} (200), and the
 * {@code current} member of a 409 DEM1002 problem. It carries the nine {@link CustomerFields}
 * plus {@code custId}, {@code chgTime}, {@code chgUser} and {@code version}.
 *
 * <ul>
 *   <li>{@code chgTime} is an {@link Instant}, serialized as an ISO-8601 instant such as
 *       {@code "2026-10-05T14:03:09.123456Z"}. It is {@code null} only for a stored aggregate that
 *       carries no time, and is sent even then.</li>
 *   <li>{@code chgUser} carries the full stored value of up to 18 characters, where the source's
 *       {@code SD_CHGUSER} showed only 15 [5250_Subfile/MTNCUSTD.DSPF:132]. This is an
 *       intentional difference recorded in the Deviations document.</li>
 *   <li>{@code version} is the stored {@code row_version}: 0 after the insert and one higher after
 *       each update. The client sends it back on {@code PUT}, where a mismatch answers 409
 *       DEM1002. It is a primitive {@code long}, so it is never {@code null}.</li>
 * </ul>
 *
 * @param custId    the 4-character base-36 customer id
 * @param name      stored customer name
 * @param addr      stored street line
 * @param city      stored city
 * @param state     stored 2-character state code
 * @param zip       stored ZIP code
 * @param corpPhone stored corporate phone
 * @param acctMgr   stored account manager name
 * @param acctPhone stored account manager phone
 * @param active    stored active flag, {@code "Y"} or {@code "N"}
 * @param chgTime   when the row was last written, or {@code null} only if the stored aggregate
 *                  carries no time
 * @param chgUser   who last wrote the row
 * @param version   the stored row version, sent back on {@code PUT}
 */
public record CustomerResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String custId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String name,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String addr,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String city,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String state,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String zip,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String corpPhone,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String acctMgr,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String acctPhone,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String active,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, types = {"string", "null"},
                format = "date-time")
        Instant chgTime,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String chgUser,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long version) {

    /**
     * Maps a stored customer to its JSON body, property for property.
     *
     * <p>Values pass through unchanged: nothing is trimmed, padded, uppercased or reformatted,
     * so the client sees exactly what is stored. The four address properties come from the
     * embedded {@code Address}, which is never {@code null}. {@code chgTime} keeps the stored
     * instant and drops only the offset, which does not change the moment it denotes.
     *
     * <p>The customer must be persisted: read from the repository, or the result of
     * {@code save}. Its id and row version are then present. A draft, which has neither, is a
     * programming error and fails here with a named {@link NullPointerException}, which the
     * error model answers as 500 DEM9999, rather than producing a body without an id or with an
     * invented version.
     *
     * @param customer the stored customer
     * @return the response body
     * @throws NullPointerException if {@code customer} is {@code null}, or it has no id or no
     *                              row version
     */
    public static CustomerResponse from(Customer customer) {
        Objects.requireNonNull(customer, "customer");
        return new CustomerResponse(
                Objects.requireNonNull(customer.custId(), "custId").value(),
                customer.name(),
                customer.address().addr(),
                customer.address().city(),
                customer.address().state(),
                customer.address().zip(),
                customer.corpPhone(),
                customer.acctMgr(),
                customer.acctPhone(),
                customer.active(),
                customer.chgTime() == null ? null : customer.chgTime().toInstant(),
                customer.chgUser(),
                Objects.requireNonNull(customer.rowVersion(), "rowVersion"));
    }
}
