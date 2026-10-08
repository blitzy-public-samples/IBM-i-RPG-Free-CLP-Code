package com.democorp.customermaster.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * The 200 body of {@code POST /api/customers/review}, the confirmation step before a save
 * [5250_Subfile/MTNCUSTR.SQLRPGLE:219-225,272-277].
 *
 * <ul>
 *   <li>{@code customer} holds the reviewed values: normalized and, when standardized, with the
 *       standardized address ({@code zip} as {@code ZIP5-ZIP4} when a ZIP+4 came back).
 *       {@code controller/DtoSchemaCustomizer} publishes its nine fields as required here, while
 *       {@link CustomerFields} as a request body keeps them optional.</li>
 *   <li>{@code standardized} is {@code true} when the address service standardized the address and
 *       {@code false} when standardization is disabled, in which case {@code customer} holds the
 *       normalized input.</li>
 *   <li>{@code notice} is DEM0000 for purpose {@code EDIT} and DEM0009 for {@code ADD}.</li>
 * </ul>
 *
 * <p>{@code service/CustomerMaintenanceService} decides every value.
 *
 * @param customer     the reviewed customer data fields, normalized and, when
 *                     {@code standardized}, carrying the standardized address
 * @param standardized {@code true} when address standardization ran and succeeded
 * @param notice       the confirmation message, DEM0000 for an edit or DEM0009 for an add
 */
public record ReviewResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) CustomerFields customer,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean standardized,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Notice notice) {
}
