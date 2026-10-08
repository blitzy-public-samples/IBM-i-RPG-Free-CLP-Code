package com.democorp.customermaster.service;

import com.democorp.customermaster.domain.ActiveStatus;
import com.democorp.customermaster.domain.Address;
import com.democorp.customermaster.domain.Customer;
import com.democorp.customermaster.service.exception.CustomerValidationException;
import com.democorp.customermaster.service.exception.CustomerValidationException.FieldError;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The nine customer field rules, applied in source order and stopping at the first failure.
 *
 * <p><b>Source.</b> MTNCUSTR's {@code EditUpdData} and its routines {@code Edit_SD_ACTIVE} through
 * {@code Edit_SD_CORPPH} [5250_Subfile/MTNCUSTR.SQLRPGLE:387-544]. {@code EditAddData} only calls
 * {@code EditUpdData}, so add and edit share the same rules. The failing rule throws a
 * {@link CustomerValidationException} carrying the source message id, its substitution argument
 * and the field to highlight, and no later rule runs.
 *
 * <table>
 *   <caption>Rules in evaluation order</caption>
 *   <tr><th>Rule</th><th>Field (JSON name)</th><th>Fails when</th><th>Code and args</th>
 *       <th>Source routine</th></tr>
 *   <tr><td>1</td><td>{@code active}</td><td>not exactly {@code Y} or {@code N}</td>
 *       <td>DEM0501 {@code ["Active Status"]}</td><td>Edit_SD_ACTIVE</td></tr>
 *   <tr><td>2</td><td>{@code name}</td><td>blank</td>
 *       <td>DEM0502 {@code ["Name"]}</td><td>Edit_SD_NAME</td></tr>
 *   <tr><td>3</td><td>{@code addr}</td><td>blank</td>
 *       <td>DEM0502 {@code ["Address"]}</td><td>Edit_SD_ADDR</td></tr>
 *   <tr><td>4</td><td>{@code city}</td><td>blank</td>
 *       <td>DEM0502 {@code ["City"]}</td><td>Edit_SD_CITY</td></tr>
 *   <tr><td>5</td><td>{@code state}</td><td>not in STATES (blank included)</td>
 *       <td>DEM0503, no args</td><td>Edit_SD_STATE</td></tr>
 *   <tr><td>6</td><td>{@code zip}</td><td>blank</td>
 *       <td>DEM0502 {@code ["ZIP"]}</td><td>Edit_SD_ZIP</td></tr>
 *   <tr><td>7</td><td>{@code acctPhone}</td><td>blank</td>
 *       <td>DEM0502 {@code ["Account Manager Phone"]}</td><td>Edit_SD_ACCTPH</td></tr>
 *   <tr><td>8</td><td>{@code acctMgr}</td><td>blank</td>
 *       <td>DEM0502 {@code ["Account Manager Name"]}</td><td>Edit_SD_ACCTMGR</td></tr>
 *   <tr><td>9</td><td>{@code corpPhone}</td><td>blank</td>
 *       <td>DEM0502 {@code ["Corporate Phone"]}</td><td>Edit_SD_CORPPH</td></tr>
 * </table>
 *
 * <p>The labels are the {@code SndSflMsg} arguments of the source, character for character. Rule 7
 * is the account manager's phone and rule 8 the account manager's name, as {@code EditUpdData}
 * calls {@code Edit_SD_ACCTPH} before {@code Edit_SD_ACCTMGR}.
 *
 * <p><b>Rule numbers are part of the contract.</b> {@code CustomerMaintenanceService.review}
 * treats a {@link CustomerValidationException#rule()} above
 * {@link CustomerValidationException#STATE_RULE} as "the State rule passed", because
 * {@code Edit_SD_STATE} had already moved the valid state into the working record
 * [5250_Subfile/MTNCUSTR.SQLRPGLE:488-494], and reports that state as {@code stateAccepted}.
 *
 * <p><b>All nine rules run.</b> The USPS variant comments out the ADDR, CITY, STATE and ZIP edits
 * and relies on {@code Edit_Address} alone [USPS_Address/MTNCUSTR.SQLRPGLE:405-459]. This class
 * keeps them, so a blank street yields {@code "Address: Must not be blank"} rather than a USPS
 * error, and address standardization stays a later stage in {@code AddressStandardizationService}.
 *
 * <p><b>It does not</b> normalize or default anything: the caller normalizes every value and
 * defaults an absent {@code active} to {@code Y} on an add, and a present value, even blank, is
 * checked as given. It enforces no format or length rule; the request DTOs enforce lengths with
 * 400 APP0400. It builds no message text and puts no user value in an exception message.
 *
 * <p>The validator holds no mutable state and {@link StateService} is thread-safe, so one instance
 * serves all requests concurrently.
 */
@Component
public class CustomerValidator {

    /** Rule failures at DEBUG: rule, field and code only, never a value. */
    private static final Logger log = LoggerFactory.getLogger(CustomerValidator.class);

    /** CUSTMSGF {@code "&1: Must be Y or N"}: the Active rule. */
    private static final String MUST_BE_Y_OR_N = "DEM0501";

    /** CUSTMSGF {@code "&1: Must not be blank"}: the seven required-field rules. */
    private static final String MUST_NOT_BE_BLANK = "DEM0502";

    /** CUSTMSGF {@code "State invalid. Can use F4 to prompt."}: the State rule, no argument. */
    private static final String STATE_INVALID = "DEM0503";

    /** Rule 1, Edit_SD_ACTIVE. */
    private static final int ACTIVE_RULE = 1;

    /** Rule 2, Edit_SD_NAME. */
    private static final int NAME_RULE = 2;

    /** Rule 3, Edit_SD_ADDR. */
    private static final int ADDR_RULE = 3;

    /** Rule 4, Edit_SD_CITY. */
    private static final int CITY_RULE = 4;

    /** Rule 5, Edit_SD_STATE; shared with the exception so the boundary has one definition. */
    private static final int STATE_RULE = CustomerValidationException.STATE_RULE;

    /** Rule 6, Edit_SD_ZIP. */
    private static final int ZIP_RULE = 6;

    /** Rule 7, Edit_SD_ACCTPH. */
    private static final int ACCT_PHONE_RULE = 7;

    /** Rule 8, Edit_SD_ACCTMGR. */
    private static final int ACCT_MGR_RULE = 8;

    /** Rule 9, Edit_SD_CORPPH. */
    private static final int CORP_PHONE_RULE = 9;

    /** The cached STATES lookup behind the State rule. */
    private final StateService stateService;

    /**
     * Creates the validator.
     *
     * @param stateService the cached STATES lookup used by the State rule
     * @throws NullPointerException if {@code stateService} is {@code null}
     */
    public CustomerValidator(StateService stateService) {
        this.stateService = Objects.requireNonNull(stateService, "stateService");
    }

    /**
     * Applies the nine field rules to an already-normalized customer, in source order, and returns
     * normally when every rule passes. The first failing rule throws; no later rule is evaluated,
     * as {@code EditUpdData} returned after the first {@code Edit_SD_*} routine that turned
     * {@code NoErrors} off.
     *
     * <p>A {@code null} address is treated as four blank address fields, so it fails rule 3. Blank
     * means {@code null}, empty or whitespace only, as the RPG test {@code SD_x <> ' '} treated a
     * field of spaces.
     *
     * @param c the customer whose nine data fields are checked; its id, change stamp and version
     *          are ignored
     * @throws CustomerValidationException for the first failing rule, carrying the rule number 1 to
     *                                     9, the CUSTMSGF code, the label argument and one field
     *                                     error named by the field's JSON property
     * @throws NullPointerException        if {@code c} is {@code null}
     * @throws IllegalStateException       if the State rule runs while STATES holds no rows (see
     *                                     {@link StateService#exists(String)})
     */
    public void validate(Customer c) {
        Objects.requireNonNull(c, "customer");
        final Address a = c.address();
        final String addr = a == null ? null : a.addr();
        final String city = a == null ? null : a.city();
        final String state = a == null ? null : a.state();
        final String zip = a == null ? null : a.zip();

        if (ActiveStatus.fromCode(c.active()).isEmpty()) {
            throw failure(ACTIVE_RULE, "active", MUST_BE_Y_OR_N, List.of("Active Status"));
        }
        requireNotBlank(c.name(), NAME_RULE, "name", "Name");
        requireNotBlank(addr, ADDR_RULE, "addr", "Address");
        requireNotBlank(city, CITY_RULE, "city", "City");
        if (!stateService.exists(state)) {
            throw failure(STATE_RULE, "state", STATE_INVALID, List.of());
        }
        requireNotBlank(zip, ZIP_RULE, "zip", "ZIP");
        requireNotBlank(c.acctPhone(), ACCT_PHONE_RULE, "acctPhone", "Account Manager Phone");
        requireNotBlank(c.acctMgr(), ACCT_MGR_RULE, "acctMgr", "Account Manager Name");
        requireNotBlank(c.corpPhone(), CORP_PHONE_RULE, "corpPhone", "Corporate Phone");
    }

    /**
     * One DEM0502 rule: the field must hold at least one non-whitespace character.
     *
     * @param value the normalized field value; may be {@code null}
     * @param rule  the rule number in source order
     * @param field the JSON property name to highlight
     * @param label the source {@code SndSflMsg} argument, substituted for {@code {0}}
     * @throws CustomerValidationException if {@code value} is blank
     */
    private static void requireNotBlank(String value, int rule, String field, String label) {
        if (value == null || value.isBlank()) {
            throw failure(rule, field, MUST_NOT_BE_BLANK, List.of(label));
        }
    }

    /**
     * Builds the exception for one failed rule, highlighting its single field with the rule's code.
     * Logged at DEBUG with the rule, field and code only, never the value.
     *
     * @param rule  the rule number in source order
     * @param field the JSON property name to highlight and focus
     * @param code  the CUSTMSGF code
     * @param args  the substitution values: the field label, or none for DEM0503
     * @return the exception to throw
     */
    private static CustomerValidationException failure(int rule, String field, String code,
            List<String> args) {
        log.debug("customer.validate failed rule={} field={} code={}", rule, field, code);
        return new CustomerValidationException(rule, code, args, List.of(new FieldError(field, code)));
    }
}
