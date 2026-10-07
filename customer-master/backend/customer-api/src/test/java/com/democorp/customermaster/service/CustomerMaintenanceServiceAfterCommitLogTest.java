package com.democorp.customermaster.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.turbo.TurboFilter;
import ch.qos.logback.core.read.ListAppender;
import ch.qos.logback.core.spi.FilterReply;
import com.democorp.customermaster.config.AppProperties;
import com.democorp.customermaster.domain.Address;
import com.democorp.customermaster.domain.Customer;
import com.democorp.customermaster.domain.CustomerId;
import com.democorp.customermaster.messages.MessageCatalog;
import com.democorp.customermaster.repository.CustomerIdAllocator;
import com.democorp.customermaster.repository.CustomerRepository;
import com.democorp.customermaster.security.CurrentUser;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.Marker;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Specifies the after-commit {@code customer.write} line of {@link CustomerMaintenanceService}: one INFO
 * line per committed add or update, none on rollback, and a logging failure that never changes the outcome
 * of a write that has already committed.
 *
 * <p><b>Why it matters.</b> Spring runs {@code TransactionSynchronization.afterCommit} after the database
 * {@code COMMIT} and lets whatever the callback throws reach the caller of the transactional method. A
 * logging failure that escaped the callback would answer 500 for a stored customer, and a client retrying
 * that add would create a second one.
 *
 * <p><b>How the transaction runs.</b> {@link TransactionTemplate} drives the service over
 * {@link NoOpTransactionManager}, an {@link AbstractPlatformTransactionManager} whose resource operations do
 * nothing. The commit therefore takes Spring's real path, {@code processCommit} then
 * {@code triggerAfterCommit}, the one the production {@code DataSourceTransactionManager} takes, with no
 * database.
 *
 * <p><b>How logging fails.</b> {@link FailingWriteLineFilter}, a Logback {@link TurboFilter}, throws
 * {@link SimulatedLoggingFailure} for the service's {@code customer.write} format only, as a broken filter or
 * appender configuration would. Every other logger keeps working.
 *
 * <p>Pure JUnit 5, AssertJ, Mockito and Logback: no Spring context, no database, no Docker. Standard error,
 * the Logback filter, the list appender and the logger level are global state, so {@link #restoreLogging()}
 * puts each one back after every case.
 */
@DisplayName("CustomerMaintenanceService: the after-commit customer.write line never fails a committed write")
final class CustomerMaintenanceServiceAfterCommitLogTest {

    /** The logger the write line is sent through: the service's class logger. */
    private static final String SERVICE_LOGGER = CustomerMaintenanceService.class.getName();

    /** The format prefix of the write line, the only format {@link FailingWriteLineFilter} rejects. */
    private static final String WRITE_LINE_PREFIX = "customer.write";

    /** The id the mocked allocator hands out: the first id on a fresh database. */
    private static final CustomerId FIRST_ID = CustomerId.parse("EEEF");

    /** The authenticated principal, stamped as {@code chguser}. */
    private static final String USER = "sales";

    /** The message of the simulated failure; the fallback line must not repeat it. */
    private static final String FAILURE_MESSAGE = "simulated failure sentinel message";

    /** Customer values the fallback line must never contain: name, street, city, ZIP, phones, manager. */
    private static final List<String> CUSTOMER_VALUES = List.of(
            "SENTINEL TOOL COMPANY", "77 SENTINEL WAY", "SENTINELVILLE", "97531",
            "(415) 555-0177", "JANE SENTINEL", "(415) 555-0188");

    private final LoggerContext loggerContext = (LoggerContext) LoggerFactory.getILoggerFactory();

    private final ch.qos.logback.classic.Logger serviceLogger = loggerContext.getLogger(SERVICE_LOGGER);

    private final FailingWriteLineFilter failingFilter = new FailingWriteLineFilter();

    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

    private PrintStream originalErr;

    private Level originalLevel;

    private CustomerRepository repository;

    private CustomerIdAllocator allocator;

    private CustomerMaintenanceService service;

    private TransactionTemplate transactions;

    @BeforeEach
    void setUp() {
        originalErr = System.err;
        originalLevel = serviceLogger.getLevel();
        // INFO is set explicitly, so the case does not depend on whatever configured the root logger.
        serviceLogger.setLevel(Level.INFO);
        appender.setContext(loggerContext);
        appender.start();
        serviceLogger.addAppender(appender);

        repository = mock(CustomerRepository.class);
        allocator = mock(CustomerIdAllocator.class);
        CustomerValidator validator = mock(CustomerValidator.class);
        AddressStandardizationService addressStandardizationService = mock(AddressStandardizationService.class);
        CurrentUser currentUser = mock(CurrentUser.class);
        MessageCatalog messageCatalog = mock(MessageCatalog.class);
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(currentUser.name()).thenReturn(USER);
        when(allocator.next()).thenReturn(FIRST_ID);
        // As Spring Data JDBC's @Version handling: an insert stores 0, an update the read version + 1.
        when(repository.save(any(Customer.class))).thenAnswer(invocation -> {
            Customer written = invocation.getArgument(0);
            Long read = written.rowVersion();
            return written.withRowVersion(read == null ? 0L : read + 1);
        });

        AppProperties appProperties = new AppProperties(
                new AppProperties.Db(Duration.ofSeconds(5)), new AppProperties.Search(12, 100, 9999));
        Clock clock = Clock.fixed(Instant.parse("2026-10-07T09:30:00Z"), ZoneOffset.UTC);
        service = new CustomerMaintenanceService(repository, allocator, validator, addressStandardizationService,
                currentUser, messageCatalog, appProperties, clock, jdbcTemplate);
        transactions = new TransactionTemplate(new NoOpTransactionManager());
    }

    @AfterEach
    void restoreLogging() {
        System.setErr(originalErr);
        loggerContext.getTurboFilterList().remove(failingFilter);
        failingFilter.stop();
        serviceLogger.detachAppender(appender);
        appender.stop();
        serviceLogger.setLevel(originalLevel);
    }

    /**
     * A draft whose every value is a sentinel the fallback line is checked against.
     *
     * @return a new draft; {@code Customer.draft} takes name, address, corpPhone, acctMgr, acctPhone, active
     */
    private static Customer draft() {
        return Customer.draft(
                CUSTOMER_VALUES.get(0),
                new Address(CUSTOMER_VALUES.get(1), CUSTOMER_VALUES.get(2), "ZQ", CUSTOMER_VALUES.get(3)),
                CUSTOMER_VALUES.get(4),
                CUSTOMER_VALUES.get(5),
                CUSTOMER_VALUES.get(6),
                "Y");
    }

    /** Makes every {@code customer.write} logging call throw, from the next call on. */
    private void failWriteLineLogging() {
        failingFilter.setContext(loggerContext);
        failingFilter.start();
        loggerContext.addTurboFilter(failingFilter);
    }

    /**
     * Redirects standard error into a buffer.
     *
     * @return the buffer that receives what is written to {@link System#err}
     */
    private static ByteArrayOutputStream captureStandardError() {
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        System.setErr(new PrintStream(captured, true, StandardCharsets.UTF_8));
        return captured;
    }

    /**
     * Adds the sentinel draft in one committed transaction.
     *
     * @return what {@link CustomerMaintenanceService#add(Customer)} returned
     */
    private Customer addCommitted() {
        return transactions.execute(status -> service.add(draft()));
    }

    /**
     * The {@code customer.write} events the list appender received.
     *
     * @return the events, in logging order
     */
    private List<ILoggingEvent> writeLines() {
        return appender.list.stream()
                .filter(event -> event.getMessage() != null && event.getMessage().startsWith(WRITE_LINE_PREFIX))
                .toList();
    }

    // ---------------------------------------------------------------------------------------------
    // A logging failure after the commit
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("a committed add returns its customer when logging throws, and is not repeated")
    void committedAddSurvivesLoggingFailure() {
        failWriteLineLogging();

        Customer added = addCommitted();

        assertThat(failingFilter.thrown()).as("customer.write logging calls that threw").isEqualTo(1);
        assertThat(added).as("returned customer").isNotNull();
        assertThat(added.custId()).as("custId").isEqualTo(FIRST_ID);
        assertThat(added.rowVersion()).as("rowVersion").isZero();
        assertThat(added.chgUser()).as("chgUser").isEqualTo(USER);
        verify(allocator, times(1)).next();
        verify(repository, times(1)).save(any(Customer.class));
    }

    @Test
    @DisplayName("a committed update returns its customer when logging throws, and is not repeated")
    void committedUpdateSurvivesLoggingFailure() {
        failWriteLineLogging();

        Customer changed = transactions.execute(status -> service.update(FIRST_ID, draft(), 0L));

        assertThat(failingFilter.thrown()).as("customer.write logging calls that threw").isEqualTo(1);
        assertThat(changed).as("returned customer").isNotNull();
        assertThat(changed.custId()).as("custId").isEqualTo(FIRST_ID);
        assertThat(changed.rowVersion()).as("rowVersion").isEqualTo(1L);
        verify(repository, times(1)).save(any(Customer.class));
        verifyNoInteractions(allocator);
    }

    @Test
    @DisplayName("a committed add returns its customer when logging and the standard-error fallback both throw")
    void committedAddSurvivesFailingFallback() {
        failWriteLineLogging();
        AtomicInteger fallbackWrites = new AtomicInteger();
        System.setErr(new PrintStream(new OutputStream() {
            @Override
            public void write(int b) {
                fallbackWrites.incrementAndGet();
                throw new IllegalStateException("standard error is broken");
            }

            @Override
            public void write(byte[] b, int off, int len) {
                fallbackWrites.incrementAndGet();
                throw new IllegalStateException("standard error is broken");
            }
        }, true, StandardCharsets.UTF_8));

        Customer added = addCommitted();

        assertThat(failingFilter.thrown()).as("customer.write logging calls that threw").isEqualTo(1);
        assertThat(fallbackWrites.get()).as("standard-error writes attempted, each of which threw").isPositive();
        assertThat(added).as("returned customer").isNotNull();
        assertThat(added.custId()).as("custId").isEqualTo(FIRST_ID);
        verify(allocator, times(1)).next();
        verify(repository, times(1)).save(any(Customer.class));
    }

    @Test
    @DisplayName("the fallback writes one standard-error line with action, id, version and failure class only")
    void fallbackLineCarriesOnlyActionIdVersionAndFailureClass() {
        failWriteLineLogging();
        ByteArrayOutputStream captured = captureStandardError();

        addCommitted();

        String standardError = captured.toString(StandardCharsets.UTF_8);
        List<String> fallbackLines = standardError.lines()
                .filter(line -> line.contains(WRITE_LINE_PREFIX))
                .toList();
        assertThat(fallbackLines).as("standard-error lines about customer.write").hasSize(1);
        assertThat(fallbackLines.get(0))
                .contains("action=ADD custId=EEEF version=0")
                .contains(SimulatedLoggingFailure.class.getName())
                .doesNotContain(FAILURE_MESSAGE)
                .doesNotContain(USER)
                .doesNotContain("user=");
        assertThat(standardError).as("everything written to standard error")
                .doesNotContain(FAILURE_MESSAGE)
                .doesNotContain(USER);
        CUSTOMER_VALUES.forEach(value -> assertThat(standardError)
                .as("standard error must not contain the customer value %s", value)
                .doesNotContain(value));
        assertThat(writeLines()).as("customer.write events that reached an appender").isEmpty();
    }

    // ---------------------------------------------------------------------------------------------
    // A working logger
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("a committed add logs exactly one INFO customer.write line with id, user and version")
    void committedAddLogsOneWriteLine() {
        addCommitted();

        assertThat(writeLines()).as("customer.write events").singleElement().satisfies(event -> {
            assertThat(event.getLevel()).as("level").isEqualTo(Level.INFO);
            assertThat(event.getFormattedMessage())
                    .isEqualTo("customer.write action=ADD custId=EEEF user=sales version=0");
        });
    }

    @Test
    @DisplayName("an add whose transaction rolls back logs no customer.write line")
    void rolledBackAddLogsNothing() {
        Customer added = transactions.execute(status -> {
            Customer saved = service.add(draft());
            status.setRollbackOnly();
            return saved;
        });

        assertThat(added).as("returned customer").isNotNull();
        verify(repository, times(1)).save(any(Customer.class));
        assertThat(writeLines()).as("customer.write events").isEmpty();
    }

    // ---------------------------------------------------------------------------------------------
    // Test doubles
    // ---------------------------------------------------------------------------------------------

    /**
     * A transaction manager with no resource: begin, commit and rollback do nothing, while synchronization,
     * {@code processCommit} and {@code triggerAfterCommit} are Spring's own.
     */
    private static final class NoOpTransactionManager extends AbstractPlatformTransactionManager {

        private static final long serialVersionUID = 1L;

        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
            // No resource to bind: the case exercises synchronization only.
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
            // Nothing to commit: the service's repository and allocator are mocks.
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
            // Nothing to roll back: the service's repository and allocator are mocks.
        }
    }

    /** What the failing Logback filter throws; its class name, not its message, may reach the fallback line. */
    private static final class SimulatedLoggingFailure extends RuntimeException {

        private static final long serialVersionUID = 1L;

        SimulatedLoggingFailure() {
            super(FAILURE_MESSAGE);
        }
    }

    /**
     * Throws {@link SimulatedLoggingFailure} for every {@code customer.write} call of the service's logger,
     * before any level check or appender runs, and stays neutral for every other call.
     */
    private static final class FailingWriteLineFilter extends TurboFilter {

        private final AtomicInteger thrown = new AtomicInteger();

        @Override
        public FilterReply decide(Marker marker, ch.qos.logback.classic.Logger logger, Level level, String format,
                Object[] params, Throwable t) {
            if (isStarted() && SERVICE_LOGGER.equals(logger.getName())
                    && format != null && format.startsWith(WRITE_LINE_PREFIX)) {
                thrown.incrementAndGet();
                throw new SimulatedLoggingFailure();
            }
            return FilterReply.NEUTRAL;
        }

        /**
         * How many logging calls this filter made throw.
         *
         * @return the count since the filter was created
         */
        int thrown() {
            return thrown.get();
        }
    }
}
