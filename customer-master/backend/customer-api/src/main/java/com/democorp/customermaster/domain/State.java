package com.democorp.customermaster.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

/**
 * One row of the {@code states} table: a USA state, territory or military postal code
 * and its name.
 *
 * <p>Replaces the STATES file, {@code STATE CHAR(2)} as primary key
 * {@code state_primary_key} and {@code NAME CHAR(30)} under the unique constraint
 * {@code state_name_unique} [5250_Subfile/States.sql:8-17]. Migration V2 creates the
 * table as {@code state char(2)} and {@code name varchar(30)}, both
 * {@code COLLATE customer_sort} and {@code NOT NULL}, and inserts the 58 source rows.
 * The same two columns are what both source readers fetch:
 * <ul>
 *   <li>PMTSTATER's {@code DataCur}, the State prompt list
 *       [5250_Subfile/PMTSTATER.SQLRPGLE:150-162], now {@code StateRepository.search}
 *       behind {@code GET /api/states} and the {@code StatePicker};</li>
 *   <li>StateVal's one-time {@code states_cursor} load into its static array
 *       [Service_Pgms/StateVal.sqlrpgle:34-62], now {@code StateRepository.findAll},
 *       which feeds the {@code StateService} cache behind the State field rule
 *       (DEM0503) and the generator's CSZ filter.</li>
 * </ul>
 *
 * <p><b>Read-only by design.</b> Rows come only from migration V2; no code path inserts,
 * updates or deletes a state. That is why an immutable record, with no {@code @Version}
 * and no new-entity marker, suffices as the Spring Data JDBC aggregate.
 *
 * <p><b>Column names.</b> Each component names its V2 column explicitly with
 * {@link Column}, as every entity in this package does, so the mapping never depends
 * on the default {@code NamingStrategy}.
 *
 * <p><b>Values are stored values.</b> This type normalizes and orders nothing:
 * <ul>
 *   <li>{@code state} is the 2-character code as stored, already uppercase
 *       ({@code "NC"}). A {@code char(2)} value of exactly two characters carries no
 *       padding.</li>
 *   <li>{@code name} keeps the source's mixed case ({@code "North Carolina"}). The
 *       case-insensitive "Name Contains" filter compares {@code rpad(upper(name), 30)}
 *       in {@code StateRepository.search}'s SQL, as {@code DataCur} compares
 *       {@code upper(NAME)}.</li>
 *   <li>The "By Name" and "By Code" sorts, PMTSTATER's F7 toggle, are {@code ORDER BY}
 *       clauses under the {@code customer_sort} collation (migration V1), so this
 *       record defines no {@link Comparable} ordering.</li>
 * </ul>
 *
 * <p>Example, as loaded from V2:
 * <pre>{@code
 * State nc = new State("NC", "North Carolina");
 * nc.state(); // "NC"
 * nc.name();  // "North Carolina"
 * }</pre>
 *
 * @param state the 2-character postal code, primary key of {@code states}
 * @param name  the state's name as stored, at most 30 characters, unique
 */
@Table("states")
public record State(
        @Id @Column("state") String state,
        @Column("name") String name) {
}
