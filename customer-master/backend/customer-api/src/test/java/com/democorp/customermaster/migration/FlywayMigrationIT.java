package com.democorp.customermaster.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import com.democorp.customermaster.domain.CustomerId;
import com.democorp.customermaster.support.AbstractPostgresIT;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * Applies the Flyway migrations to a fresh, randomly named schema and checks what they create: V1 to V4
 * on an empty schema, and V1 to V5 with the seed location (AAP 0.8.3).
 *
 * <p><b>Why a random schema.</b> Test contexts apply {@code classpath:db/migration} only, to schema
 * {@code customer_master}, so this is the only test that sees the V5 seed. Each method migrates its own
 * schema {@code fmit_<uuid>} through a programmatic {@link Flyway} on the shared container, never
 * writes to {@code customer_master}, never calls {@code clean()}, and drops its schema afterwards, so the
 * methods are independent and run in any order. A migration that hard-coded {@code customer_master} or
 * an IBM i library would fail here. That includes the target of {@code custmast_state_fk}: the context's
 * own schema already holds a {@code states} table with the same 58 codes, so the key is checked to
 * reference {@code states (state)} of the generated schema, by catalog and by data.
 *
 * <p><b>Collation.</b> V1 defines {@code customer_sort} as the ICU root collation {@code und} with the
 * tailoring rule {@code &[before 1]\u03B1<0<1<2<3<4<5<6<7<8<9}, which puts the ASCII digits after every
 * Latin letter. The reorder locale {@code und-u-kr-latn-digit} sorts the same way under
 * {@code ORDER BY} on postgres:18.6 but compares digits before letters, so this class pins the
 * committed definition and checks {@code <} comparisons and a primary-key range scan, not only order.
 *
 * <p>Catalog queries bind the schema name; row queries qualify tables with the quoted, generated name.
 */
class FlywayMigrationIT extends AbstractPostgresIT {

    /** The migrations every context applies: V1 to V4. */
    private static final String MIGRATION_LOCATION = "classpath:db/migration";

    /** The seed location Docker Compose adds: V5. */
    private static final String SEED_LOCATION = "classpath:db/seed";

    /** The form of every generated schema name: safe to double-quote and to embed in a pattern. */
    private static final String SCHEMA_NAME_FORMAT = "fmit_[0-9a-f]{32}";

    /** The ICU tailoring rule of {@code customer_sort} (migration V1). */
    private static final String CUSTOMER_SORT_RULES = "&[before 1]\u03B1<0<1<2<3<4<5<6<7<8<9";

    /** The ordinal of {@code EEEF}, the {@code START WITH} of {@code custmast_id_seq} (migration V4). */
    private static final long SEQUENCE_START = 191_957L;

    /** The number of seed rows: Custmast.sql ids 1..300. */
    private static final int SEED_ROWS = 300;

    /** A state code V2 does not load; one rolled-back check adds it to the generated schema only. */
    private static final String SCHEMA_ONLY_STATE = "QQ";

    /** A state code that no schema's {@code states} holds. */
    private static final String UNKNOWN_STATE = "ZZ";

    /** The SQLSTATE {@code foreign_key_violation}. */
    private static final String FOREIGN_KEY_VIOLATION = "23503";

    @Test
    @DisplayName("V1-V4 apply on an empty schema: collation, constraints, schema-local state key, indexes,"
            + " 58 states, sequence EEEF"
            + " (AAP 0.8.3, 0.3.3, 0.3.4; Custmast2.sql, States.sql, CRTDTAARA.clle, SRV_BASE36.RPGLE)")
    void migrationsV1toV4ApplyOnEmptySchema() {
        inFreshSchema((schema, result) -> {
            assertApplied(result, List.of("1", "2", "3", "4"));
            assertCustomerSortDefinition(schema);
            assertCustomerSortComparisons(schema);
            assertPrimaryKeyRangeScanInRolledBackTransaction(schema);
            assertConstraints(schema);
            assertStateForeignKeyStaysInSchema(schema);
            assertStateForeignKeyChecksSchemaStatesInRolledBackTransactions(schema);
            assertIndexes(schema);
            assertStates(schema);
            assertThat(count("select count(*) from " + quoted(schema) + ".custmast"))
                    .as("customers created by V1-V4, and left by the rolled-back range-scan and state-key checks")
                    .isZero();
            assertSequenceNeverCalled(schema);
        }, MIGRATION_LOCATION);
    }

    @Test
    @DisplayName("With db/seed, V5 adds the 300 transformed Custmast.sql customers AAAB..AAIM"
            + " (AAP 0.8.3, 0.7.1, 0.4.7; Custmast.sql:28-339)")
    void seedV5AddsThreeHundredTransformedCustomers() {
        inFreshSchema((schema, result) -> {
            assertApplied(result, List.of("1", "2", "3", "4", "5"));
            String custmast = quoted(schema) + ".custmast";

            assertThat(count("select count(*) from " + custmast)).isEqualTo(SEED_ROWS);
            Set<String> ids = jdbcTemplate.queryForList("select custid from " + custmast, String.class).stream()
                    .map(String::trim)
                    .collect(Collectors.toSet());
            Set<String> expectedIds = IntStream.rangeClosed(1, SEED_ROWS)
                    .mapToObj(ordinal -> CustomerId.fromOrdinal(ordinal).value())
                    .collect(Collectors.toSet());
            assertThat(ids).containsExactlyInAnyOrderElementsOf(expectedIds).contains("AAAB", "AAIM");

            // min, max and a range under the column collation: letters before digits.
            List<String> extremes = jdbcTemplate.queryForObject(
                    "select min(custid), max(custid) from " + custmast,
                    (rs, rowNum) -> List.of(rs.getString(1).trim(), rs.getString(2).trim()));
            assertThat(extremes).containsExactly("AAAB", "AAIM");
            assertThat(trimmed(jdbcTemplate.queryForList(
                    "select custid from " + custmast + " where custid > 'AAAZ' order by custid limit 3",
                    String.class)))
                    .containsExactly("AAA0", "AAA1", "AAA2");

            // Custmast.sql:331-333, ids 3, 4 and 6.
            assertThat(nameOf(custmast, "AAAD")).isEqualTo("NIBH L'LOR COMPANY");
            assertThat(nameOf(custmast, "AAAE")).isEqualTo("BLANDIT \"AT NISI\" INDUSTRIES");
            assertThat(nameOf(custmast, "AAAG")).isEqualTo("URNA \\NUNC\\ COMPANY");

            // Custmast.sql:329: name and city uppercase; addr and acctmgr keep their mixed case.
            assertThat(count(
                    "select count(*) from " + custmast + " where name <> upper(name) or city <> upper(city)"))
                    .isZero();
            Map<String, Object> first = jdbcTemplate.queryForMap(
                    "select addr, acctmgr from " + custmast + " where custid = ?", "AAAB");
            assertThat(first)
                    .containsEntry("addr", "Ap #766-3317 Penatibus St.")
                    .containsEntry("acctmgr", "Simon,  Gannon D.");

            // Custmast.sql:330: active 'Y' for every even id.
            Map<String, String> activeById = jdbcTemplate.query(
                    "select custid, active from " + custmast,
                    (rs, rowNum) -> Map.entry(rs.getString(1).trim(), rs.getString(2)))
                    .stream()
                    .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
            List<String> evenNotActive = activeById.entrySet().stream()
                    .filter(entry -> CustomerId.parse(entry.getKey()).toOrdinal() % 2 == 0)
                    .filter(entry -> !"Y".equals(entry.getValue()))
                    .map(Map.Entry::getKey)
                    .toList();
            assertThat(evenNotActive).isEmpty();
            assertThat(activeById.values().stream().filter("Y"::equals).count()).isEqualTo(226);
            assertThat(activeById.values().stream().filter("N"::equals).count()).isEqualTo(74);

            // Custmast.sql:339 and the new concurrency token.
            assertThat(count("select count(*) from " + custmast + " where chguser <> '*SYSTEM*'")).isZero();
            assertThat(count("select count(*) from " + custmast + " where row_version <> 0")).isZero();

            // AAAB..AAIM lie below EEEF: the first interactive add still gets EEEF.
            assertSequenceNeverCalled(schema);
        }, MIGRATION_LOCATION, SEED_LOCATION);
    }

    /**
     * Migrates a new schema, runs a scenario against it and drops the schema, then asserts that no
     * schema of that name is left.
     *
     * @param scenario  the assertions, given the schema name and Flyway's result
     * @param locations the Flyway locations to apply
     */
    private void inFreshSchema(SchemaScenario scenario, String... locations) {
        String schema = "fmit_" + UUID.randomUUID().toString().replace("-", "");
        assertThat(schema).matches(SCHEMA_NAME_FORMAT);
        try {
            Flyway flyway = Flyway.configure()
                    .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                    .schemas(schema)
                    .createSchemas(true)
                    .cleanDisabled(true)
                    .locations(locations)
                    .load();
            MigrateResult result = flyway.migrate();
            scenario.run(schema, result);
        } finally {
            jdbcTemplate.execute("DROP SCHEMA IF EXISTS " + quoted(schema) + " CASCADE");
        }
        assertThat(count("select count(*) from pg_namespace where nspname = ?", schema))
                .as("schemas named %s after the scenario", schema)
                .isZero();
    }

    /**
     * Asserts that the migration succeeded and applied exactly the given versions, in order.
     *
     * @param result   Flyway's result
     * @param versions the expected versions
     */
    private static void assertApplied(MigrateResult result, List<String> versions) {
        assertThat(result.success).isTrue();
        assertThat(result.migrationsExecuted).isEqualTo(versions.size());
        List<String> applied = result.migrations.stream()
                .map(migration -> migration.version)
                .filter(version -> version != null && !version.isEmpty())
                .toList();
        assertThat(applied).containsExactlyElementsOf(versions);
    }

    /**
     * Asserts V1's committed definition of {@code customer_sort} in the schema.
     *
     * @param schema the migrated schema
     */
    private void assertCustomerSortDefinition(String schema) {
        Map<String, Object> collation = jdbcTemplate.queryForMap(
                "select c.collprovider::text as provider, c.colllocale as locale, c.collicurules as rules,"
                        + " c.collisdeterministic as deterministic"
                        + " from pg_collation c join pg_namespace n on n.oid = c.collnamespace"
                        + " where n.nspname = ? and c.collname = 'customer_sort'",
                schema);
        assertThat(collation)
                .containsEntry("provider", "i")
                .containsEntry("locale", "und")
                .containsEntry("rules", CUSTOMER_SORT_RULES)
                .containsEntry("deterministic", true);
    }

    /**
     * Asserts that {@code <} under the schema's {@code customer_sort} puts letters before digits. The
     * reorder locale {@code und-u-kr-latn-digit} returns false for each of these, although its
     * {@code ORDER BY} puts the left value first.
     *
     * @param schema the migrated schema
     */
    private void assertCustomerSortComparisons(String schema) {
        String collation = quoted(schema) + ".customer_sort";
        for (List<String> pair : List.of(List.of("A", "0"), List.of("ZZZZ", "0000"), List.of("101A", "1010"))) {
            Boolean less = jdbcTemplate.queryForObject(
                    "select ?::text < ?::text collate " + collation, Boolean.class, pair.get(0), pair.get(1));
            assertThat(less).as("'%s' < '%s' collate %s", pair.get(0), pair.get(1), collation).isTrue();
        }
    }

    /**
     * Inserts six customers into the schema's {@code custmast} inside one transaction that is always
     * rolled back, and asserts that a range over {@code custmast_pkey} returns the ids above
     * {@code ZZZZ} in index order. With sequential and bitmap scans disabled the planner must walk the
     * B-tree with the range as its index condition. Under the reorder locale {@code und-u-kr-latn-digit}
     * the B-tree holds digits before letters, so the range returns no rows.
     *
     * @param schema the migrated schema
     */
    private void assertPrimaryKeyRangeScanInRolledBackTransaction(String schema) {
        String custmast = quoted(schema) + ".custmast";
        String aboveLetters = "select custid from " + custmast + " where custid > 'ZZZZ' order by custid";
        transactionTemplate.executeWithoutResult(status -> {
            status.setRollbackOnly();
            for (String id : List.of("1010", "AAA0", "0000", "ZZZZ", "AAAA", "101A")) {
                jdbcTemplate.update(
                        "insert into " + custmast + " (custid, name, addr, city, state, zip)"
                                + " values (?, ?, ?, ?, 'CA', ?)",
                        id, "MIGRATION TEST " + id, "1 MAIN ST", "SPRINGFIELD", "90210");
            }
            jdbcTemplate.execute("set local enable_seqscan = off");
            jdbcTemplate.execute("set local enable_bitmapscan = off");

            String plan = String.join("\n",
                    jdbcTemplate.queryForList("explain (verbose) " + aboveLetters, String.class));
            assertThat(plan)
                    .containsPattern("Index (Only )?Scan using custmast_pkey on " + schema + "\\.custmast")
                    .contains("Index Cond: (custmast.custid > 'ZZZZ'::bpchar)")
                    .doesNotContain("Sort");
            assertThat(trimmed(jdbcTemplate.queryForList(aboveLetters, String.class)))
                    .containsExactly("0000", "101A", "1010");
        });
    }

    /**
     * Asserts the constraints of V2 and V3 by name, kind and table. The schema and columns that
     * {@code custmast_state_fk} references are checked by {@link #assertStateForeignKeyStaysInSchema}.
     *
     * @param schema the migrated schema
     */
    private void assertConstraints(String schema) {
        Map<String, String> constraints = jdbcTemplate.query(
                "select c.conname, c.contype::text || ' ' || t.relname"
                        + " || coalesce(' -> ' || r.relname, '') as kind"
                        + " from pg_constraint c"
                        + " join pg_class t on t.oid = c.conrelid"
                        + " join pg_namespace n on n.oid = t.relnamespace"
                        + " left join pg_class r on r.oid = c.confrelid"
                        + " where n.nspname = ?",
                (rs, rowNum) -> Map.entry(rs.getString("conname"), rs.getString("kind")),
                schema)
                .stream()
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        assertThat(constraints)
                .containsEntry("custmast_pkey", "p custmast")
                .containsEntry("custmast_custid_ck", "c custmast")
                .containsEntry("custmast_active_ck", "c custmast")
                .containsEntry("custmast_state_fk", "f custmast -> states")
                .containsEntry("state_primary_key", "p states")
                .containsEntry("state_name_unique", "u states");
    }

    /**
     * Asserts from the catalog that {@code custmast_state_fk} is a foreign key from {@code custmast (state)}
     * to {@code states (state)}, both in the migrated schema, and not to the {@code states} table of this
     * context's own schema, which holds the same codes.
     *
     * @param schema the migrated schema
     */
    private void assertStateForeignKeyStaysInSchema(String schema) {
        List<Long> contextStates = jdbcTemplate.queryForList(
                "select t.oid::bigint from pg_class t join pg_namespace n on n.oid = t.relnamespace"
                        + " where n.nspname = current_schema() and t.relname = 'states'",
                Long.class);
        assertThat(contextStates)
                .as("states tables in the context's own schema, which make the target check discriminating")
                .hasSize(1);

        Map<String, Object> foreignKey = jdbcTemplate.queryForMap(
                "select c.contype::text as kind, tn.nspname as table_schema, t.relname as table_name,"
                        + " (select string_agg(a.attname::text, ',' order by k.ord)"
                        + " from unnest(c.conkey) with ordinality k(num, ord)"
                        + " join pg_attribute a on a.attrelid = c.conrelid and a.attnum = k.num) as columns,"
                        + " rn.nspname as referenced_schema, r.relname as referenced_table,"
                        + " (select string_agg(a.attname::text, ',' order by k.ord)"
                        + " from unnest(c.confkey) with ordinality k(num, ord)"
                        + " join pg_attribute a on a.attrelid = c.confrelid and a.attnum = k.num)"
                        + " as referenced_columns,"
                        + " c.confrelid::bigint as referenced_oid, to_regclass(?)::oid::bigint as schema_states_oid"
                        + " from pg_constraint c"
                        + " join pg_class t on t.oid = c.conrelid"
                        + " join pg_namespace tn on tn.oid = t.relnamespace"
                        + " join pg_class r on r.oid = c.confrelid"
                        + " join pg_namespace rn on rn.oid = r.relnamespace"
                        + " where tn.nspname = ? and c.conname = 'custmast_state_fk'",
                quoted(schema) + ".states", schema);
        assertThat(foreignKey)
                .as("custmast_state_fk of schema %s", schema)
                .containsEntry("kind", "f")
                .containsEntry("table_schema", schema)
                .containsEntry("table_name", "custmast")
                .containsEntry("columns", "state")
                .containsEntry("referenced_schema", schema)
                .containsEntry("referenced_table", "states")
                .containsEntry("referenced_columns", "state");
        assertThat(foreignKey.get("schema_states_oid"))
                .as("oid of %s.states", schema)
                .isNotNull();
        assertThat(foreignKey.get("referenced_oid"))
                .as("oid of the table custmast_state_fk references")
                .isEqualTo(foreignKey.get("schema_states_oid"))
                .isNotEqualTo(contextStates.get(0));
    }

    /**
     * Shows by data, in two transactions that are always rolled back, that {@code custmast_state_fk}
     * checks the migrated schema's {@code states}: a customer may use a code that only that schema holds,
     * which a key on the context's own {@code states} would reject, and a code that no schema holds fails
     * with SQLSTATE {@value #FOREIGN_KEY_VIOLATION}. Nothing is written outside the migrated schema.
     *
     * @param schema the migrated schema
     */
    private void assertStateForeignKeyChecksSchemaStatesInRolledBackTransactions(String schema) {
        String states = quoted(schema) + ".states";
        String insertCustomer = "insert into " + quoted(schema) + ".custmast (custid, name, addr, city, state, zip)"
                + " values (?, ?, '1 MAIN ST', 'SPRINGFIELD', ?, '90210')";
        for (String code : List.of(SCHEMA_ONLY_STATE, UNKNOWN_STATE)) {
            assertThat(count("select count(*) from states where state = ?", code))
                    .as("rows with code %s in the context's own states", code)
                    .isZero();
            assertThat(count("select count(*) from " + states + " where state = ?", code))
                    .as("rows with code %s in %s", code, states)
                    .isZero();
        }

        assertThatCode(() -> transactionTemplate.executeWithoutResult(status -> {
            status.setRollbackOnly();
            jdbcTemplate.update("insert into " + states + " (state, name) values (?, ?)",
                    SCHEMA_ONLY_STATE, "MIGRATION TEST STATE");
            assertThat(jdbcTemplate.update(insertCustomer, "AAAA", "MIGRATION TEST AAAA", SCHEMA_ONLY_STATE))
                    .as("customers inserted with state %s", SCHEMA_ONLY_STATE)
                    .isOne();
        }))
                .as("a customer with state %s, which only %s holds", SCHEMA_ONLY_STATE, states)
                .doesNotThrowAnyException();

        Throwable rejected = catchThrowable(() -> transactionTemplate.executeWithoutResult(status -> {
            status.setRollbackOnly();
            jdbcTemplate.update(insertCustomer, "AAAB", "MIGRATION TEST AAAB", UNKNOWN_STATE);
        }));
        assertThat(rejected)
                .as("a customer with state %s, which no states table holds", UNKNOWN_STATE)
                .isInstanceOf(DataIntegrityViolationException.class)
                .rootCause()
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("custmast_state_fk")
                .extracting(cause -> ((SQLException) cause).getSQLState())
                .isEqualTo(FOREIGN_KEY_VIOLATION);
    }

    /**
     * Asserts the four custmast indexes of V3 and their definitions (AAP 0.3.4).
     *
     * @param schema the migrated schema
     */
    private void assertIndexes(String schema) {
        Map<String, String> indexes = jdbcTemplate.query(
                "select indexname, indexdef from pg_indexes where schemaname = ? and tablename = 'custmast'",
                (rs, rowNum) -> Map.entry(rs.getString("indexname"), rs.getString("indexdef")),
                schema)
                .stream()
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        assertThat(indexes)
                .containsKeys("custmast_name", "custmast_city", "custmast_state", "custmast_search_keyset");
        assertThat(indexes.get("custmast_name")).contains("varchar_pattern_ops");
        assertThat(indexes.get("custmast_city")).contains("varchar_pattern_ops");
        assertThat(indexes.get("custmast_search_keyset")).contains("(name, city, state, custid)");
    }

    /**
     * Asserts V2's 58 rows, spot-checking the first and last code in mixed case.
     *
     * @param schema the migrated schema
     */
    private void assertStates(String schema) {
        String states = quoted(schema) + ".states";
        assertThat(count("select count(*) from " + states)).isEqualTo(58);
        String nameByCode = "select name from " + states + " where state = ?";
        assertThat(jdbcTemplate.queryForObject(nameByCode, String.class, "AA")).isEqualTo("Armed Forces America");
        assertThat(jdbcTemplate.queryForObject(nameByCode, String.class, "WY")).isEqualTo("Wyoming");
    }

    /**
     * Asserts V4's sequence: start {@code EEEF}, maximum {@code 9999}, no cycle, never called.
     *
     * @param schema the migrated schema
     */
    private void assertSequenceNeverCalled(String schema) {
        Map<String, Object> sequence = jdbcTemplate.queryForMap(
                "select start_value, max_value, cycle, last_value from pg_sequences"
                        + " where schemaname = ? and sequencename = 'custmast_id_seq'",
                schema);
        long start = ((Number) sequence.get("start_value")).longValue();
        assertThat(start).isEqualTo(SEQUENCE_START);
        assertThat(CustomerId.fromOrdinal(Math.toIntExact(start)).value()).isEqualTo("EEEF");
        assertThat(((Number) sequence.get("max_value")).longValue()).isEqualTo(CustomerId.MAX_ORDINAL);
        assertThat(sequence.get("cycle")).isEqualTo(false);
        assertThat(sequence.get("last_value")).isNull();
    }

    /**
     * Returns the stored name of one customer.
     *
     * @param custmast the quoted, schema-qualified table
     * @param custId   the customer id
     * @return the name
     */
    private String nameOf(String custmast, String custId) {
        return jdbcTemplate.queryForObject(
                "select name from " + custmast + " where custid = ?", String.class, custId);
    }

    /**
     * Runs a {@code count(*)} query.
     *
     * @param sql  the query
     * @param args its bound parameters
     * @return the count
     */
    private long count(String sql, Object... args) {
        Long count = jdbcTemplate.queryForObject(sql, Long.class, args);
        assertThat(count).as("result of %s", sql).isNotNull();
        return count;
    }

    /**
     * Trims each value, for {@code char(n)} columns.
     *
     * @param values the values
     * @return the trimmed values in order
     */
    private static List<String> trimmed(List<String> values) {
        return values.stream().map(String::trim).toList();
    }

    /**
     * Quotes a generated schema name for use in SQL text.
     *
     * @param schema a name matching {@link #SCHEMA_NAME_FORMAT}
     * @return the name in double quotes
     * @throws IllegalArgumentException if the name does not match that format
     */
    private static String quoted(String schema) {
        if (!schema.matches(SCHEMA_NAME_FORMAT)) {
            throw new IllegalArgumentException("not a generated schema name: " + schema);
        }
        return "\"" + schema + "\"";
    }

    /** The assertions one test runs against its migrated schema. */
    @FunctionalInterface
    private interface SchemaScenario {

        /**
         * Runs the assertions.
         *
         * @param schema the migrated schema
         * @param result Flyway's result
         */
        void run(String schema, MigrateResult result);
    }
}
