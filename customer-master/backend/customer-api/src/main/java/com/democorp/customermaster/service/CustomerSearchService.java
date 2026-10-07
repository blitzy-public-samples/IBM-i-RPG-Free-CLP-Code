package com.democorp.customermaster.service;

import com.democorp.customermaster.config.AppProperties;
import com.democorp.customermaster.domain.CustomerId;
import com.democorp.customermaster.domain.CustomerSummary;
import com.democorp.customermaster.domain.SearchCriteria;
import com.democorp.customermaster.domain.SearchPage;
import com.democorp.customermaster.domain.TextNormalizer;
import com.democorp.customermaster.messages.MessageCatalog;
import com.democorp.customermaster.repository.CustomerSearchRepository;
import com.democorp.customermaster.service.exception.InvalidSearchCriteriaException;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.ObjectWriter;
import java.io.IOException;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * The business rules of the customer search list: filter validation and normalization, the
 * opaque next-page cursor, the page size, the 9,999-row cap and the list notices.
 *
 * <p><b>What it replaces.</b> Three PMTCUSTR routines [5250_Subfile/PMTCUSTR.SQLRPGLE]:
 * <ul>
 *   <li>{@code ProcessSearchCriteria} (lines 625-665) trims the name and city entries before
 *       building their patterns, selects every state for a blank State entry and sends DEM0007
 *       "State selection field is invalid." for one whose trimmed length is not 2, and restricts
 *       the list to active customers unless F9 is on;</li>
 *   <li>{@code SflFirstPage} (lines 532-545) sends DEM0002 "No records match the selection
 *       criteria" when the first fetch finds no row;</li>
 *   <li>{@code SflFillPage} (lines 562-600), driven by the PageDown branch (lines 287-299), writes
 *       up to one 12-row page, reads one row ahead to decide whether more follow, and stops with
 *       DEM0006 "Too many records. Change the selection criteria." when it writes row 9,999
 *       ({@code MAXSFLRECDS}, line 180).</li>
 * </ul>
 * The source kept {@code ItemCur} open between screens. This service is stateless: every page is
 * one request, and the position after the last row served travels to the client and back as an
 * opaque cursor, so no cursor, session or lock outlives a call.
 *
 * <p><b>Division of work.</b>
 * <ul>
 *   <li>This class validates and normalizes the request, decodes and encodes the cursor, chooses
 *       the page limit and the look-ahead row, applies the cap and resolves the notice texts
 *       through {@link MessageCatalog}.</li>
 *   <li>{@link CustomerSearchRepository} owns all SQL: the LIKE patterns (the {@code %} appended
 *       inside the 13-character limit, backslashes doubled, {@code rpad} padding parity, the
 *       prefix predicate), the state and active predicates, the keyset comparison, the
 *       {@code ORDER BY name, city, state, custid} and its own read-only transaction with
 *       {@code SET LOCAL plan_cache_mode = force_custom_plan}. This class therefore opens no
 *       transaction and never builds or edits a pattern.</li>
 *   <li>The controller maps the returned domain {@link SearchPage} to its DTO; nothing here knows
 *       HTTP. Every rejected input raises {@link InvalidSearchCriteriaException}, which
 *       {@code ApiExceptionHandler} answers with 400 problem+json naming the field.</li>
 * </ul>
 *
 * <p><b>Request rules.</b>
 * <table>
 *   <caption>Input, rule and failure</caption>
 *   <tr><th>Input</th><th>Rule</th><th>Failure</th></tr>
 *   <tr><td>{@code size}</td><td>Absent means {@code customer-master.search.default-size} (12, the
 *       subfile page); otherwise 1 to {@code max-size} (100)</td>
 *       <td>APP0400 on {@code size}</td></tr>
 *   <tr><td>{@code name}, {@code city}</td><td>At most 13 code points as received, the width of
 *       {@code SC_NAME}/{@code SC_CITY} [5250_Subfile/PMTCUSTD.DSPF:95-98] and of the
 *       {@code varchar(13)} host variables [5250_Subfile/PMTCUSTR.SQLRPGLE:201-202]; then free of
 *       U+0000, which PostgreSQL text cannot hold; then
 *       {@link TextNormalizer#filter(String)}: trimmed at both ends, as {@code %trim(SC_NAME)},
 *       and uppercased by the length-preserving rule</td>
 *       <td>APP0400 on {@code name} or {@code city}</td></tr>
 *   <tr><td>{@code state}</td><td>Normalized as above; blank selects every state, exactly 2 code
 *       points is an exact filter, and any other length is DEM0007. A 2-code-point value that
 *       contains U+0000 is then rejected. An unknown 2-letter code simply matches nothing; it is
 *       not checked against STATES, as the source did not check it</td>
 *       <td>DEM0007 on {@code state}; APP0400 on {@code state} for U+0000</td></tr>
 *   <tr><td>{@code includeInactive}</td><td>F9: {@code false} lists active customers only</td>
 *       <td>None</td></tr>
 *   <tr><td>{@code cursor}</td><td>Blank means the first page; otherwise a cursor this class
 *       issued (see below)</td><td>APP0400 on {@code cursor}</td></tr>
 * </table>
 * Inputs are checked in that order and the first failure is reported. Every APP0400 carries one
 * fixed English reason that never echoes user input.
 *
 * <p><b>Cursor.</b> Base64url without padding over the UTF-8 JSON object
 * {@code {"name", "city", "state", "custid", "served"}}: the stored sort keys of the last row
 * served and the number of rows served so far. It carries no filters; the client resends the same
 * filters with every page, as the UI does. It is opaque to clients and fully validated on decode,
 * so a tampered value is rejected as 400 APP0400 before any SQL runs.
 *
 * <p><b>Paging and the cap.</b> A page may hold at most {@code min(size, maxRows - served)} rows,
 * and the repository is asked for one more, the look-ahead row that {@code SflFillPage} read
 * before deciding whether {@code SFLEND} showed "More...". When the rows served reach
 * {@code maxRows} (9,999), the page carries {@code limitReached}, notice DEM0006 and no next
 * cursor, whether or not further rows exist, because the source raised DEM0006 on writing row
 * 9,999 before it fetched again [5250_Subfile/PMTCUSTR.SQLRPGLE:587-592]. Otherwise the next
 * cursor is present exactly when the look-ahead row was found, and it encodes the last row
 * returned, never the look-ahead row.
 *
 * <p>Example, a first page and the page after it:
 * <pre>{@code
 * SearchPage first = service.search("nibh", null, "", false, null, null);
 * SearchPage second = service.search("nibh", null, "", false, null, first.nextCursor());
 * service.search(null, null, "C", false, null, null);  // InvalidSearchCriteriaException DEM0007
 * }</pre>
 *
 * <p>The service holds only immutable collaborators (Jackson readers and writers are immutable)
 * and is thread-safe.
 */
@Service
public class CustomerSearchService {

    private static final Logger LOG = LoggerFactory.getLogger(CustomerSearchService.class);

    /** Catalog key sent when the first page matches no row (SflFirstPage). */
    static final String NO_MATCH = "DEM0002";

    /** Catalog key sent on the page that reaches the row cap (SflFillPage, PageDown). */
    static final String TOO_MANY = "DEM0006";

    /** Width of the name and city entries: {@code SC_NAME 13A}, {@code SC_CITY 13A}. */
    private static final int FILTER_WIDTH = CustomerSearchRepository.FILTER_WIDTH;

    /** Width of the state entry, {@code SC_STATE 2A}, and of the stored {@code state char(2)}. */
    private static final int STATE_WIDTH = 2;

    /** U+0000, which no PostgreSQL text value can hold; a filter carrying it is rejected. */
    private static final char NUL = '\u0000';

    /**
     * Longest cursor accepted. An issued cursor holds at most 40 + 20 + 2 + 4 characters of keys
     * and a 4-digit count; even with every key character JSON-escaped it stays well below this.
     */
    private static final int MAX_CURSOR_LENGTH = 1024;

    private static final String FIELD_SIZE = "size";
    private static final String FIELD_NAME = "name";
    private static final String FIELD_CITY = "city";
    private static final String FIELD_STATE = "state";
    private static final String FIELD_CURSOR = "cursor";

    /** Cursor JSON property names; the payload record and the decoder share them. */
    private static final String KEY_NAME = "name";
    private static final String KEY_CITY = "city";
    private static final String KEY_STATE = "state";
    private static final String KEY_CUSTID = "custid";
    private static final String KEY_SERVED = "served";

    /** Every cursor property; a decoded object must hold exactly these. */
    private static final List<String> CURSOR_KEYS =
            List.of(KEY_NAME, KEY_CITY, KEY_STATE, KEY_CUSTID, KEY_SERVED);

    /** The one reason every cursor failure carries, so no detail of a tampered value is echoed. */
    private static final String CURSOR_INVALID_REASON = "cursor is not valid";

    private final CustomerSearchRepository customerSearchRepository;
    private final AppProperties appProperties;
    private final MessageCatalog messageCatalog;

    /** Writes the cursor payload with the Boot-configured mapper. */
    private final ObjectWriter cursorWriter;

    /**
     * Reads cursor JSON strictly: content after the object and a property given twice are
     * rejected rather than silently ignored or overwritten, so only the exact shape this class
     * writes is accepted.
     */
    private final ObjectReader cursorReader;

    /**
     * Creates the service.
     *
     * @param customerSearchRepository the keyset search query
     * @param appProperties            the {@code customer-master.search.*} limits: default and
     *                                 maximum page size, and the row cap
     * @param messageCatalog           the texts of the DEM0002 and DEM0006 notices
     * @param objectMapper             the Boot-managed mapper, used for the cursor JSON
     * @throws NullPointerException if any argument is {@code null}
     */
    public CustomerSearchService(
            CustomerSearchRepository customerSearchRepository,
            AppProperties appProperties,
            MessageCatalog messageCatalog,
            ObjectMapper objectMapper) {
        this.customerSearchRepository =
                Objects.requireNonNull(customerSearchRepository, "customerSearchRepository");
        this.appProperties = Objects.requireNonNull(appProperties, "appProperties");
        this.messageCatalog = Objects.requireNonNull(messageCatalog, "messageCatalog");
        Objects.requireNonNull(objectMapper, "objectMapper");
        this.cursorWriter = objectMapper.writerFor(CursorPayload.class);
        this.cursorReader = objectMapper.reader()
                .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .with(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY);
    }

    /**
     * Returns one page of the customer list.
     *
     * <p>Validates and normalizes every input (see the class description), decodes the cursor,
     * fetches at most {@code min(size, maxRows - served) + 1} rows, and builds the page:
     * <ul>
     *   <li>the rows served on this page, in {@code name, city, state, custid} order;</li>
     *   <li>{@code limitReached}, notice DEM0006 and no next cursor when this page brings the rows
     *       served to the cap;</li>
     *   <li>otherwise a next cursor when the look-ahead row was found, and {@code null} at the
     *       bottom of the list;</li>
     *   <li>notice DEM0002 when the first page matches nothing. A later page that comes back empty,
     *       because rows changed after the previous page, carries no notice.</li>
     * </ul>
     *
     * @param name            "Name starts with": the name prefix, or {@code null}/blank for none
     * @param city            "City starts with": the city prefix, or {@code null}/blank for none
     * @param state           "State": a 2-character code, or {@code null}/blank for every state
     * @param includeInactive F9: {@code true} to include customers whose {@code active} is
     *                        {@code N}
     * @param size            rows per page, or {@code null} for the configured default (12)
     * @param cursor          the {@code nextCursor} of the previous page, or {@code null}/blank
     *                        for the first page
     * @return the page; never {@code null}
     * @throws InvalidSearchCriteriaException DEM0007 on {@code state} for a state that is neither
     *                                        blank nor 2 characters; APP0400 on {@code size},
     *                                        {@code name}, {@code city}, {@code state} or
     *                                        {@code cursor} for an out-of-range size, an
     *                                        over-long filter, a filter containing U+0000 or a
     *                                        cursor this service did not issue
     * @throws org.springframework.dao.DataAccessException if the query fails; the API answers it
     *                                        with 500 DEM9999
     */
    public SearchPage search(
            String name,
            String city,
            String state,
            boolean includeInactive,
            Integer size,
            String cursor) {
        AppProperties.Search limits = appProperties.search();
        int maxRows = limits.maxRows();

        int pageSize = resolveSize(size, limits.maxSize(), limits.defaultSize());
        String normalizedName = normalizePrefix(name, FIELD_NAME);
        String normalizedCity = normalizePrefix(city, FIELD_CITY);
        String normalizedState = normalizeState(state);
        SearchCriteria.Cursor position = decodeCursor(cursor, maxRows);

        // remaining >= 1: decodeCursor accepts only served < maxRows.
        int served = position == null ? 0 : position.served();
        int remaining = maxRows - served;
        int pageLimit = Math.min(pageSize, remaining);

        // size carries the page limit too, so a repository that reads criteria.size() + 1 and one
        // that honours the limit argument issue the same LIMIT.
        SearchCriteria criteria = new SearchCriteria(normalizedName, normalizedCity,
                normalizedState, includeInactive, pageLimit, position);
        List<CustomerSummary> rows =
                customerSearchRepository.find(criteria, Math.addExact(pageLimit, 1));

        int returned = Math.min(rows.size(), pageLimit);
        boolean lookAheadFound = rows.size() > pageLimit;
        List<CustomerSummary> items = List.copyOf(rows.subList(0, returned));
        int servedAfter = served + returned;

        SearchPage page;
        if (servedAfter >= maxRows) {
            // SflFillPage: DEM0006 on writing row MAXSFLRECDS, before any further fetch, so the
            // list ends here whether or not more rows match.
            page = new SearchPage(items, null, true, notice(TOO_MANY));
        } else if (returned == 0) {
            // SflFirstPage: DEM0002 only when the first fetch finds nothing. An empty later page
            // (rows changed since the previous page) simply ends the list.
            page = SearchPage.empty(position == null ? notice(NO_MATCH) : null);
        } else {
            String nextCursor = lookAheadFound
                    ? encodeCursor(items.get(returned - 1), servedAfter)
                    : null;
            page = new SearchPage(items, nextCursor, false, null);
        }

        if (LOG.isDebugEnabled()) {
            // Counts only: filters and cursor keys are user data and are never logged.
            LOG.debug("customer.search firstPage={} served={} returned={} more={} limitReached={}",
                    position == null, served, returned, page.nextCursor() != null,
                    page.limitReached());
        }
        return page;
    }

    /**
     * Resolves the requested page size.
     *
     * @param size        the requested size, or {@code null}
     * @param maxSize     the largest size accepted
     * @param defaultSize the size used when none is requested
     * @return the page size, from 1 to {@code maxSize}
     * @throws InvalidSearchCriteriaException APP0400 on {@code size} when it is outside that range
     */
    private static int resolveSize(Integer size, int maxSize, int defaultSize) {
        if (size == null) {
            return defaultSize;
        }
        if (size < 1 || size > maxSize) {
            throw new InvalidSearchCriteriaException(InvalidSearchCriteriaException.APP0400,
                    FIELD_SIZE, List.of("size must be between 1 and " + maxSize));
        }
        return size;
    }

    /**
     * Checks a name or city entry against the 13-character screen width and for U+0000, then
     * normalizes it.
     *
     * <p>The width is measured on the value as received, before trimming, because the screen field
     * itself held at most 13 characters, blanks included. No pattern is built here; the repository
     * appends the {@code %} and applies the 13-character cut of the source host variable.
     *
     * @param value the entry, or {@code null}
     * @param field the request property, {@code name} or {@code city}
     * @return the trimmed, uppercased entry; {@code ""} for none
     * @throws InvalidSearchCriteriaException APP0400 on {@code field} when the entry is too long
     *                                        or, failing that, contains U+0000
     */
    private static String normalizePrefix(String value, String field) {
        if (value != null && value.codePointCount(0, value.length()) > FILTER_WIDTH) {
            throw new InvalidSearchCriteriaException(InvalidSearchCriteriaException.APP0400,
                    field, List.of(field + " must be at most " + FILTER_WIDTH + " characters"));
        }
        rejectNul(value, field);
        return TextNormalizer.filter(value);
    }

    /**
     * Normalizes the state entry and applies the PMTCUSTR rule: blank selects every state, and a
     * value whose trimmed length is not 2 is rejected with DEM0007
     * [5250_Subfile/PMTCUSTR.SQLRPGLE:635-646]. A 2-character value that contains U+0000 is then
     * rejected with APP0400; the source rule keeps precedence, so a lone U+0000 is DEM0007.
     *
     * @param value the entry, or {@code null}
     * @return {@code ""} for every state, otherwise the 2-character uppercased code
     * @throws InvalidSearchCriteriaException DEM0007 on {@code state} for a trimmed length other
     *                                        than 0 or 2; APP0400 on {@code state} for a
     *                                        2-character value containing U+0000
     */
    private static String normalizeState(String value) {
        String normalized = TextNormalizer.filter(value);
        if (normalized.isEmpty()
                || normalized.codePointCount(0, normalized.length()) == STATE_WIDTH) {
            rejectNul(value, FIELD_STATE);
            return normalized;
        }
        throw new InvalidSearchCriteriaException(InvalidSearchCriteriaException.DEM0007,
                FIELD_STATE, List.of());
    }

    /**
     * Rejects a filter entry that contains U+0000, before any repository call. PostgreSQL text
     * cannot hold that character, so the query would otherwise fail as 500 DEM9999; the entry is
     * never stripped or altered instead. The reason is fixed per field and never echoes the entry.
     *
     * @param value the entry as received, or {@code null}
     * @param field the request property, {@code name}, {@code city} or {@code state}
     * @throws InvalidSearchCriteriaException APP0400 on {@code field} when {@code value} contains
     *                                        U+0000
     */
    private static void rejectNul(String value, String field) {
        if (value != null && value.indexOf(NUL) >= 0) {
            throw new InvalidSearchCriteriaException(InvalidSearchCriteriaException.APP0400,
                    field, List.of(field + " must not contain U+0000"));
        }
    }

    /**
     * Builds a notice with its catalog text.
     *
     * @param code the catalog key
     * @return the notice
     */
    private SearchPage.Notice notice(String code) {
        return new SearchPage.Notice(code, messageCatalog.text(code));
    }


    /**
     * Encodes the position after {@code last}: its stored sort keys and the rows served so far, as
     * base64url (no padding) over the UTF-8 JSON the payload record serializes to.
     *
     * @param last   the last row returned on this page, never the look-ahead row
     * @param served the rows served up to and including this page
     * @return the opaque cursor
     * @throws IllegalStateException if the payload cannot be serialized, which only a broken mapper
     *                               configuration can cause
     */
    private String encodeCursor(CustomerSummary last, int served) {
        CursorPayload payload = new CursorPayload(
                last.name(), last.city(), last.state(), last.custId().value(), served);
        try {
            byte[] json = cursorWriter.writeValueAsBytes(payload);
            return Base64.getUrlEncoder().withoutPadding().encodeToString(json);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("search cursor could not be serialized", e);
        }
    }

    /**
     * Decodes and validates a cursor issued by {@link #encodeCursor(CustomerSummary, int)}.
     *
     * <p>Accepted only when every check holds: at most {@value #MAX_CURSOR_LENGTH} characters;
     * valid base64url; well-formed JSON with nothing after the object and no repeated property;
     * an object with exactly the five properties; {@code name}, {@code city}, {@code state} and
     * {@code custid} JSON strings (no {@code null}, number or boolean, and no scalar coercion),
     * each no longer than its column and free of U+0000, which PostgreSQL text cannot hold;
     * {@code served} a JSON integer that fits an {@code int}; {@code custid} a valid
     * {@link CustomerId}; and
     * {@code 0 <= served < maxRows}, so a page always has room for at least one row.
     *
     * <p>The column-width and U+0000 checks go beyond the shape the cursor must have: they hold for
     * every cursor this class issues, because the keys come from stored rows, and they turn a
     * tampered key the database would reject into 400 APP0400 instead of 500 DEM9999.
     *
     * @param cursor  the cursor as received, or {@code null}
     * @param maxRows the row cap
     * @return the keyset position, or {@code null} for a blank cursor (the first page)
     * @throws InvalidSearchCriteriaException APP0400 on {@code cursor} when any check fails
     */
    private SearchCriteria.Cursor decodeCursor(String cursor, int maxRows) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        if (cursor.length() > MAX_CURSOR_LENGTH) {
            throw invalidCursor();
        }
        byte[] json;
        try {
            json = Base64.getUrlDecoder().decode(cursor);
        } catch (IllegalArgumentException e) {
            throw invalidCursor();
        }
        JsonNode tree;
        try {
            tree = cursorReader.readTree(json);
        } catch (IOException e) {
            throw invalidCursor();
        }
        if (tree == null || !tree.isObject() || tree.size() != CURSOR_KEYS.size()) {
            throw invalidCursor();
        }
        for (String key : CURSOR_KEYS) {
            if (!tree.has(key)) {
                throw invalidCursor();
            }
        }
        String keyName = textKey(tree, KEY_NAME, CustomerSearchRepository.NAME_WIDTH);
        String keyCity = textKey(tree, KEY_CITY, CustomerSearchRepository.CITY_WIDTH);
        String keyState = textKey(tree, KEY_STATE, STATE_WIDTH);
        String keyCustId = textKey(tree, KEY_CUSTID, CustomerId.LENGTH);
        try {
            CustomerId.parse(keyCustId);
        } catch (IllegalArgumentException e) {
            throw invalidCursor();
        }
        JsonNode servedNode = tree.get(KEY_SERVED);
        if (!servedNode.isIntegralNumber() || !servedNode.canConvertToInt()) {
            throw invalidCursor();
        }
        int served = servedNode.intValue();
        if (served < 0 || served >= maxRows) {
            throw invalidCursor();
        }
        return new SearchCriteria.Cursor(keyName, keyCity, keyState, keyCustId, served);
    }

    /**
     * Reads one string key of a cursor.
     *
     * @param tree     the cursor object, known to hold {@code key}
     * @param key      the property name
     * @param maxWidth the most code points the stored column can hold
     * @return the key value
     * @throws InvalidSearchCriteriaException APP0400 on {@code cursor} when the value is not a JSON
     *                                        string, is too long, or contains U+0000
     */
    private static String textKey(JsonNode tree, String key, int maxWidth) {
        JsonNode node = tree.get(key);
        if (node == null || !node.isTextual()) {
            throw invalidCursor();
        }
        String value = node.textValue();
        if (value.codePointCount(0, value.length()) > maxWidth || value.indexOf('\u0000') >= 0) {
            throw invalidCursor();
        }
        return value;
    }

    /**
     * The one failure every cursor check raises; it never says which check failed, so a client
     * probing a forged cursor learns nothing about the format.
     *
     * @return APP0400 on {@code cursor}
     */
    private static InvalidSearchCriteriaException invalidCursor() {
        return new InvalidSearchCriteriaException(InvalidSearchCriteriaException.APP0400,
                FIELD_CURSOR, List.of(CURSOR_INVALID_REASON));
    }

    /**
     * The cursor JSON: the stored sort keys of the last row served and the rows served so far.
     *
     * <p>Explicit property names and order keep the wire form fixed whatever naming strategy the
     * shared mapper is given, so a cursor issued by one instance decodes on any other.
     *
     * @param name   stored {@code name}
     * @param city   stored {@code city}
     * @param state  stored {@code state}
     * @param custid the 4-character {@code custid}
     * @param served rows served up to and including the page that issued the cursor
     */
    @JsonPropertyOrder({KEY_NAME, KEY_CITY, KEY_STATE, KEY_CUSTID, KEY_SERVED})
    private record CursorPayload(
            @JsonProperty(KEY_NAME) String name,
            @JsonProperty(KEY_CITY) String city,
            @JsonProperty(KEY_STATE) String state,
            @JsonProperty(KEY_CUSTID) String custid,
            @JsonProperty(KEY_SERVED) int served) {

        /**
         * Requires every key: the columns are {@code NOT NULL}, so a missing value is a programming
         * error, not a cursor to issue.
         *
         * @throws NullPointerException if any key is {@code null}
         */
        private CursorPayload {
            Objects.requireNonNull(name, KEY_NAME);
            Objects.requireNonNull(city, KEY_CITY);
            Objects.requireNonNull(state, KEY_STATE);
            Objects.requireNonNull(custid, KEY_CUSTID);
        }
    }
}
