package com.democorp.customermaster.generator;

import com.democorp.customermaster.domain.Address;
import com.democorp.customermaster.domain.Customer;
import com.democorp.customermaster.domain.CustomerId;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Iterator;
import java.util.Objects;
import org.postgresql.PGConnection;
import org.postgresql.copy.CopyIn;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Streams generated customers into {@code custmast} with one PostgreSQL {@code COPY ... FROM STDIN}
 * statement.
 *
 * <p>Replaces LOADCUSTR's row-by-row {@code insert into custmast values(:Fld)}
 * [5250_Subfile/LOADCUSTR.SQLRPGLE:203], one statement per generated row under commitment control
 * {@code *NONE}, with a single streamed {@code COPY} that runs inside the load transaction. The
 * columns are those of [5250_Subfile/Custmast2.sql:8-24] as migration V3 creates them, listed
 * explicitly rather than positionally, plus the new {@code row_version}.
 *
 * <p>The caller, {@code CustomerLoader}, owns the connection and its transaction, the truncate, the
 * table lock and the sequence restart; this class only streams rows.
 *
 * <h2>CSV encoding</h2>
 * <p>Each row is one CSV line terminated by {@code \n}, in {@link #COPY_SQL} column order. The rules of
 * PostgreSQL's CSV format that this writer relies on:
 * <ul>
 *   <li><b>Every text field is quoted.</b> In CSV format an unquoted empty field reads as
 *       {@code NULL}, and every {@code custmast} column is {@code NOT NULL}, so a {@code null} text
 *       value is written as {@code ""} and loads as {@code ''}.</li>
 *   <li><b>Only the double quote is escaped</b>, by doubling it. Commas, line breaks, apostrophes
 *       and backslashes are literal inside quotes, and a quoted {@code \.} is data, never the
 *       end-of-data marker.</li>
 *   <li><b>Strict UTF-8</b>, pgjdbc's fixed {@code client_encoding}. Text that cannot be encoded,
 *       such as an unpaired surrogate, fails the copy instead of being replaced.</li>
 *   <li><b>Microseconds.</b> {@code chgtime} is {@code timestamptz(6)}, and PostgreSQL rounds input
 *       with more digits, so the writer truncates it to microseconds before formatting: the stored
 *       value is the load time truncated, never rounded up.</li>
 * </ul>
 * {@code chgtime}, ISO-8601 with offset, and {@code row_version}, {@code 0} for a {@code null}
 * {@code rowVersion}, are written unquoted.
 *
 * <h2>Streaming, failure and logging</h2>
 * <p>The iterator is consumed lazily. Lines are buffered and sent once the buffer holds
 * {@link #BATCH_BYTES} characters or more, only at a line boundary, so memory stays constant, about
 * one batch plus one line, whatever the row count. If generating, encoding or writing a row fails, the
 * open {@code COPY} is cancelled so the caller's transaction can roll back, a failure of the cancel is
 * added to the original exception as suppressed, and the original is rethrown unchanged. Progress is
 * logged at DEBUG every {@value #PROGRESS_INTERVAL} rows, and the total at the end; no customer data
 * is ever logged.
 *
 * <p>The bean holds no state: each call uses its own buffer and encoder, so concurrent calls on
 * different connections are safe. A single {@link PGConnection} must not be shared by concurrent
 * calls, as with any JDBC connection.
 */
@Component
public class CustomerCopyWriter {

    /**
     * The {@code COPY} statement: every V3 column of {@code custmast}, in V3 order, read as CSV from
     * the client. The table is unqualified; the connection's {@code currentSchema} resolves it.
     * {@code CustomerLoader} also passes this text to Spring's exception translator.
     */
    public static final String COPY_SQL = "COPY custmast (custid, name, addr, city, state, zip, "
            + "corpphone, acctmgr, acctphone, active, chgtime, chguser, row_version) "
            + "FROM STDIN (FORMAT csv)";

    /**
     * Buffered characters that trigger a write to the server. The character count is a lower bound
     * on the UTF-8 byte count, so every write except the last carries at least 64 KiB.
     */
    static final int BATCH_BYTES = 64 * 1024;

    /** Rows between two DEBUG progress lines. */
    static final long PROGRESS_INTERVAL = 100_000L;

    /** Capacity beyond {@link #BATCH_BYTES}, so the line that crosses it rarely grows the buffer. */
    private static final int LINE_HEADROOM = 1024;

    /** The CSV quote character, also the escape character in PostgreSQL's CSV format. */
    private static final char QUOTE = '"';

    /** Field separator of PostgreSQL's CSV format. */
    private static final char DELIMITER = ',';

    /** Row terminator. */
    private static final char NEWLINE = '\n';

    /** {@code chgtime} format; {@code timestamptz} accepts it with {@code Z} or a numeric offset. */
    private static final DateTimeFormatter CHGTIME_FORMAT = DateTimeFormatter.ISO_OFFSET_DATE_TIME;

    /** Operational log: progress and totals at DEBUG only, never customer data. */
    private static final Logger LOG = LoggerFactory.getLogger(CustomerCopyWriter.class);

    /** Creates the writer. It has no dependencies and no state. */
    public CustomerCopyWriter() {
    }

    /**
     * Copies every row the iterator yields into {@code custmast} with {@link #COPY_SQL}, on the given
     * connection, and returns the row count the server reports.
     *
     * <p>The caller owns the connection and its transaction: this method opens the {@code COPY},
     * streams the rows, ends it, and leaves the connection open and uncommitted. Rows become visible
     * to other sessions only when the caller commits.
     *
     * @param connection the PostgreSQL connection bound to the caller's load transaction, whose
     *                   {@code currentSchema} holds {@code custmast}
     * @param rows       the customers to write, consumed lazily and exactly once; each must carry an
     *                   id and a {@code chgTime}. {@code null} text values load as {@code ''}, and a
     *                   {@code null} {@code rowVersion} loads as {@code 0}
     * @return the number of rows the server copied, as reported by {@link CopyIn#endCopy()}
     * @throws SQLException             if the server rejects the {@code COPY} or a row (for example a
     *                                  duplicate id, a {@code CHECK} or foreign-key violation, or a
     *                                  value longer than its column), or the connection fails; the
     *                                  {@code COPY} has been cancelled where it was still open, and the
     *                                  caller's transaction is aborted and must roll back
     * @throws IllegalArgumentException if a row is {@code null}, has no {@code chgTime}, or holds text
     *                                  that cannot be encoded as UTF-8; the {@code COPY} has been
     *                                  cancelled
     * @throws NullPointerException     if {@code connection} or {@code rows} is {@code null}; no
     *                                  {@code COPY} has been opened
     * @throws RuntimeException         any exception the iterator throws, rethrown unchanged after the
     *                                  {@code COPY} has been cancelled
     */
    public long copy(PGConnection connection, Iterator<Customer> rows) throws SQLException {
        Objects.requireNonNull(connection, "connection");
        Objects.requireNonNull(rows, "rows");

        CopyIn copyIn = connection.getCopyAPI().copyIn(COPY_SQL);
        try {
            CharsetEncoder encoder = StandardCharsets.UTF_8.newEncoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT);
            StringBuilder batch = new StringBuilder(BATCH_BYTES + LINE_HEADROOM);
            long written = 0;
            while (rows.hasNext()) {
                Customer row = rows.next();
                written++;
                appendCsvLine(batch, row, written);
                if (batch.length() >= BATCH_BYTES) {
                    send(copyIn, encoder, batch);
                }
                if (written % PROGRESS_INTERVAL == 0 && LOG.isDebugEnabled()) {
                    LOG.debug("generator.copy rows={}", written);
                }
            }
            if (batch.length() > 0) {
                send(copyIn, encoder, batch);
            }
            long copied = copyIn.endCopy();
            LOG.debug("generator.copy done rows={} copied={}", written, copied);
            return copied;
        } catch (Throwable failure) {
            // Any failure, an Error included, leaves copy mode so the caller's transaction can roll
            // back on this connection. The original failure is rethrown as is (precise rethrow:
            // only SQLException is checked).
            cancel(copyIn, failure);
            throw failure;
        }
    }

    /**
     * Appends one row as a CSV line in {@link #COPY_SQL} column order, terminated by {@code \n}.
     *
     * <p>Package-private so the line format can be tested without a database.
     *
     * @param target    the buffer to append to
     * @param row       the customer to encode
     * @param rowNumber the 1-based position of the row in the load, used only in error messages
     * @throws IllegalArgumentException if {@code row} is {@code null} or has no {@code chgTime}
     */
    static void appendCsvLine(StringBuilder target, Customer row, long rowNumber) {
        if (row == null) {
            throw new IllegalArgumentException("Row " + rowNumber + " of the load is null");
        }
        OffsetDateTime chgTime = row.chgTime();
        if (chgTime == null) {
            throw new IllegalArgumentException("Row " + rowNumber + " of the load (custid "
                    + row.custId() + ") has no chgTime; column chgtime is NOT NULL");
        }
        CustomerId custId = row.custId();
        Address address = row.address();

        appendQuoted(target, custId == null ? null : custId.toString());
        target.append(DELIMITER);
        appendQuoted(target, row.name());
        target.append(DELIMITER);
        appendQuoted(target, address == null ? null : address.addr());
        target.append(DELIMITER);
        appendQuoted(target, address == null ? null : address.city());
        target.append(DELIMITER);
        appendQuoted(target, address == null ? null : address.state());
        target.append(DELIMITER);
        appendQuoted(target, address == null ? null : address.zip());
        target.append(DELIMITER);
        appendQuoted(target, row.corpPhone());
        target.append(DELIMITER);
        appendQuoted(target, row.acctMgr());
        target.append(DELIMITER);
        appendQuoted(target, row.acctPhone());
        target.append(DELIMITER);
        appendQuoted(target, row.active());
        target.append(DELIMITER);
        // Unquoted: an instant always has a value, and timestamptz(6) keeps microseconds only.
        CHGTIME_FORMAT.formatTo(chgTime.truncatedTo(ChronoUnit.MICROS), target);
        target.append(DELIMITER);
        appendQuoted(target, row.chgUser());
        target.append(DELIMITER);
        Long rowVersion = row.rowVersion();
        target.append(rowVersion == null ? 0L : rowVersion.longValue());
        target.append(NEWLINE);
    }

    /**
     * Appends a text field in double quotes, doubling every inner double quote. {@code null} becomes
     * {@code ""}, which PostgreSQL's CSV format reads as the empty string, not {@code NULL}.
     *
     * @param target the buffer to append to
     * @param value  the text, or {@code null}
     */
    private static void appendQuoted(StringBuilder target, String value) {
        target.append(QUOTE);
        if (value != null) {
            if (value.indexOf(QUOTE) < 0) {
                target.append(value);
            } else {
                for (int i = 0; i < value.length(); i++) {
                    char c = value.charAt(i);
                    if (c == QUOTE) {
                        target.append(QUOTE);
                    }
                    target.append(c);
                }
            }
        }
        target.append(QUOTE);
    }

    /**
     * Encodes the buffered lines as UTF-8, writes them to the open {@code COPY} and clears the
     * buffer.
     *
     * @param copyIn  the open copy operation
     * @param encoder a strict UTF-8 encoder owned by the current call
     * @param batch   the buffered complete lines; empty on return
     * @throws SQLException             if the write fails
     * @throws IllegalArgumentException if the text cannot be encoded as UTF-8
     */
    private static void send(CopyIn copyIn, CharsetEncoder encoder, StringBuilder batch)
            throws SQLException {
        ByteBuffer bytes;
        try {
            bytes = encoder.encode(CharBuffer.wrap(batch));
        } catch (CharacterCodingException e) {
            throw new IllegalArgumentException(
                    "Generated customer text cannot be encoded as UTF-8", e);
        }
        copyIn.writeToCopy(bytes.array(), bytes.arrayOffset() + bytes.position(), bytes.remaining());
        batch.setLength(0);
    }

    /**
     * Cancels the copy if it is still open, recording a failure of the cancel on the original
     * failure instead of replacing it.
     *
     * @param copyIn  the copy operation
     * @param failure the exception that ends the copy
     */
    private static void cancel(CopyIn copyIn, Throwable failure) {
        if (!copyIn.isActive()) {
            return;
        }
        try {
            copyIn.cancelCopy();
        } catch (SQLException | RuntimeException cancelFailure) {
            failure.addSuppressed(cancelFailure);
        }
    }
}
