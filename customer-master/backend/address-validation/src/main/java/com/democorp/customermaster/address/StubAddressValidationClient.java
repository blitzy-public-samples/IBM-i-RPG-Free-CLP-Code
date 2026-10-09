package com.democorp.customermaster.address;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.json.JsonParserFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;

/**
 * Deterministic, offline {@link AddressValidationClient}: the default implementation for
 * local runs, Docker Compose and every test suite.
 *
 * <p>Answers the question the USADRVAL service program put to the USPS Web Tools
 * {@code Verify} API [USPS_Address/USADRVAL.SQLRPGLE:74-137] without any network. Its
 * fixtures, read from {@value #FIXTURES_LOCATION} on the class path, are fictitious
 * stand-ins modelled on the eight sample addresses of the USADRVAL_T harness
 * [USPS_Address/USADRVAL_T.RPGLE:25-79], so no real address appears in the repository
 * and no run depends on a USPS account.
 *
 * <h2>Answers, in this order</h2>
 * <ol>
 *   <li><b>Address-level error.</b> When the uppercased {@code address1} or
 *       {@code address2} contains {@value #BAD_ADDRESS_MARKER}, the result is
 *       {@link AddressValidationResult#error error(...)} with every address component blank
 *       and the triple {@value #NOT_FOUND_NUMBER} / {@value #NOT_FOUND_SOURCE} /
 *       {@value #NOT_FOUND_DESCRIPTION}, the report Web Tools gives for an unknown address,
 *       which customer-api turns into 422 DEM9898.</li>
 *   <li><b>Fixture hit.</b> The lookup key is {@code (address2, city, state, zip5)} of the
 *       request <em>as sent</em>, each stripped and uppercased. customer-api has already cut
 *       the 40-character street to the 30 characters of {@code Address2}
 *       [USPS_Address/MTNCUSTR.SQLRPGLE:473], so a longer street matches a fixture keyed on
 *       its first 30 characters. {@code address1} (the secondary line, which Edit_Address
 *       leaves blank) is not part of the key. A hit returns the fixture's standardized
 *       address, including its ZIP+4 when it has one. Every fixture's standardized address
 *       that carries a ZIP+4 is also a key, or is already its own fixture's input, answering
 *       with that same address, so re-reviewing an address the stub already standardized
 *       keeps its ZIP+4, as USPS does. One without a ZIP+4 needs no such key, because the
 *       echo returns it unchanged.</li>
 *   <li><b>Echo.</b> Anything else returns
 *       {@link AddressValidationResult#success success(...)} with the stripped, uppercased
 *       input and a blank {@code zip4}. An input whose city is blank therefore echoes as
 *       not standardized ({@link AddressValidationResult#standardized() standardized()}
 *       false) with the error triple {@code 0} / {@code ""} / {@code ""}, the source's
 *       City test [USPS_Address/USADRVAL.SQLRPGLE:118-121] applied to the echo.</li>
 * </ol>
 * The stub never throws {@link AddressServiceUnavailableException}: it has no transport
 * that could fail. Tests that need the 502 APP0502 path mock the interface instead.
 *
 * <h2>Uppercase rule</h2>
 * Each code point is replaced by its full uppercase mapping under {@link Locale#ROOT} when
 * that mapping is exactly one code point, and is otherwise kept, so lengths never change:
 * {@code ß} stays {@code ß}, {@code é} becomes {@code É}. It is the rule customer-api's
 * {@code TextNormalizer} and the frontend's {@code upperField} apply to keyed text. This
 * module cannot depend on customer-api, so the class carries its own private copy.
 *
 * <h2>Fixture file</h2>
 * A JSON array of objects, each with an {@code input} key ({@code address2}, {@code city},
 * {@code state}, {@code zip5}), an {@code output} address (those four, {@code address1}
 * and {@code zip4}) and an optional {@code description} used only to name the fixture in
 * load errors. A missing member reads {@code ""}. Input values are normalized exactly as
 * requests are; output values are returned as written.
 *
 * <p>The file is parsed through {@link JsonParserFactory}, because this module carries no
 * JSON library. Inside customer-api that resolves to Jackson; in this module's own tests
 * it resolves to Spring Boot's {@code BasicJsonParser}, which does not unescape strings,
 * mishandles braces and brackets inside quoted values, and corrupts an array element
 * whose closing brace is followed by whitespace before the separating comma. Every value
 * is therefore a plain quoted string, ZIP codes included, with no backslash escapes,
 * braces or brackets, and each fixture's closing brace is followed directly by the comma
 * that separates it from the next, as in <code>},{</code>.
 *
 * <h2>Load failures</h2>
 * The fixtures are read once, in the constructor, and any defect fails construction (and
 * with it application startup) with {@link IllegalStateException}: a missing, unreadable
 * or non-array resource; an element that is not an object or lacks {@code input} or
 * {@code output}; a member that is not a string; an unknown member inside {@code input} or
 * {@code output}; an input value wider than its request field; two fixtures whose
 * normalized inputs coincide; an output value wider than its {@code USAdrValDS} field
 * (the {@link IllegalArgumentException} of {@link AddressValidationResult} is the cause);
 * and a blank output city, because a fixture must standardize. Messages name the resource,
 * the fixture's array index and description, and the member, never an address value.
 *
 * <h2>Threading, determinism and hygiene</h2>
 * The fixture map is immutable ({@link Map#copyOf}) and nothing else is stored, so one
 * instance can serve any number of concurrent requests, and equal requests always receive
 * equal results. No address value is ever logged; the only log line reports how many
 * fixtures were loaded and from where. The bean is created only by
 * {@link AddressValidationAutoConfiguration}.
 */
public final class StubAddressValidationClient implements AddressValidationClient {

    /** Class-path location of the fixture file read by the public constructor. */
    static final String FIXTURES_LOCATION = "stub/usps-stub-fixtures.json";

    /** Marker that makes any address line an address-level error. */
    static final String BAD_ADDRESS_MARKER = "BADADDR";

    /** Error {@code Number} Web Tools reports for an address it cannot find. */
    static final int NOT_FOUND_NUMBER = -2147219401;

    /** Error {@code Source} Web Tools reports for an address it cannot find. */
    static final String NOT_FOUND_SOURCE = "clsAMS";

    /** Error {@code Description} Web Tools reports for an address it cannot find. */
    static final String NOT_FOUND_DESCRIPTION = "Address Not Found.";

    private static final Logger log = LoggerFactory.getLogger(StubAddressValidationClient.class);

    private static final String INPUT = "input";
    private static final String OUTPUT = "output";
    private static final String DESCRIPTION = "description";
    private static final String ADDRESS1 = "address1";
    private static final String ADDRESS2 = "address2";
    private static final String CITY = "city";
    private static final String STATE = "state";
    private static final String ZIP5 = "zip5";
    private static final String ZIP4 = "zip4";

    /** Members an {@code input} object may hold: the lookup key, nothing else. */
    private static final Set<String> INPUT_MEMBERS = Set.of(ADDRESS2, CITY, STATE, ZIP5);

    /** Members an {@code output} object may hold: the returned address. */
    private static final Set<String> OUTPUT_MEMBERS = Set.of(ADDRESS1, ADDRESS2, CITY, STATE, ZIP5, ZIP4);

    /** Byte-order mark some editors write at the start of a UTF-8 file. */
    private static final char BYTE_ORDER_MARK = '\uFEFF';

    /** Distance from an ASCII lowercase letter to its uppercase letter. */
    private static final int ASCII_CASE_OFFSET = 'a' - 'A';

    /** Standardized answer per normalized input; immutable, built once. */
    private final Map<Key, AddressValidationResult> fixtures;

    /**
     * Creates the stub over the fixture file bundled with this module,
     * {@value #FIXTURES_LOCATION}.
     *
     * @throws IllegalStateException if the bundled fixtures are missing or invalid
     */
    public StubAddressValidationClient() {
        this(new ClassPathResource(FIXTURES_LOCATION));
    }

    /**
     * Creates the stub over the given fixture resource. Package-private so that tests can
     * supply their own fixture files.
     *
     * @param fixtures the JSON fixture resource, read once as UTF-8
     * @throws NullPointerException  if {@code fixtures} is {@code null}
     * @throws IllegalStateException if the resource is missing, unreadable or invalid, as
     *                               listed on the class
     */
    StubAddressValidationClient(Resource fixtures) {
        Objects.requireNonNull(fixtures, "fixtures");
        this.fixtures = load(fixtures);
        log.info("Address validation stub active: {} fixture(s) loaded from {}; no address service is called",
                this.fixtures.size(), fixtures.getDescription());
    }

    /**
     * Answers one request: the {@value #BAD_ADDRESS_MARKER} error, a fixture hit, or the
     * uppercase echo, in that order (see the class description).
     *
     * @param request the address to check; never {@code null}
     * @return the stub's answer, never {@code null}
     * @throws NullPointerException if {@code request} is {@code null}
     */
    @Override
    public AddressValidationResult validate(AddressValidationRequest request) {
        Objects.requireNonNull(request, "request");

        if (upper(request.address1()).contains(BAD_ADDRESS_MARKER)
                || upper(request.address2()).contains(BAD_ADDRESS_MARKER)) {
            return AddressValidationResult.error(
                    "", "", "", "", "", "", NOT_FOUND_NUMBER, NOT_FOUND_SOURCE, NOT_FOUND_DESCRIPTION);
        }

        final Key key = Key.of(request.address2(), request.city(), request.state(), request.zip5());
        final AddressValidationResult hit = fixtures.get(key);
        if (hit != null) {
            return hit;
        }

        // Normalizing only strips and uppercases length-preservingly, so every echoed value
        // still fits the width the request already enforced; success(...) cannot reject it.
        return AddressValidationResult.success(
                norm(request.address1()), key.address2(), key.city(), key.state(), key.zip5(), "");
    }

    // ------------------------------------------------------------------ fixture loading

    /**
     * Reads, parses and validates the fixture resource into an immutable map.
     *
     * @param resource the fixture resource
     * @return the fixtures keyed by normalized input
     * @throws IllegalStateException for every defect listed on the class
     */
    private static Map<Key, AddressValidationResult> load(Resource resource) {
        final String origin = "Stub address fixtures " + resource.getDescription();
        final List<Object> elements = parse(read(resource, origin), origin);

        final Map<Key, AddressValidationResult> loaded = new LinkedHashMap<>();
        final Map<Key, String> firstLabel = new HashMap<>();
        for (int index = 0; index < elements.size(); index++) {
            final Object element = elements.get(index);
            if (!(element instanceof Map<?, ?> fixture)) {
                throw new IllegalStateException(
                        origin + ": fixture at index " + index + " is not a JSON object");
            }
            final String name = label(index, fixture, origin);
            final String label = origin + ": " + name;

            final Map<?, ?> input = member(fixture, INPUT, label);
            final Map<?, ?> output = member(fixture, OUTPUT, label);
            final Key key = inputKey(input, label);
            final AddressValidationResult result = outputResult(output, label);

            final String previous = firstLabel.putIfAbsent(key, name);
            if (previous != null) {
                throw new IllegalStateException(
                        label + ": its normalized input duplicates that of " + previous);
            }
            loaded.put(key, result);
        }
        return Map.copyOf(loaded);
    }

    /**
     * Reads the whole resource as UTF-8 text, without a leading byte-order mark.
     */
    private static String read(Resource resource, String origin) {
        if (!resource.exists()) {
            throw new IllegalStateException(origin + " not found");
        }
        final String text;
        try {
            text = resource.getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new IllegalStateException(origin + " could not be read", ex);
        }
        return !text.isEmpty() && text.charAt(0) == BYTE_ORDER_MARK ? text.substring(1) : text;
    }

    /**
     * Parses the text as a JSON array through whichever parser Spring Boot finds on the
     * class path.
     */
    private static List<Object> parse(String json, String origin) {
        final List<Object> elements;
        try {
            elements = JsonParserFactory.getJsonParser().parseList(json);
        } catch (IllegalArgumentException ex) {
            // JsonParseException, which every Spring Boot JsonParser throws, extends
            // IllegalArgumentException; the cause names the parser's complaint.
            throw new IllegalStateException(origin + " are not a valid JSON array", ex);
        }
        if (elements == null) {
            throw new IllegalStateException(origin + " are not a valid JSON array");
        }
        return elements;
    }

    /**
     * Names one fixture for error messages: its array index and, when present, its
     * description. The description is fixture text, never request data.
     */
    private static String label(int index, Map<?, ?> fixture, String origin) {
        final String where = "fixture at index " + index;
        final String description = text(fixture, DESCRIPTION, origin + ": " + where, "");
        return description.isBlank() ? where : where + " (" + description.strip() + ")";
    }

    /**
     * Returns the required object member {@code name} of a fixture.
     */
    private static Map<?, ?> member(Map<?, ?> fixture, String name, String label) {
        final Object value = fixture.get(name);
        if (value == null) {
            throw new IllegalStateException(label + ": has no " + name + " object");
        }
        if (!(value instanceof Map<?, ?> object)) {
            throw new IllegalStateException(label + ": " + name + " must be a JSON object");
        }
        return object;
    }

    /**
     * Builds the lookup key of a fixture's {@code input}, normalized exactly as a request.
     *
     * <p>Two checks beyond the fixture contract guard against a fixture that could never
     * be hit and would silently fall through to the echo: an unknown member (for example
     * an {@code address1}, which the key ignores) and a value wider than its request field,
     * since a request can never hold more (for example a 38-character street, which
     * customer-api always sends cut to 30).
     */
    private static Key inputKey(Map<?, ?> input, String label) {
        rejectUnknownMembers(input, INPUT, INPUT_MEMBERS, label);
        final Key key = Key.of(
                text(input, ADDRESS2, label, INPUT),
                text(input, CITY, label, INPUT),
                text(input, STATE, label, INPUT),
                text(input, ZIP5, label, INPUT));
        requireRequestWidth(key.address2(), ADDRESS2, AddressValidationRequest.ADDRESS2_WIDTH, label);
        requireRequestWidth(key.city(), CITY, AddressValidationRequest.CITY_WIDTH, label);
        requireRequestWidth(key.state(), STATE, AddressValidationRequest.STATE_WIDTH, label);
        requireRequestWidth(key.zip5(), ZIP5, AddressValidationRequest.ZIP5_WIDTH, label);
        return key;
    }

    /**
     * Builds the standardized result of a fixture's {@code output}, values as written.
     */
    private static AddressValidationResult outputResult(Map<?, ?> output, String label) {
        rejectUnknownMembers(output, OUTPUT, OUTPUT_MEMBERS, label);
        final AddressValidationResult result;
        try {
            result = AddressValidationResult.success(
                    text(output, ADDRESS1, label, OUTPUT),
                    text(output, ADDRESS2, label, OUTPUT),
                    text(output, CITY, label, OUTPUT),
                    text(output, STATE, label, OUTPUT),
                    text(output, ZIP5, label, OUTPUT),
                    text(output, ZIP4, label, OUTPUT));
        } catch (IllegalArgumentException ex) {
            // The cause names the component and its width, never the value.
            throw new IllegalStateException(label + ": output exceeds a USAdrValDS field width", ex);
        }
        if (!result.standardized()) {
            throw new IllegalStateException(label + ": output.city is blank; a fixture must standardize");
        }
        return result;
    }

    /**
     * Reads the string member {@code name}; a missing or JSON {@code null} member reads
     * {@code ""}.
     *
     * @param part the enclosing object's name for messages, or {@code ""} for the fixture
     *             itself
     */
    private static String text(Map<?, ?> object, String name, String label, String part) {
        final Object value = object.get(name);
        if (value == null) {
            return "";
        }
        if (!(value instanceof String string)) {
            final String member = part.isEmpty() ? name : part + "." + name;
            throw new IllegalStateException(label + ": " + member + " must be a JSON string");
        }
        return string;
    }

    /**
     * Rejects any member of {@code object} outside {@code allowed}.
     */
    private static void rejectUnknownMembers(Map<?, ?> object, String part, Set<String> allowed, String label) {
        for (Object name : object.keySet()) {
            if (!allowed.contains(name)) {
                throw new IllegalStateException(label + ": " + part + " has unknown member " + name
                        + "; allowed are " + String.join(", ", allowed.stream().sorted().toList()));
            }
        }
    }

    /**
     * Rejects a normalized input value that no request could ever carry.
     */
    private static void requireRequestWidth(String value, String name, int width, String label) {
        if (value.codePointCount(0, value.length()) > width) {
            throw new IllegalStateException(label + ": input." + name + " exceeds " + width
                    + " characters, so no request can match it");
        }
    }

    // ------------------------------------------------------------------ normalization

    /**
     * Normalizes one request or fixture value for the lookup and the echo: strip, then
     * the uppercase rule.
     */
    private static String norm(String s) {
        return upper(s.strip());
    }

    /**
     * Applies the length-preserving uppercase rule described on the class. Returns the
     * argument itself when no code point changes.
     */
    private static String upper(String s) {
        StringBuilder out = null;
        int index = 0;
        while (index < s.length()) {
            final int cp = s.codePointAt(index);
            final int mapped = upperCodePoint(cp);
            if (out == null && mapped != cp) {
                out = new StringBuilder(s.length());
                out.append(s, 0, index);
            }
            if (out != null) {
                out.appendCodePoint(mapped);
            }
            index += Character.charCount(cp);
        }
        return out == null ? s : out.toString();
    }

    /**
     * Maps one code point: its full uppercase mapping under {@link Locale#ROOT} when that
     * mapping is exactly one code point, otherwise the code point itself. ASCII takes a
     * direct path with the same result.
     */
    private static int upperCodePoint(int cp) {
        if (cp < 0x80) {
            return cp >= 'a' && cp <= 'z' ? cp - ASCII_CASE_OFFSET : cp;
        }
        final String up = new String(Character.toChars(cp)).toUpperCase(Locale.ROOT);
        return up.codePointCount(0, up.length()) == 1 ? up.codePointAt(0) : cp;
    }

    /**
     * Lookup key: the normalized {@code address2}, {@code city}, {@code state} and
     * {@code zip5}. Its {@code toString} holds address values and must never be logged or
     * placed in a message.
     */
    private record Key(String address2, String city, String state, String zip5) {

        static Key of(String address2, String city, String state, String zip5) {
            return new Key(norm(address2), norm(city), norm(state), norm(zip5));
        }
    }
}
