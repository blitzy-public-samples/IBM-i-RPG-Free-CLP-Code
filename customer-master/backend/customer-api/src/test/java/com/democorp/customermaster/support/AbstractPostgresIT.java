package com.democorp.customermaster.support;

import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import javax.sql.DataSource;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Shared base of every backend integration test in customer-api: one PostgreSQL 18.6 container for the
 * whole Failsafe run, the full application on a random port under profile {@code test}, a database
 * reset before each test, HTTP clients for the test users, and JSON and problem+json body helpers.
 *
 * <p><b>Database.</b> {@link #POSTGRES} starts once, when the first subclass loads this class, and is
 * never stopped explicitly; Testcontainers' Ryuk removes it when the JVM exits. Its
 * {@link ServiceConnection} replaces only the datasource URL and credentials, so the schema still
 * comes from {@code application.yml} ({@code spring.datasource.hikari.schema} and
 * {@code spring.flyway.schemas}, {@code DB_SCHEMA}), and unqualified test SQL resolves to it. Flyway
 * applies {@code classpath:db/migration} only (V1 to V4): the {@code customer_sort} collation,
 * {@code states} with its 58 rows, {@code custmast} and {@code custmast_id_seq}. Test databases hold
 * no V5 seed rows.
 *
 * <p><b>Configuration.</b> Profile {@code test} adds {@code application-test.yml}: the users
 * {@value #INQUIRY_USER} ({@code INQUIRY}), {@value #MAINTENANCE_USER} and
 * {@value #MAINTENANCE2_USER} ({@code MAINTENANCE}), the stub address client and a 1-second
 * {@code customer-master.db.lock-timeout}. No {@code CM_*}, {@code DB_*} or {@code USPS_*} variable is
 * needed.
 *
 * <p><b>HTTP clients.</b> Every client factory returns a fresh {@link RestClient} bound to
 * {@link #baseUrl()} whose status handler accepts every status, so no status ever throws: tests read
 * any status with {@code retrieve().toEntity(String.class)}. HTTP Basic is the only authentication
 * ({@code SecurityConfig}); a 401 carries {@code WWW-Authenticate: Basic realm="customer-master"}
 * only when {@value #X_REQUESTED_WITH} is absent. The SPA always sends
 * {@code X-Requested-With: XMLHttpRequest}, hence the {@code ...Xhr()} variants. No client sets a
 * default {@code Accept} header; a test that needs one, such as {@code ErrorModelIT} with
 * {@code text/html}, sets it on its request.
 *
 * <p><b>Context policy binding all ITs</b> ({@code DatabaseCleaner} carries the same text). One
 * PostgreSQL container serves every cached Spring context. Each distinct {@code @MockitoBean} or
 * {@code @Import} set forks a Spring context with its own Hikari pool on that container. The suite
 * keeps to four variants:
 * <ol>
 *   <li>the base;</li>
 *   <li>{@code @MockitoBean LoadCheckpoints}, declared identically in {@code CustomerGeneratorIT} and
 *       {@code CustomerIdAllocationIT};</li>
 *   <li>{@code @MockitoBean AddressValidationClient} in {@code CustomerMaintenanceApiIT};</li>
 *   <li>{@code ErrorModelIT}, with {@code @MockitoBean CustomerSearchService} and
 *       {@code CustomerMaintenanceService} plus one nested {@code @ConditionalOnWebApplication}
 *       {@code @TestConfiguration}.</li>
 * </ol>
 * A new variant needs a reason recorded in the subclass that introduces it. Tests must release every
 * lock and close every extra connection and transaction in {@code finally}, or the {@code TRUNCATE}
 * in {@code DatabaseCleaner.clean()} blocks. Worker threads outside an HTTP request must set their own
 * {@code SecurityContext} before calling services that use {@code CurrentUser}
 * ({@code CustomerIdAllocationIT}).
 *
 * <p>This class carries no Spring stereotype and declares no nested configuration: test classes are on
 * the classpath when {@code GeneratorProcessIT}'s {@code ApplicationContextRunner} runs the
 * application's component scan without a {@code TestTypeExcludeFilter}. Failsafe includes
 * {@code *IT}, but this class is abstract, so JUnit Jupiter never runs it directly.
 *
 * <p>Example:
 * <pre>{@code
 * class StateApiIT extends AbstractPostgresIT {
 *     @Test
 *     void listsAllStates() {
 *         ResponseEntity<String> response = inquiryXhr().get().uri("/api/states")
 *                 .retrieve().toEntity(String.class);
 *         assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
 *         assertThat(json(response)).hasSize(DatabaseCleaner.STATE_COUNT);
 *     }
 * }
 * }</pre>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
public abstract class AbstractPostgresIT {

    /** Test user with role {@code INQUIRY}, from {@code application-test.yml}. */
    protected static final String INQUIRY_USER = "inq";

    /** Password of {@value #INQUIRY_USER}, from {@code application-test.yml}. */
    protected static final String INQUIRY_PASSWORD = "inq-pw";

    /** Test user with role {@code MAINTENANCE}, from {@code application-test.yml}. */
    protected static final String MAINTENANCE_USER = "maint";

    /** Password of {@value #MAINTENANCE_USER}, from {@code application-test.yml}. */
    protected static final String MAINTENANCE_PASSWORD = "maint-pw";

    /** Second test user with role {@code MAINTENANCE}, for concurrent editors. */
    protected static final String MAINTENANCE2_USER = "maint2";

    /** Password of {@value #MAINTENANCE2_USER}, from {@code application-test.yml}. */
    protected static final String MAINTENANCE2_PASSWORD = "maint2-pw";

    /** The header the SPA sends on every call; it suppresses the {@code WWW-Authenticate} challenge. */
    protected static final String X_REQUESTED_WITH = "X-Requested-With";

    /** The value the SPA sends in {@value #X_REQUESTED_WITH}. */
    protected static final String XML_HTTP_REQUEST = "XMLHttpRequest";

    /** Longest wait for a response once a request is sent. */
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(30);

    /**
     * The one JDK HTTP client behind every test client. HTTP/1.1 avoids h2c upgrade headers against
     * Tomcat. It has no {@code Authenticator}, so a 401 reaches the test as sent.
     */
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    /**
     * The shared database server, running before the first test context starts. Subclasses that open
     * their own connections or start a separate process read its address here. The URL from
     * {@code getJdbcUrl()} carries no schema, so {@code GeneratorProcessIT} must pass the schema itself,
     * for example as {@code DB_SCHEMA} or as {@code currentSchema} in the URL.
     */
    @ServiceConnection
    protected static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18.6");

    static {
        POSTGRES.start();
    }

    /** The port the application listens on in this test context. */
    @LocalServerPort
    protected int port;

    /** Runs SQL on the application's datasource; unqualified names resolve to the test schema. */
    @Autowired
    protected JdbcTemplate jdbcTemplate;

    /** The application's Hikari datasource on {@link #POSTGRES}, for tests that need a raw connection. */
    @Autowired
    protected DataSource dataSource;

    /** Boot's Jackson mapper, configured as the application's. */
    @Autowired
    protected ObjectMapper objectMapper;

    /** Boot's template over the application's single {@code JdbcTransactionManager}. */
    @Autowired
    protected TransactionTemplate transactionTemplate;

    /** For subclasses only: Spring injects the fields and JUnit runs {@link #cleanDatabase()}. */
    protected AbstractPostgresIT() {
        // Fields are injected by the Spring test context; nothing to initialize here.
    }

    /**
     * Resets the database before each test method: every test starts with an empty {@code custmast},
     * the 58 {@code states} rows, and next id {@code EEEF} ({@code DatabaseCleaner.FIRST_INTERACTIVE_ID}).
     * Data does not survive between test methods. A subclass that overrides this method must call
     * {@code super.cleanDatabase()}, because JUnit runs only the override.
     *
     * @throws IllegalStateException if {@code custmast} cannot be locked within 10 seconds because a
     *                               previous test left a transaction, lock or extra connection open
     */
    @BeforeEach
    protected void cleanDatabase() {
        new DatabaseCleaner(jdbcTemplate).clean();
    }

    /**
     * Returns the root URL of the running application.
     *
     * @return {@code "http://localhost:" + port}, with no trailing slash
     */
    protected String baseUrl() {
        return "http://localhost:" + port;
    }

    /**
     * Returns a builder for a client bound to {@link #baseUrl()} that never throws for any status, so
     * tests assert on any status through {@code retrieve().toEntity(String.class)}.
     *
     * @return a new builder with no default {@code Authorization} or {@code Accept} header
     */
    protected RestClient.Builder baseClient() {
        // JDK HttpClient only. SimpleClientHttpRequestFactory (HttpURLConnection) fails with
        // HttpRetryException when a 401 answers a streamed POST body, which SecurityRulesIT provokes
        // on purpose.
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(HTTP);
        factory.setReadTimeout(READ_TIMEOUT);
        return RestClient.builder()
                .requestFactory(factory)
                .baseUrl(baseUrl())
                .defaultStatusHandler(status -> true, (request, response) -> { });
    }

    /**
     * Returns a client that sends the given HTTP Basic credentials, which need not belong to a
     * configured user, so wrong or unknown credentials can be tested.
     *
     * @param username the username to send, encoded as UTF-8
     * @param password the password to send, encoded as UTF-8
     * @return a new client bound to {@link #baseUrl()}
     * @throws IllegalArgumentException if an argument is {@code null}
     */
    protected RestClient as(String username, String password) {
        return baseClient()
                .defaultHeaders(headers -> headers.setBasicAuth(username, password, StandardCharsets.UTF_8))
                .build();
    }

    /**
     * Returns a client that sends the given HTTP Basic credentials and
     * {@code X-Requested-With: XMLHttpRequest}, as the SPA does.
     *
     * @param username the username to send, encoded as UTF-8
     * @param password the password to send, encoded as UTF-8
     * @return a new client bound to {@link #baseUrl()}
     * @throws IllegalArgumentException if an argument is {@code null}
     */
    protected RestClient asXhr(String username, String password) {
        return baseClient()
                .defaultHeaders(headers -> headers.setBasicAuth(username, password, StandardCharsets.UTF_8))
                .defaultHeader(X_REQUESTED_WITH, XML_HTTP_REQUEST)
                .build();
    }

    /**
     * Returns a client signed in as {@value #INQUIRY_USER} (role {@code INQUIRY}).
     *
     * @return a new client sending that user's HTTP Basic credentials
     */
    protected RestClient inquiry() {
        return as(INQUIRY_USER, INQUIRY_PASSWORD);
    }

    /**
     * Returns a client signed in as {@value #INQUIRY_USER} that also sends {@code X-Requested-With}.
     *
     * @return a new client sending that user's credentials as the SPA does
     */
    protected RestClient inquiryXhr() {
        return asXhr(INQUIRY_USER, INQUIRY_PASSWORD);
    }

    /**
     * Returns a client signed in as {@value #MAINTENANCE_USER} (role {@code MAINTENANCE}).
     *
     * @return a new client sending that user's HTTP Basic credentials
     */
    protected RestClient maintenance() {
        return as(MAINTENANCE_USER, MAINTENANCE_PASSWORD);
    }

    /**
     * Returns a client signed in as {@value #MAINTENANCE_USER} that also sends {@code X-Requested-With}.
     *
     * @return a new client sending that user's credentials as the SPA does
     */
    protected RestClient maintenanceXhr() {
        return asXhr(MAINTENANCE_USER, MAINTENANCE_PASSWORD);
    }

    /**
     * Returns a client signed in as {@value #MAINTENANCE2_USER}, the second {@code MAINTENANCE} user.
     *
     * @return a new client sending that user's HTTP Basic credentials
     */
    protected RestClient maintenance2() {
        return as(MAINTENANCE2_USER, MAINTENANCE2_PASSWORD);
    }

    /**
     * Returns a client signed in as {@value #MAINTENANCE2_USER} that also sends {@code X-Requested-With}.
     *
     * @return a new client sending that user's credentials as the SPA does
     */
    protected RestClient maintenance2Xhr() {
        return asXhr(MAINTENANCE2_USER, MAINTENANCE2_PASSWORD);
    }

    /**
     * Returns a client that sends no {@code Authorization} header.
     *
     * @return a new client bound to {@link #baseUrl()}
     */
    protected RestClient anonymous() {
        return baseClient().build();
    }

    /**
     * Returns a client that sends no {@code Authorization} header, only
     * {@code X-Requested-With: XMLHttpRequest}.
     *
     * @return a new client bound to {@link #baseUrl()}
     */
    protected RestClient anonymousXhr() {
        return baseClient()
                .defaultHeader(X_REQUESTED_WITH, XML_HTTP_REQUEST)
                .build();
    }

    /**
     * Parses a response body as JSON with {@link #objectMapper}.
     *
     * @param response a response read with {@code retrieve().toEntity(String.class)}
     * @return the parsed body
     * @throws AssertionError if the body is null, empty or blank, or is not JSON; the message shows the
     *                        status and the raw body
     */
    protected JsonNode json(ResponseEntity<String> response) {
        String body = response.getBody();
        if (body == null || body.isBlank()) {
            throw new AssertionError(
                    "Expected a JSON body, but the response with status " + response.getStatusCode()
                            + " has an empty body: <" + body + ">");
        }
        try {
            return objectMapper.readTree(body);
        } catch (JsonProcessingException e) {
            throw new AssertionError(
                    "Expected a JSON body, but the response with status " + response.getStatusCode()
                            + " has: " + body,
                    e);
        }
    }

    /**
     * Asserts that a response is {@code application/problem+json} and parses it. The members are those
     * of the error model: {@code type}, {@code title}, {@code status}, {@code instance}, {@code detail},
     * {@code code}, {@code args}, {@code errors[]} ({@code {field, code, message}}), {@code current}
     * (409 DEM1002), {@code stateAccepted} (review 422/502) and {@code errorId} (500). Tests read, for
     * example, {@code problem(r).path("code").asText()},
     * {@code problem(r).path("errors").get(0).path("field")},
     * {@code problem(r).path("current").path("version")}, {@code problem(r).path("stateAccepted")} and
     * {@code problem(r).path("errorId")}.
     *
     * @param response a response read with {@code retrieve().toEntity(String.class)}
     * @return the parsed problem body
     * @throws AssertionError if the {@code Content-Type} is missing or not compatible with
     *                        {@code application/problem+json} (the message gives the status, content
     *                        type and body), or if the body is not JSON
     */
    protected JsonNode problem(ResponseEntity<String> response) {
        MediaType contentType = response.getHeaders().getContentType();
        if (contentType == null || !MediaType.APPLICATION_PROBLEM_JSON.isCompatibleWith(contentType)) {
            throw new AssertionError(
                    "Expected " + MediaType.APPLICATION_PROBLEM_JSON + ", but the response with status "
                            + response.getStatusCode() + " has content type " + contentType + " and body: "
                            + response.getBody());
        }
        return json(response);
    }
}
