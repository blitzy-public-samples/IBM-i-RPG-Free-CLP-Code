package com.democorp.customermaster.controller.dto;

import com.democorp.customermaster.domain.State;

/**
 * One element of the {@code GET /api/states} body: a USA state, territory or military postal
 * code and its name.
 *
 * <p>Replaces one row of the State prompt's subfile ({@code 5250_Subfile/PMTSTATED.DSPF}, record
 * {@code SFL}: {@code SF_CODE 2A} and {@code SF_NAME 30A}), which PMTSTATER filled from its
 * {@code DataCur} cursor, {@code select STATE, NAME from STATES ...}
 * ({@code 5250_Subfile/PMTSTATER.SQLRPGLE}, lines 150-162), over the STATES table's
 * {@code STATE CHAR(2)} and {@code NAME CHAR(30)} columns ({@code 5250_Subfile/States.sql},
 * lines 8-13).
 *
 * <p>The endpoint returns a JSON array of these, all 58 rows when the "Name Contains" filter is
 * blank, ordered by name or by code as the {@code sort} parameter asks. Filtering and ordering
 * belong to {@code StateService} and its repository; this record only carries the values.
 *
 * <p>Serialized as {@code {"state": "NC", "name": "North Carolina"}}.
 *
 * <p><b>Values pass through unchanged.</b> {@code state} is the stored 2-character code and
 * {@code name} keeps its stored mixed case ({@code "North Carolina"}, not
 * {@code "NORTH CAROLINA"}), as the subfile showed it. Only the filter is matched
 * case-insensitively, against {@code upper(name)}, and that happens in SQL, never here.
 *
 * <p>Example:
 * <pre>{@code
 * List<StateResponse> body = stateService.list(nameContains, sort).stream()
 *         .map(StateResponse::from)
 *         .toList();
 * }</pre>
 *
 * @param state the 2-character postal code, for example {@code "NC"}; the value the State
 *              picker returns to the calling field
 * @param name  the state's name as stored, at most 30 characters, for example
 *              {@code "North Carolina"}
 */
public record StateResponse(String state, String name) {

    /**
     * Maps a stored state row to its response element, copying both values as they are.
     *
     * @param s the state row read from the {@code states} table; must not be {@code null}
     * @return the response element with the row's code and name
     * @throws NullPointerException if {@code s} is {@code null}
     */
    public static StateResponse from(State s) {
        if (s == null) {
            throw new NullPointerException("state row must not be null");
        }
        return new StateResponse(s.state(), s.name());
    }
}
