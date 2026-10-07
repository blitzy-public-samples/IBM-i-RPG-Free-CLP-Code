package com.democorp.customermaster.controller.dto;

/**
 * The result of a successful review on the wire, the 200 body of
 * {@code POST /api/customers/review}: {@code {customer, standardized, notice}}.
 *
 * <p>Replaces the confirmation pass of MTNCUSTR. Once {@code EditUpdData} (and, in the USPS
 * variant, {@code Edit_Address}) found no error, the program protected every field, re-displayed
 * the edited values and sent the confirmation message to the message subfile:
 * {@code SndSflMsg('DEM0000')} on edit [5250_Subfile/MTNCUSTR.SQLRPGLE:219-225] and
 * {@code SndSflMsg('DEM0009')} on add [5250_Subfile/MTNCUSTR.SQLRPGLE:272-277], with the texts
 * defined in CUSTMSGF [5250_Subfile/CRTMSGF.CLLE:12,28]. The UI renders this body the same way:
 * the values read-only in the confirmation panel, the notice in the message region, and Enter
 * then sends the {@code PUT} or {@code POST} that commits them.
 *
 * <table>
 *   <caption>Component, source counterpart and wire meaning</caption>
 *   <tr><th>Property</th><th>Source</th><th>Content</th></tr>
 *   <tr><td>{@code customer}</td><td>The MTNCUSTD fields after {@code FillScreenFields} under
 *       {@code ProtectAll}; in the USPS variant, ADDR, CITY, STATE and ZIP overwritten by
 *       {@code Edit_Address} [USPS_Address/MTNCUSTR.SQLRPGLE:471-487]</td>
 *       <td>The nine {@link CustomerFields} as reviewed: normalized by the service (trailing
 *       blanks removed, uppercased) and, when standardization succeeded, with {@code addr},
 *       {@code city}, {@code state} and {@code zip} replaced by the standardized address
 *       ({@code zip} as {@code ZIP5-ZIP4} when a ZIP+4 was returned). The UI shows these values,
 *       not what was typed, and takes its working State from {@code customer.state}</td></tr>
 *   <tr><td>{@code standardized}</td><td>{@code AdrOut.City <> ' '}, the USPS success test
 *       [USPS_Address/MTNCUSTR.SQLRPGLE:480]</td>
 *       <td>{@code true} when the address service standardized the address; {@code false} when
 *       standardization is disabled ({@code ADDRESS_VALIDATION_ENABLED=false}), in which case
 *       {@code customer} holds the normalized input unchanged</td></tr>
 *   <tr><td>{@code notice}</td><td>{@code SndSflMsg('DEM0000')} or
 *       {@code SndSflMsg('DEM0009')}</td>
 *       <td>A {@link Notice}: DEM0000 "Press Enter to update. F12 to Cancel." for purpose
 *       {@code EDIT}, DEM0009 "Press Enter to add. Press F12 to cancel" for purpose
 *       {@code ADD}</td></tr>
 * </table>
 *
 * <p><b>Values only.</b> {@code service/CustomerMaintenanceService} decides every value: it
 * normalizes and validates the draft, calls the address standardization, and resolves the notice
 * text through the message catalog. {@code controller/CustomerController} copies its review
 * result into this record, so the record has no factory, no compact constructor and no logic, and
 * depends on no service or repository type.
 *
 * <p><b>The contract is the component list.</b> The record carries no serialization or OpenAPI
 * annotations, in particular no {@code @JsonInclude(NON_NULL)}, so every component is always
 * serialized. The component order is the JSON and OpenAPI property order, so it must not change
 * without regenerating the committed OpenAPI snapshot and the frontend's
 * {@code src/api/schema.d.ts}.
 *
 * <p>Example, an edit review with standardization succeeding:
 * <pre>{@code
 * new ReviewResponse(CustomerFields.from(result.customer()), result.standardized(),
 *         Notice.of(result.notice()));
 * // serializes as
 * // {"customer":{"name":"ACME INC","addr":"1 MAIN ST","city":"AUBURN","state":"ME",
 * //              "zip":"04210-1234","corpPhone":"(207) 555-0100","acctMgr":"JANE DOE",
 * //              "acctPhone":"(207) 555-0101","active":"Y"},
 * //  "standardized":true,
 * //  "notice":{"code":"DEM0000","message":"Press Enter to update. F12 to Cancel."}}
 * }</pre>
 *
 * <p>Instances are immutable and therefore thread-safe.
 *
 * @param customer     the reviewed customer data fields, normalized and, when
 *                     {@code standardized}, carrying the standardized address
 * @param standardized {@code true} when address standardization ran and succeeded
 * @param notice       the confirmation message, DEM0000 for an edit or DEM0009 for an add
 */
public record ReviewResponse(CustomerFields customer, boolean standardized, Notice notice) {
}
