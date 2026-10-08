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
 * The server's one source of message texts, keyed by message id. The classpath resource
 * {@value #LOCATION} replaces the {@code CUSTMSGF} message file built by {@code CRTMSGF}, and
 * {@link #text(String, Object...)} resolves an id to its text in place of the {@code SndMsgPgmQ}
 * procedure of {@code SRV_MSG}. The file holds the {@code CUSTMSGF} ids ({@code &1} written as
 * {@code {0}}, message-text typo corrections applied) plus the {@code APPnnnn} keys. Texts are corrected
 * in the file, never in code, and this class checks no key set.
 *
 * <p><b>Substitution rule.</b> Each {@code {n}}, where {@code n} is a run of ASCII digits, is replaced
 * literally by the {@code n}-th argument (0-based, rendered with {@link String#valueOf(Object)}) in one
 * left-to-right pass, with no escaping syntax; {@link #text(String, Object...)} states the details.
 * {@link java.text.MessageFormat} is deliberately not used: it treats an apostrophe as a quote and
 * would corrupt data such as {@code NIBH L'LOR COMPANY}. The browser applies the identical rule to the
 * texts served by {@code GET /api/messages}. Arguments are not trimmed: {@code SndMsgPgmQ} trims
 * trailing blanks from its fixed-length message data, but callers of this class pass normalized values
 * and the browser does not trim, so trimming would make the two sides disagree.
 *
 * <p><b>Loading and order.</b> The file is read once, in the constructor, as strict UTF-8 (malformed or
 * unmappable bytes fail rather than becoming replacement characters) with the JDK properties syntax. A
 * missing or unreadable file, a malformed {@code \}{@code uXXXX} escape or a duplicate key aborts
 * start-up with {@link IllegalStateException} naming the file, because plain {@link Properties} would
 * silently keep the last duplicate and lose the file order. {@link #all()} keeps the file order, which
 * {@code GET /api/messages} reproduces.
 *
 * <p><b>Thread safety.</b> All state is built in the constructor and the map is unmodifiable, so the
 * bean is safe to share across request threads.
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
