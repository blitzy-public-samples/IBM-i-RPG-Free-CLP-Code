package com.democorp.customermaster.service;

import com.democorp.customermaster.domain.State;
import com.democorp.customermaster.domain.TextNormalizer;
import com.democorp.customermaster.repository.StateRepository;
import com.democorp.customermaster.service.exception.InvalidSearchCriteriaException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * The one state lookup of the application: state-code validation over an immutable cache, and the
 * filtered, re-sortable state list behind the State picker.
 *
 * <p><b>Source.</b> StateVal [Service_Pgms/StateVal.sqlrpgle:16-81] becomes {@link #exists(String)};
 * its optional returned name has no caller and is not carried. MTNCUSTR's {@code Edit_SD_STATE}
 * [5250_Subfile/MTNCUSTR.SQLRPGLE:488-500], which read STATES on every validation, uses the same
 * cached lookup, so after the first load no validation queries the database; the
 * {@code custmast_state_fk} foreign key is the storage-level backstop. PMTSTATER's "Name Contains"
 * list [5250_Subfile/PMTSTATER.SQLRPGLE:150-162,395-400] becomes {@link #list(String, String)}.
 *
 * <p><b>Cache.</b> An unmodifiable map of the 58 rows migration V2 inserts, loaded on first use,
 * not at construction, so a context that never validates a state never queries STATES. No code
 * path writes {@code states}, so the cache is never refreshed or evicted, as StateVal's static array
 * never was. The load logs one INFO line, {@code states.cache loaded count=<n>}, in place of
 * StateVal's job-log line. The cache holds reference data only, never anything about a caller. An
 * empty STATES table fails the load and leaves the cache unloaded, so a later call retries (see
 * {@link #all()}).
 *
 * <p><b>Thread safety.</b> The cache reference is {@code volatile} and assigned once by
 * double-checked locking, so concurrent first calls load the table exactly once and every thread
 * then reads the same unmodifiable map without locking. {@link #list(String, String)} keeps no
 * state.
 */
@Service
public class StateService {

    private static final Logger log = LoggerFactory.getLogger(StateService.class);

    /** Width of PMTSTATED's {@code SC_NAME} "Name Contains" field [5250_Subfile/PMTSTATED.DSPF:88]. */
    private static final int NAME_CONTAINS_MAX_LENGTH = 10;

    /** Sort key for {@code ORDER BY name}: PMTSTATER's {@code SortbyName}, the initial order. */
    private static final String SORT_BY_NAME = "name";

    /** Sort key for {@code ORDER BY state}: PMTSTATER's {@code SortbyCode}, reached through F7. */
    private static final String SORT_BY_CODE = "code";

    /** The pattern PMTSTATER uses for a blank filter, which matches every state. */
    private static final String MATCH_ALL = "%%";

    /** The {@code LIKE} wildcard wrapped around a non-blank filter: "Name Contains". */
    private static final String ANY = "%";

    /** U+0000, which no PostgreSQL text value can hold; a filter carrying it is rejected. */
    private static final char NUL = '\u0000';

    /** PostgreSQL's default {@code LIKE} escape character, which Db2 does not have. */
    private static final String BACKSLASH = "\\";

    /** A backslash escaped by itself, so it matches one literal backslash. */
    private static final String ESCAPED_BACKSLASH = "\\\\";

    private final StateRepository stateRepository;

    /** Guards the one-time load of {@link #byCode}. */
    private final Object cacheLock = new Object();

    /**
     * Every state keyed by its code, in code order; {@code null} until the first call that needs it,
     * then an unmodifiable map that is never replaced.
     */
    private volatile Map<String, State> byCode;

    /**
     * Creates the service. The repository is not called here; the cache loads on first use.
     *
     * @param stateRepository read access to {@code states}
     */
    public StateService(StateRepository stateRepository) {
        this.stateRepository = Objects.requireNonNull(stateRepository, "stateRepository");
    }

    /**
     * Tells whether {@code code} is a state code in STATES: StateVal's lookup and the existence test of
     * MTNCUSTR's {@code Edit_SD_STATE}.
     *
     * <p>The code is normalized with {@link TextNormalizer#field(String)}: uppercased, as StateVal's
     * {@code p_code = %upper(p_code)}, with trailing blanks removed, because a Db2 {@code CHAR}
     * comparison ignores them. Leading characters are kept as typed, so {@code " C"} does not match
     * {@code "C "} or anything else. A {@code null}, empty or blank code is never a state; the State
     * rule reports it with DEM0503, as {@code Edit_SD_STATE} did for a blank {@code SD_STATE}.
     *
     * <p>Answered from the cache, with no database query after the first load.
     *
     * @param code the code as entered or as returned by address standardization; may be {@code null}
     * @return {@code true} if the normalized code is in STATES
     * @throws IllegalStateException if the first load finds STATES empty (see {@link #all()})
     */
    public boolean exists(String code) {
        if (code == null || code.isBlank()) {
            return false;
        }
        return cache().containsKey(TextNormalizer.field(code));
    }

    /**
     * Returns every state in code order, from the cache.
     *
     * <p>The generator uses it to keep only CSZ rows whose state exists, because
     * {@code custmast_state_fk} would reject any other.
     *
     * @return an unmodifiable list of all states, ordered by code under {@code customer_sort}
     * @throws IllegalStateException if the first load finds STATES empty. StateVal's multi-row
     *                               {@code FETCH} failed through SQLProblem when it returned no row and
     *                               left the array unloaded; here the cache likewise stays unloaded, so
     *                               a later call loads again once migration V2 has run
     */
    public List<State> all() {
        return List.copyOf(cache().values());
    }

    /**
     * Lists the states whose name contains {@code nameContains}, in the requested order: PMTSTATER's
     * "Name Contains" filter and its F7 sort toggle, behind {@code GET /api/states}.
     *
     * <p><b>Rules,</b> in this order:
     * <ol>
     *   <li><b>Length.</b> A {@code nameContains} longer than 10 code
     *       points, the width of {@code SC_NAME} [5250_Subfile/PMTSTATED.DSPF:88], is rejected with
     *       APP0400 on field {@code nameContains}, the API's "length over the column size" rule. The
     *       screen prevented such input by its field length; the UI prevents it with
     *       {@code maxLength}.</li>
     *   <li><b>U+0000.</b> A {@code nameContains} that contains U+0000, which PostgreSQL text cannot
     *       hold, is rejected with APP0400 on field {@code nameContains}, so it never reaches the
     *       query; the character is not stripped or replaced instead.</li>
     *   <li><b>Sort.</b> {@code null} or empty means {@code "name"}, PMTSTATER's initial order.
     *       Exactly {@code "name"} or {@code "code"} is accepted; anything else, a blank value such
     *       as {@code " "} included, is rejected with APP0400 on field {@code sort}.</li>
     *   <li><b>Pattern.</b> The filter is normalized with {@link TextNormalizer#filter(String)}
     *       (trimmed at both ends, as {@code %trim(SC_NAME)}, and uppercased, because PMTSTATED's field
     *       has no {@code CHECK(LC)}). Every {@code \} is doubled, because PostgreSQL treats {@code \} as
     *       the default {@code LIKE} escape and Db2 has none, so a typed backslash stays literal.
     *       {@code %} and {@code _} are not escaped: they stay wildcards, as in the source. The pattern
     *       is {@code "%%"} for a blank filter, else {@code "%" + filter + "%"}.</li>
     * </ol>
     *
     * <p>Matching and ordering happen in SQL only: the repository compares
     * {@code rpad(upper(name), 30)}, so a typed {@code _} can match a pad blank of the 30-character name
     * as on Db2 ({@code texas_} finds Texas), and orders by {@code name} or {@code state} under the
     * {@code customer_sort} collation. This method neither filters nor sorts in Java and opens no
     * transaction itself; the repository call runs in the read-only transaction {@link StateRepository}
     * declares.
     *
     * @param nameContains the "Name Contains" filter as received; may be {@code null}, which, like a
     *                     blank filter, lists every state
     * @param sort         {@code "name"} or {@code "code"}; {@code null} or empty means {@code "name"}
     * @return the matching states in the requested order; empty when none matches, never {@code null}
     * @throws InvalidSearchCriteriaException with code APP0400, on field {@code nameContains} when the
     *                                        filter is too long or contains U+0000, or on field
     *                                        {@code sort} when the sort is not {@code name} or
     *                                        {@code code}
     */
    public List<State> list(String nameContains, String sort) {
        if (nameContains != null
                && nameContains.codePointCount(0, nameContains.length()) > NAME_CONTAINS_MAX_LENGTH) {
            throw new InvalidSearchCriteriaException(
                    InvalidSearchCriteriaException.APP0400,
                    "nameContains",
                    List.of("nameContains must be at most " + NAME_CONTAINS_MAX_LENGTH + " characters"));
        }
        if (nameContains != null && nameContains.indexOf(NUL) >= 0) {
            throw new InvalidSearchCriteriaException(
                    InvalidSearchCriteriaException.APP0400,
                    "nameContains",
                    List.of("nameContains must not contain U+0000"));
        }
        final String key = sortKey(sort);
        return stateRepository.search(namePattern(nameContains), key);
    }

    /**
     * Resolves the sort parameter to the repository's sort key.
     *
     * <p>Only an absent ({@code null}) or empty value means {@code "name"}, the default order; the
     * controller's default already turns {@code sort=} into {@code "name"}. Otherwise the value must
     * be exactly {@code "name"} or {@code "code"}: it is neither trimmed nor case-folded, so a blank,
     * padded or differently cased value such as {@code " "}, {@code "name "} or {@code "NAME"} is an
     * unknown sort.
     *
     * @param sort the parameter as received; may be {@code null}
     * @return {@code "name"} or {@code "code"}
     * @throws InvalidSearchCriteriaException APP0400 on field {@code sort} for any other value
     */
    private static String sortKey(String sort) {
        if (sort == null || sort.isEmpty() || SORT_BY_NAME.equals(sort)) {
            return SORT_BY_NAME;
        }
        if (SORT_BY_CODE.equals(sort)) {
            return SORT_BY_CODE;
        }
        throw new InvalidSearchCriteriaException(
                InvalidSearchCriteriaException.APP0400,
                "sort",
                List.of("sort must be " + SORT_BY_NAME + " or " + SORT_BY_CODE));
    }

    /**
     * Builds PMTSTATER's {@code DESCLike} for PostgreSQL: {@code "%%"} for a blank filter, else
     * {@code "%" + filter + "%"}, with the filter normalized and its backslashes doubled.
     *
     * @param nameContains the filter as received; may be {@code null}
     * @return the {@code LIKE} pattern, never {@code null}
     */
    private static String namePattern(String nameContains) {
        final String filter = TextNormalizer.filter(nameContains);
        if (filter.isEmpty()) {
            return MATCH_ALL;
        }
        return ANY + filter.replace(BACKSLASH, ESCAPED_BACKSLASH) + ANY;
    }

    /**
     * Returns the cache, loading it on the first call by double-checked locking.
     *
     * @return the unmodifiable map of every state keyed by code, in code order
     */
    private Map<String, State> cache() {
        Map<String, State> loaded = byCode;
        if (loaded == null) {
            synchronized (cacheLock) {
                loaded = byCode;
                if (loaded == null) {
                    loaded = load();
                    byCode = loaded;
                }
            }
        }
        return loaded;
    }

    /**
     * Reads every state once, in the code order {@link StateRepository#findAll()} returns, keyed by the
     * code with trailing blanks removed ({@code char(2)} codes are normally already exact).
     *
     * <p>Decision: an empty STATES table fails the load instead of caching an empty map, which would make
     * every state invalid for the life of the process. StateVal failed the same way: its {@code FETCH}
     * of no rows ended in SQLProblem with {@code StatesLoaded} still off [Service_Pgms/StateVal.sqlrpgle:51-61].
     * A caller then sees a server error (500 DEM9999 through the API), not a misleading DEM0503.
     *
     * @return the unmodifiable map, never empty
     * @throws IllegalStateException if STATES holds no rows
     */
    private Map<String, State> load() {
        final Map<String, State> map = new LinkedHashMap<>();
        for (State state : stateRepository.findAll()) {
            map.put(TextNormalizer.field(state.state()), state);
        }
        if (map.isEmpty()) {
            throw new IllegalStateException("The states table holds no rows; state lookup is unavailable");
        }
        log.info("states.cache loaded count={}", map.size());
        return Collections.unmodifiableMap(map);
    }
}
