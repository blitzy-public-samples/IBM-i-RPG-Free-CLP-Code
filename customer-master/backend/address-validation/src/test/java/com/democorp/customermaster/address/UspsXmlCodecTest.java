package com.democorp.customermaster.address;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

/**
 * Specifies {@link UspsXmlCodec}, the Java replacement for the request concatenation
 * and the XMLTABLE parsing inside {@code USAdrVal} [USPS_Address/USADRVAL.SQLRPGLE:74-136],
 * over the {@code USAdrValDS} template [Copy_Mbrs/USADRVALDS.RPGLE:3-13].
 *
 * <p><b>Request</b> [USADRVAL.SQLRPGLE:74-93]. The source concatenates values unescaped, so an
 * {@code &} or {@code <} breaks the document; the codec escapes them, a deliberate behaviour
 * change, and strips surrounding blanks, as the source trims its data-area credentials. A
 * value or credential holding a character outside the XML 1.0 {@code Char} production
 * (U+0001, U+0008, U+000B, U+000C, U+000E, U+001F, U+FFFE, U+FFFF, a lone surrogate) is
 * refused with {@link IllegalArgumentException} naming only its element or attribute, so no
 * malformed document is ever produced; TAB, LF, CR, U+FFFD, private-use characters and
 * supplementary characters are written as they are. Display names name code points as
 * {@code U+XXXX} and never hold the values, most of which no XML test report can carry.
 *
 * <p><b>Valid responses</b> [USADRVAL.SQLRPGLE:100-133]. A missing or empty element reads
 * {@code ""}, the XMLTABLE {@code default ' '}; a non-blank City is a success whatever else the
 * row holds [118-121]; a blank City with one valid {@code Error} is an address-level error.
 *
 * <p><b>Faults</b> [USADRVAL.SQLRPGLE:94-96,114-116,134-136]. Every response the source's
 * {@code SQLSTATE <> '00000'} test would reject raises
 * {@link AddressServiceUnavailableException}, without resolving any DOCTYPE entity and
 * without the JDK parser printing {@code [Fatal Error]} to standard error.
 * {@code serviceError}, which reads the error texts for the fault log line, is held to the
 * same silence.
 *
 * <p><b>Test values.</b> Every address and credential is fictitious. The base row is the
 * one the {@code usps/*.xml} fixtures use; each inline boundary or fault document changes
 * one thing in a base document whose unchanged form a control test parses. The
 * placeholder password carries {@code &} and {@code "} so attribute escaping is exercised.
 *
 * <p><b>Captured output.</b> {@link OutputCaptureExtension} captures each test invocation
 * separately. The {@link CapturedOutput} a test receives holds that invocation's output
 * plus class-level output written outside any invocation's capture, such as while JUnit
 * constructs the test instance, so the standard-error assertions prove that each fault
 * case's own parse printed nothing.
 */
@DisplayName("UspsXmlCodec: USPS Web Tools request document and response rules")
@ExtendWith(OutputCaptureExtension.class)
class UspsXmlCodecTest {

    /** Fictitious USPS Web Tools user id. */
    private static final String USER_ID = "TESTUSER123";

    /** Fictitious USPS Web Tools password; {@code &} and {@code "} need escaping. */
    private static final String PASSWORD = "pl&ce\"holder";

    private static final AddressValidationRequest BASE_REQUEST =
            new AddressValidationRequest("STE 2", "8 ELMWOOD DR", "OLD HAVEN", "CT", "06399", "1234");

    /** The six {@code Address} children in source order [USADRVAL.SQLRPGLE:84-89]. */
    private static final String[] ADDRESS_FIELDS =
            {"Address1", "Address2", "City", "State", "Zip5", "Zip4"};

    private static final String[] BASE_ROW = {"STE 2", "8 ELMWOOD DR", "OLD HAVEN", "CT", "06399", "1234"};

    private static final int ADDRESS_NOT_FOUND = -2147219401;

    /**
     * UTF-16 units outside the XML 1.0 {@code Char} production: the C0 controls the reports
     * name, the two BMP noncharacters XML excludes, and a lone high and a lone low surrogate.
     */
    private static final int[] XML_ILLEGAL_UNITS =
            {0x0001, 0x0008, 0x000B, 0x000C, 0x000E, 0x001F, 0xFFFE, 0xFFFF, 0xD800, 0xDC00};

    private final UspsXmlCodec codec = new UspsXmlCodec();

    @Test
    @DisplayName("request document has the source's root, attributes, children and element order")
    void requestDocumentHasSourceStructure() throws Exception {
        Element root = parseXml(codec.requestDocument(BASE_REQUEST, USER_ID, PASSWORD))
                .getDocumentElement();

        assertThat(root.getLocalName()).isEqualTo("AddressValidateRequest");
        assertThat(root.getNamespaceURI()).as("request namespace").isNull();
        assertThat(attributeNames(root)).containsExactlyInAnyOrder("USERID", "PASSWORD");
        assertThat(root.getAttribute("USERID")).isEqualTo(USER_ID);
        assertThat(root.getAttribute("PASSWORD")).isEqualTo(PASSWORD);

        List<Element> rootChildren = childElements(root);
        assertThat(rootChildren).extracting(Element::getLocalName).containsExactly("Revision", "Address");
        assertThat(rootChildren.get(0).getTextContent()).isEqualTo("1");

        Element address = rootChildren.get(1);
        assertThat(attributeNames(address)).containsExactly("ID");
        assertThat(address.getAttribute("ID")).isEqualTo("0");

        List<Element> fields = childElements(address);
        assertThat(fields).extracting(Element::getLocalName).containsExactly(ADDRESS_FIELDS);
        assertThat(fields).extracting(Element::getTextContent)
                .containsExactly("STE 2", "8 ELMWOOD DR", "OLD HAVEN", "CT", "06399", "1234");
    }

    @Test
    @DisplayName("request document escapes & < and \" in values and attributes")
    void requestDocumentEscapesValuesAndAttributes() throws Exception {
        AddressValidationRequest request =
                new AddressValidationRequest("", "A&B <ST", "OLD HAVEN", "CT", "06399", "");

        String document = codec.requestDocument(request, USER_ID, PASSWORD);

        assertThat(document)
                .contains("A&amp;B &lt;ST")
                .contains("pl&amp;ce&quot;holder")
                .doesNotContain("A&B <ST")
                .doesNotContain(PASSWORD);

        Element root = parseXml(document).getDocumentElement();
        assertThat(root.getAttribute("PASSWORD")).isEqualTo(PASSWORD);
        Element address = assertSingleChild(root, "Address");
        assertThat(assertSingleChild(address, "Address2").getTextContent()).isEqualTo("A&B <ST");
        assertThat(assertSingleChild(address, "City").getTextContent()).isEqualTo("OLD HAVEN");
    }

    @Test
    @DisplayName("request document writes values and credentials stripped of surrounding blanks")
    void requestDocumentStripsValuesAndCredentials() throws Exception {
        // State, Zip5 and Zip4 are at their full widths, so only the wider fields and a
        // blank Zip4 can carry padding without being rejected by the record.
        AddressValidationRequest request = new AddressValidationRequest(
                " STE 2 ", "  8 ELMWOOD DR ", " OLD HAVEN  ", "CT", "06399", "  ");
        assertThat(request.address2())
                .as("the record stores values as given; stripping is the codec's job")
                .isEqualTo("  8 ELMWOOD DR ");

        Element root = parseXml(codec.requestDocument(request, " TESTUSER123 ", " pl&ce\"holder "))
                .getDocumentElement();

        assertThat(root.getAttribute("USERID")).isEqualTo(USER_ID);
        assertThat(root.getAttribute("PASSWORD")).isEqualTo(PASSWORD);
        Element address = assertSingleChild(root, "Address");
        assertThat(childElements(address)).extracting(Element::getTextContent)
                .containsExactly("STE 2", "8 ELMWOOD DR", "OLD HAVEN", "CT", "06399", "");
    }

    @Test
    @DisplayName("request document writes a 30-character street intact")
    void requestDocumentKeepsThirtyCharacterStreet() throws Exception {
        String street = "4400 SOUTHEAST LAKEVIEW TERRAC";
        assertThat(street).hasSize(30);
        AddressValidationRequest request =
                new AddressValidationRequest("", street, "OLD HAVEN", "CT", "06399", "");

        String document = codec.requestDocument(request, USER_ID, PASSWORD);

        assertThat(document).contains("<Address2>" + street + "</Address2>");
        Element address = assertSingleChild(parseXml(document).getDocumentElement(), "Address");
        assertThat(assertSingleChild(address, "Address2").getTextContent()).isEqualTo(street);
    }

    @Test
    @DisplayName("request query is API=Verify&XML= followed by the URL-encoded request document")
    void requestQueryEncodesRequestDocument() {
        String query = codec.requestQuery(BASE_REQUEST, USER_ID, PASSWORD);

        assertThat(query).startsWith("API=Verify&XML=");
        String encoded = query.substring("API=Verify&XML=".length());
        assertThat(encoded)
                .as("the document is URL-encoded, so no markup or separator survives raw")
                .doesNotContain("<", ">", "&", "\"", " ", "=");
        assertThat(URLDecoder.decode(encoded, StandardCharsets.UTF_8))
                .isEqualTo(codec.requestDocument(BASE_REQUEST, USER_ID, PASSWORD));
    }

    @ParameterizedTest(name = "[{index}] {0} with {1}")
    @MethodSource("xmlIllegalValues")
    @DisplayName("a value XML 1.0 cannot carry is refused naming its element, with no document and no query")
    void requestRefusesXmlIllegalValue(String element, String codePoint, AddressValidationRequest request) {
        String expected = "USPS request " + element + " holds a character XML 1.0 cannot carry";

        Throwable document = catchThrowable(() -> codec.requestDocument(request, USER_ID, PASSWORD));
        Throwable query = catchThrowable(() -> codec.requestQuery(request, USER_ID, PASSWORD));

        for (Throwable thrown : List.of(document, query)) {
            assertThat(thrown).as(codePoint).isExactlyInstanceOf(IllegalArgumentException.class)
                    .hasMessage(expected)
                    .hasNoCause();
            // Printable ASCII only, so neither the refused character nor the value is quoted.
            assertThat(thrown.getMessage()).matches("[ -~]+").doesNotContain(USER_ID, PASSWORD);
        }
    }

    /**
     * Each code point outside the XML 1.0 {@code Char} production the reports name, between
     * two digits in each address element, so stripping cannot remove it; State, two
     * characters wide, holds it after one letter, which only a non-whitespace code point
     * survives, since {@link String#strip()} removes U+000B, U+000C and U+001F at an end.
     */
    static Stream<Arguments> xmlIllegalValues() {
        List<Arguments> cases = new ArrayList<>();
        for (int unit : XML_ILLEGAL_UNITS) {
            String character = String.valueOf((char) unit);
            String label = String.format("U+%04X", unit);
            String between = "1" + character + "2";
            cases.add(Arguments.of("Address1", label, new AddressValidationRequest(between, "", "", "", "", "")));
            cases.add(Arguments.of("Address2", label, new AddressValidationRequest("", between, "", "", "", "")));
            cases.add(Arguments.of("City", label, new AddressValidationRequest("", "", between, "", "", "")));
            if (!Character.isWhitespace(unit)) {
                cases.add(Arguments.of("State", label,
                        new AddressValidationRequest("", "", "", "C" + character, "", "")));
            }
            cases.add(Arguments.of("Zip5", label, new AddressValidationRequest("", "", "", "", between, "")));
            cases.add(Arguments.of("Zip4", label, new AddressValidationRequest("", "", "", "", "", between)));
        }
        return cases.stream();
    }

    @ParameterizedTest(name = "[{index}] {0} with {1}")
    @MethodSource("xmlIllegalCredentials")
    @DisplayName("a credential XML 1.0 cannot carry is refused naming its attribute, never quoting it")
    void requestRefusesXmlIllegalCredential(String attribute, String codePoint, String userId, String password) {
        String expected = "USPS request " + attribute + " attribute holds a character XML 1.0 cannot carry";

        Throwable document = catchThrowable(() -> codec.requestDocument(BASE_REQUEST, userId, password));
        Throwable query = catchThrowable(() -> codec.requestQuery(BASE_REQUEST, userId, password));

        for (Throwable thrown : List.of(document, query)) {
            assertThat(thrown).as(codePoint).isExactlyInstanceOf(IllegalArgumentException.class)
                    .hasMessage(expected)
                    .hasNoCause();
            // Printable ASCII only, and no part of either credential or of the address.
            assertThat(thrown.getMessage()).matches("[ -~]+")
                    .doesNotContain("QZ", USER_ID, PASSWORD, "8 ELMWOOD DR");
        }
    }

    /**
     * Each code point outside the XML 1.0 {@code Char} production, inside a fictitious user
     * id with the valid password, and inside a fictitious password with the valid user id.
     * The {@code QZ} fragments mark the credentials, so a message quoting one is detected.
     */
    static Stream<Arguments> xmlIllegalCredentials() {
        return Arrays.stream(XML_ILLEGAL_UNITS).boxed().flatMap(unit -> {
            String character = String.valueOf((char) unit.intValue());
            String label = String.format("U+%04X", unit);
            return Stream.of(
                    Arguments.of("USERID", label, "TESTQZ" + character + "IDQZ", PASSWORD),
                    Arguments.of("PASSWORD", label, USER_ID, "pwQZ" + character + "QZpw"));
        });
    }

    @Test
    @DisplayName("TAB, LF, U+FFFD, private use and U+1F600 are written as they are and parse back unchanged")
    void requestWritesEveryXmlCharUnchanged() throws Exception {
        String emoji = new String(Character.toChars(0x1F600));
        String[] values = {"STE\t2", "8 ELMWOOD\nDR", "OLD\uFFFDHAVEN", "C\uE000", "0" + emoji + "399", "\uE00012"};
        AddressValidationRequest request =
                new AddressValidationRequest(values[0], values[1], values[2], values[3], values[4], values[5]);
        String userId = "TEST\uE000" + emoji;
        String password = "pl\uFFFD&" + emoji;

        String document = codec.requestDocument(request, userId, password);

        assertThat(document).contains("<Address1>STE\t2</Address1>", "<Address2>8 ELMWOOD\nDR</Address2>");
        Element root = parseHardened(document).getDocumentElement();
        assertThat(root.getAttribute("USERID")).isEqualTo(userId);
        assertThat(root.getAttribute("PASSWORD")).isEqualTo(password);
        assertThat(childElements(assertSingleChild(root, "Address"))).extracting(Element::getTextContent)
                .containsExactly(values);
        assertThat(URLDecoder.decode(codec.requestQuery(request, userId, password)
                .substring(UspsXmlCodec.QUERY_PREFIX.length()), StandardCharsets.UTF_8)).isEqualTo(document);
    }

    @Test
    @DisplayName("a CR inside a value is a Char: written as it is, read back as LF by XML end-of-line handling")
    void requestWritesCarriageReturnAsItIs() throws Exception {
        AddressValidationRequest request =
                new AddressValidationRequest("", "8 ELMWOOD\rDR", "OLD\r\nHAVEN", "CT", "06399", "");

        String document = codec.requestDocument(request, USER_ID, PASSWORD);

        assertThat(document).contains("<Address2>8 ELMWOOD\rDR</Address2>", "<City>OLD\r\nHAVEN</City>");
        Element address = assertSingleChild(parseHardened(document).getDocumentElement(), "Address");
        // XML 1.0 section 2.11: a parser passes CR and CR LF on as one LF.
        assertThat(assertSingleChild(address, "Address2").getTextContent()).isEqualTo("8 ELMWOOD\nDR");
        assertThat(assertSingleChild(address, "City").getTextContent()).isEqualTo("OLD\nHAVEN");
    }

    @Test
    @DisplayName("request record rejects a 31-character Address2, wider than char(30)")
    void requestRejectsOverWidthStreet() {
        String street = "A".repeat(31);

        assertThatThrownBy(() -> new AddressValidationRequest("", street, "OLD HAVEN", "CT", "06399", ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining(street);
    }

    @Test
    @DisplayName("success with ZIP+4: all six values, no error, extra elements ignored")
    void parsesSuccessWithZip4() throws IOException {
        AddressValidationResult result = codec.parse(fixture("success-zip4.xml"));

        assertThat(result.standardized()).isTrue();
        assertThat(result).isEqualTo(new AddressValidationResult(
                "STE 2", "8 ELMWOOD DR", "OLD HAVEN", "CT", "06399", "1234", 0, "", ""));
    }

    @Test
    @DisplayName("success with an empty Zip4 element reads Zip4 as blank")
    void parsesSuccessWithoutZip4() throws IOException {
        AddressValidationResult result = codec.parse(fixture("success-no-zip4.xml"));

        assertThat(result.standardized()).isTrue();
        assertThat(result).isEqualTo(new AddressValidationResult(
                "STE 2", "8 ELMWOOD DR", "OLD HAVEN", "CT", "06399", "", 0, "", ""));
    }

    @Test
    @DisplayName("success without Address1 and Zip4 elements reads both as blank, the XMLTABLE default")
    void parsesSuccessWithMissingOptionalElements() throws IOException {
        AddressValidationResult result = codec.parse(fixture("success-missing-optional.xml"));

        assertThat(result.standardized()).isTrue();
        assertThat(result).isEqualTo(new AddressValidationResult(
                "", "8 ELMWOOD DR", "OLD HAVEN", "CT", "06399", "", 0, "", ""));
    }

    @Test
    @DisplayName("blank City with one Error is an address-level error, description stripped")
    void parsesAddressNotFoundError() throws IOException {
        AddressValidationResult result = codec.parse(fixture("error-address-not-found.xml"));

        assertThat(result.standardized()).isFalse();
        assertThat(result.errorNumber()).isEqualTo(ADDRESS_NOT_FOUND);
        assertThat(result.errorSource()).isEqualTo("clsAMS");
        assertThat(result.errorDescription()).isEqualTo("Address Not Found.");
        assertThat(result).isEqualTo(new AddressValidationResult(
                "", "99 NOWHERE LN", "", "", "", "", ADDRESS_NOT_FOUND, "clsAMS", "Address Not Found."));
    }

    @Test
    @DisplayName("an empty Error Source element is valid and reads as blank")
    void parsesErrorWithEmptySource() {
        byte[] body = ("<AddressValidateResponse><Address ID=\"0\">"
                + "<Address2>99 NOWHERE LN</Address2><City></City>"
                + "<Error><Number>-2147219401</Number><Source/>"
                + "<Description>Address Not Found.</Description></Error>"
                + "</Address></AddressValidateResponse>").getBytes(StandardCharsets.UTF_8);

        AddressValidationResult result = codec.parse(body);

        assertThat(result.standardized()).isFalse();
        assertThat(result.errorNumber()).isEqualTo(ADDRESS_NOT_FOUND);
        assertThat(result.errorSource()).isEmpty();
        assertThat(result.errorDescription()).isEqualTo("Address Not Found.");
    }

    @Test
    @DisplayName("control: the unchanged inline success document is a success with the base row")
    void parsesBaseSuccessDocument() {
        AddressValidationResult result = codec.parse(response(baseAddress()));

        assertThat(result.standardized()).isTrue();
        assertThat(result).isEqualTo(new AddressValidationResult(
                "STE 2", "8 ELMWOOD DR", "OLD HAVEN", "CT", "06399", "1234", 0, "", ""));
    }

    @Test
    @DisplayName("control: the unchanged inline error document (blank City, one Error) is an address error")
    void parsesBaseErrorDocument() {
        byte[] body = response(baseAddressWith(Map.of("City", "")) + baseError());

        AddressValidationResult result = codec.parse(body);

        assertThat(result.standardized()).isFalse();
        assertThat(result).isEqualTo(new AddressValidationResult(
                "STE 2", "8 ELMWOOD DR", "", "CT", "06399", "1234",
                ADDRESS_NOT_FOUND, "clsAMS", "Address Not Found."));
    }

    @Test
    @DisplayName("success with every Address value at its full width, 30, 30, 30, 2, 5 and 4, keeps each")
    void parsesSuccessAtFullWidths() {
        String address1 = "SUITE 2210 NORTH TOWER FLOOR 9";
        String address2 = "8 ELMWOOD DRIVE EXTENSION WEST";
        String city = "OLD HAVEN JUNCTION NORTH SHORE";
        assertThat(address1).hasSize(30);
        assertThat(address2).hasSize(30);
        assertThat(city).hasSize(30);
        byte[] body = response(baseAddressWith(Map.of(
                "Address1", address1, "Address2", address2, "City", city,
                "State", "CT", "Zip5", "06399", "Zip4", "1234")));

        AddressValidationResult result = codec.parse(body);

        assertThat(result.standardized()).isTrue();
        assertThat(result).isEqualTo(new AddressValidationResult(
                "SUITE 2210 NORTH TOWER FLOOR 9", "8 ELMWOOD DRIVE EXTENSION WEST",
                "OLD HAVEN JUNCTION NORTH SHORE", "CT", "06399", "1234", 0, "", ""));
    }

    /**
     * Valid {@code Error} elements at the limits of the response rules, each named for the
     * limit it sits on and followed by the {@code Number}, {@code Source} and
     * {@code Description} the result must carry.
     */
    static Stream<Arguments> errorsAtTheirLimits() {
        return Stream.of(
                Arguments.of(Named.of("Number 2147483647, Integer.MAX_VALUE",
                                errorOf("2147483647", "clsAMS", "Address Not Found.")),
                        2147483647, "clsAMS", "Address Not Found."),
                Arguments.of(Named.of("Number -2147483648, Integer.MIN_VALUE",
                                errorOf("-2147483648", "clsAMS", "Address Not Found.")),
                        -2147483648, "clsAMS", "Address Not Found."),
                Arguments.of(Named.of("Number with surrounding whitespace, read after trimming",
                                errorOf("\n  -2147219401 \t", "clsAMS", "Address Not Found.")),
                        -2147219401, "clsAMS", "Address Not Found."),
                Arguments.of(Named.of("Source of exactly 30 characters",
                                errorOf("-2147219401", "S".repeat(30), "Address Not Found.")),
                        -2147219401, "S".repeat(30), "Address Not Found."),
                Arguments.of(Named.of("Description of exactly 512 characters",
                                errorOf("-2147219401", "clsAMS", "D".repeat(512))),
                        -2147219401, "clsAMS", "D".repeat(512)));
    }

    @ParameterizedTest(name = "blank City with an Error at {0} is an address-level error")
    @MethodSource("errorsAtTheirLimits")
    @DisplayName("a blank City with one Error at the limits of its rules is an address-level error")
    void parsesErrorAtItsLimits(String error, int number, String source, String description) {
        AddressValidationResult result = codec.parse(response(baseAddressWith(Map.of("City", "")) + error));

        assertThat(result.standardized()).isFalse();
        assertThat(result).isEqualTo(new AddressValidationResult(
                "STE 2", "8 ELMWOOD DR", "", "CT", "06399", "1234", number, source, description));
    }

    @ParameterizedTest(name = "{0} reads as blank")
    @ValueSource(strings = {"<Description/>", "<Description></Description>"})
    @DisplayName("an empty Error Description element is valid and reads as blank")
    void parsesErrorWithEmptyDescription(String emptyDescription) {
        byte[] body = response(baseAddressWith(Map.of("City", "")) + errorElement(
                element("Number", "-2147219401") + element("Source", "clsAMS") + emptyDescription));

        AddressValidationResult result = codec.parse(body);

        assertThat(result.standardized()).isFalse();
        assertThat(result).isEqualTo(new AddressValidationResult(
                "STE 2", "8 ELMWOOD DR", "", "CT", "06399", "1234", ADDRESS_NOT_FOUND, "clsAMS", ""));
    }

    @ParameterizedTest(name = "Address holding only {0} is a success")
    @ValueSource(strings = {"<City>OLD HAVEN</City>", "<Address2/><City>OLD HAVEN</City>"})
    @DisplayName("a non-blank City alone is a success, with Address2 and every other value blank")
    void parsesCityOnlySuccess(String addressChildren) {
        AddressValidationResult result = codec.parse(response(addressChildren));

        assertThat(result.standardized()).isTrue();
        assertThat(result.errorNumber()).isZero();
        assertThat(result).isEqualTo(new AddressValidationResult("", "", "OLD HAVEN", "", "", "", 0, "", ""));
    }

    /** {@code Error} content the response rules would reject if the City were blank. */
    static Stream<Named<String>> errorsInvalidForBlankCity() {
        return Stream.of(
                Named.of("an Error whose Number 80040B1A is not an integer",
                        errorOf("80040B1A", "clsAMS", "Address Not Found.")),
                Named.of("an Error without Number, Source or Description", errorElement("")),
                Named.of("two Error children", baseError() + baseError()),
                Named.of("an Error whose Source of 31 and Description of 513 characters are too long",
                        errorOf("-2147219401", "S".repeat(31), "D".repeat(513))));
    }

    @ParameterizedTest(name = "non-blank City with {0} is a plain success")
    @MethodSource("errorsInvalidForBlankCity")
    @DisplayName("a non-blank City is a success whatever else the row holds, its Error ignored")
    void parsesNonBlankCityAsSuccessWhateverItsError(String errors) {
        AddressValidationResult result = codec.parse(response(baseAddress() + errors));

        assertThat(result.standardized()).isTrue();
        assertThat(result).isEqualTo(new AddressValidationResult(
                "STE 2", "8 ELMWOOD DR", "OLD HAVEN", "CT", "06399", "1234", 0, "", ""));
    }

    @ParameterizedTest(name = "{0} is a service fault")
    @ValueSource(strings = {
        "doctype-xxe.xml",
        "malformed.xml",
        "root-error.xml",
        "no-address.xml",
        "two-addresses.xml",
        "blank-city-no-error.xml",
        "error-bad-number.xml",
        "error-missing-description.xml",
        "address2-31-chars.xml"
    })
    @DisplayName("a response the source's SQLSTATE test rejects is a service fault, printed nowhere")
    void rejectsFaultyResponse(String name, CapturedOutput output) throws IOException {
        byte[] body = fixture(name);

        assertThatThrownBy(() -> codec.parse(body))
                .isInstanceOf(AddressServiceUnavailableException.class);

        assertNothingPrintedByParser(output);
    }

    @ParameterizedTest(name = "{0} of {1} characters is a service fault")
    @CsvSource({
        "Address1, 31",
        "Address2, 31",
        "City,     31",
        "State,     3",
        "Zip5,      6",
        "Zip4,      5"
    })
    @DisplayName("an Address value over its width (30, 30, 30, 2, 5, 4) is a service fault, printed nowhere")
    void rejectsOverWidthAddressValue(String field, int length, CapturedOutput output) {
        byte[] body = response(baseAddressWith(Map.of(field, "X".repeat(length))));

        assertThatThrownBy(() -> codec.parse(body))
                .isInstanceOf(AddressServiceUnavailableException.class);

        assertNothingPrintedByParser(output);
    }

    /** Blank-City {@code Error} content the response rules reject, each one change from the base error. */
    static Stream<Named<String>> errorsRejectedForBlankCity() {
        return Stream.of(
                Named.of("two Error children, each valid", baseError() + baseError()),
                Named.of("an Error without Number", errorElement(
                        element("Source", "clsAMS") + element("Description", "Address Not Found."))),
                Named.of("an Error without Source", errorElement(
                        element("Number", "-2147219401") + element("Description", "Address Not Found."))),
                Named.of("Number 2147483648, one above Integer.MAX_VALUE",
                        errorOf("2147483648", "clsAMS", "Address Not Found.")),
                Named.of("Number -2147483649, one below Integer.MIN_VALUE",
                        errorOf("-2147483649", "clsAMS", "Address Not Found.")),
                Named.of("a Source of 31 characters",
                        errorOf("-2147219401", "S".repeat(31), "Address Not Found.")),
                Named.of("a Description of 513 characters",
                        errorOf("-2147219401", "clsAMS", "D".repeat(513))));
    }

    @ParameterizedTest(name = "blank City with {0} is a service fault")
    @MethodSource("errorsRejectedForBlankCity")
    @DisplayName("a blank City without exactly one valid Error is a service fault, printed nowhere")
    void rejectsBlankCityWithInvalidError(String errors, CapturedOutput output) {
        byte[] body = response(baseAddressWith(Map.of("City", "")) + errors);

        assertThatThrownBy(() -> codec.parse(body))
                .isInstanceOf(AddressServiceUnavailableException.class);

        assertNothingPrintedByParser(output);
    }

    @Test
    @DisplayName("an empty body is a service fault, printed nowhere")
    void rejectsEmptyBody(CapturedOutput output) {
        assertThatThrownBy(() -> codec.parse(new byte[0]))
                .isInstanceOf(AddressServiceUnavailableException.class);

        assertNothingPrintedByParser(output);
    }

    @Test
    @DisplayName("a DOCTYPE is rejected before any entity it declares is resolved")
    void rejectsDoctypeWithoutResolvingEntities() throws IOException {
        byte[] body = fixture("doctype-xxe.xml");

        Throwable thrown = catchThrowable(() -> codec.parse(body));

        assertThat(thrown).isInstanceOf(AddressServiceUnavailableException.class);
        // An identity set guards against a cause cycle, which Throwable does not forbid.
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable link = thrown; link != null && seen.add(link); link = link.getCause()) {
            assertThat(Objects.toString(link.getMessage(), ""))
                    .as("message of %s in the cause chain", link.getClass().getName())
                    .doesNotContain("root:")
                    .doesNotContain("file:");
        }
    }

    /**
     * Bodies whose root or row is named other than exactly as the source's row paths write
     * it, or sits below the row's place, each followed by the fault it must raise. The JDK's
     * XPath would match a plain name test against the part after a prefix, so each case
     * fails if the codec's steps stop matching names exactly as written.
     */
    static Stream<Arguments> bodiesWithoutAnExactlyNamedRow() {
        return Stream.of(
                Arguments.of(Named.of("an Address written as the prefixed x:Address",
                                ("<AddressValidateResponse><x:Address xmlns:x=\"urn:x\" ID=\"0\">"
                                        + baseAddress() + "</x:Address></AddressValidateResponse>")
                                        .getBytes(StandardCharsets.UTF_8)),
                        "response holds 0 Address elements"),
                Arguments.of(Named.of("an Address nested inside another element of the root",
                                ("<AddressValidateResponse><Wrapper><Address ID=\"0\">"
                                        + baseAddress() + "</Address></Wrapper></AddressValidateResponse>")
                                        .getBytes(StandardCharsets.UTF_8)),
                        "response holds 0 Address elements"),
                Arguments.of(Named.of("the root written as the prefixed p:AddressValidateResponse",
                                ("<p:AddressValidateResponse xmlns:p=\"urn:p\"><Address ID=\"0\">"
                                        + baseAddress() + "</Address></p:AddressValidateResponse>")
                                        .getBytes(StandardCharsets.UTF_8)),
                        "response root is not AddressValidateResponse"),
                Arguments.of(Named.of("a blank City whose only Error is the prefixed x:Error",
                                response(baseAddressWith(Map.of("City", "")) + prefixedBaseError())),
                        "response with a blank City holds 0 Error elements"));
    }

    @ParameterizedTest(name = "{0} is the service fault \"{1}\"")
    @MethodSource("bodiesWithoutAnExactlyNamedRow")
    @DisplayName("a path step matches the element name exactly as written, so a prefixed or nested row is a fault")
    void rejectsRowNotNamedExactlyAsThePath(byte[] body, String fault, CapturedOutput output) {
        assertThatThrownBy(() -> codec.parse(body))
                .isInstanceOf(AddressServiceUnavailableException.class)
                .hasMessage(fault);

        assertNothingPrintedByParser(output);
    }

    @Test
    @DisplayName("a prefixed x:Address and a nested Address beside the one Address are not counted: a success")
    void ignoresPrefixedAndNestedAddressBesideTheRow() {
        byte[] body = ("<AddressValidateResponse>"
                + "<x:Address xmlns:x=\"urn:x\" ID=\"1\">" + baseAddress() + "</x:Address>"
                + "<Address ID=\"0\">" + baseAddress() + "</Address>"
                + "<Wrapper><Address ID=\"2\">" + baseAddress() + "</Address></Wrapper>"
                + "</AddressValidateResponse>").getBytes(StandardCharsets.UTF_8);

        AddressValidationResult result = codec.parse(body);

        assertThat(result.standardized()).isTrue();
        assertThat(result).isEqualTo(new AddressValidationResult(
                "STE 2", "8 ELMWOOD DR", "OLD HAVEN", "CT", "06399", "1234", 0, "", ""));
    }

    @Test
    @DisplayName("a prefixed x:City inside a valid Address is not City, so City reads blank and its Error is read")
    void ignoresPrefixedCityInsideTheRow() {
        byte[] body = response(element("Address2", "8 ELMWOOD DR")
                + "<x:City xmlns:x=\"urn:x\">OLD HAVEN</x:City>" + baseError());

        AddressValidationResult result = codec.parse(body);

        assertThat(result.standardized()).isFalse();
        assertThat(result).isEqualTo(new AddressValidationResult(
                "", "8 ELMWOOD DR", "", "", "", "", ADDRESS_NOT_FOUND, "clsAMS", "Address Not Found."));
    }

    /**
     * Bodies holding a USPS {@code Error} element, each followed by the {@code Number} and
     * {@code Description} texts {@code serviceError} must read, {@code null} for an absent
     * element.
     */
    static Stream<Arguments> bodiesWithAnErrorElement() {
        return Stream.of(
                Arguments.of("root-error.xml", "-2147218662",
                        "Authorization failure.  Perhaps username and/or password is incorrect."),
                Arguments.of("error-address-not-found.xml", "-2147219401", "Address Not Found."),
                Arguments.of("error-bad-number.xml", "80040B1A", "Invalid Address."),
                Arguments.of("error-missing-description.xml", "-2147219401", null));
    }

    @ParameterizedTest(name = "{0} yields Number {1} and Description {2}")
    @MethodSource("bodiesWithAnErrorElement")
    @DisplayName("serviceError reads the Number and Description of a root <Error> or the Address's Error, unvalidated")
    void serviceErrorReadsErrorElement(String name, String number, String description, CapturedOutput output)
            throws IOException {
        assertThat(codec.serviceError(fixture(name)))
                .contains(new UspsXmlCodec.ServiceError(number, description));

        assertNothingPrintedByParser(output);
    }

    @ParameterizedTest(name = "{0} yields no Error texts")
    @ValueSource(strings = {
        "success-zip4.xml",
        "malformed.xml",
        "doctype-xxe.xml",
        "blank-city-no-error.xml",
        "no-address.xml",
        "two-addresses.xml"
    })
    @DisplayName("serviceError yields nothing for a body without a readable Error element, printed nowhere")
    void serviceErrorIsEmptyWithoutErrorElement(String name, CapturedOutput output) throws IOException {
        assertThat(codec.serviceError(fixture(name))).isEmpty();

        assertNothingPrintedByParser(output);
    }

    @Test
    @DisplayName("serviceError yields nothing for a null, empty or foreign-root body")
    void serviceErrorIsEmptyForNoDocument() {
        assertThat(codec.serviceError(null)).isEmpty();
        assertThat(codec.serviceError(new byte[0])).isEmpty();
        assertThat(codec.serviceError("<Other><Error><Number>1</Number></Error></Other>"
                .getBytes(StandardCharsets.UTF_8))).isEmpty();
    }

    @Test
    @DisplayName("serviceError reads the first Number, Description and Error, and an empty element as blank")
    void serviceErrorReadsFirstOccurrences() {
        byte[] repeated = response(baseAddressWith(Map.of("City", ""))
                + errorElement(element("Number", " 1 ") + element("Number", "2")
                        + element("Description", " First. ") + element("Description", "Second."))
                + errorOf("3", "clsAMS", "Third."));
        byte[] rootWithoutChildren = "<Error><Source>USPSCOM::DoAuth</Source><Description/></Error>"
                .getBytes(StandardCharsets.UTF_8);

        assertThat(codec.serviceError(repeated)).contains(new UspsXmlCodec.ServiceError("1", "First."));
        assertThat(codec.serviceError(rootWithoutChildren)).contains(new UspsXmlCodec.ServiceError(null, ""));
    }

    /** Bodies whose only USPS {@code Error} is written as the prefixed {@code x:Error}. */
    static Stream<Named<byte[]>> bodiesWithOnlyAPrefixedError() {
        return Stream.of(
                Named.of("a prefixed root x:Error", prefixedBaseError().getBytes(StandardCharsets.UTF_8)),
                Named.of("a blank-City Address holding a prefixed x:Error",
                        response(baseAddressWith(Map.of("City", "")) + prefixedBaseError())));
    }

    @ParameterizedTest(name = "{0} yields no Error texts")
    @MethodSource("bodiesWithOnlyAPrefixedError")
    @DisplayName("serviceError matches Error exactly as written, so a prefixed x:Error yields nothing")
    void serviceErrorIgnoresPrefixedError(byte[] body, CapturedOutput output) {
        assertThat(codec.serviceError(body)).isEmpty();

        assertNothingPrintedByParser(output);
    }

    /**
     * Asserts that the codec let no parser diagnostic reach the console: the JDK's
     * default error handler prints {@code [Fatal Error]} lines to standard error.
     */
    private static void assertNothingPrintedByParser(CapturedOutput output) {
        assertThat(output.getErr()).as("standard error").isEmpty();
        assertThat(output.getAll()).as("all captured output").doesNotContain("[Fatal Error]");
    }

    /** Reads the bytes of test fixture {@code /usps/<name>} exactly as stored. */
    private byte[] fixture(String name) throws IOException {
        try (InputStream in = Objects.requireNonNull(
                getClass().getResourceAsStream("/usps/" + name), () -> "missing test fixture /usps/" + name)) {
            return in.readAllBytes();
        }
    }

    /**
     * UTF-8 bytes of an inline {@code AddressValidateResponse} whose one
     * {@code <Address ID="0">} holds {@code addressChildren} as written.
     */
    private static byte[] response(String addressChildren) {
        return ("<AddressValidateResponse><Address ID=\"0\">" + addressChildren
                + "</Address></AddressValidateResponse>").getBytes(StandardCharsets.UTF_8);
    }

    /** The base row as the six {@code Address} children of a response, in source order. */
    private static String baseAddress() {
        return baseAddressWith(Map.of());
    }

    /**
     * The base row as the six {@code Address} children of a response, in source order,
     * except that each field named in {@code replacements} holds its replacement value.
     * A key that names no field changes nothing, so the document stays the base row.
     */
    private static String baseAddressWith(Map<String, String> replacements) {
        StringBuilder children = new StringBuilder();
        for (int i = 0; i < ADDRESS_FIELDS.length; i++) {
            String field = ADDRESS_FIELDS[i];
            children.append(element(field, replacements.getOrDefault(field, BASE_ROW[i])));
        }
        return children.toString();
    }

    /** The base error document's one {@code Error}: -2147219401, clsAMS, Address Not Found. */
    private static String baseError() {
        return errorOf("-2147219401", "clsAMS", "Address Not Found.");
    }

    /**
     * The base error's children inside an {@code Error} written as the prefixed
     * {@code x:Error}, which no path of the codec names.
     */
    private static String prefixedBaseError() {
        return "<x:Error xmlns:x=\"urn:x\">" + element("Number", "-2147219401") + element("Source", "clsAMS")
                + element("Description", "Address Not Found.") + "</x:Error>";
    }

    /** An {@code Error} holding {@code Number}, {@code Source} and {@code Description}, in that order. */
    private static String errorOf(String number, String source, String description) {
        return errorElement(element("Number", number) + element("Source", source)
                + element("Description", description));
    }

    /** An {@code Error} element holding {@code children} as written. */
    private static String errorElement(String children) {
        return "<Error>" + children + "</Error>";
    }

    /**
     * {@code <name>text</name>}. The text is written unescaped, so callers pass only
     * values without markup characters.
     */
    private static String element(String name, String text) {
        return "<" + name + ">" + text + "</" + name + ">";
    }

    /**
     * Parses the codec's own output with a plain namespace-aware DOM builder, so the
     * assertions read what a receiving XML parser would see.
     */
    private static Document parseXml(String xml)
            throws ParserConfigurationException, SAXException, IOException {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        return factory.newDocumentBuilder().parse(new InputSource(new StringReader(xml)));
    }

    /**
     * Parses the codec's own output with a namespace-aware DOM builder hardened as the
     * codec's response parser is: secure processing, no DOCTYPE, no external entities or
     * DTD, no XInclude. A malformed document fails the parse.
     */
    private static Document parseHardened(String xml)
            throws ParserConfigurationException, SAXException, IOException {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newDefaultInstance();
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        factory.setNamespaceAware(true);
        return factory.newDocumentBuilder().parse(new InputSource(new StringReader(xml)));
    }

    /** Direct element children of {@code parent}, in document order; text and other nodes skipped. */
    private static List<Element> childElements(Element parent) {
        NodeList nodes = parent.getChildNodes();
        List<Element> elements = new ArrayList<>();
        for (int i = 0; i < nodes.getLength(); i++) {
            if (nodes.item(i) instanceof Element element) {
                elements.add(element);
            }
        }
        return elements;
    }

    /** Attribute names of {@code element}, in no particular order. */
    private static List<String> attributeNames(Element element) {
        NamedNodeMap attributes = element.getAttributes();
        List<String> names = new ArrayList<>();
        for (int i = 0; i < attributes.getLength(); i++) {
            names.add(attributes.item(i).getNodeName());
        }
        return names;
    }

    /**
     * Asserts that {@code parent} has exactly one direct element child named
     * {@code localName}, and returns that child.
     */
    private static Element assertSingleChild(Element parent, String localName) {
        List<Element> matches = childElements(parent).stream()
                .filter(child -> localName.equals(child.getLocalName()))
                .toList();
        assertThat(matches).as("<%s> children of <%s>", localName, parent.getLocalName()).hasSize(1);
        return matches.get(0);
    }
}
