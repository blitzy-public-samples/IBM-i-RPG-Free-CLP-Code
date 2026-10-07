package com.democorp.customermaster.address;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.json.JsonParserFactory;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;

/**
 * Specifies {@link StubAddressValidationClient}, the deterministic, offline
 * {@link AddressValidationClient} that is the default for local runs, Docker Compose and
 * every test suite ({@code customer-master.address.client=stub}).
 *
 * <p>The stub stands in for the USPS Web Tools {@code Verify} call made by USADRVAL
 * [USPS_Address/USADRVAL.SQLRPGLE:74-137]. Its fixtures in
 * {@code stub/usps-stub-fixtures.json} are fictitious stand-ins modelled on the eight
 * harness calls of USADRVAL_T [USPS_Address/USADRVAL_T.RPGLE:25-79]; no real address
 * appears here or in the fixture file. Every request and result component keeps its
 * {@code USAdrValDS} width [Copy_Mbrs/USADRVALDS.RPGLE:4-9]: 30, 30, 30, 2, 5 and 4.
 *
 * <ul>
 *   <li><b>Fixture hit.</b> The key is {@code (address2, city, state, zip5)} of the request
 *       as sent, each stripped and uppercased; {@code address1} and {@code zip4} are not
 *       part of it. A hit returns the fixture's standardized address, including its ZIP+4
 *       when it has one.</li>
 *   <li><b>{@code BADADDR}.</b> An address line containing {@code BADADDR} returns the
 *       Web Tools report {@code -2147219401} / {@code clsAMS} / {@code Address Not Found.}
 *       with a blank city, so the result is not standardized. It takes precedence over a
 *       fixture hit.</li>
 *   <li><b>Miss.</b> Anything else echoes the stripped, uppercased input with a blank
 *       {@code zip4} and no error.</li>
 * </ul>
 *
 * <p><b>Published fixtures.</b> F1 (the primary e2e fixture, ZIP+4 {@code 2210}) and F8
 * (the 30-character key cut from a 38-character street) are relied on by customer-api's
 * {@code AddressStandardizationServiceTest} and by the e2e spec
 * {@code add-with-address-standardization.spec.ts}; F4 and F7 cover lowercase input and the
 * no-ZIP+4 answer. {@link FixtureFile#publishedFixturesMatchTheFile()} pins the constants
 * below to the file, so a fixture edit that would break those consumers fails here first.
 *
 * <p>Plain JUnit 5 and AssertJ: no application context, no mocks, no network. The fixture
 * file is read through {@link JsonParserFactory}, as the stub itself reads it, because this
 * module carries no JSON library.
 */
@DisplayName("StubAddressValidationClient: fixture hits, BADADDR error and uppercase echo")
class StubAddressValidationClientTest {

    /** Class-path location of the bundled fixture file. */
    private static final String FIXTURES = "stub/usps-stub-fixtures.json";

    /** Web Tools error {@code Number} for an address it cannot find. */
    private static final int NOT_FOUND_NUMBER = -2147219401;

    /** Web Tools error {@code Source} for an address it cannot find. */
    private static final String NOT_FOUND_SOURCE = "clsAMS";

    /** Web Tools error {@code Description} for an address it cannot find. */
    private static final String NOT_FOUND_DESCRIPTION = "Address Not Found.";

    // ------------------------------------------------ F1: primary e2e fixture, with ZIP+4

    private static final String F1_STREET = "41 QUARRY HILL ROAD";
    private static final String F1_CITY = "GRANITE FALLS";
    private static final String F1_STATE = "NH";
    private static final String F1_ZIP5 = "03999";
    private static final AddressValidationResult F1_OUTPUT = AddressValidationResult.success(
            "", "41 QUARRY HILL RD", "GRANITE FALLS", "NH", "03999", "2210");

    // ------------------------------------------------ F4: input keyed in lowercase, no ZIP

    private static final String F4_STREET = "300 WEST AMBER STREET";
    private static final String F4_CITY = "PASO VERDE";
    private static final String F4_STATE = "CA";
    private static final String F4_ZIP5 = "";
    private static final AddressValidationResult F4_OUTPUT = AddressValidationResult.success(
            "", "300 W AMBER ST", "PASO VERDE", "CA", "91999", "0042");

    // ------------------------------------------------ F7: no ZIP+4 returned

    private static final String F7_STREET = "77 HARBORVIEW LANE";
    private static final String F7_CITY = "PORT ELLISON";
    private static final String F7_STATE = "NY";
    private static final String F7_ZIP5 = "10999";
    private static final AddressValidationResult F7_OUTPUT = AddressValidationResult.success(
            "", "77 HARBORVIEW LN", "PORT ELLISON", "NY", "10999", "");

    // ------------------------------------------------ F8: 30-character key from a 38-character street

    /** The customer street, longer than the 30-character {@code Address2}. */
    private static final String F8_FULL_STREET = "4400 SOUTHEAST LAKEVIEW TERRACE APT 12";
    /** Its first 30 characters, the {@code Address2} customer-api sends. */
    private static final String F8_STREET = "4400 SOUTHEAST LAKEVIEW TERRAC";
    private static final String F8_CITY = "CEDAR BLUFF";
    private static final String F8_STATE = "OR";
    private static final String F8_ZIP5 = "97999";
    private static final AddressValidationResult F8_OUTPUT = AddressValidationResult.success(
            "", "4400 SE LAKEVIEW TER", "CEDAR BLUFF", "OR", "97999", "5512");

    // ------------------------------------------------ duplicate-key resources

    /** A fictitious, valid fixture keyed on {@code 1 DUP ST}, {@code OLD HAVEN}, {@code CT}, {@code 06399}. */
    private static final String DUP_FIRST = """
            {"description": "dup first",
             "input": {"address2": "1 DUP ST", "city": "OLD HAVEN", "state": "CT", "zip5": "06399"},
             "output": {"address1": "", "address2": "1 DUP ST", "city": "OLD HAVEN", "state": "CT",
                        "zip5": "06399", "zip4": "0001"}}""";

    /** The same key as {@link #DUP_FIRST}, differing only in case and padding. */
    private static final String DUP_SECOND = """
            {"description": "dup second",
             "input": {"address2": " 1 dup st ", "city": "old haven ", "state": "ct", "zip5": " 06399"},
             "output": {"address1": "", "address2": "1 DUP ST", "city": "OLD HAVEN", "state": "CT",
                        "zip5": "06399", "zip4": "0002"}}""";

    /** The client under test, over the bundled fixture file. */
    private final StubAddressValidationClient client = new StubAddressValidationClient();

    // ================================================================== fixture hits

    @Nested
    @DisplayName("Fixture hit")
    class FixtureHits {

        @Test
        @DisplayName("F1 returns the standardized street with its ZIP+4")
        void f1ReturnsStandardizedStreetWithZip4() {
            final AddressValidationResult result = client.validate(
                    new AddressValidationRequest("", F1_STREET, F1_CITY, F1_STATE, F1_ZIP5, ""));

            assertThat(result.standardized()).isTrue();
            assertThat(result.address1()).isEmpty();
            assertThat(result.address2()).isEqualTo("41 QUARRY HILL RD");
            assertThat(result.city()).isEqualTo("GRANITE FALLS");
            assertThat(result.state()).isEqualTo("NH");
            assertThat(result.zip5()).isEqualTo("03999");
            assertThat(result.zip4()).isEqualTo("2210");
            assertThat(result.errorNumber()).isZero();
            assertThat(result.errorSource()).isEmpty();
            assertThat(result.errorDescription()).isEmpty();
            assertThat(result).isEqualTo(F1_OUTPUT);
        }

        @Test
        @DisplayName("F7 returns a standardized address with a blank ZIP+4")
        void f7ReturnsStandardizedAddressWithoutZip4() {
            final AddressValidationResult result = client.validate(
                    new AddressValidationRequest("", F7_STREET, F7_CITY, F7_STATE, F7_ZIP5, ""));

            assertThat(result.standardized()).isTrue();
            assertThat(result.zip4()).isEmpty();
            assertThat(result.errorNumber()).isZero();
            assertThat(result).isEqualTo(F7_OUTPUT);
        }

        @Test
        @DisplayName("F4 typed in lowercase with surrounding blanks matches after strip and uppercase")
        void f4MatchesLowercaseBlankPaddedInput() {
            final AddressValidationResult result = client.validate(new AddressValidationRequest(
                    "", "  300 west amber street ", " paso verde ", "ca", " ", ""));

            assertThat(result).isEqualTo(F4_OUTPUT);
            assertThat(result.standardized()).isTrue();
        }

        @Test
        @DisplayName("F8 matches on the first 30 characters of a 38-character street")
        void f8MatchesThirtyCharacterKey() {
            assertThat(F8_FULL_STREET).hasSize(38);
            final String sent = F8_FULL_STREET.substring(0, AddressValidationRequest.ADDRESS2_WIDTH);
            assertThat(sent).isEqualTo(F8_STREET).hasSize(30);

            final AddressValidationResult result = client.validate(
                    new AddressValidationRequest("", sent, F8_CITY, F8_STATE, F8_ZIP5, ""));

            assertThat(result.standardized()).isTrue();
            assertThat(result.address2()).isEqualTo("4400 SE LAKEVIEW TER");
            assertThat(result).isEqualTo(F8_OUTPUT);
        }

        @Test
        @DisplayName("The request's address1 and zip4 are not part of the key; the fixture's values are returned")
        void address1AndZip4AreNotPartOfTheKey() {
            final AddressValidationResult result = client.validate(
                    new AddressValidationRequest("STE 5", F1_STREET, F1_CITY, F1_STATE, F1_ZIP5, "9999"));

            assertThat(result).isEqualTo(F1_OUTPUT);
        }

        @ParameterizedTest(name = "[{index}] {4}")
        @CsvSource(delimiter = '|', textBlock = """
                41 QUARRY HILL RD   | GRANITE FALLS | NH | 03999 | street differs
                41 QUARRY HILL ROAD | GRANITE FALL  | NH | 03999 | city differs
                41 QUARRY HILL ROAD | GRANITE FALLS | VT | 03999 | state differs
                41 QUARRY HILL ROAD | GRANITE FALLS | NH | 03998 | ZIP differs
                41 QUARRY HILL ROAD | GRANITE FALLS | NH | ''    | ZIP missing
                """)
        @DisplayName("All four key components must match, otherwise the input is echoed")
        void everyKeyComponentMustMatch(String street, String city, String state, String zip5, String why) {
            final AddressValidationResult result = client.validate(
                    new AddressValidationRequest("", street, city, state, zip5, ""));

            assertThat(result)
                    .as(why)
                    .isEqualTo(AddressValidationResult.success("", street, city, state, zip5, ""));
        }
    }

    // ================================================================== BADADDR

    @Nested
    @DisplayName("BADADDR")
    class BadAddress {

        @ParameterizedTest(name = "[{index}] address2 \"{0}\"")
        @ValueSource(strings = {"1 BADADDR WAY", "BADADDR", "1 badaddr way"})
        @DisplayName("in address2 returns Address Not Found with a blank city")
        void inAddress2ReturnsNotFound(String street) {
            final AddressValidationResult result = client.validate(
                    new AddressValidationRequest("", street, "OLD HAVEN", "CT", "06399", ""));

            assertNotFound(result);
        }

        @Test
        @DisplayName("in address1 returns Address Not Found with a blank city")
        void inAddress1ReturnsNotFound() {
            final AddressValidationResult result = client.validate(
                    new AddressValidationRequest("BADADDR", "8 ELMWOOD DR", "OLD HAVEN", "CT", "06399", ""));

            assertNotFound(result);
        }

        @Test
        @DisplayName("takes precedence over a fixture hit")
        void takesPrecedenceOverFixtureHit() {
            final AddressValidationResult result = client.validate(
                    new AddressValidationRequest("BADADDR", F1_STREET, F1_CITY, F1_STATE, F1_ZIP5, ""));

            assertNotFound(result);
        }

        private void assertNotFound(AddressValidationResult result) {
            assertThat(result.errorNumber()).isEqualTo(NOT_FOUND_NUMBER);
            assertThat(result.errorSource()).isEqualTo(NOT_FOUND_SOURCE);
            assertThat(result.errorDescription()).isEqualTo(NOT_FOUND_DESCRIPTION);
            assertThat(result.city()).isBlank();
            assertThat(result.standardized()).isFalse();
        }
    }

    // ================================================================== echo

    @Nested
    @DisplayName("Miss")
    class Echo {

        @Test
        @DisplayName("echoes the stripped, uppercased input with a blank ZIP+4 and no error")
        void missEchoesUppercase() {
            final AddressValidationResult result = client.validate(new AddressValidationRequest(
                    " apt 9 ", " 77 nowhere fictional rd ", " noplace ", "zz", "00001", "4321"));

            assertThat(result.address1()).isEqualTo("APT 9");
            assertThat(result.address2()).isEqualTo("77 NOWHERE FICTIONAL RD");
            assertThat(result.city()).isEqualTo("NOPLACE");
            assertThat(result.state()).isEqualTo("ZZ");
            assertThat(result.zip5()).isEqualTo("00001");
            assertThat(result.zip4()).isEmpty();
            assertThat(result.errorNumber()).isZero();
            assertThat(result.errorSource()).isEmpty();
            assertThat(result.errorDescription()).isEmpty();
            assertThat(result.standardized()).isTrue();
        }

        @Test
        @DisplayName("uppercases length-preservingly: é becomes É, ß stays ß")
        void echoUppercasesLengthPreservingly() {
            final AddressValidationResult result = client.validate(new AddressValidationRequest(
                    "", "9 rue de l'église", "kleine straße", "pa", "", ""));

            assertThat(result.address2()).isEqualTo("9 RUE DE L'ÉGLISE").hasSize("9 rue de l'église".length());
            assertThat(result.city()).isEqualTo("KLEINE STRAßE").hasSize("kleine straße".length());
            assertThat(result.state()).isEqualTo("PA");
        }

        @Test
        @DisplayName("an input with a blank city echoes as not standardized, with no error report")
        void blankCityEchoIsNotStandardized() {
            final AddressValidationResult result = client.validate(
                    new AddressValidationRequest("", "5 lonely rd", "  ", "ks", "", ""));

            assertThat(result.address2()).isEqualTo("5 LONELY RD");
            assertThat(result.city()).isEmpty();
            assertThat(result.state()).isEqualTo("KS");
            assertThat(result.standardized()).isFalse();
            assertThat(result.errorNumber()).isZero();
            assertThat(result.errorSource()).isEmpty();
            assertThat(result.errorDescription()).isEmpty();
        }

        @Test
        @DisplayName("a null request is rejected with NullPointerException")
        void nullRequestIsRejected() {
            assertThatThrownBy(() -> client.validate(null)).isInstanceOf(NullPointerException.class);
        }
    }

    // ================================================================== determinism

    @Nested
    @DisplayName("Determinism")
    class Determinism {

        @Test
        @DisplayName("equal requests receive equal results, from one instance and from another")
        void equalRequestsReceiveEqualResults() {
            final AddressValidationRequest hit =
                    new AddressValidationRequest("", F1_STREET, F1_CITY, F1_STATE, F1_ZIP5, "");
            final AddressValidationRequest miss = new AddressValidationRequest(
                    " apt 9 ", " 77 nowhere fictional rd ", " noplace ", "zz", "00001", "4321");

            final AddressValidationResult firstHit = client.validate(hit);
            assertThat(client.validate(hit)).isEqualTo(firstHit);
            assertThat(client.validate(hit)).isEqualTo(firstHit).isEqualTo(F1_OUTPUT);

            final AddressValidationResult firstMiss = client.validate(miss);
            assertThat(client.validate(miss)).isEqualTo(firstMiss);
            assertThat(client.validate(miss)).isEqualTo(firstMiss);

            final StubAddressValidationClient other = new StubAddressValidationClient();
            assertThat(other.validate(hit)).isEqualTo(firstHit);
            assertThat(other.validate(miss)).isEqualTo(firstMiss);
        }
    }

    // ================================================================== fixture file

    @Nested
    @DisplayName("Fixture file")
    class FixtureFile {

        @Test
        @DisplayName("loads through the Resource constructor and answers as the default constructor does")
        void loadsThroughResourceConstructor() {
            assertThatCode(() -> new StubAddressValidationClient(new ClassPathResource(FIXTURES)))
                    .doesNotThrowAnyException();

            final StubAddressValidationClient explicit = new StubAddressValidationClient(new ClassPathResource(FIXTURES));
            final AddressValidationRequest request =
                    new AddressValidationRequest("", F8_STREET, F8_CITY, F8_STATE, F8_ZIP5, "");
            assertThat(explicit.validate(request)).isEqualTo(client.validate(request));
        }

        @Test
        @DisplayName("every fixture standardizes its own input to its output, within the USAdrValDS widths")
        void everyFixtureStandardizesToItsOutput() throws IOException {
            final List<Object> entries = fixtureEntries();
            assertThat(entries).hasSizeGreaterThanOrEqualTo(8);

            final SoftAssertions softly = new SoftAssertions();
            for (int index = 0; index < entries.size(); index++) {
                final Map<String, Object> entry = asObject(entries.get(index), "fixture " + index);
                final String name = "fixture " + index + " (" + text(entry, "description") + ")";
                final Map<String, Object> in = asObject(entry.get("input"), name + " input");
                final Map<String, Object> out = asObject(entry.get("output"), name + " output");

                final AddressValidationResult result = client.validate(new AddressValidationRequest(
                        "", text(in, "address2"), text(in, "city"), text(in, "state"), text(in, "zip5"), ""));

                softly.assertThat(result.standardized()).as(name + " standardized").isTrue();
                softly.assertThat(result.address1()).as(name + " address1").isEqualTo(text(out, "address1"));
                softly.assertThat(result.address2()).as(name + " address2").isEqualTo(text(out, "address2"));
                softly.assertThat(result.city()).as(name + " city").isEqualTo(text(out, "city"));
                softly.assertThat(result.state()).as(name + " state").isEqualTo(text(out, "state"));
                softly.assertThat(result.zip5()).as(name + " zip5").isEqualTo(text(out, "zip5"));
                softly.assertThat(result.zip4()).as(name + " zip4").isEqualTo(text(out, "zip4"));
                softly.assertThat(result.errorNumber()).as(name + " errorNumber").isZero();

                softly.assertThat(width(text(out, "address1"))).as(name + " output.address1 width").isLessThanOrEqualTo(30);
                softly.assertThat(width(text(out, "address2"))).as(name + " output.address2 width").isLessThanOrEqualTo(30);
                softly.assertThat(width(text(out, "city"))).as(name + " output.city width").isLessThanOrEqualTo(30);
                softly.assertThat(width(text(out, "state"))).as(name + " output.state width").isLessThanOrEqualTo(2);
                softly.assertThat(width(text(out, "zip5"))).as(name + " output.zip5 width").isLessThanOrEqualTo(5);
                softly.assertThat(width(text(out, "zip4"))).as(name + " output.zip4 width").isLessThanOrEqualTo(4);
            }
            softly.assertAll();
        }

        @Test
        @DisplayName("F1, F4, F7 and F8 constants match the fixture file exactly")
        void publishedFixturesMatchTheFile() throws IOException {
            final List<Object> entries = fixtureEntries();

            assertThat(outputFor(entries, F1_STREET, F1_CITY, F1_STATE, F1_ZIP5)).as("F1").isEqualTo(F1_OUTPUT);
            assertThat(outputFor(entries, F4_STREET, F4_CITY, F4_STATE, F4_ZIP5)).as("F4").isEqualTo(F4_OUTPUT);
            assertThat(outputFor(entries, F7_STREET, F7_CITY, F7_STATE, F7_ZIP5)).as("F7").isEqualTo(F7_OUTPUT);
            assertThat(outputFor(entries, F8_STREET, F8_CITY, F8_STATE, F8_ZIP5)).as("F8").isEqualTo(F8_OUTPUT);
        }

        @Test
        @DisplayName("each duplicate-test fixture is valid on its own, so only their coincidence can fail")
        void eachDuplicateFixtureLoadsOnItsOwn() {
            final AddressValidationRequest request =
                    new AddressValidationRequest("", "1 DUP ST", "OLD HAVEN", "CT", "06399", "");

            assertThat(new StubAddressValidationClient(resource("[" + DUP_FIRST + "]")).validate(request))
                    .isEqualTo(AddressValidationResult.success("", "1 DUP ST", "OLD HAVEN", "CT", "06399", "0001"));
            assertThat(new StubAddressValidationClient(resource("[" + DUP_SECOND + "]")).validate(request))
                    .isEqualTo(AddressValidationResult.success("", "1 DUP ST", "OLD HAVEN", "CT", "06399", "0002"));
        }

        @Test
        @DisplayName("two fixtures whose normalized inputs coincide fail with IllegalStateException")
        void duplicateNormalizedInputFails() {
            final Resource duplicates = resource("[" + DUP_FIRST + "," + DUP_SECOND + "]");

            // Construction and the first lookup sit in one lambda, so the assertion holds
            // whether the stub loads its fixtures eagerly or on first use.
            assertThatThrownBy(() -> new StubAddressValidationClient(duplicates)
                    .validate(new AddressValidationRequest("", "1 DUP ST", "OLD HAVEN", "CT", "06399", "")))
                    .isInstanceOf(IllegalStateException.class);
        }

        /** Finds the fixture whose input equals the given key and returns its output as a result. */
        private AddressValidationResult outputFor(
                List<Object> entries, String street, String city, String state, String zip5) {
            for (Object element : entries) {
                final Map<String, Object> entry = asObject(element, "fixture");
                final Map<String, Object> in = asObject(entry.get("input"), "fixture input");
                if (text(in, "address2").equals(street) && text(in, "city").equals(city)
                        && text(in, "state").equals(state) && text(in, "zip5").equals(zip5)) {
                    final Map<String, Object> out = asObject(entry.get("output"), "fixture output");
                    return AddressValidationResult.success(text(out, "address1"), text(out, "address2"),
                            text(out, "city"), text(out, "state"), text(out, "zip5"), text(out, "zip4"));
                }
            }
            throw new AssertionError("No fixture in " + FIXTURES + " has input " + street + " / " + city
                    + " / " + state + " / " + zip5);
        }
    }

    // ================================================================== helpers

    /** Parses the bundled fixture file as the stub does. */
    private static List<Object> fixtureEntries() throws IOException {
        final String json = new ClassPathResource(FIXTURES).getContentAsString(StandardCharsets.UTF_8);
        return JsonParserFactory.getJsonParser().parseList(json);
    }

    /** Casts a parsed JSON value to an object, failing with {@code what} when it is not one. */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> asObject(Object value, String what) {
        assertThat(value).as(what + " is a JSON object").isInstanceOf(Map.class);
        return (Map<String, Object>) value;
    }

    /** Reads a string member null-safely: a missing or null member reads {@code ""}. */
    private static String text(Map<String, Object> object, String member) {
        final Object value = object.get(member);
        return value == null ? "" : value.toString();
    }

    /** Width in code points, as {@code USAdrValDS} fields are counted. */
    private static int width(String value) {
        return value.codePointCount(0, value.length());
    }

    /** An in-memory UTF-8 fixture resource. */
    private static Resource resource(String json) {
        return new ByteArrayResource(json.getBytes(StandardCharsets.UTF_8), "test fixtures");
    }
}
