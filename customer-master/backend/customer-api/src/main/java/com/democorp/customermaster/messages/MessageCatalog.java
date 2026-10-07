package com.democorp.customermaster.messages;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.Serial;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The server's one source of message texts, keyed by message id.
 *
 * <p><b>What it replaces.</b> On the IBM i, the CL program {@code CRTMSGF} built the message file
 * {@code CUSTMSGF} from 17 {@code ADDMSGD} descriptions, and the {@code SndMsgPgmQ} procedure of the
 * {@code SRV_MSG} service program sent a message id plus its substitution data through the
 * {@code QMHSNDPM} API, which resolved the id against the message file and placed the resulting text in
 * the screen's message subfile. Here the message file becomes the classpath resource
 * {@value #LOCATION}, and the id-to-text resolution becomes {@link #text(String, Object...)}. The catalog
 * is data, not a queue: nothing is sent or cleared, so the {@code ClrMsgPgmQ} procedure has no
 * counterpart.
 *
 * <p><b>Contents.</b> The file holds the 17 CUSTMSGF ids with their texts ({@code &1} written as
 * {@code {0}}, and the message-text typo corrections already applied) plus the {@code APPnnnn} keys for
 * conditions the IBM i programs never reach. Texts are corrected in the file, never in code. This class
 * checks no key set: it loads whatever the file holds, so a corrected or added text needs no Java change.
 *
 * <p><b>Substitution rule.</b> {@code {n}}, where {@code n} is a run of ASCII digits, is replaced
 * literally by the {@code n}-th argument (0-based) rendered with {@link String#valueOf(Object)}. The
 * template is scanned once, left to right: a placeholder with no matching argument stays exactly as
 * written, and text inserted from an argument is never scanned again. Apostrophes, dollar signs,
 * backslashes and braces that do not form {@code {digits}} are ordinary characters, and there is no
 * escaping syntax. {@link java.text.MessageFormat} is deliberately not used: it treats an apostrophe as
 * a quote and would corrupt data such as {@code NIBH L'LOR COMPANY}. The browser's message catalog
 * provider applies this identical rule to the texts served by {@code GET /api/messages}, so a message
 * reads the same whichever side formats it.
 *
 * <p><b>Arguments are not trimmed.</b> {@code SndMsgPgmQ} trimmed trailing blanks from the message
 * data because RPG passed it in fixed-length fields. Callers here pass already-normalized values and
 * field labels, and the browser rule does not trim either, so trimming here would make the two sides
 * disagree.
 *
 * <p><b>Loading.</b> The file is read once, in the constructor, as strict UTF-8 (malformed or
 * unmappable bytes fail rather than turning into replacement characters), with the JDK properties
 * syntax: {@code #} and {@code !} comments, blank lines, {@code =}, {@code :} or whitespace separators,
 * backslash line continuations and {@code \}{@code uXXXX} escapes. A missing file, an unreadable file,
 * a malformed {@code \}{@code uXXXX} escape or a key that appears twice aborts application start-up
 * with {@link IllegalStateException}, whose message names the file; plain
 * {@link Properties} would silently keep the last of two duplicate keys and lose the file order.
 *
 * <p><b>Order.</b> {@link #all()} keeps the file order, so {@code GET /api/messages} lists the
 * messages as the file does.
 *
 * <p><b>Thread safety.</b> All state is built in the constructor and never changes afterwards; the
 * map is unmodifiable, so the bean is safe to share across request threads without locking.
 *
 * <p>Example, with the packaged catalog:
 * <pre>{@code
 * catalog.text("DEM0502", "Name");                // "Name: Must not be blank"
 * catalog.text("DEM9898", "Address Not Found.");  // "USPS: Address Not Found."
 * catalog.text("DEM0004");                        // "{0} is not a valid option at this time."
 * catalog.all().get("DEM1002");                   // "Someone else changed record. Review data."
 * }</pre>
 */
@Component
public final class MessageCatalog {

    /** Classpath location of the catalog file, relative to the classpath root (no leading slash). */
    public static final String LOCATION = "messages/messages.properties";

    /** Names the input of {@link #fromReader(Reader)} in error messages, which has no classpath location. */
    private static final String READER_SOURCE = "the supplied reader";

    /**
     * A placeholder: an opening brace, one or more ASCII digits, a closing brace. {@code \d} without
     * {@link Pattern#UNICODE_CHARACTER_CLASS} matches {@code [0-9]} only, as the browser's {@code \d} does.
     */
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{(\\d+)\\}");

    private static final Object[] NO_ARGS = new Object[0];

    private static final Logger log = LoggerFactory.getLogger(MessageCatalog.class);

    /** Message id to raw template, unmodifiable, in file order. */
    private final Map<String, String> messages;

    /**
     * Loads the packaged catalog from {@value #LOCATION}. This is the constructor Spring uses, and the
     * only public one, so the choice is never ambiguous.
     *
     * @throws IllegalStateException when the file is missing, cannot be read or decoded as UTF-8,
     *     holds a malformed {@code \}{@code uXXXX} escape, or contains a key twice
     */
    public MessageCatalog() {
        this(loadFromClasspath());
        log.info("Loaded {} message texts from {}", messages.size(), LOCATION);
    }

    /**
     * Wraps parsed entries.
     *
     * @param entries the parsed entries in file order; copied, so later changes to it have no effect
     */
    private MessageCatalog(Map<String, String> entries) {
        // A LinkedHashMap copy, not Map.copyOf: the latter does not keep the file order that
        // GET /api/messages must reproduce.
        this.messages = Collections.unmodifiableMap(new LinkedHashMap<>(entries));
    }

    /**
     * Builds a catalog from properties text supplied by the caller, parsed exactly as the packaged file
     * is: same syntax, same duplicate-key rejection, same order. It lets a test feed a small fixture
     * without touching the classpath file. The reader is consumed but not closed; the caller owns it.
     *
     * <pre>{@code
     * MessageCatalog.fromReader(new StringReader("B=two\nA=one {0}\n")).text("A", "x");  // "one x"
     * MessageCatalog.fromReader(new StringReader("A=1\nA=2\n"));  // IllegalStateException naming A
     * }</pre>
     *
     * @param source properties text; must not be {@code null}
     * @return a catalog holding the entries of {@code source} in their order
     * @throws NullPointerException when {@code source} is {@code null}
     * @throws IllegalStateException when the text cannot be read, holds a malformed
     *     {@code \}{@code uXXXX} escape, or contains a key twice
     */
    public static MessageCatalog fromReader(Reader source) {
        Objects.requireNonNull(source, "source");
        return new MessageCatalog(parse(source, READER_SOURCE));
    }

    /**
     * Returns the text of a message with its placeholders substituted.
     *
     * <p>Each {@code {n}} is replaced by {@code String.valueOf(args[n])} in one left-to-right pass. A
     * placeholder whose index has no argument (including one too large for an {@code int}) stays as
     * written; inserted argument text is never re-scanned, so an argument such as {@code "{1} $ \ '"}
     * appears verbatim. A {@code null} argument array is treated as no arguments, and a {@code null}
     * element renders as {@code "null"}.
     *
     * @param code the message id, for example {@code DEM0502}
     * @param args the substitution values for {@code {0}}, {@code {1}}, ...; may be empty or {@code null}
     * @return the formatted text, never {@code null}
     * @throws IllegalArgumentException when {@code code} is {@code null} or not in the catalog
     */
    public String text(String code, Object... args) {
        String template = code == null ? null : messages.get(code);
        if (template == null) {
            throw new IllegalArgumentException("Unknown message code: " + code);
        }
        return substitute(template, args == null ? NO_ARGS : args);
    }

    /**
     * Returns every message as raw templates, placeholders unsubstituted, in file order. The same
     * unmodifiable map is returned on every call; any attempt to change it throws
     * {@link UnsupportedOperationException}.
     *
     * @return message id to template, in file order
     */
    public Map<String, String> all() {
        return messages;
    }

    /**
     * Applies the substitution rule to one template.
     *
     * @param template the raw catalog text
     * @param args the substitution values, never {@code null}
     * @return the template with each matched placeholder replaced
     */
    private static String substitute(String template, Object[] args) {
        Matcher matcher = PLACEHOLDER.matcher(template);
        if (!matcher.find()) {
            return template;
        }
        StringBuilder out = new StringBuilder(template.length() + 32);
        int copiedTo = 0;
        do {
            int index = argumentIndex(matcher.group(1));
            if (index < args.length) {
                // Copy the literal text before the placeholder, then the argument. Appending directly,
                // rather than through Matcher.appendReplacement, means '$' and '\' in an argument are
                // never read as group references or escapes.
                out.append(template, copiedTo, matcher.start());
                out.append(String.valueOf(args[index]));
                copiedTo = matcher.end();
            }
            // With no matching argument the placeholder is left in the uncopied span, so it is
            // emitted as written by the next copy.
        } while (matcher.find());
        out.append(template, copiedTo, template.length());
        return out.toString();
    }

    /**
     * Converts the digits of a placeholder to an argument index.
     *
     * @param digits one or more ASCII digits
     * @return the index, or {@link Integer#MAX_VALUE} when it does not fit an {@code int}, which no
     *     argument array can reach, so the placeholder is kept as written
     */
    private static int argumentIndex(String digits) {
        try {
            return Integer.parseInt(digits);
        } catch (NumberFormatException tooLarge) {
            return Integer.MAX_VALUE;
        }
    }

    /**
     * Reads and parses the packaged catalog file.
     *
     * @return the entries in file order
     * @throws IllegalStateException when the file is missing, unreadable, mis-encoded, has a malformed
     *     {@code \}{@code uXXXX} escape or has a duplicate key
     */
    private static Map<String, String> loadFromClasspath() {
        ClassLoader loader = Objects.requireNonNullElseGet(
                MessageCatalog.class.getClassLoader(), ClassLoader::getSystemClassLoader);
        try (InputStream in = loader.getResourceAsStream(LOCATION)) {
            if (in == null) {
                throw new IllegalStateException("Message catalog not found on classpath: " + LOCATION);
            }
            // A decoder that reports malformed or unmappable input, instead of the replacing decoder an
            // InputStreamReader built from a Charset would use; the failure surfaces as a
            // CharacterCodingException, an IOException, while the file is read.
            CharsetDecoder strictUtf8 = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT);
            try (Reader reader = new InputStreamReader(in, strictUtf8)) {
                return parse(reader, LOCATION);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Message catalog " + LOCATION + " could not be read", e);
        }
    }

    /**
     * Parses properties text, rejecting duplicate keys and keeping the file order.
     *
     * @param reader the text to parse; not closed here
     * @param sourceName the location named in error messages
     * @return the entries in file order
     * @throws IllegalStateException when the text cannot be read, holds a malformed
     *     {@code \}{@code uXXXX} escape, or contains a key twice; each message names {@code sourceName}
     */
    private static Map<String, String> parse(Reader reader, String sourceName) {
        DuplicateRejectingProperties properties = new DuplicateRejectingProperties(sourceName);
        try {
            properties.load(reader);
        } catch (IOException e) {
            throw new IllegalStateException("Message catalog " + sourceName + " could not be read", e);
        } catch (IllegalArgumentException e) {
            // Properties.load reports a malformed backslash-u escape this way. The duplicate-key
            // IllegalStateException is not a subtype, so it still passes through unchanged.
            throw new IllegalStateException(
                    "Message catalog " + sourceName + " is malformed: " + e.getMessage(), e);
        }
        return properties.entriesInOrder();
    }

    /**
     * A {@link Properties} that records each entry in arrival order and rejects a key that arrives a
     * second time.
     *
     * <p>{@link Properties#load(Reader)} stores every parsed entry through {@link #put(Object, Object)},
     * once per entry and in file order, so overriding {@code put} gives both duplicate detection and the
     * file order while the JDK keeps handling the file syntax. The order is read from {@link #ordered},
     * never from iterating the {@code Properties} itself, whose backing map is unordered. The duplicate
     * exception is unchecked, so it passes through {@code load} unchanged.
     *
     * <p>Instances are confined to one {@code parse} call and never shared or serialized.
     */
    private static final class DuplicateRejectingProperties extends Properties {

        @Serial
        private static final long serialVersionUID = 1L;

        /** Location named in the duplicate-key message. */
        private final String sourceName;

        /** Entries in arrival order; parse-time state only, so excluded from serialization. */
        private final transient Map<String, String> ordered = new LinkedHashMap<>();

        DuplicateRejectingProperties(String sourceName) {
            this.sourceName = sourceName;
        }

        @Override
        public synchronized Object put(Object key, Object value) {
            String name = (String) key;
            if (ordered.containsKey(name)) {
                throw new IllegalStateException("Duplicate message key '" + name + "' in " + sourceName);
            }
            ordered.put(name, (String) value);
            return super.put(key, value);
        }

        /**
         * Returns the recorded entries.
         *
         * @return the live map of entries in arrival order; the caller copies it
         */
        Map<String, String> entriesInOrder() {
            return ordered;
        }
    }
}
