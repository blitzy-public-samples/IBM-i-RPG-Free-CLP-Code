package com.democorp.customermaster.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.democorp.customermaster.support.AbstractPostgresIT;
import com.democorp.customermaster.support.DatabaseCleaner;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.Filter;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClientException;

/**
 * Proves that the log of an unexpected failure carries its diagnostics and nothing the failure's messages
 * quote: one ERROR line per failure with the {@code errorId} of the problem body, the SQLSTATE, the
 * exception types and their stack frames, and no customer value, no driver {@code DETAIL}, no secret and
 * no line forged by a CR/LF inside a value.
 *
 * <ul>
 *   <li><b>Persistence failure in the MVC path.</b> A row is inserted under {@code EEEF}, the id the next
 *       add allocates, so the add's {@code INSERT} fails with SQLSTATE 23505. Spring Data JDBC wraps that
 *       failure in a {@code DbActionExecutionException} whose message quotes the aggregate, and the driver's
 *       message quotes {@code Key (custid)=(EEEF) already exists}; every request value is a sentinel, some
 *       followed by text shaped like a log line ({@code FORGED-LINE-…}). The values hold no CR or LF,
 *       which the request rule rejects with 400 APP0400 before any {@code INSERT}; the filter failure
 *       below keeps a CR/LF in its exception message. {@code ApiExceptionHandler} must answer 500
 *       {@code DEM9999} and log it once, redacted.</li>
 *   <li><b>Filter failure.</b> A test-only servlet filter, registered last, throws outside any controller.
 *       {@code ErrorDispatchFilter} must hand it to the ERROR dispatch, so {@code ProblemErrorController}
 *       answers 500 {@code DEM9999} in {@code application/problem+json} and writes the only ERROR line;
 *       Tomcat's own {@code Servlet.service() ... threw exception} line must not appear.</li>
 *   <li><b>Filter failure after commit.</b> A second test-only filter commits a 200 response and then
 *       throws. {@code ErrorDispatchFilter} must write the only ERROR line itself, with an {@code errorId},
 *       and the connection must be closed so the client cannot take the truncated body as complete.</li>
 * </ul>
 *
 * <p><b>Context variant.</b> The nested {@link FilterConfig} adds the two test-only filters, so this class
 * forks a Spring context of its own beside the four the suite documents. A filter that fails outside any
 * controller cannot be provoked otherwise: no production filter throws on demand, and a mocked bean would
 * fork a context just the same. The configuration is {@link ConditionalOnWebApplication}, so it stays out
 * of a non-web context that scans the test classes.
 *
 * <p>Console output is captured by {@link OutputCaptureExtension}; Boot routes Tomcat's JULI logging to
 * the same Logback console appender, so the container's lines are captured too.
 */
@ExtendWith(OutputCaptureExtension.class)
@DisplayName("Unexpected failures: one redacted ERROR line with errorId and SQLSTATE")
class RedactedErrorLogIT extends AbstractPostgresIT {

    /** Path prefix of the test-only filter that throws before anything is written. */
    static final String FILTER_FAILURE_PATH = "/test-only/filter-failure/";

    /** Path prefix of the test-only filter that throws after committing a response. */
    static final String COMMITTED_FAILURE_PATH = "/test-only/committed-failure/";

    /** The secret the uncommitted test filter puts in its exception message. */
    static final String FILTER_SECRET = "secret-xyz";

    /** The secret the committed test filter puts in its exception message. */
    static final String COMMITTED_SECRET = "secret-committed-abc";

    /** Body the committed test filter writes and flushes before it throws. */
    static final String PARTIAL_BODY = "partial-body-";

    /** A canonical UUID, the form of every {@code errorId}. */
    private static final Pattern UUID_FORM =
            Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

    /** Text of the request values that must never reach the log. */
    private static final List<String> CUSTOMER_SENTINELS = List.of(
            "SENTNAME1", "SENTADDR1", "SENTCITY1", "SENTZIP1", "SENTCORP1", "SENTMGR1", "SENTACCT1",
            "FORGED-LINE");

    @Test
    @DisplayName("duplicate-key add: 500 DEM9999, one ERROR line with errorId and 23505, no customer value")
    void duplicateKeyAddLogsRedactedDiagnostics(CapturedOutput output) {
        jdbcTemplate.update("INSERT INTO custmast (custid, name, addr, city, state, zip)"
                + " VALUES (?, 'EXISTING ROW', '1 MAIN ST', 'AUBURN', 'ME', '04210')",
                DatabaseCleaner.FIRST_INTERACTIVE_ID);
        Map<String, String> body = new LinkedHashMap<>();
        // Control-free: the request rule rejects a CR or LF in a value with 400 before the INSERT runs.
        body.put("name", "SENTNAME1 FORGED-LINE-NAME ERROR");
        body.put("addr", "SENTADDR1 FORGED-LINE-ADDR");
        body.put("city", "SENTCITY1");
        body.put("state", "ME");
        body.put("zip", "SENTZIP1");
        body.put("corpPhone", "SENTCORP1");
        body.put("acctMgr", "SENTMGR1 FORGED-LINE-MGR");
        body.put("acctPhone", "SENTACCT1");
        body.put("active", "Y");

        ResponseEntity<String> response = maintenance().post().uri("/api/customers")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .toEntity(String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        JsonNode problem = problem(response);
        assertThat(problem.path("code").asText()).isEqualTo("DEM9999");
        String errorId = problem.path("errorId").asText();
        assertThat(errorId).matches(UUID_FORM);
        assertThat(response.getBody()).doesNotContain(CUSTOMER_SENTINELS).doesNotContain("23505");

        String log = output.getAll();
        List<String> errorLines = errorLines(log);
        assertThat(errorLines).singleElement().satisfies(line -> assertThat(line)
                .contains("Unhandled error errorId=" + errorId + " sqlState=23505 uri=/api/customers"));
        assertThat(log)
                .contains("RedactedThrowable: org.springframework.data.relational.core.conversion."
                        + "DbActionExecutionException [message withheld]")
                .contains("org.springframework.dao.DuplicateKeyException [message withheld]")
                .contains("org.postgresql.util.PSQLException [SQLSTATE 23505, vendor code 0; message withheld]")
                .contains("\tat org.springframework.data.jdbc.core.")
                .contains("\tat com.democorp.customermaster.service.CustomerMaintenanceService.add(")
                .doesNotContain(CUSTOMER_SENTINELS)
                .doesNotContain("already exists")
                .doesNotContain("InsertRoot{")
                .doesNotContain("Customer[");
        assertThat(log.lines()).noneMatch(line -> line.startsWith("FORGED"));
    }

    @Test
    @DisplayName("filter failure: 500 DEM9999 problem+json and exactly one ERROR line, from the error controller")
    void filterFailureHasOneErrorLine(CapturedOutput output) {
        ResponseEntity<String> response = maintenance().get().uri(FILTER_FAILURE_PATH + "probe")
                .retrieve()
                .toEntity(String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        JsonNode problem = problem(response);
        assertThat(problem.path("code").asText()).isEqualTo("DEM9999");
        assertThat(problem.path("instance").asText()).isEqualTo(FILTER_FAILURE_PATH + "probe");
        String errorId = problem.path("errorId").asText();
        assertThat(errorId).matches(UUID_FORM);
        assertThat(response.getBody()).doesNotContain(FILTER_SECRET).doesNotContain("IllegalStateException");

        String log = output.getAll();
        assertThat(errorLines(log)).singleElement().satisfies(line -> assertThat(line)
                .contains("ProblemErrorController")
                .contains("Unhandled error errorId=" + errorId + " status=500 uri=" + FILTER_FAILURE_PATH
                        + "probe sqlState=-"));
        assertThat(log)
                .contains("RedactedThrowable: java.lang.IllegalStateException [message withheld]")
                .contains("\tat com.democorp.customermaster.controller.RedactedErrorLogIT$FilterConfig")
                .doesNotContain(FILTER_SECRET)
                .doesNotContain("Servlet.service()");
    }

    @Test
    @DisplayName("filter failure after commit: connection closed, exactly one ERROR line with errorId")
    void committedFilterFailureHasOneErrorLine(CapturedOutput output) {
        Throwable failure = catchThrowable(() -> maintenance().get().uri(COMMITTED_FAILURE_PATH + "probe")
                .retrieve()
                .toEntity(String.class));

        assertThat(failure)
                .as("the truncated response must fail to read, not look complete")
                .isInstanceOf(RestClientException.class)
                .hasRootCauseInstanceOf(IOException.class);
        String log = output.getAll();
        List<String> errorLines = errorLines(log);
        assertThat(errorLines).singleElement().satisfies(line -> assertThat(line)
                .contains("ErrorDispatchFilter")
                .contains("status=200 uri=" + COMMITTED_FAILURE_PATH + "probe sqlState=-"));
        String errorId = errorLines.get(0).replaceAll(".*errorId=(\\S+) .*", "$1");
        assertThat(errorId).matches(UUID_FORM);
        assertThat(log)
                .contains("Response already committed; errorId=" + errorId + " not answered, connection closed")
                .contains("RedactedThrowable: java.lang.IllegalStateException [message withheld]")
                .doesNotContain(COMMITTED_SECRET)
                .doesNotContain("Servlet.service()");
    }

    /**
     * Returns the log lines at level ERROR.
     *
     * @param log the captured console output
     * @return the lines whose level field is {@code ERROR}
     */
    private static List<String> errorLines(String log) {
        return log.lines().filter(line -> line.matches("^\\S+\\s+ERROR\\s.*")).toList();
    }

    /**
     * The two test-only filters, registered after every application filter; web contexts only.
     */
    @TestConfiguration(proxyBeanMethods = false)
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    static class FilterConfig {

        /**
         * A filter that throws before anything is written, its message carrying {@value #FILTER_SECRET}.
         *
         * @return the registration of the filter on {@value #FILTER_FAILURE_PATH}{@code *}
         */
        @Bean
        FilterRegistrationBean<Filter> failingFilter() {
            FilterRegistrationBean<Filter> registration = new FilterRegistrationBean<>((request, response, chain) -> {
                throw new IllegalStateException("filter failure " + FILTER_SECRET + "\r\nFORGED-LINE-FILTER");
            });
            registration.addUrlPatterns(FILTER_FAILURE_PATH + "*");
            registration.setOrder(Ordered.LOWEST_PRECEDENCE);
            return registration;
        }

        /**
         * A filter that commits a 200 response with part of a body, then throws, its message carrying
         * {@value #COMMITTED_SECRET}.
         *
         * @return the registration of the filter on {@value #COMMITTED_FAILURE_PATH}{@code *}
         */
        @Bean
        FilterRegistrationBean<Filter> committedFailingFilter() {
            FilterRegistrationBean<Filter> registration = new FilterRegistrationBean<>((request, response, chain) -> {
                HttpServletResponse http = (HttpServletResponse) response;
                http.setStatus(HttpServletResponse.SC_OK);
                http.setContentType(MediaType.TEXT_PLAIN_VALUE);
                PrintWriter writer = http.getWriter();
                writer.write(PARTIAL_BODY);
                writer.flush();
                http.flushBuffer();
                throw new IllegalStateException("committed failure " + COMMITTED_SECRET);
            });
            registration.addUrlPatterns(COMMITTED_FAILURE_PATH + "*");
            registration.setOrder(Ordered.LOWEST_PRECEDENCE);
            return registration;
        }
    }
}
