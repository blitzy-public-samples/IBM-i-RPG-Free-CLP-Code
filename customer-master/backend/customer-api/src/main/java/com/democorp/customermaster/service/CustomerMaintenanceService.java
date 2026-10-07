package com.democorp.customermaster.service;

import com.democorp.customermaster.address.AddressServiceUnavailableException;
import com.democorp.customermaster.config.AppProperties;
import com.democorp.customermaster.domain.Address;
import com.democorp.customermaster.domain.Customer;
import com.democorp.customermaster.domain.CustomerId;
import com.democorp.customermaster.domain.SearchPage;
import com.democorp.customermaster.domain.TextNormalizer;
import com.democorp.customermaster.messages.MessageCatalog;
import com.democorp.customermaster.repository.CustomerIdAllocator;
import com.democorp.customermaster.repository.CustomerRepository;
import com.democorp.customermaster.security.CurrentUser;
import com.democorp.customermaster.service.exception.CustomerIdExhaustedException;
import com.democorp.customermaster.service.exception.CustomerLockedException;
import com.democorp.customermaster.service.exception.CustomerNotFoundException;
import com.democorp.customermaster.service.exception.CustomerValidationException;
import com.democorp.customermaster.service.exception.ReviewFailedException;
import com.democorp.customermaster.service.exception.StaleCustomerException;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.Objects;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * The one maintenance flow of the customer master: display, review, add and change one customer
 * (F-002; UC-02, UC-03, UC-04).
 *
 * <p><b>What it replaces.</b> Both MTNCUSTR variants. The 5250 variant supplies the record
 * procedures and the nine field rules; the USPS variant runs the same logic and adds
 * {@code Edit_Address}:
 * <table>
 *   <caption>Source procedure and method</caption>
 *   <tr><th>Source</th><th>Here</th></tr>
 *   <tr><td>{@code ReadRecd} [5250_Subfile/MTNCUSTR.SQLRPGLE:315-338] for function {@code D}
 *       and {@code E}</td>
 *       <td>{@link #get(CustomerId)}; a missing row is 404 DEM0599 instead of
 *       {@code SQLProblem('Calling error 1 ...')} ending the program</td></tr>
 *   <tr><td>{@code EditUpdData} / {@code EditAddData} [5250_Subfile/MTNCUSTR.SQLRPGLE:387-544],
 *       then {@code Edit_Address} [USPS_Address/MTNCUSTR.SQLRPGLE:471-499], then the
 *       DEM0000 / DEM0009 confirmation [5250_Subfile/MTNCUSTR.SQLRPGLE:225,277]</td>
 *       <td>{@link #review(Purpose, Customer)}</td></tr>
 *   <tr><td>{@code AddRecd} [5250_Subfile/MTNCUSTR.SQLRPGLE:548-566]: CUSTNEXT
 *       {@code in *LOCK}, {@code BASE36ADD}, {@code out}, stamp, {@code insert}</td>
 *       <td>{@link #add(Customer)}, with {@link CustomerIdAllocator#next()} in place of the data
 *       area</td></tr>
 *   <tr><td>{@code UpdateRecd} [5250_Subfile/MTNCUSTR.SQLRPGLE:567-607]: {@code update ...
 *       where CUSTID = :CUSTID and CHGTIME = :Orig_CHGTIME}, then {@code SQLNODATA} → DEM1002
 *       and re-read, {@code SQLROWLOCKED} → DEM1001</td>
 *       <td>{@link #update(CustomerId, Customer, long)}, with the {@code row_version} token in
 *       place of the timestamp equality</td></tr>
 * </table>
 *
 * <p><b>One flow for both variants.</b> Every write runs all nine 5250 field rules in 5250 order,
 * stopping at the first error ({@link CustomerValidator}). Address standardization runs only in
 * {@link #review(Purpose, Customer)}, after the nine rules pass, so the user confirms the
 * standardized values before saving; {@link #add(Customer)} and
 * {@link #update(CustomerId, Customer, long)} re-run the field rules as an API guard but never call
 * the address service again. With standardization disabled the flow is exactly the 5250 variant,
 * because {@link AddressStandardizationService} then returns the address unchanged.
 *
 * <p><b>Normalization.</b> Every value is passed through {@link TextNormalizer#field(String)}
 * before the rules run: trailing blanks are removed (CHAR padding parity) and text is uppercased
 * with the length-preserving rule, as the 5250 fields without {@code CHECK(LC)} uppercase what is
 * keyed [5250_Subfile/MTNCUSTD.DSPF:54-133]. An absent {@code active} on an add, or on an ADD
 * review, becomes {@code Y}, as {@code ADDING} sets {@code ACTIVE = 'Y'} before the first screen
 * [5250_Subfile/MTNCUSTR.SQLRPGLE:251-255]; any value that is present, blank included, is validated
 * as given.
 *
 * <p><b>Transactions and locking.</b>
 * <ul>
 *   <li>{@link #get(CustomerId)}: one read-only transaction.</li>
 *   <li>{@link #review(Purpose, Customer)}: no transaction, so no connection is held while the
 *       address service is called. The class carries no {@code @Transactional} for this reason.</li>
 *   <li>{@link #add(Customer)}: one transaction: {@code SET LOCAL lock_timeout}, the allocation guard
 *       and {@code nextval} inside {@link CustomerIdAllocator#next()}, then the {@code INSERT}.
 *       The field rules and the principal check run before {@code SET LOCAL lock_timeout}, the
 *       allocation and any write, so a rejected add consumes no id. If the State rule is the first
 *       use of the {@code StateService} cache, the cache loads with one read of STATES inside this
 *       transaction, before the lock timeout is set.</li>
 *   <li>{@link #update(CustomerId, Customer, long)}: one transaction: {@code SET LOCAL lock_timeout},
 *       then {@code UPDATE ... WHERE custid = ? AND row_version = ?}. When no row matches, the row is
 *       re-read in the same transaction: absent gives 404 DEM0599, present gives 409 DEM1002 with the
 *       row as now stored.</li>
 * </ul>
 * No row lock outlives the request: there is no {@code SELECT ... FOR UPDATE}, and this class never
 * touches the id sequence itself. A lock wait longer than {@code customer-master.db.lock-timeout}
 * (SQLSTATE {@code 55P03}, the counterpart of the source's {@code 57033}) becomes
 * {@link CustomerLockedException}, 409 DEM1001.
 *
 * <p><b>Audit.</b> The change stamp is the only audit, as in the source: {@code chgtime} from the
 * injected {@link Clock}, {@code chguser} from {@link CurrentUser#name()} (the authenticated
 * principal, never the database role) and {@code row_version} are written by the same
 * {@code INSERT} or {@code UPDATE} as the data, so they commit or roll back with it. After each
 * commit one INFO line {@code customer.write action=ADD|UPDATE custId=... user=... version=...} is
 * logged; it carries no customer data beyond the id. The line is operational and best-effort: a
 * logging failure never changes the outcome of the committed write, and is reported instead by one
 * line on standard error.
 *
 * <p><b>Errors.</b> Every failure is a typed exception carrying a message code only; no SQL text
 * or SQLSTATE reaches a message. {@code controller.ApiExceptionHandler} maps them to problem+json.
 *
 * <p>Example, as the controller calls it:
 * <pre>{@code
 * ReviewResult checked = service.review(Purpose.ADD, draft);  // 200 DEM0009, or 422 / 502
 * Customer added = service.add(checked.customer());            // EEEF on a fresh database
 * Customer changed = service.update(added.custId(), edited, added.rowVersion());
 * changed.rowVersion();                                        // 1
 * }</pre>
 *
 * <p>The bean is stateless apart from its collaborators and the lock-timeout statement fixed at
 * construction, so it is thread-safe. It is deliberately not {@code final}: Spring proxies it for
 * {@code @Transactional}, and tests replace it with a Mockito bean.
 */
@Service
public class CustomerMaintenanceService {

    /** The after-commit write line, and nothing else, is logged through this logger. */
    private static final Logger log = LoggerFactory.getLogger(CustomerMaintenanceService.class);

    /** Edit confirmation: "Press Enter to update. F12 to Cancel." [5250_Subfile/MTNCUSTR.SQLRPGLE:225]. */
    static final String EDIT_CONFIRMATION = "DEM0000";

    /** Add confirmation: "Press Enter to add. Press F12 to cancel" [5250_Subfile/MTNCUSTR.SQLRPGLE:277]. */
    static final String ADD_CONFIRMATION = "DEM0009";

    /** The active code an add starts with [5250_Subfile/MTNCUSTR.SQLRPGLE:254]. */
    static final String DEFAULT_ACTIVE = "Y";

    /** PostgreSQL {@code lock_not_available}: a {@code lock_timeout} expired. */
    static final String LOCK_NOT_AVAILABLE = "55P03";

    /** Action name of the after-commit line for an insert. */
    private static final String ACTION_ADD = "ADD";

    /** Action name of the after-commit line for an update. */
    private static final String ACTION_UPDATE = "UPDATE";

    /** Most throwables {@link #isLockWait(Throwable)} inspects in one cause graph. */
    private static final int MAX_CAUSE_DEPTH = 32;

    /** Smallest lock timeout accepted; PostgreSQL reads {@code 0} as "wait forever". */
    private static final Duration MIN_LOCK_TIMEOUT = Duration.ofMillis(1);

    /** Largest lock timeout accepted: PostgreSQL's {@code lock_timeout} is an {@code int} of ms. */
    private static final Duration MAX_LOCK_TIMEOUT = Duration.ofMillis(Integer.MAX_VALUE);

    /** Reads and writes {@code custmast}; {@code save} issues the INSERT or the versioned UPDATE. */
    private final CustomerRepository repository;

    /** Takes the allocation guard and draws the next id; only add calls it. */
    private final CustomerIdAllocator allocator;

    /** The nine field rules, in source order, stopping at the first error. */
    private final CustomerValidator validator;

    /** The Edit_Address mapping; only review calls it. */
    private final AddressStandardizationService addressStandardizationService;

    /** The authenticated principal, the only source of {@code chguser}. */
    private final CurrentUser currentUser;

    /** The texts of the DEM0000 and DEM0009 confirmation notices. */
    private final MessageCatalog messageCatalog;

    /** The UTC clock {@code chgtime} is read from. */
    private final Clock clock;

    /** Runs {@code SET LOCAL lock_timeout} on the transaction's own connection. */
    private final JdbcTemplate jdbcTemplate;

    /** {@code SET LOCAL lock_timeout = '<n>ms'}, formatted once from configuration. */
    private final String setLockTimeoutSql;

    /**
     * Why a review is requested, bound from the JSON {@code purpose} of
     * {@code POST /api/customers/review}. The constant names are the wire values {@code "ADD"} and
     * {@code "EDIT"}.
     */
    public enum Purpose {
        /** Review before {@code POST /api/customers}: the MTNCUSTR function {@code A}. */
        ADD,
        /** Review before {@code PUT /api/customers/{custId}}: the MTNCUSTR function {@code E}. */
        EDIT
    }

    /**
     * The outcome of a review that passed every rule: the values the user is asked to confirm.
     *
     * @param customer     the normalized draft, with the standardized address when the address
     *                     service ran; {@code custId}, {@code chgTime}, {@code chgUser} and
     *                     {@code rowVersion} are carried over from the draft unchanged
     * @param standardized {@code true} when the address service standardized the address,
     *                     {@code false} when standardization is disabled
     * @param notice       the confirmation prompt: DEM0000 for {@link Purpose#EDIT}, DEM0009 for
     *                     {@link Purpose#ADD}, with its catalog text
     */
    public record ReviewResult(Customer customer, boolean standardized, SearchPage.Notice notice) {

        /**
         * Rejects a result without its customer or its confirmation notice.
         *
         * @throws NullPointerException if {@code customer} or {@code notice} is {@code null}
         */
        public ReviewResult {
            Objects.requireNonNull(customer, "customer");
            Objects.requireNonNull(notice, "notice");
        }
    }

    /**
     * Creates the service and fixes its lock-timeout statement from configuration.
     *
     * @param repository                    reads and writes {@code custmast}
     * @param allocator                     allocates the next customer id under the allocation guard
     * @param validator                     the nine field rules, in source order
     * @param addressStandardizationService the Edit_Address mapping, used by review only
     * @param currentUser                   the authenticated principal, written as {@code chguser}
     * @param messageCatalog                the texts of the confirmation notices
     * @param appProperties                 {@code customer-master.db.lock-timeout} for add and update
     * @param clock                         the UTC clock {@code chgtime} is taken from
     * @param jdbcTemplate                  runs {@code SET LOCAL lock_timeout} on the transaction's
     *                                      connection
     * @throws NullPointerException     if any argument, or the {@code db} settings or their lock
     *                                  timeout, is {@code null}
     * @throws IllegalArgumentException if the lock timeout is not between 1 ms and
     *                                  {@link Integer#MAX_VALUE} ms
     */
    public CustomerMaintenanceService(
            CustomerRepository repository,
            CustomerIdAllocator allocator,
            CustomerValidator validator,
            AddressStandardizationService addressStandardizationService,
            CurrentUser currentUser,
            MessageCatalog messageCatalog,
            AppProperties appProperties,
            Clock clock,
            JdbcTemplate jdbcTemplate) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.allocator = Objects.requireNonNull(allocator, "allocator");
        this.validator = Objects.requireNonNull(validator, "validator");
        this.addressStandardizationService =
                Objects.requireNonNull(addressStandardizationService, "addressStandardizationService");
        this.currentUser = Objects.requireNonNull(currentUser, "currentUser");
        this.messageCatalog = Objects.requireNonNull(messageCatalog, "messageCatalog");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate");
        Objects.requireNonNull(appProperties, "appProperties");
        AppProperties.Db db = Objects.requireNonNull(appProperties.db(), "appProperties.db");
        this.setLockTimeoutSql = lockTimeoutSql(Objects.requireNonNull(db.lockTimeout(), "lockTimeout"));
    }

    /**
     * Reads one customer for display or edit: {@code ReadRecd} [5250_Subfile/MTNCUSTR.SQLRPGLE:315-338].
     *
     * <p>Where the source continued with {@code SQLNODATA} and then ended the program through
     * {@code SQLProblem} [5250_Subfile/MTNCUSTR.SQLRPGLE:181-185], a missing row here is
     * {@link CustomerNotFoundException}, 404 DEM0599 "Customer deleted. Exit &amp; redo search.":
     * a stateless request cannot end a program, and the message the source shows for a row that
     * vanished during edit is the right one.
     *
     * @param id the customer id from the request path
     * @return the stored customer, including its change stamp and {@code rowVersion}
     * @throws CustomerNotFoundException if no row has that id
     * @throws NullPointerException      if {@code id} is {@code null}
     */
    @Transactional(readOnly = true)
    public Customer get(CustomerId id) {
        Objects.requireNonNull(id, "id");
        return repository.findById(id).orElseThrow(() -> new CustomerNotFoundException(id));
    }

    /**
     * Checks a draft and returns the values the user is asked to confirm: {@code EditUpdData}, then
     * {@code Edit_Address} when standardization is enabled, then the DEM0000 or DEM0009 prompt.
     *
     * <p>Steps, each stopping at its first failure:
     * <ol>
     *   <li>Normalize the nine fields; for {@link Purpose#ADD} an absent {@code active} becomes
     *       {@code Y}.</li>
     *   <li>Run the nine field rules. A failure at rules 1 to 5 ({@code active}, {@code name},
     *       {@code addr}, {@code city}, the State rule) is thrown as the
     *       {@link CustomerValidationException} itself. A failure at rules 6 to 9 ({@code zip},
     *       {@code acctPhone}, {@code acctMgr}, {@code corpPhone}) is wrapped in
     *       {@link ReviewFailedException} carrying the normalized input State, because the State
     *       rule passed first and {@code Edit_SD_STATE} has already moved it into the working
     *       {@code STATE} [5250_Subfile/MTNCUSTR.SQLRPGLE:488-494].</li>
     *   <li>Standardize the address. A failure, whether DEM9898 "USPS: ...", the standardized-State
     *       DEM0503, or the address service being unavailable (502 APP0502), is wrapped in
     *       {@link ReviewFailedException} with the same accepted State, never the standardized
     *       one.</li>
     * </ol>
     * The UI reads {@code stateAccepted} from the problem response to keep its working State, which
     * the State prompt's cancel restores [5250_Subfile/MTNCUSTR.SQLRPGLE:369-375].
     *
     * <p>Runs with no transaction: the address call may take seconds and must hold no connection.
     * The State lookups of the rules and of the standardized-State check read the
     * {@code StateService} cache; when a review is its first use, the State rule loads it with one
     * short read of its own, finished before the address call.
     *
     * @param purpose why the review is requested; it selects the {@code active} default and the
     *                confirmation notice
     * @param draft   the nine data fields as received
     * @return the normalized and, when enabled, standardized values with the confirmation notice
     * @throws CustomerValidationException if rules 1 to 5 fail (422, no {@code stateAccepted})
     * @throws ReviewFailedException       if a later rule or the standardization fails; its cause is
     *                                     the {@link CustomerValidationException} (422) or the
     *                                     {@link AddressServiceUnavailableException} (502)
     * @throws NullPointerException        if {@code purpose} or {@code draft} is {@code null}
     */
    public ReviewResult review(Purpose purpose, Customer draft) {
        Objects.requireNonNull(purpose, "purpose");
        Objects.requireNonNull(draft, "draft");
        Customer normalized = normalize(draft, purpose == Purpose.ADD);
        try {
            validator.validate(normalized);
        } catch (CustomerValidationException e) {
            if (e.rule() > CustomerValidationException.STATE_RULE) {
                throw new ReviewFailedException(e, normalized.address().state());
            }
            throw e;
        }

        // The State rule has passed: this is the normalized input State, which the working State
        // keeps whatever the address service later answers.
        String stateAccepted = normalized.address().state();
        AddressStandardizationService.Result standardization;
        try {
            standardization = addressStandardizationService.standardize(normalized.address());
        } catch (CustomerValidationException | AddressServiceUnavailableException e) {
            throw new ReviewFailedException(e, stateAccepted);
        }

        Customer reviewed = normalized.withAddress(standardization.address());
        String code = confirmationCode(purpose);
        return new ReviewResult(reviewed, standardization.standardized(),
                new SearchPage.Notice(code, messageCatalog.text(code)));
    }

    /**
     * Adds a customer under the next id: {@code AddRecd} [5250_Subfile/MTNCUSTR.SQLRPGLE:548-566].
     *
     * <p>Within one transaction, in this order:
     * <ol>
     *   <li>normalize (an absent {@code active} becomes {@code Y}) and run the nine field rules; no
     *       standardization, which belongs to review;</li>
     *   <li>{@code SET LOCAL lock_timeout} from {@code customer-master.db.lock-timeout};</li>
     *   <li>{@link CustomerIdAllocator#next()}: the {@code ROW EXCLUSIVE} allocation guard on
     *       {@code custmast}, then {@code nextval}, which replaces CUSTNEXT {@code in *LOCK},
     *       {@code BASE36ADD} and {@code out}; the first add on a fresh database receives
     *       {@code EEEF}, the successor of CRTDTAARA's {@code EEEE};</li>
     *   <li>the {@code INSERT} of the data with {@code chgtime}, {@code chguser} and
     *       {@code row_version} 0.</li>
     * </ol>
     * The rules and the principal are checked before {@code SET LOCAL lock_timeout}, the allocation
     * and any write, so a rejected add consumes no id. If the State rule is the first use of the
     * {@code StateService} cache, the cache loads with one read of STATES inside this transaction,
     * before the lock timeout is set. An id consumed by a later failure leaves a gap, as the source
     * consumes the key before an insert that may fail.
     *
     * @param draft the nine data fields as received; any {@code custId}, stamp or version it carries
     *              is replaced
     * @return the stored customer with its new id, change stamp and {@code rowVersion} 0
     * @throws CustomerValidationException  if a field rule fails (422)
     * @throws CustomerLockedException      if the allocation guard or the insert waits longer than
     *                                      the lock timeout, as while a generator load runs (409
     *                                      DEM1001)
     * @throws CustomerIdExhaustedException if no id is left after {@code 9999} (503 APP0503)
     * @throws IllegalStateException        if no user is authenticated
     * @throws NullPointerException         if {@code draft} is {@code null}
     */
    @Transactional
    public Customer add(Customer draft) {
        Objects.requireNonNull(draft, "draft");
        Customer normalized = normalize(draft, true);
        validator.validate(normalized);
        String user = currentUser.name();

        Customer saved;
        try {
            applyLockTimeout();
            CustomerId id = allocator.next();
            saved = repository.save(normalized
                    .withCustId(id)
                    .withRowVersion(null)
                    .withStamp(now(), user));
        } catch (RuntimeException e) {
            throw translateWriteFailure(e);
        }
        registerAfterCommitLog(ACTION_ADD, saved);
        return saved;
    }

    /**
     * Changes a customer if it is still at the version the caller read: {@code UpdateRecd}
     * [5250_Subfile/MTNCUSTR.SQLRPGLE:567-607].
     *
     * <p>Within one transaction: normalize (no {@code active} default) and run the nine field rules,
     * {@code SET LOCAL lock_timeout}, then {@code UPDATE custmast SET ..., row_version = version + 1
     * WHERE custid = :id AND row_version = :version}, which replaces UpdateRecd's
     * {@code CHGTIME = :Orig_CHGTIME} condition. When no row matches, the row is re-read in the same
     * transaction, as UpdateRecd re-reads it after {@code SQLNODATA}
     * [5250_Subfile/MTNCUSTR.SQLRPGLE:593-599]: absent gives DEM0599, present gives DEM1002 with the
     * row as now stored. The zero-row {@code UPDATE} does not abort the PostgreSQL transaction, so the
     * re-read works; the transaction then rolls back with the thrown exception.
     *
     * @param id      the customer id from the request path
     * @param draft   the nine data fields as received; any {@code custId}, stamp or version it
     *                carries is replaced
     * @param version the {@code rowVersion} the caller read
     * @return the stored customer with the new change stamp and {@code rowVersion} {@code version + 1}
     * @throws CustomerValidationException if a field rule fails (422)
     * @throws CustomerNotFoundException   if no row has that id (404 DEM0599)
     * @throws StaleCustomerException      if the stored version differs; carries the current row (409
     *                                     DEM1002)
     * @throws CustomerLockedException     if the row stays locked longer than the lock timeout (409
     *                                     DEM1001)
     * @throws IllegalStateException       if no user is authenticated
     * @throws NullPointerException        if {@code id} or {@code draft} is {@code null}
     */
    @Transactional
    public Customer update(CustomerId id, Customer draft, long version) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(draft, "draft");
        Customer normalized = normalize(draft, false);
        validator.validate(normalized);
        String user = currentUser.name();

        Customer saved;
        try {
            applyLockTimeout();
            saved = repository.save(normalized
                    .withCustId(id)
                    .withRowVersion(version)
                    .withStamp(now(), user));
        } catch (OptimisticLockingFailureException noRowMatched) {
            throw staleOrMissing(id);
        } catch (RuntimeException e) {
            throw translateWriteFailure(e);
        }
        registerAfterCommitLog(ACTION_UPDATE, saved);
        return saved;
    }

    /**
     * Normalizes the nine data fields with {@link TextNormalizer#field(String)}: trailing blanks
     * removed, text uppercased, {@code null} read as {@code ""}.
     *
     * <p>With {@code defaultActive}, an absent ({@code null}) {@code active} becomes {@code Y}; a
     * present value, blank or {@code X} included, is normalized and later fails DEM0501 as given.
     * Without it, {@code null} becomes {@code ""} and fails DEM0501. {@code custId},
     * {@code rowVersion} and the stamp are left as they are; the stamp is replaced on every write.
     *
     * @param draft         the draft as received
     * @param defaultActive whether an absent {@code active} defaults to {@code Y} (ADD)
     * @return a normalized copy; {@code draft} is unchanged
     */
    private static Customer normalize(Customer draft, boolean defaultActive) {
        Address in = draft.address() != null ? draft.address() : new Address(null, null, null, null);
        String active = defaultActive && draft.active() == null
                ? DEFAULT_ACTIVE
                : TextNormalizer.field(draft.active());
        return draft
                .withName(TextNormalizer.field(draft.name()))
                .withAddress(new Address(
                        TextNormalizer.field(in.addr()),
                        TextNormalizer.field(in.city()),
                        TextNormalizer.field(in.state()),
                        TextNormalizer.field(in.zip())))
                .withCorpPhone(TextNormalizer.field(draft.corpPhone()))
                .withAcctMgr(TextNormalizer.field(draft.acctMgr()))
                .withAcctPhone(TextNormalizer.field(draft.acctPhone()))
                .withActive(active);
    }

    /**
     * The confirmation notice code a passed review returns.
     *
     * @param purpose the review purpose
     * @return DEM0000 for an edit, DEM0009 for an add
     */
    private static String confirmationCode(Purpose purpose) {
        return switch (purpose) {
            case EDIT -> EDIT_CONFIRMATION;
            case ADD -> ADD_CONFIRMATION;
        };
    }

    /**
     * Limits every lock wait of the current transaction, as the source's row-lock wait ends in
     * {@code SQLROWLOCKED}. {@code SET LOCAL} ends with the transaction, so the pooled connection
     * returns with its default.
     */
    private void applyLockTimeout() {
        jdbcTemplate.execute(setLockTimeoutSql);
    }

    /**
     * The {@code chgtime} of a write, at the microsecond precision of {@code timestamptz(6)}, so the
     * returned entity equals what a later read returns.
     *
     * @return the current instant in the clock's zone (UTC)
     */
    private OffsetDateTime now() {
        return OffsetDateTime.now(clock).truncatedTo(ChronoUnit.MICROS);
    }

    /**
     * Decides why a versioned {@code UPDATE} matched no row, by re-reading it in the same
     * transaction.
     *
     * @param id the customer id
     * @return {@link StaleCustomerException} with the current row, or
     *         {@link CustomerNotFoundException} when the row is gone
     */
    private RuntimeException staleOrMissing(CustomerId id) {
        return repository.findById(id)
                .<RuntimeException>map(StaleCustomerException::new)
                .orElseGet(() -> new CustomerNotFoundException(id));
    }

    /**
     * Maps a failure of the database section of {@link #add(Customer)} or
     * {@link #update(CustomerId, Customer, long)}: an expired lock wait becomes
     * {@link CustomerLockedException}, which carries no data, as DEM1001 has no {@code &1}. Every
     * other failure is returned unchanged: {@link CustomerIdExhaustedException} keeps its 503, and a
     * {@code DuplicateKeyException} or any other error reaches the 500 DEM9999 catch-all.
     *
     * @param failure the failure thrown inside the transaction
     * @return the exception to throw
     */
    private static RuntimeException translateWriteFailure(RuntimeException failure) {
        if (failure instanceof CustomerIdExhaustedException) {
            return failure;
        }
        if (isLockWait(failure)) {
            return new CustomerLockedException(failure);
        }
        return failure;
    }

    /**
     * Whether a failure is an expired lock wait.
     *
     * <p>Walks the cause graph, including {@link SQLException#getNextException()}, because Spring
     * Data JDBC wraps write failures in {@code DbActionExecutionException} and Spring's PostgreSQL
     * translation leaves {@code 55P03} uncategorized. A failure is a lock wait when the graph holds a
     * {@link PessimisticLockingFailureException}, the type of the
     * {@link org.springframework.dao.CannotAcquireLockException} that {@link CustomerIdAllocator}
     * raises for its guard, or an {@link SQLException} whose SQLSTATE is {@code 55P03}. At most
     * {@value #MAX_CAUSE_DEPTH} throwables are inspected, each once, so a cyclic or self-referencing
     * cause chain ends.
     *
     * @param failure the failure, possibly {@code null}
     * @return {@code true} if the failure is, or is caused by, a lock wait
     */
    private static boolean isLockWait(Throwable failure) {
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        Deque<Throwable> pending = new ArrayDeque<>();
        if (failure != null) {
            pending.add(failure);
        }
        while (!pending.isEmpty() && visited.size() < MAX_CAUSE_DEPTH) {
            Throwable current = pending.poll();
            if (!visited.add(current)) {
                continue;
            }
            if (current instanceof PessimisticLockingFailureException) {
                return true;
            }
            if (current instanceof SQLException sql) {
                if (LOCK_NOT_AVAILABLE.equals(sql.getSQLState())) {
                    return true;
                }
                SQLException next = sql.getNextException();
                if (next != null) {
                    pending.add(next);
                }
            }
            Throwable cause = current.getCause();
            if (cause != null) {
                pending.add(cause);
            }
        }
        return false;
    }

    /**
     * Logs one {@code customer.write} INFO line once the current transaction commits, and none when
     * it rolls back. Only the id, the user and the version are captured, never customer data.
     * Nothing is registered when no transaction synchronization is active.
     *
     * <p>The line is best-effort. Spring calls {@code afterCommit} after the database
     * {@code COMMIT} and passes anything it throws on to the caller of the transactional method, so
     * an escaping logging failure would answer 500 for a stored customer, and a retried add would
     * store a second one. A {@link RuntimeException} thrown while logging is therefore caught and
     * never rethrown: the committed write is neither retried nor rolled back, and its result is
     * returned as usual. {@link #reportUnloggedWrite(String, CustomerId, Long, RuntimeException)}
     * writes one line to standard error instead. An {@link Error} is not caught.
     *
     * @param action {@code ADD} or {@code UPDATE}
     * @param saved  the customer as written
     */
    private static void registerAfterCommitLog(String action, Customer saved) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        CustomerId custId = saved.custId();
        String user = saved.chgUser();
        Long version = saved.rowVersion();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    log.info("customer.write action={} custId={} user={} version={}",
                            action, custId, user, version);
                } catch (RuntimeException loggingFailure) {
                    // The write has committed: a failing logger must not turn it into an error
                    // response, so the failure is reported once and the call returns normally.
                    reportUnloggedWrite(action, custId, version, loggingFailure);
                }
            }
        });
    }

    /**
     * Reports on standard error that the {@code customer.write} line of a committed write could not
     * be logged.
     *
     * <p>Standard error is written directly because it depends on neither SLF4J nor Logback, the
     * framework that has just failed; {@code java.util.logging} is not used either, because Spring
     * Boot routes it to SLF4J. The one line carries fixed text, the action, the id, the version and
     * the failure's class name. It carries no user, no customer data and no exception message, which
     * could hold either. Nothing is retried and nothing is logged again, so the report is bounded
     * and cannot recurse into the failing logger.
     *
     * @param action  {@code ADD} or {@code UPDATE}
     * @param custId  the id the committed write stored
     * @param version the {@code row_version} the committed write stored
     * @param failure what the logging call threw
     */
    private static void reportUnloggedWrite(String action, CustomerId custId, Long version,
            RuntimeException failure) {
        try {
            System.err.println("customer.write not logged after commit: action=" + action
                    + " custId=" + custId + " version=" + version
                    + " failure=" + failure.getClass().getName());
        } catch (RuntimeException ignored) {
            // Standard error failed too. Nothing further is attempted: any other report could fail
            // the same way, and the committed write must still return its result to the caller.
        }
    }

    /**
     * Formats {@code SET LOCAL lock_timeout} from the configured duration. {@code SET} takes no bind
     * parameters, so the value is a whole number of milliseconds formatted from configuration, never
     * caller text.
     *
     * <p>The range is checked so the configured wait is the wait PostgreSQL applies: a duration
     * under 1 ms would format as {@code 0ms}, which PostgreSQL reads as "no timeout" and would let an
     * add or update wait on a lock indefinitely; a duration over {@link Integer#MAX_VALUE} ms exceeds
     * the parameter's range and would fail every write. Sub-millisecond parts are dropped.
     *
     * @param lockTimeout {@code customer-master.db.lock-timeout}
     * @return the statement, for example {@code SET LOCAL lock_timeout = '5000ms'}
     * @throws IllegalArgumentException if the duration is under 1 ms or over
     *                                  {@link Integer#MAX_VALUE} ms
     */
    private static String lockTimeoutSql(Duration lockTimeout) {
        // Fails at startup rather than silently disabling the lock-wait limit: the add and update
        // transactions promise a bounded wait before 409 DEM1001.
        if (lockTimeout.compareTo(MIN_LOCK_TIMEOUT) < 0 || lockTimeout.compareTo(MAX_LOCK_TIMEOUT) > 0) {
            throw new IllegalArgumentException("customer-master.db.lock-timeout must be between "
                    + MIN_LOCK_TIMEOUT.toMillis() + " ms and " + MAX_LOCK_TIMEOUT.toMillis()
                    + " ms, was " + lockTimeout);
        }
        return "SET LOCAL lock_timeout = '" + lockTimeout.toMillis() + "ms'";
    }
}
