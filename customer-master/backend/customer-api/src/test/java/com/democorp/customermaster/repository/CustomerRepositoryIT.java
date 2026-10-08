package com.democorp.customermaster.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.democorp.customermaster.domain.Address;
import com.democorp.customermaster.domain.Customer;
import com.democorp.customermaster.domain.CustomerId;
import com.democorp.customermaster.support.AbstractPostgresIT;
import com.democorp.customermaster.support.DatabaseCleaner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.logging.LogLevel;
import org.springframework.boot.logging.LoggerConfiguration;
import org.springframework.boot.logging.LoggingSystem;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.relational.core.conversion.DbActionExecutionException;

/**
 * Proves that the Spring Data JDBC mapping of {@link Customer} reads and writes exactly the columns
 * migration V3 creates, that saving a loaded aggregate issues the version-conditional {@code UPDATE},
 * and that a stale save fails with an unwrapped {@link OptimisticLockingFailureException}.
 *
 * <p><b>Source behaviour re-verified.</b> MTNCUSTR's three record procedures, which
 * {@link CustomerRepository} replaces:
 * <ul>
 *   <li>{@code ReadRecd} selects all twelve CUSTMAST columns by {@code CUSTID} and keeps
 *       {@code Orig_CHGTIME} as the concurrency token [5250_Subfile/MTNCUSTR.SQLRPGLE:315-338];
 *       here {@code findById} fills every property, including the token {@code row_version};</li>
 *   <li>{@code AddRecd} takes the next key (CUSTNEXT, advanced by BASE36ADD from CRTDTAARA's
 *       {@code EEEE}), stamps CHGTIME and CHGUSER, and inserts
 *       [5250_Subfile/MTNCUSTR.SQLRPGLE:548-566], [5250_Subfile/CRTDTAARA.clle:1-9]; here
 *       {@code CustomerIdAllocator.next()} returns {@code EEEF} and {@code save} of the new aggregate
 *       inserts it with {@code row_version} 0;</li>
 *   <li>{@code UpdateRecd} updates {@code WHERE CUSTID = :CUSTID AND CHGTIME = :Orig_CHGTIME}, and zero
 *       rows means DEM1002 [5250_Subfile/MTNCUSTR.SQLRPGLE:574-607]; here the token is
 *       {@code row_version} ({@code @Version}), and zero rows raise
 *       {@link OptimisticLockingFailureException}, which the maintenance service turns into DEM1002.</li>
 * </ul>
 *
 * <p><b>The risk this class closes.</b> Spring Data JDBC's default naming strategy would derive
 * {@code cust_id}, {@code corp_phone}, {@code acct_mgr}, {@code acct_phone}, {@code chg_time} and
 * {@code chg_user}, while V3 keeps the source names ({@code custid}, {@code corpphone},
 * {@code acctmgr}, {@code acctphone}, {@code chgtime}, {@code chguser}) plus {@code row_version}
 * [5250_Subfile/Custmast2.sql:9-21]. Every property of {@link Customer} and {@link Address} therefore
 * names its column with {@code @Column}, and {@link #generatedSqlUsesOnlyV3ColumnNames} proves that the
 * generated {@code SELECT}, {@code INSERT} and {@code UPDATE} use only V3 names.
 *
 * <p><b>How the generated SQL is observed.</b> At runtime, with no property change: for the duration of
 * one action, {@link #withJdbcDebug(Runnable)} raises the {@value #JDBC_TEMPLATE_LOGGER} logger to
 * {@code DEBUG} through Boot's {@link LoggingSystem} and restores its configured level in
 * {@code finally}. {@link OutputCaptureExtension} captures the console, and only the output written
 * after a mark taken before the action is parsed, so statements of other tests and of
 * {@code DatabaseCleaner} never reach the assertions. {@code JdbcTemplate} logs each prepared statement
 * as {@code Executing prepared SQL statement [...]}, with {@code ?} placeholders after
 * {@code NamedParameterJdbcTemplate} has expanded the named parameters Spring Data JDBC binds.
 *
 * <p><b>Context policy.</b> The class uses the base context of {@link AbstractPostgresIT} unchanged: no
 * {@code @MockitoBean}, {@code @TestPropertySource}, {@code @Import} or nested configuration, so it adds
 * no context variant. {@link OutputCaptureExtension} is a JUnit extension and leaves the context alone.
 *
 * <p><b>Data and transactions.</b> Test databases hold no seed rows, and the base class empties
 * {@code custmast} and restarts {@code custmast_id_seq} before every test, so every row here is
 * inserted by the test and the first allocation is {@value DatabaseCleaner#FIRST_INTERACTIVE_ID}.
 * Plain SQL runs in autocommit through {@code jdbcTemplate}. {@code CustomerIdAllocator.next()}
 * requires an open transaction ({@code Propagation.MANDATORY}), so it is only ever called inside
 * {@code transactionTemplate}, together with the {@code save} it allocates for; no transaction or lock
 * outlives a test.
 */
@ExtendWith(OutputCaptureExtension.class)
class CustomerRepositoryIT extends AbstractPostgresIT {

    /** The logger of Boot's {@code JdbcTemplate}, which Spring Data JDBC executes every statement through. */
    private static final String JDBC_TEMPLATE_LOGGER = "org.springframework.jdbc.core.JdbcTemplate";

    /** One prepared statement as {@code JdbcTemplate} logs it at DEBUG, one statement per line. */
    private static final Pattern PREPARED_STATEMENT_LOG =
            Pattern.compile("Executing prepared SQL statement \\[(.*)\\]$", Pattern.MULTILINE);

    /** A double-quoted SQL identifier; Spring Data JDBC quotes every table, column and alias name. */
    private static final Pattern QUOTED_IDENTIFIER = Pattern.compile("\"([^\"]+)\"");

    /** The column names the default naming strategy would derive, as whole words in any case. */
    private static final Pattern DERIVED_COLUMN_NAME = Pattern.compile(
            "\\b(?:cust_id|corp_phone|acct_mgr|acct_phone|chg_time|chg_user)\\b", Pattern.CASE_INSENSITIVE);

    /**
     * The version-conditional update, UpdateRecd's {@code where CUSTID = :CUSTID and CHGTIME =
     * :Orig_CHGTIME} with {@code row_version} as the token; quoting and table qualification optional.
     */
    private static final Pattern VERSIONED_UPDATE = Pattern.compile(
            "UPDATE\\s+\"?custmast\"?\\s+SET\\s+.*WHERE\\s+(\"?custmast\"?\\.)?\"?custid\"?\\s*=\\s*\\?"
                    + "\\s+AND\\s+(\"?custmast\"?\\.)?\"?row_version\"?\\s*=\\s*\\?",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    /** A generated read of the customer table. */
    private static final Pattern SELECT_FROM_CUSTMAST =
            Pattern.compile("^\\s*SELECT\\s.*\\sFROM\\s+\"custmast\"",
                    Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    /** A generated insert into the customer table. */
    private static final Pattern INSERT_INTO_CUSTMAST =
            Pattern.compile("^\\s*INSERT\\s+INTO\\s+\"custmast\"", Pattern.CASE_INSENSITIVE);

    /** A generated update of the customer table. */
    private static final Pattern UPDATE_CUSTMAST =
            Pattern.compile("^\\s*UPDATE\\s+\"custmast\"", Pattern.CASE_INSENSITIVE);

    /** The thirteen columns of V3, in V3 order. */
    private static final List<String> V3_COLUMNS = List.of("custid", "name", "addr", "city", "state", "zip",
            "corpphone", "acctmgr", "acctphone", "active", "chgtime", "chguser", "row_version");

    /** The table every generated statement names. */
    private static final String CUSTMAST = "custmast";

    /** Inserts one complete row with an explicit column list, in V3 order. */
    private static final String INSERT_ROW_SQL = "INSERT INTO custmast (custid, name, addr, city, state, zip,"
            + " corpphone, acctmgr, acctphone, active, chgtime, chguser, row_version)"
            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

    /** Reads one row back as stored, every column. */
    private static final String SELECT_ROW_SQL = "SELECT custid, name, addr, city, state, zip, corpphone,"
            + " acctmgr, acctphone, active, chgtime, chguser, row_version FROM custmast WHERE custid = ?";

    /** The id of the fully populated plain-SQL row. */
    private static final String ACME_ID = "AAAB";

    /** The name of the plain-SQL row. */
    private static final String ACME_NAME = "ACME TOOLS";

    /** The street of every plain-SQL row. */
    private static final String ROW_ADDR = "12 ELM ST";

    /** The city of every plain-SQL row. */
    private static final String ROW_CITY = "SPRINGFIELD";

    /** The state of every plain-SQL row, present in V2's {@code states}. */
    private static final String ROW_STATE = "IL";

    /** The ZIP of every plain-SQL row, a ZIP+4 so the full 10-character value is read. */
    private static final String ROW_ZIP = "62701-1234";

    /** The corporate phone of every plain-SQL row. */
    private static final String ROW_CORP_PHONE = "(217) 555-0100";

    /** The account manager of every plain-SQL row. */
    private static final String ROW_ACCT_MGR = "JANE Q PUBLIC";

    /** The account manager's phone of every plain-SQL row. */
    private static final String ROW_ACCT_PHONE = "(217) 555-0199";

    /** The active code of every plain-SQL row: {@code N}, so the column default {@code Y} cannot pass. */
    private static final String ROW_ACTIVE = "N";

    /** The change time of every plain-SQL row, at the column's microsecond precision. */
    private static final Instant ROW_CHG_TIME = Instant.parse("2026-10-05T14:03:09.123456Z");

    /** The change user of every plain-SQL row, distinct from the draft's user. */
    private static final String ROW_CHG_USER = MAINTENANCE2_USER;

    /** The row version of the fully populated row: neither 0 nor 1, so no default can pass. */
    private static final long ACME_ROW_VERSION = 7L;

    /** The change time stamped on every draft: UTC, truncated to the column's microseconds. */
    private static final OffsetDateTime DRAFT_CHG_TIME =
            OffsetDateTime.of(2026, 10, 6, 9, 30, 15, 987_654_000, ZoneOffset.UTC);

    /** The application's repository bean, the Spring Data proxy the maintenance service calls. */
    @Autowired
    private CustomerRepository repository;

    /** The application's allocator, which assigns the id of a new aggregate inside its transaction. */
    @Autowired
    private CustomerIdAllocator allocator;

    /** Boot's logging system, which raises and restores the {@value #JDBC_TEMPLATE_LOGGER} level. */
    @Autowired
    private LoggingSystem loggingSystem;

    /**
     * ReadRecd: {@code findById} of a row written by plain SQL fills every property of the aggregate,
     * the embedded {@link Address} included, from the V3 column of the same name; an unknown id reads as
     * empty, where ReadRecd continued with {@code SQLNODATA}.
     */
    @Test
    void findByIdFillsEveryPropertyFromPlainSqlRow() {
        insertRow(ACME_ID, ACME_NAME, ACME_ROW_VERSION);

        Customer found = repository.findById(CustomerId.parse(ACME_ID)).orElseThrow(
                () -> new AssertionError("findById(" + ACME_ID + ") returned no customer"));

        assertThat(found.custId()).isEqualTo(CustomerId.parse(ACME_ID));
        assertThat(found.name()).isEqualTo(ACME_NAME);
        assertThat(found.address()).isEqualTo(new Address(ROW_ADDR, ROW_CITY, ROW_STATE, ROW_ZIP));
        assertThat(found.address().addr()).isEqualTo(ROW_ADDR);
        assertThat(found.address().city()).isEqualTo(ROW_CITY);
        assertThat(found.address().state()).isEqualTo(ROW_STATE);
        assertThat(found.address().zip()).isEqualTo(ROW_ZIP);
        assertThat(found.corpPhone()).isEqualTo(ROW_CORP_PHONE);
        assertThat(found.acctMgr()).isEqualTo(ROW_ACCT_MGR);
        assertThat(found.acctPhone()).isEqualTo(ROW_ACCT_PHONE);
        assertThat(found.active()).isEqualTo(ROW_ACTIVE);
        // The offset the dialect reads timestamptz with is not part of the contract; the instant is.
        assertThat(found.chgTime()).as("chgTime").isNotNull();
        assertThat(found.chgTime().toInstant()).isEqualTo(ROW_CHG_TIME);
        assertThat(found.chgUser()).isEqualTo(ROW_CHG_USER);
        assertThat(found.rowVersion()).isEqualTo(ACME_ROW_VERSION);

        assertThat(repository.findById(CustomerId.parse("ZZZZ"))).isEmpty();
    }

    /**
     * AddRecd: an id allocated inside the add transaction is {@code EEEF} on a fresh database, and
     * {@code save} of the new aggregate ({@code rowVersion == null}) inserts it with
     * {@code row_version} 0, writing every property to its V3 column.
     */
    @Test
    void saveNewAggregateAfterAllocationInsertsVersionZero() {
        Customer draft = newDraft("NEW CO");

        Customer saved = transactionTemplate.execute(status -> {
            CustomerId id = allocator.next();
            return repository.save(draft.withCustId(id));
        });

        assertThat(saved).isNotNull();
        assertThat(saved.custId()).isEqualTo(CustomerId.parse(DatabaseCleaner.FIRST_INTERACTIVE_ID));
        assertThat(saved.rowVersion()).isEqualTo(0L);

        StoredRow stored = storedRow(DatabaseCleaner.FIRST_INTERACTIVE_ID);
        assertThat(stored).isEqualTo(new StoredRow(DatabaseCleaner.FIRST_INTERACTIVE_ID, "NEW CO",
                draft.address().addr(), draft.address().city(), draft.address().state(),
                draft.address().zip(),
                draft.corpPhone(), draft.acctMgr(), draft.acctPhone(), draft.active(),
                DRAFT_CHG_TIME.toInstant(), MAINTENANCE_USER, 0L));
        assertThat(stored.rowVersion()).isZero();
        assertThat(stored.chgUser()).isEqualTo(MAINTENANCE_USER);
        assertThat(stored.name()).isEqualTo("NEW CO");
        assertThat(customerCount()).isEqualTo(1);
    }

    /**
     * UpdateRecd: {@code save} of a loaded aggregate issues
     * {@code UPDATE custmast SET ... WHERE custid = ? AND row_version = ?}, stores the new values with
     * the version incremented, and returns a copy carrying it. Once the capture window closes, the
     * {@code JdbcTemplate} logger is back at its previous level and logs no further statement.
     *
     * @param output the console captured by {@link OutputCaptureExtension}
     */
    @Test
    void saveLoadedAggregateIssuesVersionedUpdate(CapturedOutput output) {
        insertRow("AAAC", "BEFORE CO", 0L);
        Customer loaded = repository.findById(CustomerId.parse("AAAC")).orElseThrow();
        LogLevel levelBefore = configuredJdbcTemplateLevel();
        LogLevel effectiveBefore = effectiveJdbcTemplateLevel();

        AtomicReference<Customer> saved = new AtomicReference<>();
        List<String> statements = capturePreparedStatements(output,
                () -> saved.set(repository.save(loaded.withName("RENAMED CO"))));

        assertThat(saved.get()).isNotNull();
        assertThat(saved.get().rowVersion()).isEqualTo(1L);
        StoredRow stored = storedRow("AAAC");
        assertThat(stored.rowVersion()).isEqualTo(1L);
        assertThat(stored.name()).isEqualTo("RENAMED CO");

        List<String> updates = matching(statements, UPDATE_CUSTMAST);
        assertThat(updates).as("UPDATE statements on custmast captured from %s", statements).hasSize(1);
        assertThat(updates.get(0)).containsPattern(VERSIONED_UPDATE);

        // The logger level is restored, so statements after the window are no longer logged.
        assertThat(configuredJdbcTemplateLevel()).isEqualTo(levelBefore);
        assertThat(effectiveJdbcTemplateLevel()).isEqualTo(effectiveBefore);
        if (effectiveBefore != null && effectiveBefore.compareTo(LogLevel.DEBUG) > 0) {
            int mark = output.getAll().length();
            assertThat(repository.findById(CustomerId.parse("AAAC"))).isPresent();
            assertThat(preparedStatements(output.getAll().substring(mark)))
                    .as("prepared statements logged after the DEBUG window").isEmpty();
        }
    }

    /**
     * UpdateRecd's {@code SQLNODATA} branch: of two copies loaded at version 0, the first save wins and
     * the second, now stale, fails with {@link OptimisticLockingFailureException} itself, not wrapped in
     * a {@link DbActionExecutionException}, and changes no row.
     */
    @Test
    void staleSaveRaisesOptimisticLockingFailureAndChangesNothing() {
        insertRow("AAAC", "BEFORE CO", 0L);
        Customer first = repository.findById(CustomerId.parse("AAAC")).orElseThrow();
        Customer second = repository.findById(CustomerId.parse("AAAC")).orElseThrow();

        Customer winner = repository.save(first.withName("FIRST WINS"));
        assertThat(winner.rowVersion()).isEqualTo(1L);

        assertThatThrownBy(() -> repository.save(second.withName("SECOND LOSES")))
                .isInstanceOf(OptimisticLockingFailureException.class)
                .isNotInstanceOf(DbActionExecutionException.class)
                // Customer.toString names only the id and the version, never customer data.
                .hasMessageNotContaining("SECOND LOSES");

        StoredRow stored = storedRow("AAAC");
        assertThat(stored.name()).isEqualTo("FIRST WINS");
        assertThat(stored.rowVersion()).isEqualTo(1L);
        assertThat(customerCount()).isEqualTo(1);
    }

    /**
     * The generated {@code SELECT} of {@code findById}, the {@code INSERT} of a new aggregate and the
     * {@code UPDATE} of a loaded one (also when stale) name only V3 columns and the table itself, and
     * none of the names the default naming strategy would derive.
     *
     * @param output the console captured by {@link OutputCaptureExtension}
     */
    @Test
    void generatedSqlUsesOnlyV3ColumnNames(CapturedOutput output) {
        // The allowed names come from the database, read before DEBUG is raised.
        List<String> columns = jdbcTemplate.queryForList(
                "SELECT column_name FROM information_schema.columns"
                        + " WHERE table_schema = current_schema() AND table_name = 'custmast'",
                String.class);
        assertThat(columns).containsExactlyInAnyOrderElementsOf(V3_COLUMNS);
        Set<String> allowed = new TreeSet<>(columns);
        allowed.add(CUSTMAST);

        insertRow("AAAD", "READ CO", 0L);
        AtomicReference<Throwable> staleFailure = new AtomicReference<>();
        List<String> statements = capturePreparedStatements(output, () -> {
            Customer loaded = repository.findById(CustomerId.parse("AAAD")).orElseThrow();
            transactionTemplate.execute(
                    status -> repository.save(newDraft("INSERTED CO").withCustId(allocator.next())));
            repository.save(loaded.withName("UPDATED CO"));
            try {
                repository.save(loaded.withName("STALE CO"));
            } catch (OptimisticLockingFailureException expected) {
                staleFailure.set(expected);
            }
        });

        assertThat(staleFailure.get()).as("stale save failure")
                .isInstanceOf(OptimisticLockingFailureException.class);
        assertThat(storedRow("AAAD").name()).isEqualTo("UPDATED CO");
        assertThat(storedRow(DatabaseCleaner.FIRST_INTERACTIVE_ID).name()).isEqualTo("INSERTED CO");

        // Not vacuous: each kind of generated statement was captured.
        assertThat(matching(statements, SELECT_FROM_CUSTMAST)).as("SELECT from custmast in %s", statements)
                .isNotEmpty();
        assertThat(matching(statements, INSERT_INTO_CUSTMAST)).as("INSERT into custmast in %s", statements)
                .isNotEmpty();
        assertThat(matching(statements, UPDATE_CUSTMAST)).as("UPDATE of custmast in %s", statements)
                .hasSize(2);

        // Spring Data JDBC quotes every identifier by default, so the quoted names are the full set of
        // table, column and alias names it generated; a non-empty set also proves quoting is on.
        Set<String> identifiers = new TreeSet<>();
        for (String statement : statements) {
            Matcher matcher = QUOTED_IDENTIFIER.matcher(statement);
            while (matcher.find()) {
                identifiers.add(matcher.group(1));
            }
        }
        assertThat(identifiers).as("quoted identifiers in %s", statements).isNotEmpty();
        assertThat(allowed).as("identifiers in the generated SQL must be V3 names").containsAll(identifiers);

        assertThat(statements).as("statements naming a derived column")
                .noneMatch(statement -> DERIVED_COLUMN_NAME.matcher(statement).find());
    }

    /**
     * Writes one complete {@code custmast} row with plain SQL and an explicit column list. Every column
     * but the id, the name and the version carries the distinctive {@code ROW_*} value.
     *
     * @param custId     the 4-character id
     * @param name       the customer name
     * @param rowVersion the stored row version
     */
    private void insertRow(String custId, String name, long rowVersion) {
        jdbcTemplate.update(INSERT_ROW_SQL, custId, name, ROW_ADDR, ROW_CITY, ROW_STATE, ROW_ZIP,
                ROW_CORP_PHONE, ROW_ACCT_MGR, ROW_ACCT_PHONE, ROW_ACTIVE,
                OffsetDateTime.ofInstant(ROW_CHG_TIME, ZoneOffset.UTC), ROW_CHG_USER, rowVersion);
    }

    /**
     * Builds a valid new aggregate: no id and {@code rowVersion == null}, stamped by
     * {@value AbstractPostgresIT#MAINTENANCE_USER} at {@link #DRAFT_CHG_TIME}, because both stamp columns
     * are NOT NULL.
     *
     * @param name the customer name
     * @return the draft, ready for an allocated id
     */
    private static Customer newDraft(String name) {
        Customer draft = Customer.draft(name, new Address("1 MAIN ST", "BEVERLY HILLS", "CA", "90210"),
                "(310) 555-0100", "JOHN DOE", "(310) 555-0101", "Y")
                .withStamp(DRAFT_CHG_TIME, MAINTENANCE_USER);
        assertThat(draft.rowVersion()).as("rowVersion of a draft").isNull();
        return draft;
    }

    /**
     * Reads one row back with plain SQL.
     *
     * @param custId the 4-character id
     * @return every column of the row
     */
    private StoredRow storedRow(String custId) {
        return jdbcTemplate.queryForObject(SELECT_ROW_SQL, CustomerRepositoryIT::mapStoredRow, custId);
    }

    /**
     * Counts the rows of {@code custmast}.
     *
     * @return the row count
     */
    private int customerCount() {
        Integer count = jdbcTemplate.queryForObject("SELECT count(*) FROM custmast", Integer.class);
        return count == null ? 0 : count;
    }

    /**
     * Runs an action with the {@value #JDBC_TEMPLATE_LOGGER} logger at {@code DEBUG} and returns the
     * prepared statements logged while it ran.
     *
     * @param output the console captured by {@link OutputCaptureExtension}
     * @param action the repository calls to observe
     * @return the statements, in execution order, as logged with {@code ?} placeholders
     */
    private List<String> capturePreparedStatements(CapturedOutput output, Runnable action) {
        int mark = output.getAll().length();
        withJdbcDebug(action);
        return preparedStatements(output.getAll().substring(mark));
    }

    /**
     * Raises the {@value #JDBC_TEMPLATE_LOGGER} logger to {@code DEBUG} for the duration of an action,
     * then restores the level it was configured with; a previous {@code null} (inherited) level is
     * restored as {@code null}, which resets the logger to inherit again.
     *
     * @param action the code to run with DEBUG logging
     */
    private void withJdbcDebug(Runnable action) {
        LogLevel previousConfiguredLevel = configuredJdbcTemplateLevel();
        loggingSystem.setLogLevel(JDBC_TEMPLATE_LOGGER, LogLevel.DEBUG);
        try {
            action.run();
        } finally {
            loggingSystem.setLogLevel(JDBC_TEMPLATE_LOGGER, previousConfiguredLevel);
        }
    }

    /**
     * Returns the level configured on the {@value #JDBC_TEMPLATE_LOGGER} logger itself.
     *
     * @return the configured level, or {@code null} when the logger inherits its level
     */
    private LogLevel configuredJdbcTemplateLevel() {
        LoggerConfiguration configuration = loggingSystem.getLoggerConfiguration(JDBC_TEMPLATE_LOGGER);
        return configuration == null ? null : configuration.getConfiguredLevel();
    }

    /**
     * Returns the level the {@value #JDBC_TEMPLATE_LOGGER} logger logs at, configured or inherited.
     *
     * @return the effective level, or {@code null} when the logging system reports none
     */
    private LogLevel effectiveJdbcTemplateLevel() {
        LoggerConfiguration configuration = loggingSystem.getLoggerConfiguration(JDBC_TEMPLATE_LOGGER);
        return configuration == null ? null : configuration.getEffectiveLevel();
    }

    /**
     * Extracts the logged prepared statements from a slice of console output.
     *
     * @param log the output written inside the capture window
     * @return the SQL of each {@code Executing prepared SQL statement [...]} line, in order
     */
    private static List<String> preparedStatements(String log) {
        List<String> statements = new ArrayList<>();
        Matcher matcher = PREPARED_STATEMENT_LOG.matcher(log);
        while (matcher.find()) {
            statements.add(matcher.group(1));
        }
        return statements;
    }

    /**
     * Selects the statements a pattern finds a match in.
     *
     * @param statements the captured statements
     * @param pattern    the pattern to look for
     * @return the matching statements, in order
     */
    private static List<String> matching(List<String> statements, Pattern pattern) {
        return statements.stream().filter(statement -> pattern.matcher(statement).find()).toList();
    }

    /**
     * Maps one plain-SQL row to a {@link StoredRow}.
     *
     * @param rs     the result set, positioned on the row
     * @param rowNum the row number, unused
     * @return the row's columns
     * @throws SQLException if a column cannot be read
     */
    private static StoredRow mapStoredRow(ResultSet rs, int rowNum) throws SQLException {
        OffsetDateTime chgTime = rs.getObject("chgtime", OffsetDateTime.class);
        return new StoredRow(rs.getString("custid"), rs.getString("name"), rs.getString("addr"),
                rs.getString("city"), rs.getString("state"), rs.getString("zip"), rs.getString("corpphone"),
                rs.getString("acctmgr"), rs.getString("acctphone"), rs.getString("active"),
                chgTime == null ? null : chgTime.toInstant(), rs.getString("chguser"),
                rs.getLong("row_version"));
    }

    /**
     * One {@code custmast} row as stored, read with plain SQL, the change time as an instant.
     *
     * @param custId     {@code custid}
     * @param name       {@code name}
     * @param addr       {@code addr}
     * @param city       {@code city}
     * @param state      {@code state}
     * @param zip        {@code zip}
     * @param corpPhone  {@code corpphone}
     * @param acctMgr    {@code acctmgr}
     * @param acctPhone  {@code acctphone}
     * @param active     {@code active}
     * @param chgTime    {@code chgtime}
     * @param chgUser    {@code chguser}
     * @param rowVersion {@code row_version}
     */
    private record StoredRow(String custId, String name, String addr, String city, String state,
            String zip, String corpPhone, String acctMgr, String acctPhone, String active, Instant chgTime,
            String chgUser, long rowVersion) {
    }
}
