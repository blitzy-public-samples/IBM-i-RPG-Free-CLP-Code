package com.democorp.customermaster.address;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.StringJoiner;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Redacts text the USPS service sends back, such as an {@code Error} element's
 * {@code Number} or {@code Description}: the shared redactor of the log lines of
 * {@link UspsWebToolsAddressValidationClient}, which redact a fault reason through
 * {@link #redact redact} and a {@code Number} by the rule of
 * {@link #redactDescription redactDescription}, and of a USPS {@code Description}, which
 * a customer-facing message and those log lines alike take from
 * {@link #redactDescription redactDescription}.
 *
 * <p>{@link #redact redact} replaces each echo of the outbound request that
 * {@link #redactRequest redactRequest} recognizes with a marker, then {@link #mask masks}
 * every configured credential; all other text is kept, so an ordinary USPS message passes
 * unchanged. The recognized echo forms, listed at {@link #redactRequest redactRequest}, are
 * the literal and entity-escaped ones and each percent-encoding of them, to any depth: a
 * token still encoded after {@value #MAX_DECODE_LAYERS} decodings is withheld whole, since
 * what it hides cannot be checked. The client's log lines add the steps that replace the
 * values a call submitted and every other URL with markers.
 * {@link #redactDescription redactDescription} returns what {@code redact} returns unless
 * a credential shorter than {@value #SHORT_CREDENTIAL_CODE_POINTS} code points occurs in
 * the description; it then shows a documented USPS description as sent and withholds any
 * other whole, because masking a short credential in place garbles the text and reveals
 * the credential.
 *
 * <p>Immutable and thread-safe: it holds only the configured base URL and the credential
 * patterns derived from the settings it is built from, and must itself never be logged.
 */
public final class UspsTextRedactor {

    /**
     * Replaces an echoed request document, in any of the forms {@link #REQUEST_DOCUMENT}
     * matches, and, whole, an encoded token that echoes one.
     */
    static final String REQUEST_DOCUMENT_MARKER = "[request document]";

    /**
     * Replaces an echoed Verify query, in any of the forms {@link #REQUEST_QUERY} matches,
     * and, whole, an encoded token that echoes one.
     */
    static final String REQUEST_QUERY_MARKER = "[request query]";

    /**
     * Replaces each occurrence of the configured base URL and, whole, an encoded token that
     * echoes it.
     */
    static final String REQUEST_URL_MARKER = "[request URL]";

    /**
     * Replaces, whole, a token holding {@code %} that is still percent-encoded after
     * {@value #MAX_DECODE_LAYERS} decodings: what it hides cannot be checked, so it is
     * withheld.
     */
    static final String ENCODED_TEXT_MARKER = "[encoded text]";

    /**
     * The most percent-decodings {@link #decodedLayers decodedLayers} applies to one token.
     * An echoed request URL, encoded once more by the service that echoes it, carries the
     * request document and its credentials encoded twice; four layers reach them through two
     * further encodings. A token still encoded after four is replaced whole by
     * {@value #ENCODED_TEXT_MARKER}, so no depth of encoding lets an echo or a credential
     * through, and the bound keeps the work on each token a fixed multiple of its length.
     */
    static final int MAX_DECODE_LAYERS = 4;

    /**
     * A credential shorter than this many Unicode code points is short: it occurs by chance
     * in ordinary words, so {@link #redactDescription redactDescription} withholds a
     * description that holds it rather than masking it in place.
     */
    static final int SHORT_CREDENTIAL_CODE_POINTS = 4;

    /** Replaces, whole, a description that {@link #redactDescription redactDescription} withholds. */
    public static final String DESCRIPTION_WITHHELD_MARKER = "[description withheld]";

    /**
     * An echoed request document, which carries the credentials and the customer address:
     * decoded, from {@code <AddressValidateRequest} to the next
     * {@code </AddressValidateRequest>} inclusive, or to the end of the text when unclosed;
     * the same in its {@code &lt;} entity form, closed by
     * {@code &lt;/AddressValidateRequest&gt;}; or URL-encoded, from
     * {@code %3CAddressValidateRequest} (hex digits in either case) to the next whitespace,
     * a span no encoded document breaks. Each form ends only at a closing tag of its own
     * form, which the values inside it, escaped once more, can never spell. Linear in the
     * text length: each lazy span tests one fixed-length closing tag at each character and
     * never backtracks into itself, and the encoded span is one run of non-whitespace.
     */
    private static final Pattern REQUEST_DOCUMENT = Pattern.compile(
            "<AddressValidateRequest.*?(?:</AddressValidateRequest>|\\z)"
                    + "|&lt;AddressValidateRequest.*?(?:&lt;/AddressValidateRequest&gt;|\\z)"
                    + "|%3[Cc]AddressValidateRequest\\S*",
            Pattern.DOTALL);

    /**
     * An echoed Verify query, its separator written as {@code &} or {@code &amp;}, from the
     * literal {@code API=Verify} to the next whitespace, so it spans the {@code XML} value
     * in any percent-encoding. A {@link #REQUEST_DOCUMENT_MARKER} the document step left
     * right after {@code XML=} belongs to the query and is taken whole, although it holds a
     * blank. Linear: fixed literals followed by one run of non-whitespace.
     */
    private static final Pattern REQUEST_QUERY = Pattern.compile(
            "API=Verify(?:&amp;|&)XML=(?:" + Pattern.quote(REQUEST_DOCUMENT_MARKER) + ")?\\S*");

    /**
     * The request document's element name, which a decoded layer of an encoded token holds
     * when the token echoes the document, whatever form its opening {@code <} takes.
     */
    private static final String REQUEST_ELEMENT = "AddressValidateRequest";

    /** The Verify query's start, its separator written as {@code &} or {@code &amp;}. */
    private static final List<String> VERIFY_QUERY_STARTS = List.of("API=Verify&XML=", "API=Verify&amp;XML=");

    /**
     * The markers that hold a blank. {@link #replaceEncodedTokens replaceEncodedTokens} reads
     * each as part of the token it adjoins, so a marker an earlier step left inside an
     * encoded token is replaced together with that token, never split from it.
     */
    private static final List<String> BLANK_MARKERS = List.of(REQUEST_DOCUMENT_MARKER, REQUEST_QUERY_MARKER,
            REQUEST_URL_MARKER, ENCODED_TEXT_MARKER, DESCRIPTION_WITHHELD_MARKER);

    private final String baseUrl;

    /** The {@link #mask} pattern of every credential form; {@code null} when none is configured. */
    private final Pattern secrets;

    /**
     * The {@link #mask}-style pattern of every form of the {@link #shortCredentials short}
     * credentials alone; {@code null} when none is short.
     */
    private final Pattern shortSecrets;

    /**
     * Creates the redactor for the given settings.
     *
     * @param usps the USPS settings, already normalized and validated by their constructor;
     *             the base URL and both credentials are used
     * @throws NullPointerException if {@code usps} is {@code null}
     */
    public UspsTextRedactor(AddressValidationProperties.Usps usps) {
        Objects.requireNonNull(usps, "usps");
        this.baseUrl = usps.baseUrl();
        this.secrets = secretPattern(secretForms(usps.userId(), usps.password()));
        this.shortSecrets = secretPattern(secretForms(shortCredentials(usps.userId(), usps.password())));
    }

    /**
     * Returns {@code text} with every recognized echo of the request
     * {@link #redactRequest replaced} by its marker, then every configured credential
     * {@link #mask masked}. The request step runs first, so a document or query is
     * replaced whole, credentials and customer address included.
     *
     * @param text text the USPS service sent; {@code null} reads as {@code ""}
     * @return the redacted text
     */
    public String redact(String text) {
        return mask(redactRequest(text));
    }

    /**
     * Returns a USPS {@code Description} as a customer-facing message may show it. Every
     * recognized echo of the request is first {@link #redactRequest replaced} by its marker.
     * When no {@link #shortCredentials short} credential, one shorter than
     * {@value #SHORT_CREDENTIAL_CODE_POINTS} code points, then occurs in any form
     * {@link #mask mask} matches, the result is masked and returned, exactly as
     * {@link #redact redact} returns it. Otherwise the description is:
     * <ul>
     *   <li>returned as it stands when it is one of the
     *       {@link UspsWebToolsAddressValidationClient#DOCUMENTED_DESCRIPTIONS documented}
     *       USPS texts, such as {@code Address Not Found.} or {@code Invalid City.}: a fixed
     *       text echoes nothing, so a short credential found in it is coincidence and
     *       showing it reveals nothing;</li>
     *   <li>replaced whole by {@value #DESCRIPTION_WITHHELD_MARKER} in every other case.</li>
     * </ul>
     *
     * <p>The reason: a short credential occurs by chance in ordinary words. Masking each
     * occurrence garbles the text, so that {@code Address Not Found.} with the password
     * {@code s} would read {@code Addre******** Not Found.}, and the text left around each
     * mask reveals the credential. The client's log lines therefore carry a
     * {@code Description} and a {@code Number} each as this method returns it, so a
     * {@code Number} in which a short credential occurs logs as
     * {@value #DESCRIPTION_WITHHELD_MARKER}, while their fault reasons keep masking every
     * occurrence through {@link #redact redact} and {@link #mask mask}.
     *
     * @param description the {@code Description} the USPS service sent; {@code null} reads
     *                    as {@code ""}
     * @return the masked description, a documented description as sent, or
     *         {@value #DESCRIPTION_WITHHELD_MARKER}
     */
    public String redactDescription(String description) {
        String redacted = redactRequest(description);
        if (shortSecrets == null || !shortSecrets.matcher(redacted).find()) {
            return mask(redacted);
        }
        if (UspsWebToolsAddressValidationClient.DOCUMENTED_DESCRIPTIONS.contains(redacted)) {
            return redacted;
        }
        return DESCRIPTION_WITHHELD_MARKER;
    }

    /**
     * Replaces every echo of the outbound request in {@code text}, in this order:
     * <ol>
     *   <li>a request document, with {@value #REQUEST_DOCUMENT_MARKER}: decoded, from
     *       {@code <AddressValidateRequest} to its closing tag or the end of the text; the
     *       same in its {@code &lt;} entity form; or URL-encoded, from
     *       {@code %3CAddressValidateRequest} (hex digits in either case) to the next
     *       whitespace;</li>
     *   <li>a Verify query, with {@value #REQUEST_QUERY_MARKER}: the literal text
     *       {@code API=Verify}, then {@code &} or {@code &amp;}, then {@code XML=}, up to the
     *       next whitespace;</li>
     *   <li>the configured base URL, with {@value #REQUEST_URL_MARKER}, as its literal
     *       configured string;</li>
     *   <li>each {@link #replaceEncodedTokens token} still holding {@code %}, whole, when the
     *       token itself or one of its {@link #decodedLayers decoded layers} holds an echo:
     *       with {@value #REQUEST_DOCUMENT_MARKER} when one holds
     *       {@code AddressValidateRequest}, whatever form its opening {@code <} takes; else
     *       with {@value #REQUEST_QUERY_MARKER} when one holds {@code API=Verify&XML=} or
     *       {@code API=Verify&amp;XML=}; else with {@value #REQUEST_URL_MARKER} when one
     *       holds the configured base URL; else with {@value #ENCODED_TEXT_MARKER} when the
     *       token is still encoded after {@value #MAX_DECODE_LAYERS} decodings.</li>
     * </ol>
     * Every other token and character is kept, so text the first three steps leave without
     * a {@code %}, and a token such as {@code 100%} or {@code %41BC} that decodes to no
     * echo, read as before. Linear in the text length.
     *
     * <p>Package-private so that the tests can check every form it covers.
     *
     * @param text text the USPS service sent; {@code null} reads as {@code ""}
     * @return the text with every recognized echo of the request replaced by its marker
     */
    String redactRequest(String text) {
        if (text == null) {
            return "";
        }
        String redacted = REQUEST_DOCUMENT.matcher(text)
                .replaceAll(Matcher.quoteReplacement(REQUEST_DOCUMENT_MARKER));
        redacted = REQUEST_QUERY.matcher(redacted)
                .replaceAll(Matcher.quoteReplacement(REQUEST_QUERY_MARKER));
        redacted = redacted.replace(baseUrl, REQUEST_URL_MARKER);
        return replaceEncodedTokens(redacted, this::encodedEcho);
    }

    /**
     * The replacement {@link #redactRequest redactRequest} gives a token holding {@code %}:
     * the marker of the first echo the token or one of its decoded layers holds, in the order
     * document, query, base URL; else {@value #ENCODED_TEXT_MARKER} when it is still encoded
     * after {@value #MAX_DECODE_LAYERS} decodings; else the token itself.
     *
     * @param token a token holding {@code %}; never {@code null}
     * @return the marker, or {@code token}
     */
    private String encodedEcho(String token) {
        DecodedLayers decoded = decodedLayers(token);
        List<String> layers = new ArrayList<>(decoded.layers().size() + 1);
        layers.add(token);
        layers.addAll(decoded.layers());
        if (layers.stream().anyMatch(layer -> layer.contains(REQUEST_ELEMENT))) {
            return REQUEST_DOCUMENT_MARKER;
        }
        if (layers.stream().anyMatch(layer -> VERIFY_QUERY_STARTS.stream().anyMatch(layer::contains))) {
            return REQUEST_QUERY_MARKER;
        }
        if (layers.stream().anyMatch(layer -> layer.contains(baseUrl))) {
            return REQUEST_URL_MARKER;
        }
        return decoded.stillEncoded() ? ENCODED_TEXT_MARKER : token;
    }

    /**
     * Replaces with {@value AddressValidationProperties#MASK} every stretch of {@code text}
     * that spells a {@link #secretForms credential form} with each of its code points either
     * written as itself or percent-encoded as its UTF-8 bytes, hex digits in either case; a
     * space also matches {@code +} and {@code %20}. Raw, wholly, partly and mixed-case
     * URL-encoded forms are therefore all masked in place, while every literal character is
     * matched case-sensitively, as credentials are. The longest form is tried first, so one
     * credential contained in the other is still masked whole. Then each
     * {@link #replaceEncodedTokens token} still holding {@code %} becomes
     * {@value AddressValidationProperties#MASK} whole when one of its
     * {@link #decodedLayers decoded layers} holds such a stretch, so a form percent-encoded
     * again, up to {@value #MAX_DECODE_LAYERS} more times, is masked too, as an encoded echo
     * of the request URL carries it. Linear in the text length for a given
     * configuration: each code point is one group of fixed-length alternatives, with no
     * repetition, and each token is decoded a bounded number of times.
     *
     * <p>Package-private so that the tests can check every credential form it covers.
     *
     * @param text text about to be logged or thrown; {@code null} reads as {@code ""}
     * @return the masked text
     */
    String mask(String text) {
        if (text == null) {
            return "";
        }
        if (secrets == null) {
            return text;
        }
        String masked = secrets.matcher(text)
                .replaceAll(Matcher.quoteReplacement(AddressValidationProperties.MASK));
        return replaceEncodedTokens(masked, token -> decodedLayers(token).layers().stream()
                .anyMatch(layer -> secrets.matcher(layer).find()) ? AddressValidationProperties.MASK : token);
    }

    /**
     * Replaces each token of {@code text} that holds a {@code %} with what
     * {@code replacement} returns for it, and keeps every other character. A token is a
     * maximal run of characters other than the six whitespace characters {@code \s}
     * matches (blank, tab, line feed, line tabulation, form feed and carriage return), in
     * which each {@link #BLANK_MARKERS marker that holds a blank} counts as one character,
     * so a token never ends inside a marker. A simple scan, linear in the text length when
     * {@code replacement} is linear in the token's.
     *
     * <p>Package-private so that the client's log lines can replace an encoded URL by token
     * as this class replaces an encoded echo.
     *
     * @param text        the text to scan; never {@code null}
     * @param replacement maps a token holding {@code %} to its replacement, the token
     *                    itself to keep it
     * @return the text with each such token replaced
     */
    static String replaceEncodedTokens(String text, UnaryOperator<String> replacement) {
        if (text.indexOf('%') < 0) {
            return text;
        }
        StringBuilder replaced = new StringBuilder(text.length());
        int length = text.length();
        int index = 0;
        while (index < length) {
            if (isTokenBoundary(text.charAt(index))) {
                replaced.append(text.charAt(index));
                index++;
                continue;
            }
            int start = index;
            boolean encoded = false;
            while (index < length && !isTokenBoundary(text.charAt(index))) {
                int marker = blankMarkerLength(text, index);
                if (marker > 0) {
                    index += marker;
                } else {
                    encoded |= text.charAt(index) == '%';
                    index++;
                }
            }
            String token = text.substring(start, index);
            replaced.append(encoded ? replacement.apply(token) : token);
        }
        return replaced.toString();
    }

    /**
     * Whether {@code character} ends a {@link #replaceEncodedTokens token}: one of the six
     * whitespace characters {@code \s} matches.
     */
    private static boolean isTokenBoundary(char character) {
        return character == ' ' || character == '\t' || character == '\n'
                || character == '\u000B' || character == '\f' || character == '\r';
    }

    /**
     * The length of the {@link #BLANK_MARKERS marker that holds a blank} starting at
     * {@code index}, or 0 when none starts there.
     */
    private static int blankMarkerLength(String text, int index) {
        if (text.charAt(index) != '[') {
            return 0;
        }
        for (String marker : BLANK_MARKERS) {
            if (text.startsWith(marker, index)) {
                return marker.length();
            }
        }
        return 0;
    }

    /**
     * The percent-decoded layers of a token, as {@link #decodedLayers decodedLayers} returns
     * them.
     *
     * @param layers       the token decoded once, twice and so on, each layer differing from
     *                     the one before; at most {@value #MAX_DECODE_LAYERS}, and empty when
     *                     the token holds no escape
     * @param stillEncoded whether the last layer still holds an escape, so one more decoding
     *                     would change it; always {@code false} when fewer than
     *                     {@value #MAX_DECODE_LAYERS} layers exist
     */
    record DecodedLayers(List<String> layers, boolean stillEncoded) {

        /**
         * Copies {@code layers}, so the record stays immutable.
         *
         * @throws NullPointerException if {@code layers} or one of its elements is {@code null}
         */
        DecodedLayers {
            layers = List.copyOf(layers);
        }
    }

    /**
     * Decodes {@code token} with {@link #percentDecode percentDecode} until a decoding
     * changes nothing or {@value #MAX_DECODE_LAYERS} layers exist, and reports whether the
     * last of them would still change. Linear in the token length: each layer is shorter
     * than the one before.
     *
     * @param token the text to decode; never {@code null}
     * @return the decoded layers
     */
    static DecodedLayers decodedLayers(String token) {
        List<String> layers = new ArrayList<>(MAX_DECODE_LAYERS);
        String current = token;
        while (layers.size() < MAX_DECODE_LAYERS) {
            if (firstEscape(current) < 0) {
                return new DecodedLayers(layers, false);
            }
            current = percentDecode(current);
            layers.add(current);
        }
        return new DecodedLayers(layers, firstEscape(current) >= 0);
    }

    /**
     * Percent-decodes {@code text} leniently: each run of escapes, a {@code %} followed by
     * two hex digits in either case, becomes the UTF-8 text of its octets, with U+FFFD in
     * place of each malformed sequence; every other character is kept as it stands, a
     * {@code %} that starts no escape included, and so is {@code +}, which {@link #mask mask}
     * already matches as a space. Linear in the text length.
     *
     * @param text the text to decode; never {@code null}
     * @return the decoded text, {@code text} itself when it holds no escape
     */
    static String percentDecode(String text) {
        int index = firstEscape(text);
        if (index < 0) {
            return text;
        }
        StringBuilder decoded = new StringBuilder(text.length());
        decoded.append(text, 0, index);
        byte[] octets = new byte[(text.length() - index) / 3];
        while (index < text.length()) {
            if (!isEscape(text, index)) {
                decoded.append(text.charAt(index));
                index++;
                continue;
            }
            int count = 0;
            while (index < text.length() && isEscape(text, index)) {
                octets[count++] = (byte) (hexValue(text.charAt(index + 1)) << 4 | hexValue(text.charAt(index + 2)));
                index += 3;
            }
            decoded.append(new String(octets, 0, count, StandardCharsets.UTF_8));
        }
        return decoded.toString();
    }

    /** The index of the first escape in {@code text}, or -1 when it holds none. */
    private static int firstEscape(String text) {
        for (int index = text.indexOf('%'); index >= 0; index = text.indexOf('%', index + 1)) {
            if (isEscape(text, index)) {
                return index;
            }
        }
        return -1;
    }

    /** Whether an escape, {@code %} and two hex digits in either case, starts at {@code index}. */
    private static boolean isEscape(String text, int index) {
        return text.charAt(index) == '%' && index + 2 < text.length()
                && hexValue(text.charAt(index + 1)) >= 0 && hexValue(text.charAt(index + 2)) >= 0;
    }

    /**
     * The value of an ASCII hex digit in either case, or -1 for any other character, a
     * non-ASCII digit included.
     */
    private static int hexValue(char character) {
        if (character >= '0' && character <= '9') {
            return character - '0';
        }
        if (character >= 'a' && character <= 'f') {
            return character - 'a' + 10;
        }
        if (character >= 'A' && character <= 'F') {
            return character - 'A' + 10;
        }
        return -1;
    }

    /**
     * The short credentials: those not blank and shorter than
     * {@value #SHORT_CREDENTIAL_CODE_POINTS} Unicode code points, each surrogate pair
     * counting once, so a password of two emoji is short.
     *
     * @param credentials the configured credentials; a {@code null} one is skipped
     * @return the short credentials, in their given order; empty when none is short
     */
    private static String[] shortCredentials(String... credentials) {
        List<String> shortOnes = new ArrayList<>();
        for (String credential : credentials) {
            if (credential == null || credential.isBlank()) {
                continue;
            }
            if (credential.codePointCount(0, credential.length()) < SHORT_CREDENTIAL_CODE_POINTS) {
                shortOnes.add(credential);
            }
        }
        return shortOnes.toArray(String[]::new);
    }

    /**
     * The forms in which a credential can appear in text before any URL encoding, which
     * {@link #mask} matches in every percent-encoding:
     * <ul>
     *   <li>raw, as configured;</li>
     *   <li>the request-wire form, the attribute text {@link UspsXmlCodec#attributeValue}
     *       returns, which is what the request document carries and which leaves an
     *       apostrophe literal ({@code p'&q} travels as {@code p'&amp;q});</li>
     *   <li>the full-entity form of {@link #xmlEscape xmlEscape}, which also encodes the
     *       apostrophe ({@code p&apos;&amp;q}), as other XML serializers write it.</li>
     * </ul>
     * Blank credentials yield no form. Duplicates are dropped, and the forms are sorted
     * longest first.
     *
     * @param credentials the configured credentials; a {@code null} one is skipped
     * @return the forms, longest first; empty when every credential is blank
     */
    private static List<String> secretForms(String... credentials) {
        Set<String> forms = new LinkedHashSet<>();
        for (String credential : credentials) {
            if (credential == null || credential.isBlank()) {
                continue;
            }
            forms.add(credential);
            forms.add(UspsXmlCodec.attributeValue(credential));
            forms.add(xmlEscape(credential));
        }
        List<String> sorted = new ArrayList<>(forms);
        sorted.sort(Comparator.comparingInt(String::length).reversed());
        return List.copyOf(sorted);
    }

    /**
     * Compiles a {@link #mask} pattern, of every credential or of the short ones alone: an
     * alternation over {@code forms}, in their order, of each form's
     * {@link #encodings encodings}.
     *
     * @param forms the credential forms, longest first
     * @return the pattern, or {@code null} when {@code forms} is empty
     */
    private static Pattern secretPattern(List<String> forms) {
        if (forms.isEmpty()) {
            return null;
        }
        StringJoiner alternation = new StringJoiner("|");
        for (String form : forms) {
            alternation.add(encodings(form));
        }
        return Pattern.compile(alternation.toString());
    }

    /**
     * The regular expression of {@code form} in which each code point is one group that
     * matches the code point itself or its UTF-8 bytes percent-encoded, each hex digit in
     * either case, and for a space also {@code +}: {@code ;} becomes
     * {@code (?:\Q;\E|%3[Bb])} and {@code é} becomes {@code (?:\Qé\E|%[Cc]3%[Aa]9)}.
     *
     * @param form a credential form; never {@code null}
     * @return the regular expression, which holds no quantifier
     */
    private static String encodings(String form) {
        StringBuilder regex = new StringBuilder();
        form.codePoints().forEach(codePoint -> {
            String character = Character.toString(codePoint);
            regex.append("(?:").append(Pattern.quote(character));
            if (codePoint == ' ') {
                regex.append("|\\+");
            }
            regex.append('|');
            for (byte octet : character.getBytes(StandardCharsets.UTF_8)) {
                regex.append('%').append(hexDigit((octet >> 4) & 0xF)).append(hexDigit(octet & 0xF));
            }
            regex.append(')');
        });
        return regex.toString();
    }

    /**
     * One hex digit as a regular expression: a decimal digit as itself, a letter in either case.
     *
     * @param value the digit's value, 0 to 15
     * @return the digit, or a two-letter class such as {@code [Bb]}
     */
    private static String hexDigit(int value) {
        char lower = Character.forDigit(value, 16);
        return value < 10 ? String.valueOf(lower) : "[" + Character.toUpperCase(lower) + lower + "]";
    }

    /**
     * Escapes every character an XML attribute value can carry as a predefined entity,
     * the apostrophe included. This is the full-entity form other XML serializers produce;
     * the request document's own form comes from {@link UspsXmlCodec#attributeValue}.
     *
     * @param value the text to escape; never {@code null}
     * @return the escaped text
     */
    static String xmlEscape(String value) {
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }
}
