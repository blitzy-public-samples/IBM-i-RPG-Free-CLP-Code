package com.democorp.customermaster.repository;

import com.democorp.customermaster.domain.CustomerId;
import com.democorp.customermaster.domain.CustomerSummary;
import com.democorp.customermaster.domain.SearchCriteria;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * The customer search list query: one keyset-paginated page of {@link CustomerSummary} rows, built
 * from fixed SQL fragments for the filters a {@link SearchCriteria} carries.
 *
 * <p><b>What it replaces.</b> PMTCUSTR's cursor {@code ItemCur}
 * [5250_Subfile/PMTCUSTR.SQLRPGLE:208-223]:
 * <pre>
 * select NAME, CITY, STATE, ZIP, ACTIVE, CUSTID from CUSTMAST
 * where NAME LIKE :wkName and CITY LIKE :wkCity
 *   and STATE between :wkStateLow and :wkStateHigh
 *   and ACTIVE between :wkActiveLow and :wkActiveHigh
 * order by NAME, CITY, STATE optimize for 13 rows for fetch only
 * </pre>
 * with the host variables {@code ProcessSearchCriteria} builds
 * [5250_Subfile/PMTCUSTR.SQLRPGLE:625-665]. The target keeps no open cursor between requests, so
 * each page is one statement that restarts after the last row served.
 *
 * <p><b>The statement.</b> Without a literal lead, one ordered {@code SELECT}:
 * <pre>
 * SELECT custid, name, city, state, left(zip, 5) AS zip5, active FROM custmast
 * [WHERE &lt;name&gt; AND &lt;city&gt; AND &lt;state&gt; AND &lt;active&gt; AND &lt;keyset&gt;]
 * ORDER BY name, city, state, custid LIMIT :limit
 * </pre>
 * When the name or city pattern has a literal lead (one that yields an index prefix; see "Name and
 * city patterns" below), the same select list and predicates, that lead's among them, form a
 * materialized candidate set, and the page is ordered and limited outside it:
 * <pre>
 * WITH candidates AS MATERIALIZED (
 *   SELECT custid, name, city, state, left(zip, 5) AS zip5, active FROM custmast
 *   WHERE &lt;name and/or city&gt; [AND &lt;state&gt; AND &lt;active&gt; AND &lt;keyset&gt;])
 * SELECT custid, name, city, state, zip5, active FROM candidates
 * ORDER BY name, city, state, custid LIMIT :limit
 * </pre>
 * Each bracketed predicate is added only when its input is present, so the planner never sees an
 * always-true optional branch that would defeat index use. {@code zip5} is the first five characters
 * of the ZIP, as the subfile field {@code SF_ZIP 5A} showed [5250_Subfile/PMTCUSTD.DSPF:71].
 * {@code custid} is the unique tiebreaker keyset pagination needs. {@code LIMIT} replaces
 * {@code OPTIMIZE FOR 13 ROWS}, which was only a hint, and the read-only transaction replaces
 * {@code FOR FETCH ONLY}. Every value is a bound parameter; no filter or cursor text is ever placed
 * in the SQL, so names such as {@code URNA \NUNC\ COMPANY} and {@code NIBH L'LOR COMPANY} match
 * literally and nothing a user types can alter the statement.
 *
 * <p><b>Why a literal lead is fenced.</b> {@code name} is the leading key of the {@code ORDER BY}
 * and of {@code custmast_search_keyset}. In one statement the planner can cost an ordered walk of
 * that index with the {@code LIKE} predicates as filters, assuming the matches are spread evenly
 * through the order so that 13 arrive early. A name lead's matches are instead clustered at the
 * lead's position in the leading key, so for a late lead such as {@code Z} the walk examines almost
 * the whole table before its first match. A city lead's matches arrive early in that walk only if
 * they are in fact spread evenly through the name order, so the walk's cost rests on the planner's
 * estimate rather than on the lead. {@code MATERIALIZED} stops the planner from pushing the
 * {@code ORDER BY} and {@code LIMIT} into the candidate scan: the candidate set is bounded by the
 * lead's matches, which {@code custmast_name} or {@code custmast_city} returns, the {@code rpad}
 * check (when issued) filters them, and a top-N sort keeps the page. Inside the fence the planner
 * still chooses the access path, for example the narrower of two leads, an index or bitmap scan, or
 * a {@code BitmapAnd} with the keyset position. Only a search with no literal lead in either
 * pattern keeps the single statement and the ordered walk (for a state filter,
 * {@code custmast_state} when the planner costs it lower): no filter, the state and active filters,
 * a keyset position alone, and patterns that start with a wildcard, which are evaluated over the
 * walk as the source scans.
 *
 * <table>
 *   <caption>Predicates, in the order they are added</caption>
 *   <tr><th>Input</th><th>Source rule</th><th>Fragment</th></tr>
 *   <tr><td>{@code name} (non-blank)</td><td>{@code NAME LIKE :wkName}, {@code NAME CHAR(40)}</td>
 *       <td>See "Name and city patterns" below; parameters {@code namePattern},
 *       {@code namePrefix}</td></tr>
 *   <tr><td>{@code city} (non-blank)</td><td>{@code CITY LIKE :wkCity}, {@code CITY CHAR(20)}</td>
 *       <td>The same construction; parameters {@code cityPattern}, {@code cityPrefix}</td></tr>
 *   <tr><td>{@code state} (non-blank)</td><td>{@code STATE between SC_STATE and SC_STATE}; blank
 *       meant {@code ' '..'ZZ'}</td><td>{@code state = CAST(:state AS char(2))}</td></tr>
 *   <tr><td>{@code includeInactive == false}</td><td>{@code ACTIVE between 'Y' and 'Y'}; with F9,
 *       {@code ' '..'Z'}</td><td>{@code active = 'Y'}; nothing when inactive rows are included,
 *       because the {@code custmast_active_ck} CHECK limits the column to {@code Y} and
 *       {@code N}</td></tr>
 *   <tr><td>{@code cursor} (not the first page)</td><td>The next fetch on the open cursor</td>
 *       <td>{@code (name, city, state, custid) > (:kName, :kCity, CAST(:kState AS char(2)),
 *       CAST(:kId AS char(4)))}</td></tr>
 * </table>
 *
 * <p><b>Name and city patterns.</b> Db2 matches {@code LIKE} against the whole blank-padded
 * {@code CHAR} value [5250_Subfile/Custmast2.sql:11,13] and has no default escape character; the
 * target columns are {@code varchar} with no padding, and PostgreSQL's default {@code LIKE} escape is
 * {@code \}. {@link #likeParts(String)} therefore builds the source pattern and its index aid:
 * <ol>
 *   <li><b>Pattern.</b> The filter plus {@code %}, cut to {@value #FILTER_WIDTH} code points, as
 *       {@code wkName = %trim(SC_NAME) + '%'} assigns into a {@code varchar(13)}
 *       [5250_Subfile/PMTCUSTR.SQLRPGLE:201-202,633-634]. A full 13-character entry thus carries no
 *       wildcard and finds nothing against the padded value: a preserved source defect.</li>
 *   <li><b>Escape parity.</b> Every {@code \} is doubled after the cut, so PostgreSQL's escape is
 *       inert, as on Db2. User {@code %} and {@code _} stay wildcards, and a {@code _} can match a pad
 *       blank, as on Db2.</li>
 *   <li><b>Match.</b> {@code rpad(name, 40) LIKE :namePattern} ({@code rpad(city, 20)} for the city)
 *       compares against the padded value exactly as Db2 does.</li>
 *   <li><b>Index path.</b> {@code name LIKE :namePrefix}: the pattern's literal lead (everything before
 *       its first {@code %} or {@code _}) with trailing blanks removed, plus {@code %}. Every padded
 *       match starts with that lead, so this predicate only narrows the rows, and lets the
 *       {@code varchar_pattern_ops} indexes {@code custmast_name} and {@code custmast_city} serve them.
 *       It is omitted when the stripped lead is empty.</li>
 *   <li><b>Simplification.</b> When the pattern is exactly a non-empty literal lead plus one final
 *       {@code %}, and the lead does not end in a blank, padding cannot contribute to a match, so
 *       {@code name LIKE :namePattern} alone is issued. A lead that ends in a blank keeps
 *       {@code rpad}, because padding can complete it.</li>
 * </ol>
 *
 * <table>
 *   <caption>Filters and the fragments they produce</caption>
 *   <tr><th>Filter</th><th>Pattern</th><th>Fragments</th><th>Result</th></tr>
 *   <tr><td>{@code ABC}</td><td>{@code ABC%}</td><td>{@code name LIKE :namePattern}</td>
 *       <td>Fenced: {@code custmast_name} serves the candidate set (index or bitmap scan, the
 *       planner's choice), then a top-N sort; no {@code rpad}</td></tr>
 *   <tr><td>{@code AB_}</td><td>{@code AB_%}</td>
 *       <td>{@code rpad(name, 40) LIKE 'AB_%'} and {@code name LIKE 'AB%'}</td>
 *       <td>Fenced on lead {@code AB}; finds {@code AB}: the {@code _} matches a pad
 *       blank</td></tr>
 *   <tr><td>{@code AB}, ten blanks, {@code %}</td><td>The same 13 characters</td>
 *       <td>{@code rpad} and prefix {@code AB%}</td><td>Fenced on lead {@code AB}; finds
 *       {@code AB}, not {@code ABX}</td></tr>
 *   <tr><td>{@code ABCDEFGHIJKLM}</td><td>{@code ABCDEFGHIJKLM}, no wildcard</td>
 *       <td>{@code rpad} and prefix {@code ABCDEFGHIJKLM%}</td><td>Fenced; finds nothing
 *       (preserved defect)</td></tr>
 *   <tr><td>{@code %ONE}</td><td>{@code %ONE%}</td><td>{@code rpad} only</td>
 *       <td>No literal lead, so no index aid; alone it is not fenced and is evaluated over the
 *       ordered walk, as the source scans</td></tr>
 *   <tr><td>{@code URNA \NUNC}</td><td>{@code URNA \\NUNC%}</td><td>{@code name LIKE :namePattern}</td>
 *       <td>Fenced; matches {@code URNA \NUNC\ COMPANY} literally</td></tr>
 * </table>
 *
 * <p><b>Planning.</b> {@link #find(SearchCriteria, int)} runs in a read-only transaction that first
 * issues {@code SET LOCAL plan_cache_mode = force_custom_plan}. The patterns arrive as JDBC
 * parameters; pgjdbc switches to server-prepared statements after repeated executions, and a cached
 * generic plan cannot derive an index range from an unknown pattern. Forcing custom plans makes every
 * execution plan with the actual prefix, for this transaction only.
 *
 * <p><b>Locking.</b> The search takes no lock of its own. Behind a table lock on {@code custmast},
 * such as a generator load's {@code ACCESS EXCLUSIVE}, it waits until the holder commits, through
 * {@link LockWaitingReads}, which re-checks after every {@code customer-master.db.lock-timeout} so
 * the socket bound only ends a search on a database that stops answering.
 *
 * <p><b>Division of work.</b> {@code service/CustomerSearchService} normalizes the filters
 * ({@code TextNormalizer.filter}: trimmed, uppercased), rejects an invalid state filter (DEM0007),
 * decodes the opaque cursor, chooses {@code limit} ({@code size + 1} for the look-ahead row) and
 * applies the 9,999-row cap with {@code Cursor.served()}. This class ignores {@code served} and
 * {@code size}, raises no user-facing error, and reaches only {@code custmast}, unqualified, so the
 * schema comes from the connection ({@code DB_SCHEMA}).
 *
 * <p>Example, the second 12-row page of names starting with {@code NIBH}:
 * <pre>{@code
 * var cursor = new SearchCriteria.Cursor("NIBH L'LOR COMPANY", "AUBURN", "ME", "AAAD", 12);
 * var criteria = new SearchCriteria("NIBH", "", "", false, 12, cursor);
 * List<CustomerSummary> rows = repository.find(criteria, 13); // 12 rows plus the look-ahead row
 * SqlQuery query = repository.buildQuery(criteria, 13);       // the same statement, for EXPLAIN
 * }</pre>
 *
 * <p>The class holds no mutable state and is thread-safe.
 */
@Repository
public class CustomerSearchRepository {

    /** Width of PMTCUSTR's {@code wkName}/{@code wkCity varchar(13)}: the pattern is cut to it. */
    public static final int FILTER_WIDTH = 13;

    /** Width of the source {@code NAME CHAR(40)}: the {@code rpad} width of the name match. */
    public static final int NAME_WIDTH = 40;

    /** Width of the source {@code CITY CHAR(20)}: the {@code rpad} width of the city match. */
    public static final int CITY_WIDTH = 20;

    private static final Logger LOG = LoggerFactory.getLogger(CustomerSearchRepository.class);

    /** The select list and table; {@code zip5} is the five-character ZIP of {@code SF_ZIP 5A}. */
    private static final String SELECT_FROM =
            "SELECT custid, name, city, state, left(zip, 5) AS zip5, active FROM custmast";

    /**
     * Opens the materialized candidate set of a name or city filter with a literal lead; the select
     * list, table and predicates follow unchanged.
     */
    private static final String CANDIDATES_OPEN = "WITH candidates AS MATERIALIZED (";

    /**
     * Closes the candidate set and selects the page from it, under the labels of
     * {@link #SELECT_FROM}; the {@code ORDER BY} and {@code LIMIT} follow.
     */
    private static final String CANDIDATES_CLOSE =
            ") SELECT custid, name, city, state, zip5, active FROM candidates";

    /** Exact state; the cast keeps the varchar bind typed as the {@code char(2)} column. */
    private static final String STATE_PREDICATE = "state = CAST(:state AS char(2))";

    /** Active rows only, when F9 has not included the inactive ones. */
    private static final String ACTIVE_ONLY_PREDICATE = "active = 'Y'";

    /**
     * Keyset position after the last row served. The row comparison matches the ORDER BY. In the
     * single statement it is one index condition on {@code custmast_search_keyset}, so a deep page
     * costs what the first does; inside the candidate set of a literal lead it keeps only the lead's
     * matches after that row. The casts keep the binds typed as the {@code char} columns.
     */
    private static final String KEYSET_PREDICATE = "(name, city, state, custid) > "
            + "(:kName, :kCity, CAST(:kState AS char(2)), CAST(:kId AS char(4)))";

    /** Joins the predicates. */
    private static final String AND = " AND ";

    /** Starts the predicate list. */
    private static final String WHERE = " WHERE ";

    /** The source order plus the unique tiebreaker, and the page bound. */
    private static final String ORDER_AND_LIMIT = " ORDER BY name, city, state, custid LIMIT :limit";

    /** Parameter of the state predicate. */
    private static final String PARAM_STATE = "state";

    /** Keyset parameter: stored name of the last row served. */
    private static final String PARAM_KEY_NAME = "kName";

    /** Keyset parameter: stored city of the last row served. */
    private static final String PARAM_KEY_CITY = "kCity";

    /** Keyset parameter: stored state of the last row served. */
    private static final String PARAM_KEY_STATE = "kState";

    /** Keyset parameter: stored id of the last row served. */
    private static final String PARAM_KEY_ID = "kId";

    /** Parameter of the {@code LIMIT}. */
    private static final String PARAM_LIMIT = "limit";

    /** The source's wildcard appended to the trimmed filter. */
    private static final String PERCENT = "%";

    /** The only blank {@code rpad} supplies, and so the only one stripped from a lead. */
    private static final char PAD_BLANK = ' ';

    /** Maps one result row; the {@code custmast_custid_ck} CHECK guarantees a parseable id. */
    private static final RowMapper<CustomerSummary> ROW_MAPPER = (rs, rowNum) -> new CustomerSummary(
            CustomerId.parse(rs.getString("custid")),
            rs.getString("name"),
            rs.getString("city"),
            rs.getString("state"),
            rs.getString("zip5"),
            rs.getString("active"));

    private final NamedParameterJdbcTemplate namedJdbc;

    /** Runs the page query so that it waits through a table lock until the holder commits. */
    private final LockWaitingReads lockWaitingReads;

    /**
     * Creates the repository over the application's datasource.
     *
     * @param namedJdbc        the named-parameter template Spring Boot configures on that datasource
     * @param lockWaitingReads runs the page query so that it waits through a table lock
     * @throws NullPointerException if {@code namedJdbc} or {@code lockWaitingReads} is {@code null}
     */
    public CustomerSearchRepository(NamedParameterJdbcTemplate namedJdbc, LockWaitingReads lockWaitingReads) {
        this.namedJdbc = Objects.requireNonNull(namedJdbc, "namedJdbc");
        this.lockWaitingReads = Objects.requireNonNull(lockWaitingReads, "lockWaitingReads");
    }

    /**
     * Returns one page of the customer list for {@code c}: at most {@code limit} rows in the order
     * {@code name, city, state, custid}, starting after {@code c.cursor()} when it is set.
     *
     * <p>Runs in a read-only transaction (joining the caller's, if one is open) that first issues
     * {@code SET LOCAL plan_cache_mode = force_custom_plan}, so the statement is always planned with
     * the actual patterns; the setting ends with the transaction. The query then runs through
     * {@link LockWaitingReads#read(java.util.function.Supplier)}: while a table lock is held on
     * {@code custmast}, such as a generator load's {@code ACCESS EXCLUSIVE}, it waits until the holder
     * commits or rolls back, re-checking after every {@code customer-master.db.lock-timeout} with the
     * planning setting still in force, and then reads the committed rows. The connection returns to
     * the pool when the transaction ends; no lock and no cursor outlive the call.
     *
     * @param c     the normalized criteria; {@code size} and {@code cursor.served()} are not read
     * @param limit the most rows to return, at least 1; the service passes {@code size + 1} so the
     *              extra row tells it whether a next page exists
     * @return the matching rows in list order; empty when none matches, never {@code null}
     * @throws NullPointerException     if {@code c} is {@code null}
     * @throws IllegalArgumentException if {@code limit} is less than 1
     * @throws org.springframework.transaction.IllegalTransactionStateException if called on an
     *                                  instance that is not the Spring proxy, outside any transaction
     * @throws org.springframework.dao.DataAccessException if the database rejects or fails the
     *                                  statement; the API maps it to 500 DEM9999
     */
    @Transactional(readOnly = true)
    public List<CustomerSummary> find(SearchCriteria c, int limit) {
        SqlQuery q = buildQuery(c, limit);
        namedJdbc.getJdbcOperations().execute("SET LOCAL plan_cache_mode = force_custom_plan");
        List<CustomerSummary> rows = lockWaitingReads.read(() -> namedJdbc.query(q.sql(), q.params(), ROW_MAPPER));
        if (LOG.isDebugEnabled()) {
            // The SQL text holds only fixed fragments; parameter values (user filters) are not logged.
            LOG.debug("customer.search sql=[{}] limit={} rows={}", q.sql(), limit, rows.size());
        }
        return rows;
    }

    /**
     * Builds the exact statement and parameters {@link #find(SearchCriteria, int)} executes, without
     * touching the database. Tests and the benchmark run {@code "EXPLAIN (FORMAT JSON) " + sql()} with
     * {@link SqlQuery#params()} to inspect the production plan.
     *
     * <p>Predicates are added in the order name, city, state, active, keyset, each only when its
     * input is present; with none, the statement has no {@code WHERE}. A blank filter (see
     * {@link SearchCriteria#hasName()}, {@link SearchCriteria#hasCity()} and
     * {@link SearchCriteria#hasState()}) adds nothing. When the literal lead of the name or city
     * pattern yields an index prefix, that {@code SELECT} with all its predicates becomes the
     * materialized candidate set {@code candidates}, and the {@code ORDER BY} and {@code LIMIT}
     * apply to it (see "The statement" on the class); otherwise they close the {@code SELECT}
     * itself.
     *
     * @param c     the normalized criteria
     * @param limit the {@code LIMIT}, at least 1
     * @return the statement and its parameters in the order they were added, ending with
     *         {@code limit}
     * @throws NullPointerException     if {@code c} is {@code null}
     * @throws IllegalArgumentException if {@code limit} is less than 1
     */
    public SqlQuery buildQuery(SearchCriteria c, int limit) {
        Objects.requireNonNull(c, "criteria");
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be at least 1, was " + limit);
        }
        List<String> predicates = new ArrayList<>(6);
        Map<String, Object> params = new LinkedHashMap<>();
        boolean fenced = false;

        if (c.hasName()) {
            LikeParts name = likeParts(c.name());
            LikeColumn.NAME.addTo(name, predicates, params);
            fenced = name.prefix() != null;
        }
        if (c.hasCity()) {
            LikeParts city = likeParts(c.city());
            LikeColumn.CITY.addTo(city, predicates, params);
            fenced = fenced || city.prefix() != null;
        }
        if (c.hasState()) {
            predicates.add(STATE_PREDICATE);
            params.put(PARAM_STATE, c.state());
        }
        if (!c.includeInactive()) {
            predicates.add(ACTIVE_ONLY_PREDICATE);
        }
        SearchCriteria.Cursor cursor = c.cursor();
        if (cursor != null) {
            predicates.add(KEYSET_PREDICATE);
            params.put(PARAM_KEY_NAME, cursor.name());
            params.put(PARAM_KEY_CITY, cursor.city());
            params.put(PARAM_KEY_STATE, cursor.state());
            params.put(PARAM_KEY_ID, cursor.custid());
        }
        params.put(PARAM_LIMIT, Integer.valueOf(limit));

        StringBuilder sql = new StringBuilder();
        if (fenced) {
            sql.append(CANDIDATES_OPEN);
        }
        sql.append(SELECT_FROM);
        if (!predicates.isEmpty()) {
            sql.append(WHERE).append(String.join(AND, predicates));
        }
        if (fenced) {
            sql.append(CANDIDATES_CLOSE);
        }
        sql.append(ORDER_AND_LIMIT);
        return new SqlQuery(sql.toString(), params);
    }

    /**
     * Builds the {@code LIKE} pattern of a name or city filter and its index aid, by the five steps
     * on the class: cut {@code filter + "%"} to {@value #FILTER_WIDTH} code points, double every
     * {@code \}, take the literal lead before the first {@code %} or {@code _}, strip its trailing
     * blanks for the prefix, and decide whether the padded match is needed.
     *
     * <p>Examples: {@code "ABC"} gives {@code ("ABC%", "ABC%", false)}; {@code "AB_"} gives
     * {@code ("AB_%", "AB%", true)}; {@code "%ONE"} gives {@code ("%ONE%", null, true)};
     * {@code "ABCDEFGHIJKLM"} gives {@code ("ABCDEFGHIJKLM", "ABCDEFGHIJKLM%", true)}.
     *
     * @param filter the normalized filter, not empty
     * @return the pattern, prefix and padding decision
     * @throws NullPointerException     if {@code filter} is {@code null}
     * @throws IllegalArgumentException if {@code filter} is empty
     */
    static LikeParts likeParts(String filter) {
        Objects.requireNonNull(filter, "filter");
        if (filter.isEmpty()) {
            throw new IllegalArgumentException("filter must not be empty");
        }
        // 1. The source pattern: %trim(SC_NAME) + '%' assigned into varchar(13), which truncates.
        String raw = filter + PERCENT;
        String cut = raw.codePointCount(0, raw.length()) > FILTER_WIDTH
                ? raw.substring(0, raw.offsetByCodePoints(0, FILTER_WIDTH))
                : raw;
        // 2. Escape parity: Db2 has no default LIKE escape, PostgreSQL uses '\'. Doubling every
        //    backslash makes each one literal, so every remaining '%' and '_' is a real wildcard.
        String pattern = cut.replace("\\", "\\\\");
        // 3. The literal lead: everything before the first wildcard (backslashes stay doubled).
        int wildcard = firstWildcard(pattern);
        String lead = wildcard < 0 ? pattern : pattern.substring(0, wildcard);
        // 4. The index prefix: only rpad supplies blanks, so only U+0020 is stripped.
        int end = lead.length();
        while (end > 0 && lead.charAt(end - 1) == PAD_BLANK) {
            end--;
        }
        String prefix = end == 0 ? null : lead.substring(0, end) + PERCENT;
        // 5. Padding cannot contribute when the pattern is a literal lead plus one final '%' and the
        //    lead ends on a non-blank: a match then ends inside the stored value.
        boolean simple = !lead.isEmpty()
                && lead.charAt(lead.length() - 1) != PAD_BLANK
                && pattern.equals(lead + PERCENT);
        return new LikeParts(pattern, prefix, !simple);
    }

    /**
     * Returns the index of the first {@code LIKE} wildcard, {@code %} or {@code _}.
     *
     * @param pattern a pattern whose backslashes are already doubled
     * @return the index, or -1 when the pattern has no wildcard
     */
    private static int firstWildcard(String pattern) {
        for (int i = 0; i < pattern.length(); i++) {
            char ch = pattern.charAt(i);
            if (ch == '%' || ch == '_') {
                return i;
            }
        }
        return -1;
    }

    /**
     * The statement {@link #find(SearchCriteria, int)} runs and its named parameters.
     *
     * @param sql    the SQL text with {@code :name} placeholders, built only from fixed fragments
     * @param params the parameter values by placeholder name, in the order the predicates were added;
     *               stored as an unmodifiable copy that keeps that order
     */
    public record SqlQuery(String sql, Map<String, Object> params) {

        /**
         * Copies the parameters into an unmodifiable, insertion-ordered map.
         *
         * @throws NullPointerException if {@code sql} or {@code params} is {@code null}
         */
        public SqlQuery {
            Objects.requireNonNull(sql, "sql");
            Objects.requireNonNull(params, "params");
            params = Collections.unmodifiableMap(new LinkedHashMap<>(params));
        }
    }

    /**
     * The result of {@link #likeParts(String)} for one filter.
     *
     * @param pattern the source pattern, cut and with backslashes doubled; always bound
     * @param prefix  the literal lead with trailing blanks removed plus {@code %}, or {@code null}
     *                when that lead is empty. It is issued as its own predicate only when
     *                {@code padded} is {@code true}; otherwise the pattern itself is the prefix match
     * @param padded  {@code true} when the match must compare against the {@code rpad} value;
     *                {@code false} for the pure-prefix simplification
     */
    record LikeParts(String pattern, String prefix, boolean padded) {
    }

    /**
     * The two filtered text columns, each with its fixed fragments, parameter names and source
     * width. Keeping the fragments here means no SQL is assembled from anything but these constants.
     */
    private enum LikeColumn {

        /** {@code NAME CHAR(40)} [5250_Subfile/Custmast2.sql:11]; index {@code custmast_name}. */
        NAME("name LIKE :namePattern",
                "rpad(name, " + NAME_WIDTH + ") LIKE :namePattern",
                "name LIKE :namePrefix",
                "namePattern",
                "namePrefix"),

        /** {@code CITY CHAR(20)} [5250_Subfile/Custmast2.sql:13]; index {@code custmast_city}. */
        CITY("city LIKE :cityPattern",
                "rpad(city, " + CITY_WIDTH + ") LIKE :cityPattern",
                "city LIKE :cityPrefix",
                "cityPattern",
                "cityPrefix");

        private final String simplePredicate;
        private final String paddedPredicate;
        private final String prefixPredicate;
        private final String patternParam;
        private final String prefixParam;

        LikeColumn(String simplePredicate, String paddedPredicate, String prefixPredicate,
                String patternParam, String prefixParam) {
            this.simplePredicate = simplePredicate;
            this.paddedPredicate = paddedPredicate;
            this.prefixPredicate = prefixPredicate;
            this.patternParam = patternParam;
            this.prefixParam = prefixParam;
        }

        /**
         * Adds this column's predicates and parameters for one filter: the plain pattern match for
         * the simplification, otherwise the padded match plus the prefix predicate when a prefix
         * exists.
         *
         * @param parts      the filter's pattern, prefix and padding decision
         * @param predicates the predicate list to append to
         * @param params     the parameter map to put into
         */
        void addTo(LikeParts parts, List<String> predicates, Map<String, Object> params) {
            if (!parts.padded()) {
                predicates.add(simplePredicate);
                params.put(patternParam, parts.pattern());
                return;
            }
            predicates.add(paddedPredicate);
            params.put(patternParam, parts.pattern());
            if (parts.prefix() != null) {
                predicates.add(prefixPredicate);
                params.put(prefixParam, parts.prefix());
            }
        }
    }
}
