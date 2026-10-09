package com.democorp.customermaster.controller.dto;

import com.democorp.customermaster.domain.State;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * One element of the {@code GET /api/states} body: a USA state, territory or military postal
 * code and its name, one row of the State prompt's {@code SFL} record
 * [5250_Subfile/PMTSTATED.DSPF:54-62].
 *
 * <p>Values pass through unchanged: {@code state} is the stored 2-character code and {@code name}
 * keeps its stored mixed case ({@code "North Carolina"}, not {@code "NORTH CAROLINA"}).
 *
 * @param state the 2-character postal code, for example {@code "NC"}; the value the State
 *              picker returns to the calling field
 * @param name  the state's name as stored, at most 30 characters, for example
 *              {@code "North Carolina"}
 */
public record StateResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String state,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String name) {

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
