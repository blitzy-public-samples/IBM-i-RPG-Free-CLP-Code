package com.democorp.customermaster.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.democorp.customermaster.config.ConnectionPoolSaturation;
import com.democorp.customermaster.messages.MessageCatalog;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.transaction.CannotCreateTransactionException;

/**
 * Specifies how {@link ApiExceptionHandler} answers a transaction that could not start for want of a
 * pooled connection: an add or an update whose borrow timed out in a saturated pool while PostgreSQL
 * answers is the lock wait it is, 409 {@code DEM1001} exactly as a {@code CustomerLockedException};
 * every other request or cause keeps the catch-all 500 {@code DEM9999} with an {@code errorId}.
 * {@code ConnectionPoolSaturationIT} proves the same over HTTP with a real saturated pool.
 *
 * <p>Plain JUnit 5, AssertJ and Mockito over a real {@link ProblemFactory} and {@link MessageCatalog},
 * with Spring's mock servlet request and response. The probe of {@link ConnectionPoolSaturation} is
 * mocked; the saturation predicate is the real static method, fed the exception shapes HikariCP
 * throws. Console output is captured by {@link OutputCaptureExtension}.
 */
@ExtendWith(OutputCaptureExtension.class)
@DisplayName("ApiExceptionHandler: writes in a saturated connection pool")
final class ApiExceptionHandlerPoolSaturationTest {

    /** The add route. */
    private static final String ADD = "/api/customers";

    /** An update route. */
    private static final String UPDATE = "/api/customers/EEEF";

    /** A canonical UUID, the form of every {@code errorId}. */
    private static final Pattern UUID_FORM =
            Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

    /** The text of HikariCP's borrow timeout, which must never reach a body or a log line. */
    private static final String HIKARI_TIMEOUT_MESSAGE = "HikariPool-1 - Connection is not available, request"
            + " timed out after 5000ms (total=10, active=10, idle=0, waiting=1)";

    /** The text of a connection-creation failure, which must never reach a body. */
    private static final String CONNECTION_REFUSED_MESSAGE = "Connection to db:5432 refused";

    /** The probe, answering as each test sets it. */
    private ConnectionPoolSaturation poolSaturation;

    /** The handler under test. */
    private ApiExceptionHandler handler;

    @BeforeEach
    void createHandler() {
        poolSaturation = mock(ConnectionPoolSaturation.class);
        handler = new ApiExceptionHandler(new ProblemFactory(new MessageCatalog(), new ObjectMapper()),
                poolSaturation);
    }

    @Test
    @DisplayName("an add whose borrow timed out in a saturated pool, PostgreSQL answering: 409 DEM1001")
    void addInSaturatedPoolIsALockWait(CapturedOutput output) {
        when(poolSaturation.databaseAnswers()).thenReturn(true);

        ResponseEntity<Object> response = handle("POST", ADD, saturationFailure());

        assertLockWait(response, ADD);
        assertThat(output.getAll().lines().filter(line -> line.contains(" WARN ")).toList())
                .singleElement()
                .satisfies(line -> assertThat(line)
                        .contains("Connection pool stayed saturated; write answered code=DEM1001 uri=" + ADD));
        assertThat(output.getAll())
                .doesNotContain("Connection is not available")
                .doesNotContain(" ERROR ");
    }

    @Test
    @DisplayName("an update whose borrow timed out in a saturated pool, PostgreSQL answering: 409 DEM1001")
    void updateInSaturatedPoolIsALockWait() {
        when(poolSaturation.databaseAnswers()).thenReturn(true);

        assertLockWait(handle("PUT", UPDATE, saturationFailure()), UPDATE);
    }

    @Test
    @DisplayName("the write route is matched on the path within the application, after the context path")
    void writeRouteIgnoresTheContextPath() {
        when(poolSaturation.databaseAnswers()).thenReturn(true);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/app" + ADD);
        request.setContextPath("/app");

        ResponseEntity<Object> response = handler.handleCannotCreateTransaction(saturationFailure(), request,
                new MockHttpServletResponse());

        assertLockWait(response, "/app" + ADD);
    }

    @Test
    @DisplayName("a saturated pool while PostgreSQL does not answer the probe: 500 DEM9999 with errorId")
    void saturatedPoolWithoutDatabaseIsAProgramError(CapturedOutput output) {
        when(poolSaturation.databaseAnswers()).thenReturn(false);

        String errorId = assertProgramError(handle("POST", ADD, saturationFailure()));

        verify(poolSaturation).databaseAnswers();
        assertThat(output.getAll()).contains("Unhandled error errorId=" + errorId + " sqlState=- uri=" + ADD);
    }

    @Test
    @DisplayName("a borrow timeout carrying a connection failure is a lost database, never probed: 500")
    void borrowTimeoutWithConnectionFailureIsAProgramError() {
        when(poolSaturation.databaseAnswers()).thenReturn(true);
        SQLException refused = new SQLException(CONNECTION_REFUSED_MESSAGE, "08001");
        SQLTransientConnectionException timeout =
                new SQLTransientConnectionException(HIKARI_TIMEOUT_MESSAGE, "08001", 0, refused);
        timeout.setNextException(refused);

        assertProgramError(handle("PUT", UPDATE, new CannotCreateTransactionException(
                "Could not open JDBC Connection for transaction", timeout)));

        verify(poolSaturation, never()).databaseAnswers();
    }

    @Test
    @DisplayName("a transaction that failed to start for another reason is a program error, never probed")
    void otherTransactionStartFailureIsAProgramError() {
        when(poolSaturation.databaseAnswers()).thenReturn(true);

        assertProgramError(handle("POST", ADD, new CannotCreateTransactionException(
                "Could not open JDBC Connection for transaction", new SQLException("I/O error", "08006"))));

        verify(poolSaturation, never()).databaseAnswers();
    }

    @Test
    @DisplayName("a read in a saturated pool keeps the catch-all 500 DEM9999, never probed")
    void readInSaturatedPoolIsAProgramError() {
        when(poolSaturation.databaseAnswers()).thenReturn(true);

        assertProgramError(handle("GET", UPDATE, saturationFailure()));
        assertProgramError(handle("GET", ADD, saturationFailure()));

        verify(poolSaturation, never()).databaseAnswers();
    }

    @Test
    @DisplayName("a review in a saturated pool keeps the catch-all 500 DEM9999, never probed")
    void reviewInSaturatedPoolIsAProgramError() {
        when(poolSaturation.databaseAnswers()).thenReturn(true);

        assertProgramError(handle("POST", ADD + "/review", saturationFailure()));

        verify(poolSaturation, never()).databaseAnswers();
    }

    @Test
    @DisplayName("routes that are neither the add nor an update keep the catch-all 500 DEM9999")
    void otherRoutesInSaturatedPoolAreProgramErrors() {
        when(poolSaturation.databaseAnswers()).thenReturn(true);

        assertProgramError(handle("PUT", ADD, saturationFailure()));
        assertProgramError(handle("PUT", ADD + "/", saturationFailure()));
        assertProgramError(handle("PUT", UPDATE + "/x", saturationFailure()));
        assertProgramError(handle("POST", UPDATE, saturationFailure()));
        assertProgramError(handle("POST", "/api/customersX", saturationFailure()));

        verify(poolSaturation, never()).databaseAnswers();
    }

    /**
     * Builds the failure of a transaction start whose borrow timed out in a saturated pool, as
     * {@code JdbcTransactionManager} wraps HikariCP's timeout: no cause and no next exception, because
     * no connection attempt failed.
     *
     * @return the failure
     */
    private static CannotCreateTransactionException saturationFailure() {
        return new CannotCreateTransactionException("Could not open JDBC Connection for transaction",
                new SQLTransientConnectionException(HIKARI_TIMEOUT_MESSAGE));
    }

    private ResponseEntity<Object> handle(String method, String uri, CannotCreateTransactionException ex) {
        return handler.handleCannotCreateTransaction(ex, new MockHttpServletRequest(method, uri),
                new MockHttpServletResponse());
    }

    private static void assertLockWait(ResponseEntity<Object> response, String instance) {
        assertThat(response).isNotNull();
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getHeaders().getContentType()).isNotNull();
        assertThat(MediaType.APPLICATION_PROBLEM_JSON.isCompatibleWith(response.getHeaders().getContentType()))
                .isTrue();
        ProblemDetail problem = (ProblemDetail) response.getBody();
        assertThat(problem).isNotNull();
        assertThat(problem.getDetail()).isEqualTo("Customer being updated by another user or job.");
        assertThat(problem.getInstance()).hasToString(instance);
        assertThat(problem.getProperties())
                .containsEntry("code", "DEM1001")
                .containsEntry("args", List.of())
                .doesNotContainKey("errorId")
                .doesNotContainKey("errors")
                .doesNotContainKey("current");
        assertThat(String.valueOf(problem)).doesNotContain("Connection is not available");
    }

    private static String assertProgramError(ResponseEntity<Object> response) {
        assertThat(response).isNotNull();
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        ProblemDetail problem = (ProblemDetail) response.getBody();
        assertThat(problem).isNotNull();
        assertThat(problem.getProperties()).containsEntry("code", "DEM9999");
        assertThat(problem.getProperties().get("errorId")).asString().matches(UUID_FORM);
        assertThat(String.valueOf(problem))
                .doesNotContain("Connection is not available")
                .doesNotContain(CONNECTION_REFUSED_MESSAGE);
        return (String) problem.getProperties().get("errorId");
    }
}
