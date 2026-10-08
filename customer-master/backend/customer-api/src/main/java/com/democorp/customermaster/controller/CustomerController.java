package com.democorp.customermaster.controller;

import java.net.URI;
import java.util.Objects;

import com.democorp.customermaster.controller.dto.CustomerFields;
import com.democorp.customermaster.controller.dto.CustomerResponse;
import com.democorp.customermaster.controller.dto.CustomerUpdateRequest;
import com.democorp.customermaster.controller.dto.Notice;
import com.democorp.customermaster.controller.dto.ReviewRequest;
import com.democorp.customermaster.controller.dto.ReviewResponse;
import com.democorp.customermaster.controller.dto.SearchResponse;
import com.democorp.customermaster.domain.Customer;
import com.democorp.customermaster.domain.CustomerId;
import com.democorp.customermaster.domain.SearchPage;
import com.democorp.customermaster.service.CustomerMaintenanceService;
import com.democorp.customermaster.service.CustomerSearchService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/customers}: the customer list, one customer, the review before a save, the add and the
 * update.
 *
 * <p><b>Source parameters.</b> PMTCUSTR ({@code pParmType}, {@code pCustID};
 * {@code 5250_Subfile/PMTCUSTR.SQLRPGLE}, lines 76-79) becomes the stateless page query
 * {@code GET /api/customers}, whose position travels as the opaque {@code cursor}. MTNCUSTR's
 * function code {@code pMaintain}, passed with {@code pID} ({@code 5250_Subfile/MTNCUSTR.SQLRPGLE},
 * lines 45-48), becomes the HTTP method: {@code GET} displays, {@code POST /review} runs the Enter
 * pass that ends in the DEM0000 or DEM0009 confirmation, {@code PUT} commits an edit and {@code POST}
 * commits an add. Of PMTCUSTR's mode letters, only {@code I} and {@code M} become roles,
 * {@code INQUIRY} for the two reads and {@code MAINTENANCE} for review, add and update;
 * {@link com.democorp.customermaster.security.SecurityConfig SecurityConfig} enforces them, so this
 * class performs no role check. {@code S} (Selection) is not a role but a browser context that
 * reads through the same {@code GET}.
 *
 * <p><b>Layering.</b> The controller binds the request, delegates and maps the result to DTOs; the
 * business rules are the services', and every failure propagates to {@link ApiExceptionHandler}.
 *
 * <p><b>Request binding.</b>
 * <ul>
 *   <li>The {@code custId} path {@code @Pattern} is checked by Spring's built-in method validation,
 *       which raises {@code HandlerMethodValidationException}: 400 APP0400. A lower-case id is
 *       rejected, not uppercased, because ids are issued in upper case.</li>
 *   <li>The constraints the body DTOs declare are checked through {@link Valid}: 400 APP0400. On
 *       {@code PUT}, whose path variable is constrained too, those body errors arrive inside the
 *       same {@code HandlerMethodValidationException}.</li>
 *   <li>A {@code custId}, {@code chgTime}, {@code chgUser}, {@code rowVersion} or any other member
 *       the DTO does not declare is rejected by Jackson
 *       ({@code spring.jackson.deserialization.fail-on-unknown-properties}): 400 APP0400.</li>
 *   <li>{@code name}, {@code city}, {@code size} and {@code state} deliberately carry no constraint,
 *       as their parameter comments say. {@link CustomerSearchService} counts the 13-character widths
 *       of {@code name} and {@code city} ({@code 5250_Subfile/PMTCUSTD.DSPF}, lines 95-97) in code
 *       points and answers 400 APP0400, as it does for a {@code size} outside 1 to 100, and a
 *       {@code state} other than blank or 2 characters must reach it for 400 DEM0007 on field
 *       {@code state}, as PMTCUSTR does [5250_Subfile/PMTCUSTR.SQLRPGLE:635-646].</li>
 * </ul>
 *
 * <p><b>No {@code @Validated}.</b> Spring Framework 6.2 validates constrained handler parameters
 * itself and raises {@code HandlerMethodValidationException}, which {@link ApiExceptionHandler} maps
 * to APP0400. {@code @Validated} would switch to AOP method validation, whose
 * {@code ConstraintViolationException} would bypass that mapping and surface as 500 DEM9999.
 *
 * <p>The statuses every operation shares, 500 DEM9999, 405, 406 and, with a request body, 415, come
 * from {@link ProblemResponsesCustomizer} and are not declared per operation.
 */
@RestController
@RequestMapping(CustomerController.BASE_PATH)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@Tag(name = "Customers")
@SecurityRequirement(name = "basicAuth")
public class CustomerController {

    /** The collection path; also the prefix of the {@code Location} of an added customer. */
    static final String BASE_PATH = "/api/customers";

    /**
     * Width of the "Name starts with" and "City starts with" entries, {@code SC_NAME 13A},
     * {@code SC_CITY 13A}, in characters; published as the OpenAPI {@code maxLength} of both
     * parameters and enforced, in code points, by {@link CustomerSearchService}.
     */
    static final int FILTER_MAX_LENGTH = 13;

    /**
     * Format of a customer id path segment: 4 characters of the base-36 digit alphabet, upper case.
     * Anchored because the OpenAPI {@code pattern} it is also published as matches anywhere in the
     * value; {@code @Pattern} matches the whole value either way.
     */
    static final String CUST_ID_PATTERN = "^[A-Z0-9]{4}$";

    /** OpenAPI description of the {@code custId} path variable, shared by get and update. */
    private static final String CUST_ID_DESCRIPTION = "4-character base-36 customer id";

    /** The keyset page query behind the customer list. */
    private final CustomerSearchService searchService;

    /** Display, review, add and update of one customer. */
    private final CustomerMaintenanceService maintenanceService;

    /**
     * Creates the controller.
     *
     * @param searchService      the customer list query; must not be {@code null}
     * @param maintenanceService the single-customer operations; must not be {@code null}
     * @throws NullPointerException if either argument is {@code null}
     */
    public CustomerController(CustomerSearchService searchService,
            CustomerMaintenanceService maintenanceService) {
        this.searchService = Objects.requireNonNull(searchService, "searchService");
        this.maintenanceService = Objects.requireNonNull(maintenanceService, "maintenanceService");
    }

    /**
     * Returns one page of the customer list: PMTCUSTR's {@code ItemCur} with its filters, 12 rows a
     * page and the 9,999-row cap [5250_Subfile/PMTCUSTR.SQLRPGLE:208-223,287-297].
     *
     * <p>Example: {@code GET /api/customers?name=nibh} returns the first 12 active customers whose
     * name starts with {@code NIBH}, ordered by name, city, state and id; passing the response's
     * {@code nextCursor} as {@code cursor} with the same filters returns the next page.
     *
     * @param name            "Name starts with", at most 13 characters; absent or blank for none
     * @param city            "City starts with", at most 13 characters; absent or blank for none
     * @param state           a 2-character state code; absent or blank for every state
     * @param includeInactive F9: {@code true} to include customers whose {@code active} is {@code N}
     * @param size            rows per page, 1 to 100; absent for the default of 12
     * @param cursor          the {@code nextCursor} of the previous page; absent for the first page
     * @return the page, with {@code nextCursor}, {@code limitReached} and the DEM0002 or DEM0006
     *         notice; every member is present, {@code null} where it does not apply
     */
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "searchCustomers", summary = "Search customers by name, city and state")
    @ApiResponse(
            responseCode = "200",
            description = "One page of matching customers",
            content = @Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = SearchResponse.class)))
    @ApiResponse(
            responseCode = "400",
            description = "APP0400 (cursor, size, lengths, U+0000) or DEM0007 (state)",
            content = @Content(
                    mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemSchema.class)))
    @ApiResponse(
            responseCode = "401",
            description = "APP0401",
            content = @Content(
                    mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemSchema.class)))
    public SearchResponse search(
            // Width documented, not constrained: @Size counts UTF-16 units, while the 13-character
            // limit counts code points; the service enforces it and answers APP0400 on the field.
            @Parameter(
                    description = "Name starts with (at most 13 characters)",
                    schema = @Schema(maxLength = FILTER_MAX_LENGTH))
            @RequestParam(required = false)
            String name,
            @Parameter(
                    description = "City starts with (at most 13 characters)",
                    schema = @Schema(maxLength = FILTER_MAX_LENGTH))
            @RequestParam(required = false)
            String city,
            // No size constraint: a value that is neither blank nor 2 characters must reach the
            // service, which answers DEM0007 on field state, as PMTCUSTR does.
            @Parameter(description = "Two-letter state code; blank = all")
            @RequestParam(required = false)
            String state,
            @Parameter(description = "Include inactive customers (F9)")
            @RequestParam(defaultValue = "false")
            boolean includeInactive,
            // Range and default documented only: the service applies the configured default and
            // answers APP0400 on size outside 1 to 100. The type is explicit because an annotated
            // schema without one is published as a string.
            @Parameter(
                    description = "Page size 1–100, default 12",
                    schema = @Schema(
                            type = "integer",
                            format = "int32",
                            minimum = "1",
                            maximum = "100",
                            defaultValue = "12"))
            @RequestParam(required = false)
            Integer size,
            @Parameter(description = "Opaque cursor from nextCursor")
            @RequestParam(required = false)
            String cursor) {
        SearchPage page = searchService.search(name, city, state, includeInactive, size, cursor);
        return SearchResponse.from(page);
    }

    /**
     * Returns one customer for display or edit: MTNCUSTR's {@code ReadRecd}
     * [5250_Subfile/MTNCUSTR.SQLRPGLE:315-338].
     *
     * <p>Example: {@code GET /api/customers/AAAD} returns {@code NIBH L'LOR COMPANY} with its change
     * stamp and {@code version}.
     *
     * @param custId the customer id, exactly 4 characters of {@code [A-Z0-9]}
     * @return the stored customer, including {@code chgTime}, {@code chgUser} and {@code version}
     */
    @GetMapping(value = "/{custId}", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "getCustomer", summary = "Read one customer")
    @ApiResponse(
            responseCode = "200",
            description = "The stored customer",
            content = @Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = CustomerResponse.class)))
    @ApiResponse(
            responseCode = "400",
            description = "APP0400 (id format)",
            content = @Content(
                    mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemSchema.class)))
    @ApiResponse(
            responseCode = "401",
            description = "APP0401",
            content = @Content(
                    mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemSchema.class)))
    @ApiResponse(
            responseCode = "404",
            description = "DEM0599",
            content = @Content(
                    mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemSchema.class)))
    public CustomerResponse get(
            @Parameter(
                    description = CUST_ID_DESCRIPTION,
                    schema = @Schema(
                            pattern = CUST_ID_PATTERN,
                            minLength = CustomerId.LENGTH,
                            maxLength = CustomerId.LENGTH))
            @PathVariable
            @Pattern(regexp = CUST_ID_PATTERN)
            String custId) {
        Customer customer = maintenanceService.get(CustomerId.parse(custId));
        return CustomerResponse.from(customer);
    }

    /**
     * Checks a draft and returns the values the user is asked to confirm: MTNCUSTR's Enter pass,
     * {@code EditUpdData} and, when address standardization is enabled, {@code Edit_Address}, ending
     * in the DEM0000 (edit) or DEM0009 (add) confirmation [5250_Subfile/MTNCUSTR.SQLRPGLE:219-291],
     * [USPS_Address/MTNCUSTR.SQLRPGLE:405-499]. Nothing is stored.
     *
     * <p>Example: {@code POST /api/customers/review} with
     * {@code {"purpose":"ADD","name":"acme inc",...}} and no {@code active} answers 200 with the
     * uppercased values, {@code active} {@code Y} and the DEM0009 notice.
     *
     * @param request the purpose and the nine data fields
     * @return the normalized and, when enabled, standardized values, whether the address service
     *         standardized them, and the confirmation notice
     */
    @PostMapping(
            value = "/review",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "reviewCustomer", summary = "Validate and standardize a customer before saving")
    @ApiResponse(
            responseCode = "200",
            description = "All rules passed; confirm with DEM0000 (EDIT) or DEM0009 (ADD)",
            content = @Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = ReviewResponse.class)))
    @ApiResponse(
            responseCode = "400",
            description = "APP0400",
            content = @Content(
                    mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemSchema.class)))
    @ApiResponse(
            responseCode = "401",
            description = "APP0401",
            content = @Content(
                    mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemSchema.class)))
    @ApiResponse(
            responseCode = "403",
            description = "APP0403",
            content = @Content(
                    mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemSchema.class)))
    @ApiResponse(
            responseCode = "422",
            description = "DEM0501, DEM0502, DEM0503, DEM9898; errors names the fields at fault, and "
                    + "stateAccepted carries the normalized State when the State rule passed",
            content = @Content(
                    mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemSchema.class)))
    @ApiResponse(
            responseCode = "502",
            description = "APP0502; stateAccepted carries the normalized State when the State rule passed",
            content = @Content(
                    mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemSchema.class)))
    public ReviewResponse review(@Valid @RequestBody ReviewRequest request) {
        CustomerMaintenanceService.ReviewResult result =
                maintenanceService.review(request.purpose(), request.fields().toDraft());
        return new ReviewResponse(
                CustomerFields.from(result.customer()),
                result.standardized(),
                Notice.of(result.notice()));
    }

    /**
     * Adds a customer under the next id: MTNCUSTR's {@code AddRecd}
     * [5250_Subfile/MTNCUSTR.SQLRPGLE:548-566]. The service re-runs the nine field rules, defaults an
     * absent {@code active} to {@code Y}, allocates the id and stamps the row.
     *
     * <p>Example: the first add on a fresh database answers 201 with
     * {@code Location: /api/customers/EEEF} and a body whose {@code version} is 0.
     *
     * @param fields the nine data fields; an absent {@code active} reaches the service as {@code null}
     * @return 201 with a relative {@code Location} naming the new customer, and the stored customer
     */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "addCustomer", summary = "Add a customer under the next id")
    @ApiResponse(
            responseCode = "201",
            description = "Created",
            headers = @Header(
                    name = "Location",
                    description = "Path of the new customer, /api/customers/{custId}",
                    required = true,
                    schema = @Schema(type = "string")),
            content = @Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = CustomerResponse.class)))
    @ApiResponse(
            responseCode = "400",
            description = "APP0400",
            content = @Content(
                    mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemSchema.class)))
    @ApiResponse(
            responseCode = "401",
            description = "APP0401",
            content = @Content(
                    mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemSchema.class)))
    @ApiResponse(
            responseCode = "403",
            description = "APP0403",
            content = @Content(
                    mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemSchema.class)))
    @ApiResponse(
            responseCode = "409",
            description = "DEM1001",
            content = @Content(
                    mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemSchema.class)))
    @ApiResponse(
            responseCode = "422",
            description = "DEM0501, DEM0502, DEM0503",
            content = @Content(
                    mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemSchema.class)))
    @ApiResponse(
            responseCode = "503",
            description = "APP0503",
            content = @Content(
                    mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemSchema.class)))
    public ResponseEntity<CustomerResponse> add(@Valid @RequestBody CustomerFields fields) {
        Customer saved = maintenanceService.add(fields.toDraft());
        // Relative on purpose: the browser reaches the API through a same-origin proxy, so a URL
        // built from this server's host and port would name an address the client cannot use.
        URI location = URI.create(BASE_PATH + "/" + saved.custId().value());
        return ResponseEntity.created(location).body(CustomerResponse.from(saved));
    }

    /**
     * Changes a customer if it is still at the version the caller read: MTNCUSTR's
     * {@code UpdateRecd} [5250_Subfile/MTNCUSTR.SQLRPGLE:567-607]. The service re-runs the nine field
     * rules, issues the versioned {@code UPDATE} and stamps the row.
     *
     * <p>Example: {@code PUT /api/customers/AAAD} with the edited fields and {@code "version":0}
     * answers 200 with {@code version} 1; sent again with {@code "version":0}, it answers 409 DEM1002
     * carrying the record as now stored.
     *
     * @param custId  the customer id, exactly 4 characters of {@code [A-Z0-9]}
     * @param request the nine data fields and the {@code version} the caller read
     * @return the stored customer with its new change stamp and {@code version + 1}
     */
    @PutMapping(
            value = "/{custId}",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "updateCustomer", summary = "Change a customer at the version read")
    @ApiResponse(
            responseCode = "200",
            description = "The stored customer with version + 1",
            content = @Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = CustomerResponse.class)))
    @ApiResponse(
            responseCode = "400",
            description = "APP0400 (including missing version)",
            content = @Content(
                    mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemSchema.class)))
    @ApiResponse(
            responseCode = "401",
            description = "APP0401",
            content = @Content(
                    mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemSchema.class)))
    @ApiResponse(
            responseCode = "403",
            description = "APP0403",
            content = @Content(
                    mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemSchema.class)))
    @ApiResponse(
            responseCode = "404",
            description = "DEM0599",
            content = @Content(
                    mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemSchema.class)))
    @ApiResponse(
            responseCode = "409",
            description = "DEM1002 (stale version), whose current carries the customer as now stored, "
                    + "including its version; or DEM1001 (row locked), without current",
            content = @Content(
                    mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemSchema.class)))
    @ApiResponse(
            responseCode = "422",
            description = "DEM0501, DEM0502, DEM0503",
            content = @Content(
                    mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemSchema.class)))
    public CustomerResponse update(
            @Parameter(
                    description = CUST_ID_DESCRIPTION,
                    schema = @Schema(
                            pattern = CUST_ID_PATTERN,
                            minLength = CustomerId.LENGTH,
                            maxLength = CustomerId.LENGTH))
            @PathVariable
            @Pattern(regexp = CUST_ID_PATTERN)
            String custId,
            @Valid @RequestBody CustomerUpdateRequest request) {
        Customer saved = maintenanceService.update(
                CustomerId.parse(custId), request.fields().toDraft(), request.version());
        return CustomerResponse.from(saved);
    }
}
