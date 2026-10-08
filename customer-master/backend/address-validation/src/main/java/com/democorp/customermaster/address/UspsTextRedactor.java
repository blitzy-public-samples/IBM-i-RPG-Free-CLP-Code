package com.democorp.customermaster.address;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.StringJoiner;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Redacts text the USPS service sends back, such as an {@code Error} element's
 * {@code Number} or {@code Description}: the shared redactor for the log lines of
 * {@link UspsWebToolsAddressValidationClient} and for a USPS {@code Description} that
 * reaches a customer-facing message.
 *
 * <p>{@link #redact redact} replaces each echo of the outbound request that
 * {@link #redactRequest redactRequest} recognizes with a marker, then {@link #mask masks}
 * every configured credential; all other text is kept, so an ordinary USPS message passes
 * unchanged. The recognized echo forms are literal ones, listed at
 * {@link #redactRequest redactRequest}; the client's log projection adds the steps that
 * keep every other form of URL and undocumented text out of the log.
 *
 * <p>Immutable and thread-safe: it holds only the configured base URL and the credential
 * pattern derived from the settings it is built from, and must itself never be logged.
 */
public final class UspsTextRedactor {

    /** Replaces an echoed request document, in any of the forms {@link #REQUEST_DOCUMENT} matches. */
    static final String REQUEST_DOCUMENT_MARKER = "[request document]";

    /** Replaces an echoed Verify query, in any of the forms {@link #REQUEST_QUERY} matches. */
    static final String REQUEST_QUERY_MARKER = "[request query]";

    /** Replaces each occurrence of the configured base URL. */
    static final String REQUEST_URL_MARKER = "[request URL]";

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

    private final String baseUrl;

    /** The {@link #mask} pattern of every credential form; {@code null} when none is configured. */
    private final Pattern secrets;

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
     *   <li>the configured base URL, with {@value #REQUEST_URL_MARKER}, only as its literal
     *       configured string.</li>
     * </ol>
     * No other form is recognized: a percent-encoded {@code API%3DVerify} query and an
     * encoded base URL are kept as they are, as is every other character. Linear in the
     * text length.
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
        return redacted.replace(baseUrl, REQUEST_URL_MARKER);
    }

    /**
     * Replaces with {@value AddressValidationProperties#MASK} every stretch of {@code text}
     * that spells a {@link #secretForms credential form} with each of its code points either
     * written as itself or percent-encoded as its UTF-8 bytes, hex digits in either case; a
     * space also matches {@code +} and {@code %20}. Raw, wholly, partly and mixed-case
     * URL-encoded forms are therefore all masked, while every literal character is matched
     * case-sensitively, as credentials are. A doubly encoded form is not matched. The
     * longest form is tried first, so one credential contained in the other is still masked
     * whole. Linear in the text length for a given configuration: each code point is one
     * group of fixed-length alternatives, with no repetition.
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
        return secrets.matcher(text).replaceAll(Matcher.quoteReplacement(AddressValidationProperties.MASK));
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
     * Compiles the {@link #mask} pattern: an alternation over {@code forms}, in their order,
     * of each form's {@link #encodings encodings}.
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
