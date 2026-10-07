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
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
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
 * <ul>
 *   <li><b>Request</b> [USADRVAL.SQLRPGLE:74-93]. Root {@code AddressValidateRequest} with
 *       {@code USERID} and {@code PASSWORD}, {@code <Revision>1</Revision>} and one
 *       {@code <Address ID="0">} holding {@code Address1}, {@code Address2}, {@code City},
 *       {@code State}, {@code Zip5} and {@code Zip4} in that order. The source inserts
 *       values without escaping, so an {@code &} or {@code <} broke the document; the codec
 *       escapes them (a behaviour changed on purpose) and strips surrounding blanks, as
 *       the source trims its data-area credentials.</li>
 *   <li><b>Valid responses</b> [USADRVAL.SQLRPGLE:100-133]. Missing or empty address
 *       elements read {@code ""}, the {@code default ' '} of the XMLTABLE columns; a
 *       non-blank City is a success [118-121]; a blank City with one valid {@code Error}
 *       is an address-level error.</li>
 *   <li><b>Faults</b> [USADRVAL.SQLRPGLE:94-96,114-116,134-136]. Every response the
 *       source's {@code SQLSTATE <> '00000'} test would reject raises
 *       {@link AddressServiceUnavailableException}, without the JDK parser printing
 *       {@code [Fatal Error]} to standard error and without resolving any DOCTYPE
 *       entity.</li>
 * </ul>
 *
 * <p><b>Test values.</b> Every address and credential is fictitious. The base row
 * ({@code STE 2}, {@code 8 ELMWOOD DR}, {@code OLD HAVEN}, {@code CT}, {@code 06399},
 * {@code 1234}) is the one the {@code usps/*.xml} fixtures use. The placeholder password
 * carries {@code &} and {@code "} so that attribute escaping is exercised.
 *
 * <p><b>Captured output.</b> {@link OutputCaptureExtension} captures each test invocation
 * separately. The {@link CapturedOutput} a test receives holds that invocation's output
 * plus class-level output written outside any invocation's capture, such as while JUnit
 * constructs the test instance, so the standard-error assertions prove that each fault
 * case's own parse printed nothing.
 *
 * <p>Plain JUnit 5 and AssertJ: no application context, no network.
 */
@DisplayName("UspsXmlCodec: USPS Web Tools request document and response rules")
@ExtendWith(OutputCaptureExtension.class)
class UspsXmlCodecTest {

    /** Fictitious USPS Web Tools user id. */
    private static final String USER_ID = "TESTUSER123";

    /** Fictitious USPS Web Tools password; {@code &} and {@code "} need escaping. */
    private static final String PASSWORD = "pl&ce\"holder";

    /** The fixtures' base row as a request. */
    private static final AddressValidationRequest BASE_REQUEST =
            new AddressValidationRequest("STE 2", "8 ELMWOOD DR", "OLD HAVEN", "CT", "06399", "1234");

    /** The six {@code Address} children in source order [USADRVAL.SQLRPGLE:84-89]. */
    private static final String[] ADDRESS_FIELDS =
            {"Address1", "Address2", "City", "State", "Zip5", "Zip4"};

    /** Error number Web Tools returns for an address it cannot find. */
    private static final int ADDRESS_NOT_FOUND = -2147219401;

    private final UspsXmlCodec codec = new UspsXmlCodec();

    // ---------------------------------------------------------------------------------
    // Request document [USADRVAL.SQLRPGLE:74-93]
    // ---------------------------------------------------------------------------------

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
        assertThat(childText(addressOf(root), "Address2")).isEqualTo("A&B <ST");
        assertThat(childText(addressOf(root), "City")).isEqualTo("OLD HAVEN");
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
        assertThat(childElements(addressOf(root))).extracting(Element::getTextContent)
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
        assertThat(childText(addressOf(parseXml(document).getDocumentElement()), "Address2"))
                .isEqualTo(street);
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

    @Test
    @DisplayName("request record rejects a 31-character Address2, wider than char(30)")
    void requestRejectsOverWidthStreet() {
        String street = "A".repeat(31);

        assertThatThrownBy(() -> new AddressValidationRequest("", street, "OLD HAVEN", "CT", "06399", ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining(street);
    }

    // ---------------------------------------------------------------------------------
    // Valid responses [USADRVAL.SQLRPGLE:100-133]
    // ---------------------------------------------------------------------------------

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

    // ---------------------------------------------------------------------------------
    // Faults [USADRVAL.SQLRPGLE:94-96,114-116,134-136]
    // ---------------------------------------------------------------------------------

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

    // ---------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------

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
     * Parses the codec's own output with a plain namespace-aware DOM builder, so the
     * assertions read what a receiving XML parser would see.
     */
    private static Document parseXml(String xml)
            throws ParserConfigurationException, SAXException, IOException {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
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

    /** The single {@code Address} child of the request root. */
    private static Element addressOf(Element root) {
        return singleChild(root, "Address");
    }

    /** Text of the single {@code localName} child of {@code parent}. */
    private static String childText(Element parent, String localName) {
        return singleChild(parent, localName).getTextContent();
    }

    private static Element singleChild(Element parent, String localName) {
        List<Element> matches = childElements(parent).stream()
                .filter(child -> localName.equals(child.getLocalName()))
                .toList();
        assertThat(matches).as("<%s> children of <%s>", localName, parent.getLocalName()).hasSize(1);
        return matches.get(0);
    }
}

