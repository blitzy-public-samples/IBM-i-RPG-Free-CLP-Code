package com.democorp.customermaster.service;

import com.democorp.customermaster.address.AddressServiceUnavailableException;
import com.democorp.customermaster.address.AddressValidationClient;
import com.democorp.customermaster.address.AddressValidationProperties;
import com.democorp.customermaster.address.AddressValidationRequest;
import com.democorp.customermaster.address.AddressValidationResult;
import com.democorp.customermaster.address.UspsTextRedactor;
import com.democorp.customermaster.domain.Address;
import com.democorp.customermaster.domain.TextNormalizer;
import com.democorp.customermaster.service.exception.CustomerValidationException;
import com.democorp.customermaster.service.exception.CustomerValidationException.FieldError;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * Standardizes a customer's address through the address-validation module: the target of the USPS
 * variant's {@code Edit_Address} routine [USPS_Address/MTNCUSTR.SQLRPGLE:471-499].
 *
 * <p><b>Anti-corruption boundary.</b> This class is the only place where customer fields meet the
 * USPS Web Tools field layout. In that layout {@code Address2} is the street line and
 * {@code Address1} the secondary line; the quirk stays inside {@link #standardize(Address)} and
 * never reaches the {@link Address} model, the API or the UI.
 *
 * <p><b>Mapping.</b> {@code toRequest} builds the request within the
 * {@link AddressValidationRequest} widths, {@code fromResult} overwrites the address when
 * {@link AddressValidationResult#standardized()} is true, and {@code addressNotStandardized} builds
 * the DEM9898 failure on the four address fields [USPS_Address/MTNCUSTR.SQLRPGLE:491-498]. A
 * standardized state must then exist in STATES, because the {@code custmast_state_fk} foreign key
 * requires it; this standardized-State check is distinct from the State field rule.
 *
 * <p><b>When it runs.</b> Only {@code CustomerMaintenanceService.review} calls this service, after
 * the nine field rules have passed, so the user confirms the standardized values before saving.
 * Add and update never call it, which avoids a second USPS call per save. It always calls
 * {@link #standardize(Address)}; this class alone reads the {@code customer-master.address.enabled}
 * flag ({@code ADDRESS_VALIDATION_ENABLED}). With the flag off, the input is returned unchanged and
 * the maintenance flow is exactly the 5250 variant.
 *
 * <p><b>No transaction.</b> The class holds no database connection and is deliberately not
 * {@code @Transactional}: the outbound address call must never run while a connection or lock is
 * held. The only data it reads is the {@link StateService} cache.
 *
 * <p><b>Service faults.</b> An {@link AddressServiceUnavailableException} from the client is not
 * caught here. It propagates unchanged and becomes 502 APP0502 with no {@code errors}; it is never
 * turned into DEM9898 or a 500.
 *
 * <p><b>Privacy.</b> Nothing this class logs contains an address value, a USPS response value, a
 * URL or a credential.
 *
 * <p>Thread safety: the service is stateless after construction and safe for concurrent use.
 */
@Service
public class AddressStandardizationService {

    private static final Logger log = LoggerFactory.getLogger(AddressStandardizationService.class);

    /**
     * Width of the customer {@code city} column and of {@code SD_CITY}
     * [5250_Subfile/Custmast2.sql:13]. USPS returns a city of up to 30 characters, which the
     * source assigns to the 20-character field, so the returned city is cut to this width.
     */
    static final int CITY_COLUMN_WIDTH = 20;

    /** Catalog code of an address the service examined and could not standardize. */
    private static final String ADDRESS_NOT_STANDARDIZED = "DEM9898";

    /** Catalog code of the standardized-State check ("State invalid. Can use F4 to prompt."). */
    private static final String STATE_INVALID = "DEM0503";

    /** The fields a DEM9898 highlights, in this order; the first receives focus. */
    private static final List<String> ADDRESS_FIELDS = List.of("addr", "city", "state", "zip");

    /** Separator between ZIP and ZIP+4 in the stored {@code zip}, as {@code Zip5 + '-' + Zip4}. */
    private static final String ZIP4_SEPARATOR = "-";

    private final AddressValidationProperties properties;
    private final ObjectProvider<AddressValidationClient> clients;
    private final StateService stateService;
    private final UspsTextRedactor redactor;

    /**
     * The outcome of {@link #standardize(Address)}.
     *
     * @param address      the address to continue with: the standardized values when
     *                     {@code standardized} is true, otherwise the input unchanged
     * @param standardized {@code true} when the address service standardized the address;
     *                     {@code false} when standardization is disabled
     */
    public record Result(Address address, boolean standardized) {

        /**
         * Rejects a missing address.
         *
         * @throws NullPointerException if {@code address} is {@code null}
         */
        public Result {
            Objects.requireNonNull(address, "address");
        }
    }

    /**
     * Creates the service.
     *
     * @param properties   the bound {@code customer-master.address.*} settings:
     *                     {@link AddressValidationProperties#enabled()} decides whether the client
     *                     is called, and {@link AddressValidationProperties#usps()} configures the
     *                     redactor of the DEM9898 description
     * @param clients      provider of the one {@link AddressValidationClient} bean, the stub by
     *                     default or the Web Tools client when {@code client=usps}; it is resolved
     *                     only when standardization is enabled, so a context without a client
     *                     still works with the flag off
     * @param stateService the state lookup used by the standardized-State check
     * @throws NullPointerException if any argument is {@code null}
     */
    public AddressStandardizationService(
            AddressValidationProperties properties,
            ObjectProvider<AddressValidationClient> clients,
            StateService stateService) {
        this.properties = Objects.requireNonNull(properties, "properties");
        this.clients = Objects.requireNonNull(clients, "clients");
        this.stateService = Objects.requireNonNull(stateService, "stateService");
        this.redactor = new UspsTextRedactor(properties.usps());
    }

    /**
     * Standardizes {@code in}, as {@code Edit_Address} does.
     *
     * <p>With standardization disabled, returns {@code new Result(in, false)} without resolving or
     * calling the client. Otherwise sends one request and either returns the standardized address
     * or throws.
     *
     * @param in the address that passed the field rules; its components are normally normalized
     *           already, and are uppercased and trimmed again before they are sent
     * @return the standardized address with {@code standardized = true}, or the input with
     *         {@code standardized = false} when standardization is disabled
     * @throws CustomerValidationException        at {@link CustomerValidationException#ADDRESS_RULE}:
     *                                            DEM9898 with the USPS description on {@code addr},
     *                                            {@code city}, {@code state} and {@code zip} when the
     *                                            address was not standardized, or DEM0503 on
     *                                            {@code state} when the standardized state is not in
     *                                            STATES
     * @throws AddressServiceUnavailableException when the address service gives no usable answer;
     *                                            propagated unchanged
     * @throws NullPointerException               if {@code in} is {@code null}
     */
    public Result standardize(Address in) {
        Objects.requireNonNull(in, "in");
        if (!properties.enabled()) {
            log.debug("address.standardize outcome=disabled");
            return new Result(in, false);
        }

        AddressValidationResult result = clients.getObject().validate(toRequest(in));

        if (!result.standardized()) {
            log.debug("address.standardize outcome=not-standardized");
            throw addressNotStandardized(result);
        }

        Address standardized = fromResult(result);
        if (!stateService.exists(standardized.state())) {
            log.debug("address.standardize outcome=state-not-found");
            throw new CustomerValidationException(
                    CustomerValidationException.ADDRESS_RULE,
                    STATE_INVALID,
                    List.of(),
                    List.of(new FieldError("state", STATE_INVALID)));
        }
        log.debug("address.standardize outcome=standardized");
        return new Result(standardized, true);
    }

    /**
     * Builds the request as {@code Edit_Address} fills {@code AdrIn}
     * [USPS_Address/MTNCUSTR.SQLRPGLE:472-476].
     *
     * <p>Only the first 30 characters of the 40-character street are sent, because the source
     * assigns {@code SD_ADDR} to the 30-character {@code AdrIn.Address2}
     * [Copy_Mbrs/USADRVALDS.RPGLE:5]. This is a preserved source defect, kept deliberately: the
     * request is not widened to the full street. The cuts also guarantee that the request's
     * width checks never reject a value.
     */
    private static AddressValidationRequest toRequest(Address in) {
        return new AddressValidationRequest(
                "",
                cut(TextNormalizer.filter(in.addr()), AddressValidationRequest.ADDRESS2_WIDTH),
                cut(TextNormalizer.filter(in.city()), AddressValidationRequest.CITY_WIDTH),
                cut(TextNormalizer.filter(in.state()), AddressValidationRequest.STATE_WIDTH),
                cut(TextNormalizer.filter(in.zip()), AddressValidationRequest.ZIP5_WIDTH),
                "");
    }

    /**
     * Maps a standardized result back onto the customer address, overwriting every component
     * [USPS_Address/MTNCUSTR.SQLRPGLE:480-488].
     *
     * <p>The street becomes the returned {@code Address2}, at most 30 characters, so a longer
     * street is shortened whenever standardization succeeds. This is preserved source behaviour,
     * kept deliberately rather than merging the returned street with the original.
     */
    private static Address fromResult(AddressValidationResult result) {
        String addr = TextNormalizer.field(result.address2());
        String city = TextNormalizer.field(cut(result.city(), CITY_COLUMN_WIDTH));
        String state = TextNormalizer.field(result.state());
        String zip5 = TextNormalizer.field(result.zip5());
        String zip4 = TextNormalizer.field(result.zip4());
        String zip = zip4.isBlank() ? zip5 : zip5 + ZIP4_SEPARATOR + zip4;
        return new Address(addr, city, state, zip);
    }

    /**
     * Builds the DEM9898 failure: the USPS description is the message argument, and the four
     * address fields are highlighted with {@code addr} first.
     *
     * <p>The argument is the description as the shared {@link UspsTextRedactor} returns it: each
     * echo of the request it recognizes and each configured credential in every form it masks are
     * replaced by their markers, and all other text is kept as the service sent it. Neither the
     * DEM9898 detail nor the four field messages can therefore carry a credential in those forms;
     * {@code result} itself is left raw.
     */
    private CustomerValidationException addressNotStandardized(AddressValidationResult result) {
        String description = redactor.redact(result.errorDescription());
        List<FieldError> errors = ADDRESS_FIELDS.stream()
                .map(field -> new FieldError(field, ADDRESS_NOT_STANDARDIZED))
                .toList();
        return new CustomerValidationException(
                CustomerValidationException.ADDRESS_RULE,
                ADDRESS_NOT_STANDARDIZED,
                List.of(description),
                errors);
    }

    /**
     * Cuts {@code s} to at most {@code width} code points, never splitting a surrogate pair.
     * {@code null} becomes {@code ""}.
     */
    static String cut(String s, int width) {
        if (s == null) {
            return "";
        }
        if (s.codePointCount(0, s.length()) <= width) {
            return s;
        }
        return s.substring(0, s.offsetByCodePoints(0, width));
    }
}
