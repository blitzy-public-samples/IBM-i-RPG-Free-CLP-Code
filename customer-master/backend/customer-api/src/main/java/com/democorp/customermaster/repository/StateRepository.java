package com.democorp.customermaster.repository;

import com.democorp.customermaster.domain.State;
import java.util.List;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * Read access to the 58-row {@code states} table, as a Spring Data JDBC repository of {@link State}.
 *
 * <p><b>What it replaces.</b> Two source readers of STATES:
 * <ul>
 *   <li>StateVal's {@code states_cursor}, {@code select state, name from states order by state},
 *       fetched {@code for 100 rows} into a static array [Service_Pgms/StateVal.sqlrpgle:41-52]. It
 *       becomes {@link #findAll()}, which {@code StateService} loads once into its immutable cache.
 *       No row limit applies.</li>
 *   <li>PMTSTATER's {@code DataCur}, {@code select STATE, NAME from STATES where upper(NAME) like
 *       :DESCLike order by case :SQLSortSeq when :SortbyName then NAME when :SortbyCode then STATE
 *       else '1' end} [5250_Subfile/PMTSTATER.SQLRPGLE:150-162]. It becomes
 *       {@link #search(String, String)}. The CASE-driven sort becomes a choice between two fixed
 *       statements, {@code ORDER BY name} and {@code ORDER BY state}, so each is planned on its own
 *       and no sort text is ever assembled.</li>
 * </ul>
 *
 * <p><b>Pattern contract</b> (both search statements). {@code pattern} arrives fully built from
 * {@code service/StateService}, which normalizes the filter ({@code TextNormalizer.filter}), doubles
 * every {@code \} (PostgreSQL's default {@code LIKE} escape, made inert as on Db2), and wraps it as
 * {@code '%' + filter + '%'}, or passes {@code '%%'} when the filter is blank. This repository never
 * builds or concatenates pattern text; parameters are always bound. User {@code %} and {@code _} stay
 * wildcards. Db2 matched against the blank-padded {@code NAME CHAR(30)} [5250_Subfile/States.sql:8-13];
 * the target column is {@code varchar(30)}, so the statements compare {@code rpad(upper(name), 30)},
 * and a user {@code _} can still match a pad blank: {@code %TEXAS_%} finds {@code Texas}.
 *
 * <p><b>Order.</b> Both columns carry the {@code customer_sort} collation (migrations V1 and V2), so
 * "By Code" puts letters before digits, the base-36 order, and begins {@code AA}, {@code AE},
 * {@code AK}; "By Name" begins {@code Alabama}, {@code Alaska}, {@code American Samoa}.
 *
 * <p>The repository is read-only: it declares no save or delete method, because only migration V2
 * writes {@code states}.
 */
public interface StateRepository extends Repository<State, String> {

    /**
     * Returns every state ordered by code: StateVal's {@code states_cursor},
     * {@code select state, name from states order by state} [Service_Pgms/StateVal.sqlrpgle:41-52],
     * which {@code StateService} loads once into its cache, with no 100-row limit.
     *
     * @return all rows of {@code states}, ordered by {@code state} under {@code customer_sort};
     *         never {@code null}
     */
    @Query("SELECT state, name FROM states ORDER BY state")
    List<State> findAll();

    /**
     * Returns the states whose padded, uppercased name matches {@code pattern}, ordered by name:
     * the "By Name" statement of PMTSTATER's {@code DataCur} [5250_Subfile/PMTSTATER.SQLRPGLE:150-162].
     *
     * @param pattern the {@code LIKE} pattern, always bound and fully built by
     *                {@code service/StateService}: the {@code TextNormalizer.filter} result with every
     *                {@code \} doubled, wrapped as {@code '%' + filter + '%'}, or {@code '%%'} for a
     *                blank filter, which matches every state
     * @return the matching states ordered by {@code name}; empty when none matches, never {@code null}
     */
    @Query("SELECT state, name FROM states WHERE rpad(upper(name), 30) LIKE :pattern ORDER BY name")
    List<State> searchOrderByName(@Param("pattern") String pattern);

    /**
     * Returns the states whose padded, uppercased name matches {@code pattern}, ordered by code:
     * the "By Code" statement of PMTSTATER's {@code DataCur} [5250_Subfile/PMTSTATER.SQLRPGLE:150-162].
     *
     * @param pattern the {@code LIKE} pattern, always bound and fully built by
     *                {@code service/StateService}: the {@code TextNormalizer.filter} result with every
     *                {@code \} doubled, wrapped as {@code '%' + filter + '%'}, or {@code '%%'} for a
     *                blank filter, which matches every state
     * @return the matching states ordered by {@code state}; empty when none matches, never {@code null}
     */
    @Query("SELECT state, name FROM states WHERE rpad(upper(name), 30) LIKE :pattern ORDER BY state")
    List<State> searchOrderByCode(@Param("pattern") String pattern);

    /**
     * Runs PMTSTATER's {@code DataCur} [5250_Subfile/PMTSTATER.SQLRPGLE:150-162]: its CASE-driven
     * sort becomes a choice between two fixed statements, {@link #searchOrderByName(String)} and
     * {@link #searchOrderByCode(String)}, so no sort text is ever assembled.
     *
     * <p>Example: {@code search("%CAR%", "name")} returns North Carolina, then South Carolina.
     *
     * @param pattern the {@code LIKE} pattern, always bound and fully built by
     *                {@code service/StateService}: the {@code TextNormalizer.filter} result with every
     *                {@code \} doubled, wrapped as {@code '%' + filter + '%'}, or {@code '%%'} for a
     *                blank filter; this repository never builds or concatenates pattern text
     * @param sort    {@code "name"} for {@code ORDER BY name} or {@code "code"} for
     *                {@code ORDER BY state}; {@code StateService} or {@code StateController} validates
     *                it first and maps an unknown value to 400 APP0400
     * @return the matching states in the requested order; empty when none matches, never {@code null}
     * @throws IllegalArgumentException if {@code sort} is neither {@code "name"} nor {@code "code"},
     *                                  including {@code null}
     */
    default List<State> search(String pattern, String sort) {
        return switch (sort) {
            case "name" -> searchOrderByName(pattern);
            case "code" -> searchOrderByCode(pattern);
            case null, default -> throw new IllegalArgumentException("Unknown sort: " + sort);
        };
    }
}
