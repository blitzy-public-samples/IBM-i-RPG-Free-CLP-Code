package com.democorp.customermaster.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.democorp.customermaster.domain.Address;
import com.democorp.customermaster.domain.Customer;
import com.democorp.customermaster.service.exception.CustomerValidationException;
import java.util.List;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Specifies {@link CustomerValidator}, the nine customer field rules of MTNCUSTR's {@code EditUpdData}
 * [5250_Subfile/MTNCUSTR.SQLRPGLE:387-544].
 *
 * <p><b>What the source does.</b> {@code EditUpdData} calls {@code Edit_SD_ACTIVE}, {@code Edit_SD_NAME},
 * {@code Edit_SD_ADDR}, {@code Edit_SD_CITY}, {@code Edit_SD_STATE}, {@code Edit_SD_ZIP},
 * {@code Edit_SD_ACCTPH}, {@code Edit_SD_ACCTMGR} and {@code Edit_SD_CORPPH} in that order and returns as
 * soon as one of them turns {@code NoErrors} off. A failing routine sends one CUSTMSGF message through
 * {@code SndSflMsg} and sets the reverse-image and position-cursor indicators of its own field. The
 * messages are those of [5250_Subfile/CRTMSGF.CLLE:30-35]: DEM0501 {@code "&1: Must be Y or N"}, DEM0502
 * {@code "&1: Must not be blank"} and DEM0503 {@code "State invalid. Can use F4 to prompt."}, which takes no
 * argument.
 *
 * <p><b>What the target must do.</b> The first failing rule throws one {@link CustomerValidationException}
 * carrying the rule number (1 to 9 in source order), the CUSTMSGF code, the {@code SndSflMsg} label verbatim
 * as its only argument, and exactly one {@link CustomerValidationException.FieldError} that names the field's
 * JSON property with the same code. Rule 7 is the account manager's phone and rule 8 the account manager's
 * name, because {@code EditUpdData} calls {@code Edit_SD_ACCTPH} before {@code Edit_SD_ACCTMGR}
 * [5250_Subfile/MTNCUSTR.SQLRPGLE:419-433]; that is not the screen's label order.
 *
 * <p><b>Inputs.</b> The validator receives values the maintenance service has already normalized with
 * {@code TextNormalizer.field}: uppercase, no trailing blanks, a blank field as {@code ""}. Every fixture
 * below is written in that form, and each failing case changes exactly one field of {@link #valid()}.
 *
 * <p><b>STATES.</b> {@link StateService} is a plain Mockito mock that knows only {@code CA}; every other code
 * gets Mockito's default {@code false}. No strict-stubbing extension is used, because cases that fail before
 * the State rule never call {@link StateService#exists(String)} and would otherwise report the shared stub as
 * unnecessary.
 *
 * <p>Pure JUnit 5, AssertJ and Mockito: no Spring context, no database, no Docker.
 */
@DisplayName("CustomerValidator: the nine EditUpdData field rules, first error stops")
final class CustomerValidatorTest {

    /** The only state code the mocked STATES lookup knows. */
    private static final String KNOWN_STATE = "CA";

    /** A two-letter code absent from STATES. */
    private static final String UNKNOWN_STATE = "XX";

    /** CUSTMSGF {@code "&1: Must be Y or N"}. */
    private static final String DEM0501 = "DEM0501";

    /** CUSTMSGF {@code "&1: Must not be blank"}. */
    private static final String DEM0502 = "DEM0502";

    /** CUSTMSGF {@code "State invalid. Can use F4 to prompt."}. */
    private static final String DEM0503 = "DEM0503";

    private StateService stateService;

    private CustomerValidator validator;

    @BeforeEach
    void setUp() {
        stateService = mock(StateService.class);
        when(stateService.exists(KNOWN_STATE)).thenReturn(true);
        validator = new CustomerValidator(stateService);
    }

    /**
     * An already-normalized customer that passes all nine rules.
     *
     * @return a new draft; {@code Customer.draft} takes name, address, corpPhone, acctMgr, acctPhone, active
     */
    static Customer valid() {
        return Customer.draft(
                "ACME TOOL COMPANY",
                new Address("12 MAIN STREET", "SPRINGFIELD", KNOWN_STATE, "90210"),
                "(415) 555-0100",
                "JANE DOE",
                "(415) 555-0199",
                "Y");
    }

    /**
     * Builds one failing case: {@code breaker} changes a single field of {@link #valid()}.
     *
     * @param rule    the expected rule number
     * @param field   the expected JSON property name of the highlighted field
     * @param change  a readable description of the change, for the display name
     * @param breaker the one-field change applied to the valid customer
     * @param code    the expected CUSTMSGF code
     * @param args    the expected substitution arguments, verbatim
     * @return the parameter set
     */
    private static Arguments failure(int rule, String field, String change, UnaryOperator<Customer> breaker,
            String code, List<String> args) {
        return Arguments.of(rule, field, change, breaker, code, args);
    }

    /**
     * One case per rule and message of the field-rule table, with the source line of each routine.
     *
     * @return rule, field, change, breaker, code and args per case
     */
    static Stream<Arguments> singleFieldFailures() {
        return Stream.of(
                // Edit_SD_ACTIVE, MTNCUSTR:444-453: SndSflMsg('DEM0501': 'Active Status').
                failure(1, "active", "active = X",
                        c -> c.withActive("X"), DEM0501, List.of("Active Status")),
                // A present blank value is checked as given and fails; only an absent one is defaulted to Y.
                failure(1, "active", "active = \"\"",
                        c -> c.withActive(""), DEM0501, List.of("Active Status")),
                // Edit_SD_NAME, MTNCUSTR:455-464: SndSflMsg('DEM0502': 'Name').
                failure(2, "name", "name = \"\"",
                        c -> c.withName(""), DEM0502, List.of("Name")),
                // Edit_SD_ADDR, MTNCUSTR:466-475: SndSflMsg('DEM0502': 'Address').
                failure(3, "addr", "addr = \"\"",
                        c -> c.withAddress(c.address().withAddr("")), DEM0502, List.of("Address")),
                // Edit_SD_CITY, MTNCUSTR:477-486: SndSflMsg('DEM0502': 'City').
                failure(4, "city", "city = \"\"",
                        c -> c.withAddress(c.address().withCity("")), DEM0502, List.of("City")),
                // Edit_SD_STATE, MTNCUSTR:488-500: no STATES row for a blank code; SndSflMsg('DEM0503').
                failure(5, "state", "state = \"\"",
                        c -> c.withAddress(c.address().withState("")), DEM0503, List.of()),
                // Same routine: a well-formed but unknown code finds no STATES row either.
                failure(5, "state", "state = XX",
                        c -> c.withAddress(c.address().withState(UNKNOWN_STATE)), DEM0503, List.of()),
                // Edit_SD_ZIP, MTNCUSTR:502-511: SndSflMsg('DEM0502': 'ZIP').
                failure(6, "zip", "zip = \"\"",
                        c -> c.withAddress(c.address().withZip("")), DEM0502, List.of("ZIP")),
                // Edit_SD_ACCTPH, MTNCUSTR:513-522: SndSflMsg('DEM0502': 'Account Manager Phone').
                failure(7, "acctPhone", "acctPhone = \"\"",
                        c -> c.withAcctPhone(""), DEM0502, List.of("Account Manager Phone")),
                // Edit_SD_ACCTMGR, MTNCUSTR:524-533: SndSflMsg('DEM0502': 'Account Manager Name').
                failure(8, "acctMgr", "acctMgr = \"\"",
                        c -> c.withAcctMgr(""), DEM0502, List.of("Account Manager Name")),
                // Edit_SD_CORPPH, MTNCUSTR:535-544: SndSflMsg('DEM0502': 'Corporate Phone').
                failure(9, "corpPhone", "corpPhone = \"\"",
                        c -> c.withCorpPhone(""), DEM0502, List.of("Corporate Phone")));
    }

    /**
     * Two invalid fields per case; only the one whose rule runs first is reported.
     *
     * @return rule, field, change, breaker, code and args per case
     */
    static Stream<Arguments> twoFieldFailures() {
        return Stream.of(
                failure(2, "name", "name = \"\" and zip = \"\"",
                        c -> c.withName("").withAddress(c.address().withZip("")),
                        DEM0502, List.of("Name")),
                failure(1, "active", "active = X and corpPhone = \"\"",
                        c -> c.withActive("X").withCorpPhone(""),
                        DEM0501, List.of("Active Status")),
                failure(5, "state", "state = XX and acctMgr = \"\"",
                        c -> c.withAddress(c.address().withState(UNKNOWN_STATE)).withAcctMgr(""),
                        DEM0503, List.of()),
                failure(6, "zip", "zip = \"\" and corpPhone = \"\"",
                        c -> c.withAddress(c.address().withZip("")).withCorpPhone(""),
                        DEM0502, List.of("ZIP")));
    }

    // ---------------------------------------------------------------------------------------------
    // One case per rule
    // ---------------------------------------------------------------------------------------------

    @ParameterizedTest(name = "[{index}] rule {0}: {2} -> {4} {5} on {1}")
    @MethodSource("singleFieldFailures")
    @DisplayName("each rule reports its rule number, code, verbatim source label and one field error")
    void eachRuleReportsItsCodeLabelAndField(int rule, String field, String change,
            UnaryOperator<Customer> breaker, String code, List<String> args) {
        Customer customer = breaker.apply(valid());

        assertThatThrownBy(() -> validator.validate(customer))
                .as("validate(%s)", change)
                .isInstanceOfSatisfying(CustomerValidationException.class,
                        e -> assertFailure(e, rule, field, code, args));
    }

    @ParameterizedTest(name = "[{index}] state = \"{0}\"")
    @ValueSource(strings = {"", UNKNOWN_STATE})
    @DisplayName("a blank or unknown state fails at the State rule, the stateAccepted boundary")
    void stateFailureIsTheStateRule(String state) {
        Customer customer = valid().withAddress(valid().address().withState(state));

        assertThatThrownBy(() -> validator.validate(customer))
                .isInstanceOfSatisfying(CustomerValidationException.class, e -> {
                    assertThat(e.rule()).as("rule").isEqualTo(CustomerValidationException.STATE_RULE);
                    assertFailure(e, CustomerValidationException.STATE_RULE, "state", DEM0503, List.of());
                });
    }

    @Test
    @DisplayName("an unknown state is checked against STATES, as Edit_SD_STATE selected from STATES")
    void unknownStateConsultsStates() {
        Customer customer = valid().withAddress(valid().address().withState(UNKNOWN_STATE));

        assertThatThrownBy(() -> validator.validate(customer))
                .isInstanceOf(CustomerValidationException.class);
        verify(stateService).exists(UNKNOWN_STATE);
    }

    // ---------------------------------------------------------------------------------------------
    // Order and first-error stop
    // ---------------------------------------------------------------------------------------------

    @ParameterizedTest(name = "[{index}] {2} -> rule {0} on {1}")
    @MethodSource("twoFieldFailures")
    @DisplayName("with two invalid fields only the first in rule order is reported")
    void firstErrorStopsEvaluation(int rule, String field, String change,
            UnaryOperator<Customer> breaker, String code, List<String> args) {
        Customer customer = breaker.apply(valid());

        assertThatThrownBy(() -> validator.validate(customer))
                .as("validate(%s)", change)
                .isInstanceOfSatisfying(CustomerValidationException.class,
                        e -> assertFailure(e, rule, field, code, args));
    }

    @Test
    @DisplayName("a failure before the State rule returns without consulting STATES")
    void failureBeforeStateRuleNeverReachesStates() {
        Customer customer = valid().withName("").withAddress(valid().address().withState(UNKNOWN_STATE));

        assertThatThrownBy(() -> validator.validate(customer))
                .isInstanceOfSatisfying(CustomerValidationException.class,
                        e -> assertFailure(e, 2, "name", DEM0502, List.of("Name")));
        verifyNoInteractions(stateService);
    }

    @Test
    @DisplayName("rules 1 to 9 run in EditUpdData order: active, name, addr, city, state, zip, "
            + "acctPhone, acctMgr, corpPhone")
    void rulesRunInEditUpdDataOrder() {
        // Every one of the nine fields is invalid; each step corrects the field just reported, so the next
        // report names the next rule. The sequence is the order of the Edit_SD_* calls in EditUpdData.
        Customer customer = Customer.draft("", new Address("", "", UNKNOWN_STATE, ""), "", "", "", "X");
        List<String> expectedFields = List.of(
                "active", "name", "addr", "city", "state", "zip", "acctPhone", "acctMgr", "corpPhone");
        List<UnaryOperator<Customer>> corrections = List.of(
                c -> c.withActive("Y"),
                c -> c.withName("ACME TOOL COMPANY"),
                c -> c.withAddress(c.address().withAddr("12 MAIN STREET")),
                c -> c.withAddress(c.address().withCity("SPRINGFIELD")),
                c -> c.withAddress(c.address().withState(KNOWN_STATE)),
                c -> c.withAddress(c.address().withZip("90210")),
                c -> c.withAcctPhone("(415) 555-0199"),
                c -> c.withAcctMgr("JANE DOE"),
                c -> c.withCorpPhone("(415) 555-0100"));

        for (int i = 0; i < expectedFields.size(); i++) {
            int expectedRule = i + 1;
            String expectedField = expectedFields.get(i);
            Customer current = customer;
            assertThatThrownBy(() -> validator.validate(current))
                    .as("step %d", expectedRule)
                    .isInstanceOfSatisfying(CustomerValidationException.class, e -> {
                        assertThat(e.rule()).as("rule at step %d", expectedRule).isEqualTo(expectedRule);
                        assertThat(e.errors()).as("errors at step %d", expectedRule).hasSize(1);
                        assertThat(e.errors().get(0).field())
                                .as("field at step %d", expectedRule)
                                .isEqualTo(expectedField);
                    });
            customer = corrections.get(i).apply(customer);
        }

        assertThat(customer).isEqualTo(valid());
        Customer corrected = customer;
        assertThatCode(() -> validator.validate(corrected)).doesNotThrowAnyException();
    }

    // ---------------------------------------------------------------------------------------------
    // Valid input
    // ---------------------------------------------------------------------------------------------

    @ParameterizedTest(name = "[{index}] active = {0}")
    @ValueSource(strings = {"Y", "N"})
    @DisplayName("a customer valid in every field passes, active or inactive")
    void validCustomerPasses(String active) {
        Customer customer = valid().withActive(active);

        assertThatCode(() -> validator.validate(customer)).doesNotThrowAnyException();
        verify(stateService).exists(KNOWN_STATE);
    }

    @Test
    @DisplayName("ZIP and phones need only be non-blank: the source has no format rule")
    void zipAndPhonesHaveNoFormatRule() {
        // Edit_SD_ZIP, Edit_SD_ACCTPH and Edit_SD_CORPPH test only SD_x <> ' ' [MTNCUSTR:502-544].
        Customer customer = valid()
                .withAddress(valid().address().withZip("A"))
                .withAcctPhone("-")
                .withCorpPhone("X");

        assertThatCode(() -> validator.validate(customer)).doesNotThrowAnyException();
    }

    // ---------------------------------------------------------------------------------------------
    // Absent values and arguments
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("an absent address reaches the rules as blank fields and fails at the Address rule")
    void absentAddressFailsAtAddressRule() {
        Customer customer = Customer.draft("ACME TOOL COMPANY", null,
                "(415) 555-0100", "JANE DOE", "(415) 555-0199", "Y");

        assertThatThrownBy(() -> validator.validate(customer))
                .isInstanceOfSatisfying(CustomerValidationException.class,
                        e -> assertFailure(e, 3, "addr", DEM0502, List.of("Address")));
    }

    @Test
    @DisplayName("an absent active value fails the Active rule; the add default is applied by the caller")
    void absentActiveFailsAtActiveRule() {
        Customer customer = valid().withActive(null);

        assertThatThrownBy(() -> validator.validate(customer))
                .isInstanceOfSatisfying(CustomerValidationException.class,
                        e -> assertFailure(e, 1, "active", DEM0501, List.of("Active Status")));
    }

    @Test
    @DisplayName("null collaborators and a null customer are rejected")
    void rejectsNulls() {
        assertThatNullPointerException().isThrownBy(() -> new CustomerValidator(null));
        assertThatNullPointerException().isThrownBy(() -> validator.validate(null));
    }

    /**
     * Asserts the complete outcome of one failed rule.
     *
     * @param e     the thrown exception
     * @param rule  the expected rule number
     * @param field the expected highlighted field
     * @param code  the expected CUSTMSGF code, shared by the exception and its one field error
     * @param args  the expected substitution arguments, in order
     */
    private static void assertFailure(CustomerValidationException e, int rule, String field, String code,
            List<String> args) {
        assertThat(e.rule()).as("rule").isEqualTo(rule);
        assertThat(e.code()).as("code").isEqualTo(code);
        assertThat(e.args()).as("args").containsExactlyElementsOf(args);
        assertThat(e.errors()).as("errors").hasSize(1);
        assertThat(e.errors().get(0).field()).as("errors[0].field").isEqualTo(field);
        assertThat(e.errors().get(0).code()).as("errors[0].code").isEqualTo(code);
        // The message is the code alone, so no typed value reaches a log line.
        assertThat(e).hasMessage(code);
    }
}
