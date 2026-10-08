package com.democorp.customermaster.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import com.democorp.customermaster.controller.dto.CustomerResponse;
import com.democorp.customermaster.controller.dto.CustomerUpdateRequest;
import com.democorp.customermaster.support.AbstractPostgresIT;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;

/**
 * Specifies optimistic concurrency on {@code PUT /api/customers/{custId}}: a stale version answers 409
 * DEM1002 with the record as now stored, and a row lock that outlasts the lock timeout answers 409
 * DEM1001 with no data.
 *
 * <p><b>Source behaviour.</b> MTNCUSTR's {@code UpdateRecd} rewrites the row
 * {@code where CUSTID = :CUSTID and CHGTIME = :Orig_CHGTIME}, so a row someone else changed since it was
 * read matches nothing. On {@code SQLNODATA} it sends DEM1002 and re-reads the row so the user can review
 * the changed data; on {@code SQLROWLOCKED} it sends DEM1001 with {@code SQLERRMC} as message data, which
 * the DEM1001 text never displays because it has no {@code &1} [5250_Subfile/MTNCUSTR.SQLRPGLE:567-607],
 * [5250_Subfile/CRTMSGF.CLLE:38-41]. The program holds no lock while the user thinks.
 *
 * <p><b>Target behaviour.</b>
 * <ul>
 *   <li>The integer {@code row_version} replaces the CHGTIME equality: {@code UPDATE ... WHERE custid = ?
 *       AND row_version = ?}. A stale {@code version} answers 409 with {@code code} {@code DEM1002},
 *       {@code detail} exactly the catalog text "Someone else changed record. Review data." (the source's
 *       "Rewiew" and double space corrected) and {@code current}, the re-read row in the
 *       {@code CustomerResponse} shape. The stale write changes nothing, and re-applying the edit on
 *       {@code current.version} succeeds, as the client's "Re-apply my changes" does.</li>
 *   <li>The update runs under {@code SET LOCAL lock_timeout}; the test profile sets
 *       {@code customer-master.db.lock-timeout} to 1 second. A row held by another transaction makes the
 *       wait expire with SQLSTATE 55P03, the counterpart of the source's 57033, which answers 409 DEM1001
 *       carrying no data: no {@code errors}, {@code current}, {@code errorId} or {@code stateAccepted}
 *       member, and an empty {@code args}. Nothing is written, and once the other transaction ends the
 *       same edit at the same version succeeds, so no lock outlives the request.</li>
 * </ul>
 *
 * <p><b>Context.</b> The base context variant of {@link AbstractPostgresIT}: no mocked beans, no imports
 * and no property overrides. The lock test borrows one extra connection from the application's pool and
 * rolls it back and returns it in {@code finally}, so {@code DatabaseCleaner}'s {@code TRUNCATE} before the
 * next test never waits on it.
 */
@DisplayName("Optimistic concurrency: stale version DEM1002 and row lock DEM1001")
class OptimisticConcurrencyIT extends AbstractPostgresIT {

    /** The collection path. */
    private static final String CUSTOMERS = "/api/customers";

    /** The catalog text of DEM1002, with the source typos corrected. */
    private static final String STALE_TEXT = "Someone else changed record. Review data.";

    /** The catalog text of DEM1001, which carries no substitution variable. */
    private static final String LOCKED_TEXT = "Customer being updated by another user or job.";

    /** The {@code type} prefix of every problem. */
    private static final String PROBLEM_TYPE_PREFIX = "urn:customer-master:problem:";

    /** Format of an allocated customer id. */
    private static final String CUST_ID_FORMAT = "[A-Z0-9]{4}";

    /** The test profile's {@code customer-master.db.lock-timeout}, less a margin for timer granularity. */
    private static final Duration MIN_LOCK_WAIT = Duration.ofMillis(900);

    /** Longest acceptable answer to a blocked update; far beyond the 1-second lock timeout. */
    private static final Duration MAX_LOCK_WAIT = Duration.ofSeconds(15);

    /** The name every test customer is created with, as sent. */
    private static final String CREATED_NAME = "concurrency co";

    /** The street line as sent; every request in this class sends the same value. */
    private static final String ADDR = "5 elm st";

    /** The city as sent. */
    private static final String CITY = "dayton";

    /** The state as sent; already upper case. */
    private static final String STATE = "OH";

    /** The ZIP as sent. */
    private static final String ZIP = "45402";

    /** The corporate phone as sent. */
    private static final String CORP_PHONE = "(937) 555-0100";

    /** The account manager as sent. */
    private static final String ACCT_MGR = "sam roe";

    /** The account manager phone as sent. */
    private static final String ACCT_PHONE = "(937) 555-0101";

    /** The active flag as sent. */
    private static final String ACTIVE = "Y";

    @Test
    @DisplayName("a stale version answers 409 DEM1002 with current, writes nothing, and re-applies on current.version")
    void staleVersionGivesDem1002WithCurrent() {
        String id = create();

        // Two editors read the same row at version 0.
        CustomerResponse readByFirst = get(maintenance(), id);
        CustomerResponse readBySecond = get(maintenance2(), id);
        assertThat(readByFirst.version()).isZero();
        assertThat(readBySecond.version()).isZero();
        assertThat(readBySecond).isEqualTo(readByFirst);

        // The first editor saves.
        ResponseEntity<String> firstSave = put(maintenance(), id, "first client name", 0);
        assertThat(firstSave.getStatusCode()).isEqualTo(HttpStatus.OK);
        CustomerResponse saved = customer(firstSave);
        assertThat(saved.version()).isEqualTo(1);
        assertThat(saved.name()).isEqualTo("FIRST CLIENT NAME");
        assertThat(saved.chgUser()).isEqualTo(MAINTENANCE_USER);

        // The second editor saves on the version it read: 409 DEM1002 with the row as now stored.
        ResponseEntity<String> staleSave = put(maintenance2(), id, "second client name", 0);
        assertThat(staleSave.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        JsonNode problem = problem(staleSave);
        assertThat(problem.path("status").asInt()).isEqualTo(HttpStatus.CONFLICT.value());
        assertThat(problem.path("code").asText()).isEqualTo("DEM1002");
        assertThat(problem.path("type").asText()).isEqualTo(PROBLEM_TYPE_PREFIX + "DEM1002");
        assertThat(problem.path("detail").asText()).isEqualTo(STALE_TEXT);
        assertThat(problem.path("instance").asText()).isEqualTo(CUSTOMERS + "/" + id);
        assertThat(problem.has("errors")).as("DEM1002 names no field").isFalse();

        JsonNode currentNode = problem.path("current");
        assertThat(currentNode.isObject()).as("current member: %s", problem).isTrue();
        assertThat(currentNode.path("custId").asText()).isEqualTo(id);
        assertThat(currentNode.path("name").asText()).isEqualTo("FIRST CLIENT NAME");
        assertThat(currentNode.path("version").asLong()).isEqualTo(1);
        assertThat(currentNode.path("chgUser").asText()).isEqualTo(MAINTENANCE_USER);
        // The other eight data fields are the first client's values as stored (normalized).
        assertThat(currentNode.path("addr").asText()).isEqualTo("5 ELM ST");
        assertThat(currentNode.path("city").asText()).isEqualTo("DAYTON");
        assertThat(currentNode.path("state").asText()).isEqualTo(STATE);
        assertThat(currentNode.path("zip").asText()).isEqualTo(ZIP);
        assertThat(currentNode.path("corpPhone").asText()).isEqualTo(CORP_PHONE);
        assertThat(currentNode.path("acctMgr").asText()).isEqualTo("SAM ROE");
        assertThat(currentNode.path("acctPhone").asText()).isEqualTo(ACCT_PHONE);
        assertThat(currentNode.path("active").asText()).isEqualTo(ACTIVE);
        // current is the record the first save returned, change stamp included, and nothing more:
        // the application's mapper rejects any member CustomerResponse does not declare.
        assertThat(toCustomer(currentNode)).isEqualTo(saved);

        // The stale write changed nothing.
        CustomerResponse afterStale = get(maintenance2(), id);
        assertThat(afterStale.name()).isEqualTo("FIRST CLIENT NAME");
        assertThat(afterStale).isEqualTo(saved);

        // Re-apply the second editor's change on current.version.
        ResponseEntity<String> reapplied = put(maintenance2(), id, "second client name",
                currentNode.path("version").asLong());
        assertThat(reapplied.getStatusCode()).isEqualTo(HttpStatus.OK);
        CustomerResponse resaved = customer(reapplied);
        assertThat(resaved.version()).isEqualTo(2);
        assertThat(resaved.name()).isEqualTo("SECOND CLIENT NAME");
        assertThat(resaved.chgUser()).isEqualTo(MAINTENANCE2_USER);
        assertThat(get(maintenance(), id)).isEqualTo(resaved);
    }

    @Test
    @DisplayName("a row held past the 1-second lock timeout answers 409 DEM1001 with no data and writes nothing")
    void heldRowLockGivesDem1001() throws SQLException {
        String id = create();

        ResponseEntity<String> blocked;
        Duration elapsed;
        try (Connection lockHolder = dataSource.getConnection()) {
            lockHolder.setAutoCommit(false);
            try {
                // A second session updates the row and keeps its transaction open, holding the row lock.
                // Unqualified: the pool's schema setting resolves the name, as in DatabaseCleaner.
                try (PreparedStatement lock =
                        lockHolder.prepareStatement("UPDATE custmast SET name = name WHERE custid = ?")) {
                    lock.setString(1, id);
                    assertThat(lock.executeUpdate()).isEqualTo(1);
                }

                long start = System.nanoTime();
                // Preemptive, so a wait that never ends fails here and still releases the lock below.
                blocked = assertTimeoutPreemptively(MAX_LOCK_WAIT,
                        () -> put(maintenance(), id, "blocked name", 0),
                        "the blocked update must end with the lock timeout");
                elapsed = Duration.ofNanos(System.nanoTime() - start);
            } finally {
                // Release the row lock before the connection returns to the pool.
                lockHolder.rollback();
            }
        }

        assertThat(blocked.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        JsonNode problem = problem(blocked);
        assertThat(problem.path("status").asInt()).isEqualTo(HttpStatus.CONFLICT.value());
        assertThat(problem.path("code").asText()).isEqualTo("DEM1001");
        assertThat(problem.path("type").asText()).isEqualTo(PROBLEM_TYPE_PREFIX + "DEM1001");
        assertThat(problem.path("detail").asText()).isEqualTo(LOCKED_TEXT);
        assertThat(problem.path("instance").asText()).isEqualTo(CUSTOMERS + "/" + id);
        // No data travels with DEM1001: the source's SQLERRMC was never displayed.
        assertThat(problem.has("errors")).as("errors member in %s", problem).isFalse();
        assertThat(problem.has("current")).as("current member in %s", problem).isFalse();
        assertThat(problem.has("errorId")).as("errorId member in %s", problem).isFalse();
        assertThat(problem.has("stateAccepted")).as("stateAccepted member in %s", problem).isFalse();
        if (problem.has("args")) {
            JsonNode args = problem.get("args");
            assertThat(args.isArray()).as("args in %s", problem).isTrue();
            assertThat(args.size()).as("args in %s", problem).isZero();
        }
        // The wait ended with the configured lock timeout, not earlier and not by hanging.
        assertThat(elapsed).isGreaterThanOrEqualTo(MIN_LOCK_WAIT).isLessThan(MAX_LOCK_WAIT);

        // The blocked attempt changed nothing.
        CustomerResponse unchanged = get(maintenance(), id);
        assertThat(unchanged.name()).isEqualTo("CONCURRENCY CO");
        assertThat(unchanged.version()).isZero();

        // With the lock released, the same edit at the same version succeeds at once.
        ResponseEntity<String> unblocked = put(maintenance(), id, "unblocked name", 0);
        assertThat(unblocked.getStatusCode()).isEqualTo(HttpStatus.OK);
        CustomerResponse saved = customer(unblocked);
        assertThat(saved.version()).isEqualTo(1);
        assertThat(saved.name()).isEqualTo("UNBLOCKED NAME");
        assertThat(saved.chgUser()).isEqualTo(MAINTENANCE_USER);
    }

    /**
     * Adds the test customer as {@value #MAINTENANCE_USER} with nine valid fields.
     *
     * @return the allocated customer id
     */
    private String create() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", CREATED_NAME);
        body.put("addr", ADDR);
        body.put("city", CITY);
        body.put("state", STATE);
        body.put("zip", ZIP);
        body.put("corpPhone", CORP_PHONE);
        body.put("acctMgr", ACCT_MGR);
        body.put("acctPhone", ACCT_PHONE);
        body.put("active", ACTIVE);

        ResponseEntity<String> response = maintenance().post().uri(CUSTOMERS)
                .contentType(MediaType.APPLICATION_JSON).body(toJson(body))
                .retrieve().toEntity(String.class);

        assertThat(response.getStatusCode()).as("add answered %s", response.getBody()).isEqualTo(HttpStatus.CREATED);
        CustomerResponse created = customer(response);
        assertThat(created.custId()).matches(CUST_ID_FORMAT);
        assertThat(created.version()).isZero();
        return created.custId();
    }

    /**
     * Puts the test customer's nine fields, with {@code name} replaced, at the given version.
     *
     * @param client  the signed-in client
     * @param id      the customer id
     * @param name    the new name, as sent
     * @param version the version the client read
     * @return the response, any status
     */
    private ResponseEntity<String> put(RestClient client, String id, String name, long version) {
        CustomerUpdateRequest body = new CustomerUpdateRequest(name, ADDR, CITY, STATE, ZIP, CORP_PHONE,
                ACCT_MGR, ACCT_PHONE, ACTIVE, version);
        return client.put().uri(CUSTOMERS + "/{custId}", id)
                .contentType(MediaType.APPLICATION_JSON).body(toJson(body))
                .retrieve().toEntity(String.class);
    }

    /**
     * Reads one customer and asserts that it exists.
     *
     * @param client the signed-in client
     * @param id     the customer id
     * @return the stored customer
     */
    private CustomerResponse get(RestClient client, String id) {
        ResponseEntity<String> response = client.get().uri(CUSTOMERS + "/{custId}", id)
                .retrieve().toEntity(String.class);
        assertThat(response.getStatusCode()).as("get answered %s", response.getBody()).isEqualTo(HttpStatus.OK);
        return customer(response);
    }

    /**
     * Parses a customer body.
     *
     * @param response a 200 or 201 response
     * @return the body as a {@link CustomerResponse}
     */
    private CustomerResponse customer(ResponseEntity<String> response) {
        return toCustomer(json(response));
    }

    /**
     * Converts a JSON customer with the application's mapper, which rejects members
     * {@link CustomerResponse} does not declare.
     *
     * @param node the customer object
     * @return the customer
     * @throws AssertionError if the node is not a {@link CustomerResponse}
     */
    private CustomerResponse toCustomer(JsonNode node) {
        try {
            return objectMapper.treeToValue(node, CustomerResponse.class);
        } catch (JsonProcessingException e) {
            throw new AssertionError("Not a CustomerResponse: " + node, e);
        }
    }

    /**
     * Serializes a request body with the application's mapper.
     *
     * @param body the body
     * @return the JSON text
     */
    private String toJson(Object body) {
        try {
            return objectMapper.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new AssertionError("Cannot serialize " + body, e);
        }
    }
}
