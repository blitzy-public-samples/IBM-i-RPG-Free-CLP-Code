package com.democorp.customermaster.generator;

import com.democorp.customermaster.domain.TextNormalizer;
import com.democorp.customermaster.service.StateService;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Reads the city/state/ZIP (CSZ) file the test-data generator draws every customer's city, state and
 * ZIP from.
 *
 * <p><b>What it replaces.</b> LOADCUSTR's two statements over table {@code CSZ}, a count and the cursor
 * {@code csz_cur} fetched into {@code csz_a} [5250_Subfile/LOADCUSTR.SQLRPGLE:101-129], with its
 * {@code csz} record [5250_Subfile/LOADCUSTR.SQLRPGLE:38-43], become one pass of {@link #load(String)}
 * over a CSV file. The table was an upload of the unitedstateszipcodes.org ZIP database
 * [5250_Subfile/README.md:86-107], and that download is accepted unedited.
 *
 * <p><b>Locations.</b> A {@code classpath:} location, such as the default bundled sample
 * {@code classpath:generator/csz-sample.csv}, is opened through the thread context class loader, then
 * this class's own. Anything else is a filesystem path with an optional {@code file:} prefix, such as
 * the Compose mount {@code /data/csz.csv}, and is never looked up on the class path.
 *
 * <p><b>File format.</b> UTF-8 CSV per RFC 4180, in which a {@code "} inside an unquoted field makes the
 * record malformed. Lines end in {@code \n}, {@code \r\n} or {@code \r}, a leading byte-order mark is
 * ignored, blank lines are skipped, and the first non-blank record is the header. Header names match
 * case-insensitively after trimming: {@code zip}, {@code state}, and {@code city} or its alias
 * {@code primary_city} are required ({@code city} wins when both are present), {@code type} is
 * optional, every other column is ignored, and a repeated name uses its first column.
 *
 * <p><b>Row rules,</b> applied to every data record in file order:
 * <ol>
 *   <li>{@code zip}, trimmed, must be a non-negative integer that fits the source's {@code int(10)};
 *       {@code 00501} reads as 501. Anything else fails the whole load.</li>
 *   <li>The city, trimmed, is kept only when it is at most {@value #CITY_MAX_LENGTH} code points, the
 *       source's {@code length(trim(city)) <= 20}. A blank city is kept, as in the source. The kept
 *       city is uppercased by {@link TextNormalizer#filter(String)}, the source's
 *       {@code upper(city)}.</li>
 *   <li>The state, trimmed (the source's {@code trim(state)}, not uppercased), is kept only when
 *       {@link StateService#exists(String)} knows it. This rule is new: the {@code custmast_state_fk}
 *       foreign key would reject any other row. {@code exists} matches case-insensitively;
 *       {@code CustomerDataGenerator} normalizes the state it writes.</li>
 *   <li>A city that the two rules above keep must not contain U+0000 (NUL), which {@code custmast.city}
 *       cannot store: such a row fails the whole load before any write. A row those rules drop is
 *       dropped whatever it holds.</li>
 *   <li>{@code type} is trimmed, or {@code ""} when the file has no such column.</li>
 * </ol>
 * Retained rows keep their file order and are never deduplicated, because {@code CustomerDataGenerator}
 * draws them by index and a seeded run must repeat exactly. The counts read, kept and dropped are logged
 * at INFO. A file with no usable row yields an empty list; {@code CustomerGeneratorRunner} reports it.
 *
 * <p><b>Errors.</b> Every failure is a {@link CszFileException} with a one-line message naming the
 * location and, for a record, the line it starts on; a failure of {@link StateService#exists(String)}
 * itself propagates unchanged.
 *
 * <p><b>Scope.</b> The class only reads, reaches STATES only through {@link StateService}, holds no
 * state between calls, is thread-safe and carries no profile.
 */
@Component
public class CszSource {

    private static final Logger log = LoggerFactory.getLogger(CszSource.class);

    /** Longest city kept, in code points: {@code custmast.city varchar(20)}, LOADCUSTR's filter. */
    public static final int CITY_MAX_LENGTH = 20;

    /** Prefix that selects a class-path resource. */
    public static final String CLASSPATH_PREFIX = "classpath:";

    /** Optional prefix of a filesystem location. */
    public static final String FILE_PREFIX = "file:";

    private static final String COLUMN_ZIP = "zip";

    private static final String COLUMN_CITY = "city";

    /** Header alias of the city column, as named in the unedited unitedstateszipcodes.org download. */
    private static final String COLUMN_PRIMARY_CITY = "primary_city";

    private static final String COLUMN_STATE = "state";

    private static final String COLUMN_TYPE = "type";

    /** Value of {@link CszRow#type()} when the file has no {@code type} column. */
    private static final String NO_TYPE = "";

    /** Byte-order mark a spreadsheet may write at the start of a UTF-8 file. */
    private static final char BYTE_ORDER_MARK = '\uFEFF';

    private static final char COMMA = ',';

    private static final char QUOTE = '"';

    /** Longest field value quoted in an error message before it is shortened. */
    private static final int MAX_QUOTED_VALUE = 40;

    /** U+2028, which some viewers render as a line break although it is no control character. */
    private static final char LINE_SEPARATOR = '\u2028';

    /** U+2029, which some viewers render as a line break although it is no control character. */
    private static final char PARAGRAPH_SEPARATOR = '\u2029';

    /** U+0000, which no PostgreSQL text value can hold; a kept city carrying it fails the load. */
    private static final char NUL = '\u0000';

    /** The STATES cache that decides which rows are kept. */
    private final StateService stateService;

    /**
     * Creates the reader.
     *
     * @param stateService the state lookup whose cache decides which rows are kept
     */
    public CszSource(StateService stateService) {
        this.stateService = Objects.requireNonNull(stateService, "stateService");
    }

    /**
     * Reads, filters and normalizes the CSZ file at {@code location}.
     *
     * @param location {@code classpath:<resource>}, or a filesystem path with an optional {@code file:}
     *                 prefix; surrounding whitespace is ignored
     * @return the retained rows in file order, unmodifiable; empty when no row is usable
     * @throws CszFileException if the location is empty, cannot be opened or read, is not valid UTF-8,
     *                          lacks a required column, or holds a malformed record, a bad ZIP or a kept
     *                          city containing U+0000
     */
    public List<CszRow> load(String location) {
        if (location == null || location.isBlank()) {
            throw new CszFileException("CSZ file location is empty");
        }
        final String name = location.strip();
        final InputStream in = open(name);
        try (in) {
            return parse(name, in);
        } catch (IOException e) {
            // Only close() reaches this handler; read failures are reported with their line by parse.
            throw new CszFileException(prefix(name) + " cannot be read: " + reason(e), e);
        }
    }

    /**
     * Opens the location as a class-path resource or as a file.
     *
     * @param location the stripped, non-blank location
     * @return an open stream, which the caller closes
     */
    private static InputStream open(String location) {
        if (location.startsWith(CLASSPATH_PREFIX)) {
            return openResource(location);
        }
        return openFile(location);
    }

    /**
     * Opens a {@code classpath:} location: the thread context class loader first, then this class's.
     *
     * @param location the location, starting with {@value #CLASSPATH_PREFIX}
     * @return an open stream
     * @throws CszFileException if no loader finds the resource
     */
    private static InputStream openResource(String location) {
        int start = CLASSPATH_PREFIX.length();
        while (start < location.length() && location.charAt(start) == '/') {
            start++;
        }
        final String resource = location.substring(start);
        if (resource.isEmpty()) {
            throw new CszFileException(prefix(location) + " not found");
        }
        final ClassLoader own = CszSource.class.getClassLoader();
        final ClassLoader context = Thread.currentThread().getContextClassLoader();
        InputStream in = context != null ? context.getResourceAsStream(resource) : null;
        if (in == null && own != null && own != context) {
            in = own.getResourceAsStream(resource);
        }
        if (in == null) {
            throw new CszFileException(prefix(location) + " not found");
        }
        return in;
    }

    /**
     * Opens a filesystem location, after removing an optional {@value #FILE_PREFIX} prefix.
     *
     * <p>Decision: a location without {@value #CLASSPATH_PREFIX} is always a path. A Spring
     * {@code ResourceLoader} is deliberately not used, because it resolves a bare path such as
     * {@code generator/csz-sample.csv} on the class path, which would let a mistyped file name load the
     * bundled sample instead of failing.
     *
     * @param location the location
     * @return an open stream
     * @throws CszFileException if the path is invalid, absent, a directory, or cannot be opened
     */
    private static InputStream openFile(String location) {
        final String pathText = location.startsWith(FILE_PREFIX)
                ? location.substring(FILE_PREFIX.length())
                : location;
        if (pathText.isBlank()) {
            throw new CszFileException(prefix(location) + " not found");
        }
        final Path path;
        try {
            path = Path.of(pathText);
        } catch (InvalidPathException e) {
            throw new CszFileException(prefix(location) + " is not a valid path: " + oneLine(e.getReason()), e);
        }
        if (Files.isDirectory(path)) {
            throw new CszFileException(prefix(location) + " is a directory, not a file");
        }
        try {
            return Files.newInputStream(path);
        } catch (NoSuchFileException e) {
            throw new CszFileException(prefix(location) + " not found", e);
        } catch (AccessDeniedException e) {
            throw new CszFileException(prefix(location) + " cannot be read: permission denied", e);
        } catch (IOException e) {
            throw new CszFileException(prefix(location) + " cannot be read: " + reason(e), e);
        } catch (SecurityException e) {
            throw new CszFileException(prefix(location) + " cannot be read: access denied", e);
        }
    }

    /**
     * Reads the header and every data record, applying the row rules described on the class.
     *
     * @param location the location, for messages
     * @param in       the open file
     * @return the retained rows in file order, unmodifiable
     */
    private List<CszRow> parse(String location, InputStream in) {
        final RecordReader records = new RecordReader(location, in);
        final CsvRecord header = records.next();
        if (header == null) {
            throw new CszFileException(prefix(location) + " is empty: it has no header row");
        }
        final Columns columns = Columns.of(location, header);

        final List<CszRow> kept = new ArrayList<>();
        long read = 0;
        long droppedCity = 0;
        long droppedState = 0;
        for (CsvRecord record = records.next(); record != null; record = records.next()) {
            read++;
            final List<String> fields = record.fields();
            if (fields.size() < columns.minimumFields()) {
                throw new CszFileException(prefix(location) + " line " + record.line()
                        + ": expected at least " + columns.minimumFields() + " fields, found " + fields.size());
            }
            final int zip = parseZip(location, record.line(), fields.get(columns.zip()));

            // length(trim(city)) <= 20, counted in code points like a varchar(20) column.
            final String city = fields.get(columns.city()).strip();
            if (city.codePointCount(0, city.length()) > CITY_MAX_LENGTH) {
                droppedCity++;
                continue;
            }

            // trim(state) only, as the source; rows outside STATES would break custmast_state_fk.
            final String state = fields.get(columns.state()).strip();
            if (!stateService.exists(state)) {
                droppedState++;
                continue;
            }

            // PostgreSQL text cannot store U+0000, so COPY would fail inside the loader's transaction after
            // TRUNCATE; a kept city carrying it fails here, before any write, naming its line and column.
            if (city.indexOf(NUL) >= 0) {
                throw new CszFileException(prefix(location) + " line " + record.line() + ": "
                        + columns.cityName() + " " + quoted(city) + " contains U+0000 (NUL), which custmast.city"
                        + " cannot store; remove the character from the file");
            }

            final String type = columns.type() >= 0 && columns.type() < fields.size()
                    ? fields.get(columns.type()).strip()
                    : NO_TYPE;
            kept.add(new CszRow(zip, type, TextNormalizer.filter(city), state));
        }
        log.info("csz.file loaded location={} cityColumn={} read={} kept={} droppedCityTooLong={} "
                        + "droppedUnknownState={}",
                oneLine(location), columns.cityName(), read, kept.size(), droppedCity, droppedState);
        return Collections.unmodifiableList(kept);
    }

    /**
     * Parses a ZIP field: trimmed ASCII digits whose value fits a non-negative {@code int}, the
     * source's {@code zip int(10)}. Leading zeros are allowed, so {@code 00501} is 501.
     *
     * @param location the location, for messages
     * @param line     the record's starting line, for messages
     * @param raw      the field as read
     * @return the ZIP value
     * @throws CszFileException if the field is blank, holds anything but digits, or overflows
     */
    private static int parseZip(String location, long line, String raw) {
        final String text = raw.strip();
        if (text.isEmpty()) {
            throw new CszFileException(prefix(location) + " line " + line + ": zip is blank");
        }
        long value = 0;
        for (int i = 0; i < text.length(); i++) {
            final char c = text.charAt(i);
            if (c < '0' || c > '9') {
                throw new CszFileException(
                        prefix(location) + " line " + line + ": zip " + quoted(text) + " is not a number");
            }
            value = value * 10 + (c - '0');
            if (value > Integer.MAX_VALUE) {
                throw new CszFileException(prefix(location) + " line " + line + ": zip " + quoted(text)
                        + " is out of range (at most " + Integer.MAX_VALUE + ")");
            }
        }
        return (int) value;
    }

    /**
     * A UTF-8 decoder that reports malformed input instead of replacing it.
     *
     * <p>Decision: a file in another encoding, such as a spreadsheet saved as Windows-1252, fails with a
     * message naming its line rather than loading cities with replacement characters. Each physical line
     * is decoded on its own (see {@link RecordReader}), because a {@code Reader} decodes thousands of
     * characters ahead and would report the failure against an earlier line.
     *
     * @return a new strict decoder
     */
    private static CharsetDecoder strictUtf8() {
        return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
    }

    /**
     * The common start of every location-specific message.
     *
     * @param location the location
     * @return {@code "CSZ file <location>"} on one line
     */
    private static String prefix(String location) {
        return "CSZ file " + oneLine(location);
    }

    /**
     * Describes an I/O failure in a few words, without a stack trace.
     *
     * @param e the failure
     * @return its message, or its type when it has none
     */
    private static String reason(IOException e) {
        final String message = e.getMessage();
        return oneLine(message == null || message.isBlank() ? e.getClass().getSimpleName() : message);
    }

    /**
     * Quotes a field value for a message, shortened to {@value #MAX_QUOTED_VALUE} code points.
     *
     * @param value the value
     * @return the value in double quotes, on one line
     */
    private static String quoted(String value) {
        String shown = value;
        if (shown.codePointCount(0, shown.length()) > MAX_QUOTED_VALUE) {
            shown = shown.substring(0, shown.offsetByCodePoints(0, MAX_QUOTED_VALUE)) + "...";
        }
        return "\"" + oneLine(shown) + "\"";
    }

    /**
     * Replaces every control character, line breaks included, with a space, so a message stays on one
     * line whatever a file name or field holds.
     *
     * @param text the text; may be {@code null}
     * @return the text on one line; {@code ""} for {@code null}
     */
    private static String oneLine(String text) {
        if (text == null) {
            return "";
        }
        final StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            final char c = text.charAt(i);
            final boolean lineBreaking = Character.isISOControl(c)
                    || c == LINE_SEPARATOR
                    || c == PARAGRAPH_SEPARATOR;
            out.append(lineBreaking ? ' ' : c);
        }
        return out.toString();
    }

    /**
     * One CSV record: its fields as read (unquoted, untrimmed) and the physical line it starts on.
     *
     * @param line   the 1-based physical line on which the record starts
     * @param fields the field values, at least one
     */
    private record CsvRecord(long line, List<String> fields) {
    }

    /** Parser states inside one record. */
    private enum FieldState {
        /** At the start of a field: a quote opens a quoted field, a comma ends an empty one. */
        FIELD_START,
        /** Inside an unquoted field: a comma ends it; a quote makes the record malformed. */
        UNQUOTED,
        /** Inside a quoted field: {@code ""} is a quote, a lone quote closes the field. */
        QUOTED,
        /** After a closing quote: only blanks may precede the comma or the end of the record. */
        AFTER_QUOTE
    }

    /**
     * Splits the file into RFC 4180 records, one at a time, counting physical lines.
     *
     * <p>Lines end at {@code \n}, {@code \r\n} or {@code \r}, as {@link java.io.BufferedReader#readLine()}
     * splits them. They are split on bytes, which is exact for UTF-8 because neither byte occurs inside
     * a multi-byte sequence, and each line is then decoded strictly, so a decoding error names the line
     * that holds it. A line break inside a quoted field is kept as {@code \n}. A blank record (no quote,
     * no comma, only whitespace) is skipped, which also covers a trailing newline. A quote inside an
     * unquoted field, or anything but blanks between a closing quote and the next comma or the end of
     * the record, makes the record malformed; the failure names its start line and 1-based field number.
     *
     * <p>A physical line is limited to {@value #MAX_LINE_BYTES} bytes and a record to
     * {@value #MAX_RECORD_CHARS} characters, so a binary file or a quote that is never closed fails with
     * a message instead of exhausting memory. A line of the ZIP database is a few hundred bytes.
     */
    private static final class RecordReader {

        /** Bytes read from the stream at a time. */
        private static final int CHUNK_BYTES = 64 * 1024;

        /** Initial capacity of the line buffer. */
        private static final int INITIAL_LINE_BYTES = 512;

        /** Longest physical line accepted, in bytes. */
        private static final int MAX_LINE_BYTES = 1024 * 1024;

        /** Longest record accepted, in characters, line breaks inside quotes included. */
        private static final int MAX_RECORD_CHARS = 1024 * 1024;

        /** The location, for messages. */
        private final String location;

        /** The open file; closed by {@link CszSource#load(String)}. */
        private final InputStream in;

        /** Strict UTF-8 decoder, reused for every line of this file. */
        private final CharsetDecoder decoder = strictUtf8();

        /** Bytes read ahead from {@link #in}; {@code chunk[chunkPos..chunkEnd)} is still unread. */
        private final byte[] chunk = new byte[CHUNK_BYTES];

        /** Next unread position in {@link #chunk}. */
        private int chunkPos;

        /** End of the valid bytes in {@link #chunk}. */
        private int chunkEnd;

        /** {@link #in} has reported its end. */
        private boolean endOfStream;

        /** The bytes of the physical line being read; grows up to {@value #MAX_LINE_BYTES}. */
        private byte[] lineBytes = new byte[INITIAL_LINE_BYTES];

        /** The previous line ended in {@code \r}, so a {@code \n} that follows belongs to it. */
        private boolean skipLineFeed;

        /** Physical lines read so far; the number of the line most recently read. */
        private long physicalLine;

        /**
         * Creates a reader positioned before the first line.
         *
         * @param location the location, for messages
         * @param in       the open file
         */
        RecordReader(String location, InputStream in) {
            this.location = location;
            this.in = in;
        }

        /**
         * Returns the next non-blank record.
         *
         * @return the record, or {@code null} at the end of the file
         * @throws CszFileException if the file cannot be read, is not valid UTF-8, or holds a malformed
         *                          record
         */
        CsvRecord next() {
            for (String line = readLine(); line != null; line = readLine()) {
                final long start = physicalLine;
                String text = line;
                if (start == 1 && !text.isEmpty() && text.charAt(0) == BYTE_ORDER_MARK) {
                    text = text.substring(1);
                }
                final CsvRecord record = parseRecord(text, start);
                if (record != null) {
                    return record;
                }
            }
            return null;
        }

        /**
         * Parses one record that starts with {@code firstLine}, reading further lines while a quoted
         * field is open.
         *
         * @param firstLine the record's first physical line, without its terminator
         * @param start     that line's number
         * @return the record, or {@code null} when it is blank
         */
        private CsvRecord parseRecord(String firstLine, long start) {
            final List<String> fields = new ArrayList<>();
            final StringBuilder field = new StringBuilder();
            boolean structured = false;
            FieldState state = FieldState.FIELD_START;
            String line = firstLine;
            long recordChars = firstLine.length();
            int pos = 0;
            while (true) {
                if (pos == line.length()) {
                    if (state != FieldState.QUOTED) {
                        fields.add(field.toString());
                        break;
                    }
                    final String nextLine = readLine();
                    if (nextLine == null) {
                        throw new CszFileException(prefix(location) + " line " + start
                                + ": quoted field is not closed before the end of the file");
                    }
                    recordChars += 1L + nextLine.length();
                    if (recordChars > MAX_RECORD_CHARS) {
                        throw new CszFileException(prefix(location) + " line " + start
                                + ": record is longer than " + MAX_RECORD_CHARS
                                + " characters; check for a quote that is never closed");
                    }
                    field.append('\n');
                    line = nextLine;
                    pos = 0;
                    continue;
                }
                final char c = line.charAt(pos++);
                switch (state) {
                    case FIELD_START -> {
                        if (c == QUOTE) {
                            structured = true;
                            state = FieldState.QUOTED;
                        } else if (c == COMMA) {
                            structured = true;
                            fields.add(field.toString());
                            field.setLength(0);
                        } else {
                            field.append(c);
                            state = FieldState.UNQUOTED;
                        }
                    }
                    case UNQUOTED -> {
                        if (c == COMMA) {
                            structured = true;
                            fields.add(field.toString());
                            field.setLength(0);
                            state = FieldState.FIELD_START;
                        } else if (c == QUOTE) {
                            throw new CszFileException(prefix(location) + " line " + start
                                    + ": quote inside unquoted field " + (fields.size() + 1)
                                    + "; enclose the field in double quotes and double each quote inside it");
                        } else {
                            field.append(c);
                        }
                    }
                    case QUOTED -> {
                        if (c != QUOTE) {
                            field.append(c);
                        } else if (pos < line.length() && line.charAt(pos) == QUOTE) {
                            field.append(QUOTE);
                            pos++;
                        } else {
                            state = FieldState.AFTER_QUOTE;
                        }
                    }
                    case AFTER_QUOTE -> {
                        if (c == COMMA) {
                            fields.add(field.toString());
                            field.setLength(0);
                            state = FieldState.FIELD_START;
                        } else if (c != ' ' && c != '\t') {
                            throw new CszFileException(prefix(location) + " line " + start
                                    + ": unexpected character after a closing quote in field " + (fields.size() + 1));
                        }
                    }
                    default -> throw new IllegalStateException("Unknown parser state " + state);
                }
            }
            if (!structured && fields.size() == 1 && fields.get(0).isBlank()) {
                return null;
            }
            return new CsvRecord(start, fields);
        }

        /**
         * Reads one physical line, decodes it and counts it.
         *
         * @return the line without its terminator, or {@code null} at the end of the file
         * @throws CszFileException naming the line that failed to decode or read, or that is too long
         */
        private String readLine() {
            final long lineNumber = physicalLine + 1;
            try {
                int b = read();
                if (skipLineFeed) {
                    skipLineFeed = false;
                    if (b == '\n') {
                        b = read();
                    }
                }
                if (b < 0) {
                    return null;
                }
                int length = 0;
                while (b >= 0 && b != '\n' && b != '\r') {
                    if (length == lineBytes.length) {
                        if (length >= MAX_LINE_BYTES) {
                            throw new CszFileException(prefix(location) + " line " + lineNumber
                                    + ": line is longer than " + MAX_LINE_BYTES + " bytes; is this a CSV file?");
                        }
                        lineBytes = Arrays.copyOf(lineBytes, Math.min(length * 2, MAX_LINE_BYTES));
                    }
                    lineBytes[length++] = (byte) b;
                    b = read();
                }
                skipLineFeed = b == '\r';
                physicalLine = lineNumber;
                return decoder.decode(ByteBuffer.wrap(lineBytes, 0, length)).toString();
            } catch (CharacterCodingException e) {
                throw new CszFileException(prefix(location) + " line " + lineNumber
                        + ": not valid UTF-8; save the file as UTF-8 CSV", e);
            } catch (IOException e) {
                throw new CszFileException(prefix(location) + " line " + lineNumber
                        + ": cannot be read: " + reason(e), e);
            }
        }

        /**
         * Returns the next byte of the stream.
         *
         * @return the byte as 0..255, or -1 at the end of the stream
         * @throws IOException if the stream fails
         */
        private int read() throws IOException {
            while (chunkPos == chunkEnd) {
                if (endOfStream) {
                    return -1;
                }
                final int n = in.read(chunk, 0, chunk.length);
                if (n < 0) {
                    endOfStream = true;
                    return -1;
                }
                chunkPos = 0;
                chunkEnd = n;
            }
            return chunk[chunkPos++] & 0xFF;
        }
    }

    /**
     * Where the used columns sit in each record, resolved from the header.
     *
     * @param zip      index of {@code zip}
     * @param city     index of {@code city}, or of {@code primary_city} when there is no {@code city}
     * @param cityName the header name of the city column used, for the log and messages
     * @param state    index of {@code state}
     * @param type     index of {@code type}, or -1 when the file has none
     */
    private record Columns(int zip, int city, String cityName, int state, int type) {

        /**
         * Resolves the columns from the header record.
         *
         * @param location the location, for messages
         * @param header   the first non-blank record
         * @return the resolved columns
         * @throws CszFileException naming every missing required column and the header's line
         */
        static Columns of(String location, CsvRecord header) {
            final Map<String, Integer> byName = new HashMap<>();
            final List<String> names = header.fields();
            for (int i = 0; i < names.size(); i++) {
                // First occurrence wins when a name repeats.
                byName.putIfAbsent(names.get(i).strip().toLowerCase(Locale.ROOT), i);
            }
            final Integer zip = byName.get(COLUMN_ZIP);
            final Integer city = byName.containsKey(COLUMN_CITY)
                    ? byName.get(COLUMN_CITY)
                    : byName.get(COLUMN_PRIMARY_CITY);
            final Integer state = byName.get(COLUMN_STATE);

            final List<String> missing = new ArrayList<>();
            if (zip == null) {
                missing.add(COLUMN_ZIP);
            }
            if (state == null) {
                missing.add(COLUMN_STATE);
            }
            if (city == null) {
                missing.add(COLUMN_CITY + " (or " + COLUMN_PRIMARY_CITY + ")");
            }
            if (!missing.isEmpty()) {
                throw new CszFileException(prefix(location) + " line " + header.line()
                        + ": missing required column" + (missing.size() == 1 ? " " : "s ")
                        + String.join(", ", missing)
                        + "; the header must name zip, state, and city or primary_city");
            }
            final String cityName = byName.containsKey(COLUMN_CITY) ? COLUMN_CITY : COLUMN_PRIMARY_CITY;
            return new Columns(zip, city, cityName, state, byName.getOrDefault(COLUMN_TYPE, -1));
        }

        /**
         * The fewest fields a data record may have: one past the highest required index. The optional
         * {@code type} column does not count; a record that ends before it has no type.
         *
         * @return the minimum field count
         */
        int minimumFields() {
            return Math.max(zip, Math.max(city, state)) + 1;
        }
    }

    /**
     * One retained city/state/ZIP row: LOADCUSTR's {@code csz} record.
     *
     * @param zip   the ZIP as an integer, non-negative; {@code CustomerDataGenerator} writes its last five
     *              digits zero-padded
     * @param type  the ZIP type such as {@code STANDARD}, trimmed; {@code ""} when the file has no
     *              {@code type} column (and for {@code null})
     * @param city  the city, trimmed and uppercased, at most {@value CszSource#CITY_MAX_LENGTH} code
     *              points; may be blank
     * @param state the state code as in the file, trimmed, and known to {@link StateService#exists(String)}
     */
    public record CszRow(int zip, String type, String city, String state) {

        /**
         * Validates the row.
         *
         * @throws IllegalArgumentException if {@code zip} is negative
         * @throws NullPointerException     if {@code city} or {@code state} is {@code null}
         */
        public CszRow {
            if (zip < 0) {
                throw new IllegalArgumentException("zip must not be negative: " + zip);
            }
            type = type == null ? NO_TYPE : type;
            Objects.requireNonNull(city, "city");
            Objects.requireNonNull(state, "state");
        }
    }

    /**
     * A CSZ file that cannot be used: an empty or unknown location, an unreadable file, invalid UTF-8,
     * a missing required column, a malformed record, a bad ZIP, or a kept city containing U+0000.
     *
     * <p>The message is always one line, naming the location and, for a record, the 1-based physical
     * line where it starts; {@code CustomerGeneratorRunner} prints it verbatim and exits with status 1.
     */
    public static final class CszFileException extends RuntimeException {

        /** Serialization version. */
        private static final long serialVersionUID = 1L;

        /**
         * Creates the exception.
         *
         * @param message the one-line message; any line break in it is replaced by a space
         */
        public CszFileException(String message) {
            super(oneLine(message));
        }

        /**
         * Creates the exception with its cause.
         *
         * @param message the one-line message; any line break in it is replaced by a space
         * @param cause   the underlying failure
         */
        public CszFileException(String message, Throwable cause) {
            super(oneLine(message), cause);
        }
    }
}
