package com.democorp.customermaster.address;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.StringWriter;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.ErrorHandler;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;

/**
 * Writes the USPS Web Tools {@code AddressValidateRequest} document and reads the
 * {@code AddressValidateResponse} document, applying the source's response tests.
 *
 * <p>Replaces the XML handling inside {@code USAdrVal}
 * [USPS_Address/USADRVAL.SQLRPGLE:74-136]. The source concatenated the request document
 * without escaping [74-93], so an {@code &} or {@code <} in an address produced a
 * malformed request; {@link #requestDocument requestDocument} writes the same elements,
 * attributes and order with a StAX {@link XMLStreamWriter}, which escapes them, and
 * {@link #requestQuery requestQuery} adds the query prefix and URL encoding. The source
 * read the response through XMLTABLE [98-136]; {@link #parse parse} reads the same paths
 * from a hardened DOM.
 *
 * <p><b>Response rules.</b> After the HTTP call and after each XMLTABLE the source tests
 * {@code SQLSTATE <> '00000'} and ends the program through SQLProblem
 * [94-96,114-116,134-136]. That test rejects a malformed document, no row or several
 * rows, a NULL fetched without an indicator, a failed integer cast and a truncation
 * warning. {@link #parse parse} applies the same tests, in this order, and raises
 * {@link AddressServiceUnavailableException} for each fault:
 * <ol>
 *   <li>the body is empty, is not well-formed XML, or declares a DOCTYPE;</li>
 *   <li>the root is not {@code AddressValidateResponse}, which includes the Web Tools
 *       root {@code <Error>} used for request-level faults such as an authorization
 *       failure;</li>
 *   <li>the root holds no {@code Address} child, or more than one;</li>
 *   <li>one of {@code Address1}, {@code Address2}, {@code City}, {@code State},
 *       {@code Zip5} and {@code Zip4} occurs more than once, or is longer than
 *       30, 30, 30, 2, 5 or 4 characters;</li>
 *   <li>a non-blank {@code City} is a success, whatever else the row holds
 *       [118-121];</li>
 *   <li>with a blank {@code City}, the {@code Address} holds no {@code Error} child, or
 *       more than one;</li>
 *   <li>that {@code Error} lacks {@code Number}, {@code Source} or {@code Description},
 *       or holds one of them more than once; {@code Number} is not a 32-bit integer;
 *       {@code Source} is longer than 30 characters or {@code Description} longer than
 *       512.</li>
 * </ol>
 * A response that passes all of them with a blank City is an address-level error and is
 * returned as {@link AddressValidationResult#error error(...)}; customer-api turns it into
 * 422 DEM9898. Every fault becomes 502 APP0502 downstream and is never turned into such
 * a result or into a 500.
 *
 * <p><b>Value reading.</b> Only direct children are examined, so element counts are
 * exact. An element's value is its text content with surrounding whitespace removed by
 * {@link String#strip()}; a missing or empty element reads {@code ""}, the
 * {@code default ' '} of the source columns. Widths are counted in Unicode code points
 * after the strip. Elements the source does not read ({@code DeliveryPoint},
 * {@code CarrierRoute}, {@code ReturnText}, {@code HelpFile}, {@code HelpContext},
 * {@code Urbanization} and so on) and the {@code Address ID} attribute are ignored.
 *
 * <p><b>Secret and data hygiene.</b> The request document and query carry the USPS user
 * id and password; neither is ever logged or placed in an exception message by this
 * class. Fault messages name the kind of fault only and never quote the body, an element
 * value, a URL or a credential. Parser exceptions are not chained, because a
 * {@link SAXParseException} can quote the document it failed on. For the log line of a
 * fault or of an address-level error, {@link #serviceError serviceError} reads the
 * {@code Number} and {@code Description} of the body's USPS {@code Error} element with
 * the same hardened parser; the client turns them into a log-safe projection before
 * logging and never puts them in an exception message.
 *
 * <p><b>Threading.</b> The class holds no mutable state. JAXP factories, builders and
 * writers are not thread-safe, so each call creates its own; one instance can be shared
 * by any number of threads.
 */
public final class UspsXmlCodec {

    /** Query prefix the source places before the encoded document [USADRVAL.SQLRPGLE:77]. */
    static final String QUERY_PREFIX = "API=Verify&XML=";

    private static final String REQUEST_ROOT = "AddressValidateRequest";
    private static final String RESPONSE_ROOT = "AddressValidateResponse";
    private static final String ADDRESS = "Address";
    private static final String ADDRESS1 = "Address1";
    private static final String ADDRESS2 = "Address2";
    private static final String CITY = "City";
    private static final String STATE = "State";
    private static final String ZIP5 = "Zip5";
    private static final String ZIP4 = "Zip4";
    private static final String ERROR = "Error";
    private static final String NUMBER = "Number";
    private static final String SOURCE = "Source";
    private static final String DESCRIPTION = "Description";

    /**
     * Lexical form of {@code xs:integer}, which the source's {@code Number integer}
     * column casts from: an optional sign and ASCII digits. {@link Integer#parseInt}
     * alone would also accept non-ASCII Unicode digits, which the cast rejects.
     */
    private static final Pattern INTEGER_LEXICAL = Pattern.compile("[+-]?[0-9]+");

    /**
     * JDK limit on element nesting depth for parsed responses. A valid response is four
     * levels deep ({@code AddressValidateResponse/Address/Error/Number}); the limit bounds
     * the recursion of {@link Node#getTextContent()} on hostile input.
     */
    private static final String MAX_ELEMENT_DEPTH_PROPERTY = "jdk.xml.maxElementDepth";
    private static final String MAX_ELEMENT_DEPTH = "32";

    private static final String NOT_WELL_FORMED =
            "response is not well-formed XML or declares a DOCTYPE";

    /** Stateless, so one instance serves every parse. */
    private static final ErrorHandler SILENT_ERROR_HANDLER = new RethrowingErrorHandler();

    /** Creates a codec. It holds no state, so one instance can be shared. */
    public UspsXmlCodec() {
    }

    /**
     * Writes the {@code AddressValidateRequest} document for {@code request}.
     *
     * <p>The document is the one the source concatenates [USADRVAL.SQLRPGLE:79-91],
     * without an XML declaration, in this exact order: root
     * {@code AddressValidateRequest} with attributes {@code USERID} then
     * {@code PASSWORD}; child {@code <Revision>1</Revision>}; child
     * {@code <Address ID="0">} holding {@code Address1}, {@code Address2}, {@code City},
     * {@code State}, {@code Zip5} and {@code Zip4}. Every element is written with a start
     * and an end tag, so an empty value appears as {@code <Address1></Address1>}.
     *
     * <p>Every value and both credentials are stripped of surrounding whitespace, as the
     * source trims the data-area credentials [USADRVAL.SQLRPGLE:66-69]; {@code null}
     * credentials are written as {@code ""}. Text and attribute values are escaped by the
     * writer, so {@code &}, {@code <} and {@code "} cannot break the document.
     *
     * <p>For an all-empty request with user {@code U} and password {@code P} the result is
     * <pre>{@code
     * <AddressValidateRequest USERID="U" PASSWORD="P"><Revision>1</Revision><Address ID="0"><Address1></Address1><Address2></Address2><City></City><State></State><Zip5></Zip5><Zip4></Zip4></Address></AddressValidateRequest>
     * }</pre>
     *
     * <p>The result contains the credentials: callers must never log it.
     *
     * @param request  the address to validate; widths are already enforced by the record
     * @param userId   the USPS Web Tools user id; {@code null} is written as {@code ""}
     * @param password the USPS Web Tools password; {@code null} is written as {@code ""}
     * @return the request document
     * @throws NullPointerException  if {@code request} is {@code null}
     * @throws IllegalStateException if the XML writer fails, which a {@link StringWriter}
     *                               target does not do in practice; the message carries
     *                               no value and no credential
     */
    public String requestDocument(AddressValidationRequest request, String userId, String password) {
        Objects.requireNonNull(request, "request");
        StringWriter out = new StringWriter();
        try {
            XMLStreamWriter xml = newWriter(out);
            try {
                writeRequest(xml, request, clean(userId), clean(password));
                xml.flush();
            } finally {
                xml.close();
            }
        } catch (XMLStreamException e) {
            // Not chained: the writer's message is not under this class's control, and the
            // document being written holds credentials and customer data.
            throw new IllegalStateException(
                    "could not write the USPS request document (" + e.getClass().getSimpleName() + ")");
        }
        return out.toString();
    }

    /**
     * Builds the query string for the Web Tools Verify call:
     * {@code API=Verify&XML=} followed by the {@link #requestDocument request document}
     * URL-encoded as UTF-8, which replaces the source's {@code url_encode}
     * [USADRVAL.SQLRPGLE:77-78].
     *
     * <p><b>The result contains the user id and password.</b> Callers must never log it,
     * put it in an exception message or record it in metrics, and the same holds for any
     * URL built from it.
     *
     * @param request  the address to validate
     * @param userId   the USPS Web Tools user id; {@code null} is written as {@code ""}
     * @param password the USPS Web Tools password; {@code null} is written as {@code ""}
     * @return the query string, without a leading {@code ?}
     * @throws NullPointerException  if {@code request} is {@code null}
     * @throws IllegalStateException if the XML writer fails
     */
    public String requestQuery(AddressValidationRequest request, String userId, String password) {
        return QUERY_PREFIX
                + URLEncoder.encode(requestDocument(request, userId, password), StandardCharsets.UTF_8);
    }

    /**
     * Returns {@code value} exactly as {@link #requestDocument requestDocument} writes it
     * between the double quotes of an attribute: stripped of surrounding whitespace
     * ({@code null} reads {@code ""}) and escaped by the same JDK {@link XMLStreamWriter}.
     * That writer escapes {@code &}, {@code <}, {@code >} and {@code "} but leaves an
     * apostrophe, a tab and a line break as they are, so the password {@code p'&q} travels
     * as {@code p'&amp;q}.
     *
     * <p>{@link UspsTextRedactor} derives the request-wire form of each credential it
     * masks from this method, so its mask follows the escaping the request document
     * actually applies instead of a second, hand-written escaping rule that could drift
     * from it.
     *
     * <p>The result is as secret as its input: callers must never log it.
     *
     * @param value the attribute value; {@code null} is written as {@code ""}
     * @return the escaped attribute text, without the surrounding quotes
     * @throws IllegalStateException if the XML writer fails or does not write the attribute
     *                               in the expected form; the message carries no value
     */
    static String attributeValue(String value) {
        String attribute = "PASSWORD";
        StringWriter out = new StringWriter();
        try {
            XMLStreamWriter xml = newWriter(out);
            try {
                xml.writeStartElement(REQUEST_ROOT);
                xml.writeAttribute(attribute, clean(value));
                xml.writeEndElement();
                xml.flush();
            } finally {
                xml.close();
            }
        } catch (XMLStreamException e) {
            // Not chained, for the same reason as in requestDocument: the value is a credential.
            throw new IllegalStateException(
                    "could not write a USPS request attribute (" + e.getClass().getSimpleName() + ")");
        }
        String written = out.toString();
        String start = "<" + REQUEST_ROOT + " " + attribute + "=\"";
        String end = "\"></" + REQUEST_ROOT + ">";
        if (written.length() < start.length() + end.length()
                || !written.startsWith(start) || !written.endsWith(end)) {
            throw new IllegalStateException("the USPS request attribute was not written in the expected form");
        }
        return written.substring(start.length(), written.length() - end.length());
    }

    /**
     * Reads an {@code AddressValidateResponse} body into a result, applying the response
     * rules listed on this class.
     *
     * <p>A non-blank {@code City} returns
     * {@link AddressValidationResult#success success(...)} with the six address values.
     * A blank {@code City} with one valid {@code Error} returns
     * {@link AddressValidationResult#error error(...)} with the address values, the error
     * number, and the source and description stripped of surrounding blanks (an empty
     * {@code Source} or {@code Description} reads {@code ""}). Anything else is a fault.
     *
     * <p>The parser is hardened: secure processing on, DOCTYPE declarations rejected,
     * external entities, external DTDs, external schemas and XInclude off, and a nesting
     * limit. A rejected DOCTYPE is reported as a fault and nothing it names is read. The
     * parser's diagnostics go to a handler that prints nothing.
     *
     * @param body the raw response body; the parser detects its encoding from the XML
     *             declaration or byte order mark (USPS sends UTF-8)
     * @return the standardized address or the address-level error
     * @throws AddressServiceUnavailableException for every fault; the message names the
     *                                            kind of fault and quotes no content
     * @throws IllegalStateException              if the JDK parser cannot be configured as
     *                                            required, which is a platform defect
     */
    public AddressValidationResult parse(byte[] body) {
        if (body == null || body.length == 0) {
            throw fault("response body is empty");
        }
        Element root = parseDocument(body).getDocumentElement();
        if (!RESPONSE_ROOT.equals(root.getNodeName())) {
            throw fault("response root is not " + RESPONSE_ROOT);
        }

        List<Element> addresses = childElements(root, ADDRESS);
        if (addresses.size() != 1) {
            throw fault("response holds " + addresses.size() + " " + ADDRESS + " elements");
        }
        Element address = addresses.get(0);

        String address1 = addressValue(address, ADDRESS1, AddressValidationRequest.ADDRESS1_WIDTH);
        String address2 = addressValue(address, ADDRESS2, AddressValidationRequest.ADDRESS2_WIDTH);
        String city = addressValue(address, CITY, AddressValidationRequest.CITY_WIDTH);
        String state = addressValue(address, STATE, AddressValidationRequest.STATE_WIDTH);
        String zip5 = addressValue(address, ZIP5, AddressValidationRequest.ZIP5_WIDTH);
        String zip4 = addressValue(address, ZIP4, AddressValidationRequest.ZIP4_WIDTH);

        // "If a city was returned, assume it worked" [USADRVAL.SQLRPGLE:118-121].
        if (!city.isEmpty()) {
            return AddressValidationResult.success(address1, address2, city, state, zip5, zip4);
        }

        List<Element> errors = childElements(address, ERROR);
        if (errors.size() != 1) {
            throw fault("response with a blank City holds " + errors.size() + " " + ERROR + " elements");
        }
        Element error = errors.get(0);

        // Number, Source and Description have no default in the source's XMLTABLE, so a
        // missing one is fetched as NULL without an indicator, which fails the statement.
        String numberText = text(requiredChild(error, NUMBER));
        String source = text(requiredChild(error, SOURCE));
        String description = text(requiredChild(error, DESCRIPTION));

        int number = parseNumber(numberText);
        requireWidth(ERROR, SOURCE, source, AddressValidationResult.ERROR_SOURCE_WIDTH);
        requireWidth(ERROR, DESCRIPTION, description, AddressValidationResult.ERROR_DESCRIPTION_WIDTH);

        return AddressValidationResult.error(
                address1, address2, city, state, zip5, zip4, number, source, description);
    }

    /**
     * Reads the {@code Number} and {@code Description} of the USPS {@code Error} element a
     * body holds, for the log line of a fault {@link #parse parse} reported or of an
     * address-level error it returned. The {@code Number} is kept as text, not parsed, so
     * the client can project what the service sent: an error code logs as sent, anything
     * undocumented as markers. Logging only: the values are not validated, and no result
     * or exception is built from them.
     *
     * <p>The {@code Error} read is:
     * <ul>
     *   <li>the root, when the root element is a Web Tools {@code <Error>}, the form of
     *       request-level faults such as an authorization failure;</li>
     *   <li>the first {@code Error} child of the {@code Address}, when the root is
     *       {@code AddressValidateResponse} holding exactly one {@code Address}.</li>
     * </ul>
     * Each value is the first such child's text with surrounding whitespace removed, read
     * as {@link #parse parse} reads values, or {@code null} when the {@code Error} has no
     * such child. Any other body, one that is empty, not well-formed or declares a DOCTYPE
     * included, yields an empty {@link Optional}. The body is parsed by the same hardened
     * builder as {@link #parse parse}, so nothing external is resolved, and a bad body never
     * raises an exception.
     *
     * @param body the raw response body; {@code null} yields an empty {@link Optional}
     * @return the {@code Error} element's texts, or empty when the body holds none
     * @throws IllegalStateException if the JDK parser cannot be configured as required, a
     *                               platform defect {@link #parse parse} reports first
     */
    Optional<ServiceError> serviceError(byte[] body) {
        if (body == null || body.length == 0) {
            return Optional.empty();
        }
        Document document;
        try {
            document = newHardenedBuilder().parse(new ByteArrayInputStream(body));
        } catch (SAXException | IOException e) {
            return Optional.empty();
        }
        Element root = document.getDocumentElement();
        Element error;
        if (ERROR.equals(root.getNodeName())) {
            error = root;
        } else if (RESPONSE_ROOT.equals(root.getNodeName())) {
            List<Element> addresses = childElements(root, ADDRESS);
            if (addresses.size() != 1) {
                return Optional.empty();
            }
            List<Element> errors = childElements(addresses.get(0), ERROR);
            if (errors.isEmpty()) {
                return Optional.empty();
            }
            error = errors.get(0);
        } else {
            return Optional.empty();
        }
        return Optional.of(new ServiceError(firstChildText(error, NUMBER), firstChildText(error, DESCRIPTION)));
    }

    /**
     * Creates the XML writer behind {@link #requestDocument requestDocument} and
     * {@link #attributeValue attributeValue}. The JDK's own writer is used, rather than
     * whichever StAX provider happens to be on the classpath, so the empty-element form and
     * the escaping are fixed.
     */
    private static XMLStreamWriter newWriter(StringWriter out) throws XMLStreamException {
        return XMLOutputFactory.newDefaultFactory().createXMLStreamWriter(out);
    }

    /**
     * Writes the request elements in source order. Every element gets a start tag, its
     * characters and an end tag, never an empty-element tag.
     */
    private static void writeRequest(
            XMLStreamWriter xml, AddressValidationRequest request, String userId, String password)
            throws XMLStreamException {
        xml.writeStartElement(REQUEST_ROOT);
        xml.writeAttribute("USERID", userId);
        xml.writeAttribute("PASSWORD", password);
        writeElement(xml, "Revision", "1");
        xml.writeStartElement(ADDRESS);
        xml.writeAttribute("ID", "0");
        writeElement(xml, ADDRESS1, clean(request.address1()));
        writeElement(xml, ADDRESS2, clean(request.address2()));
        writeElement(xml, CITY, clean(request.city()));
        writeElement(xml, STATE, clean(request.state()));
        writeElement(xml, ZIP5, clean(request.zip5()));
        writeElement(xml, ZIP4, clean(request.zip4()));
        xml.writeEndElement();
        xml.writeEndElement();
    }

    private static void writeElement(XMLStreamWriter xml, String name, String value)
            throws XMLStreamException {
        xml.writeStartElement(name);
        xml.writeCharacters(value);
        xml.writeEndElement();
    }

    /**
     * Parses {@code body} with a hardened builder. Every parse or I/O failure, a rejected
     * DOCTYPE included, becomes the same fault without its cause, because the parser's
     * message can quote the document.
     */
    private static Document parseDocument(byte[] body) {
        DocumentBuilder builder = newHardenedBuilder();
        try {
            return builder.parse(new ByteArrayInputStream(body));
        } catch (SAXException | IOException e) {
            throw fault(NOT_WELL_FORMED);
        }
    }

    /**
     * Creates a DOM builder that resolves nothing external and reports silently.
     *
     * <p>The JDK's own factory is requested, rather than whichever JAXP provider happens
     * to be on the classpath, because every feature and attribute below is guaranteed to
     * be supported there; another provider could reject one of them or ignore it.
     */
    private static DocumentBuilder newHardenedBuilder() {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newDefaultInstance();
        try {
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            factory.setNamespaceAware(false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            factory.setAttribute(MAX_ELEMENT_DEPTH_PROPERTY, MAX_ELEMENT_DEPTH);
            DocumentBuilder builder = factory.newDocumentBuilder();
            builder.setErrorHandler(SILENT_ERROR_HANDLER);
            return builder;
        } catch (ParserConfigurationException | IllegalArgumentException e) {
            throw new IllegalStateException("the JDK XML parser cannot be configured securely", e);
        }
    }

    /**
     * Reads one optional {@code Address} child: {@code ""} when absent, a fault when it
     * occurs more than once or exceeds its {@code USAdrValDS} width.
     */
    private static String addressValue(Element address, String name, int width) {
        List<Element> found = childElements(address, name);
        if (found.size() > 1) {
            throw fault(ADDRESS + " element " + name + " occurs more than once");
        }
        String value = found.isEmpty() ? "" : text(found.get(0));
        requireWidth(ADDRESS, name, value, width);
        return value;
    }

    /** Returns the single {@code name} child of {@code error}; a fault when absent or repeated. */
    private static Element requiredChild(Element error, String name) {
        List<Element> found = childElements(error, name);
        if (found.isEmpty()) {
            throw fault(ERROR + " element " + name + " is missing");
        }
        if (found.size() > 1) {
            throw fault(ERROR + " element " + name + " occurs more than once");
        }
        return found.get(0);
    }

    /** Converts the stripped {@code Number} text as the source's {@code integer} cast does. */
    private static int parseNumber(String text) {
        if (!INTEGER_LEXICAL.matcher(text).matches()) {
            throw fault(ERROR + " " + NUMBER + " is not an integer");
        }
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException e) {
            // Matches the lexical form but lies outside the 32-bit range of int(10).
            throw fault(ERROR + " " + NUMBER + " is not an integer");
        }
    }

    private static void requireWidth(String scope, String name, String value, int width) {
        if (value.codePointCount(0, value.length()) > width) {
            throw fault(scope + " element " + name + " exceeds " + width + " characters");
        }
    }

    /** Direct element children of {@code parent} named {@code name}, in document order. */
    private static List<Element> childElements(Element parent, String name) {
        NodeList children = parent.getChildNodes();
        List<Element> found = new ArrayList<>(1);
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getNodeType() == Node.ELEMENT_NODE && name.equals(child.getNodeName())) {
                found.add((Element) child);
            }
        }
        return found;
    }

    /**
     * The element's string value, as XMLTABLE atomizes it (text and CDATA of all
     * descendants, comments excluded), stripped of surrounding whitespace.
     */
    private static String text(Element element) {
        return element.getTextContent().strip();
    }

    /** The {@link #text text} of the first {@code name} child of {@code parent}, or {@code null} when it has none. */
    private static String firstChildText(Element parent, String name) {
        List<Element> found = childElements(parent, name);
        return found.isEmpty() ? null : text(found.get(0));
    }

    private static String clean(String value) {
        return value == null ? "" : value.strip();
    }

    private static AddressServiceUnavailableException fault(String kind) {
        return new AddressServiceUnavailableException(kind);
    }

    /**
     * The texts of a USPS {@code Error} element, as {@link #serviceError serviceError}
     * reads them for a log line. Neither is validated, and either can echo the request, its
     * URL and credentials included, so the client logs only its log-safe projection of them.
     *
     * @param number      the {@code Number} text, stripped; {@code null} when the element is absent
     * @param description the {@code Description} text, stripped; {@code null} when the element is absent
     */
    record ServiceError(String number, String description) {
    }

    /**
     * Ignores warnings and rethrows errors and fatal errors, so the parser never prints
     * {@code [Fatal Error]} lines to standard error, as the JDK's default handler does.
     */
    private static final class RethrowingErrorHandler implements ErrorHandler {

        @Override
        public void warning(SAXParseException exception) {
            // Warnings do not affect the document; the response rules decide its fate.
        }

        @Override
        public void error(SAXParseException exception) throws SAXException {
            throw exception;
        }

        @Override
        public void fatalError(SAXParseException exception) throws SAXException {
            throw exception;
        }
    }
}
