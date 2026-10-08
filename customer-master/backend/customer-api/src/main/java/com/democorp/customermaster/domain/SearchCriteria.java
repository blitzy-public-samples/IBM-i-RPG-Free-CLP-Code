package com.democorp.customermaster.domain;

import java.util.Objects;

/**
 * One normalized customer search request: the filters of a single page fetch plus
 * the keyset position at which that page starts.
 *
 * <p>Replaces the host variables that PMTCUSTR's {@code ProcessSearchCriteria}
 * builds before it opens {@code ItemCur}
 * [5250_Subfile/PMTCUSTR.SQLRPGLE:208-223,625-665], and the open SQL cursor that
 * kept fetching on PageDown. The target server keeps no cursor between requests,
 * so the position travels with the request as a {@link Cursor}.
 *
 * <p>The filters are normalized values, trimmed and uppercased by
 * {@code TextNormalizer.filter}, and {@code ""} means no filter; the {@link Cursor} keys
 * are raw stored values. This type carries values and enforces programming invariants
 * only: {@code service/CustomerSearchService} validates every user-supplied value before
 * constructing it, so the guards here never produce a user-facing error.
 *
 * @param name            normalized name prefix filter; {@code null} is stored as {@code ""}
 * @param city            normalized city prefix filter; {@code null} is stored as {@code ""}
 * @param state           normalized state filter, {@code ""} or exactly 2 characters;
 *                        {@code null} is stored as {@code ""}
 * @param includeInactive {@code true} to include rows whose {@code active} is {@code N}
 *                        (F9 "Include Inactive")
 * @param size            maximum number of rows this page returns, at least 1
 * @param cursor          keyset position of the last row already served, or
 *                        {@code null} for the first page
 */
public record SearchCriteria(
        String name,
        String city,
        String state,
        boolean includeInactive,
        int size,
        Cursor cursor) {

    /**
     * Applies the programming guards. Null filters become {@code ""} (no filter) so
     * consumers never test for {@code null}. A {@code size} below 1 is a caller bug,
     * because the service validates the requested page size before constructing.
     *
     * @throws IllegalArgumentException if {@code size} is less than 1
     */
    public SearchCriteria {
        name = name == null ? "" : name;
        city = city == null ? "" : city;
        state = state == null ? "" : state;
        if (size < 1) {
            throw new IllegalArgumentException("size must be at least 1, was " + size);
        }
    }

    /**
     * Whether this request fetches the first page, which has no keyset position.
     *
     * @return {@code true} when {@link #cursor()} is {@code null}
     */
    public boolean firstPage() {
        return cursor == null;
    }

    /**
     * Whether a name prefix filter is present. The filter arrives stripped by
     * {@code TextNormalizer.filter}, so non-blank and non-empty coincide.
     *
     * @return {@code true} when {@link #name()} is not blank
     */
    public boolean hasName() {
        return !name.isBlank();
    }

    /**
     * Whether a city prefix filter is present.
     *
     * @return {@code true} when {@link #city()} is not blank
     */
    public boolean hasCity() {
        return !city.isBlank();
    }

    /**
     * Whether a state filter is present. A blank state selects every state, as
     * PMTCUSTR's {@code ' '..'ZZ'} range does [5250_Subfile/PMTCUSTR.SQLRPGLE:635-638].
     *
     * @return {@code true} when {@link #state()} is not blank
     */
    public boolean hasState() {
        return !state.isBlank();
    }

    /**
     * Keyset position: the stored sort-key values of the last row already served,
     * and the number of rows served so far in this search.
     *
     * <p>The four keys are raw column values, not filters. The repository compares
     * them as {@code (name, city, state, custid) > (:kName, :kCity, :kState, :kId)},
     * matching the target's {@code ORDER BY name, city, state, custid}; {@code custid}
     * is the unique tiebreaker that makes the position exact. {@code served} counts
     * the rows returned on earlier pages, so the service can stop at 9,999 rows with
     * DEM0006 and {@code limitReached}, as the subfile cap did
     * [5250_Subfile/PMTCUSTR.SQLRPGLE:180,287-292].
     *
     * <p>Empty strings are legal key values, because they compare like any other
     * stored value. The service validates the decoded {@code custid} format and the
     * {@code served} range before constructing; these guards catch programming errors
     * only.
     *
     * @param name   stored {@code name} of the last row served
     * @param city   stored {@code city} of the last row served
     * @param state  stored {@code state} of the last row served
     * @param custid stored 4-character {@code custid} of the last row served
     * @param served rows already returned before the page this cursor starts, at least 0
     */
    public record Cursor(String name, String city, String state, String custid, int served) {

        /**
         * Rejects null keys and a negative row count.
         *
         * @throws NullPointerException     if any key value is {@code null}
         * @throws IllegalArgumentException if {@code served} is negative
         */
        public Cursor {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(city, "city");
            Objects.requireNonNull(state, "state");
            Objects.requireNonNull(custid, "custid");
            if (served < 0) {
                throw new IllegalArgumentException("served must not be negative, was " + served);
            }
        }
    }
}
