package com.democorp.customermaster.repository;

import com.democorp.customermaster.domain.Customer;
import com.democorp.customermaster.domain.CustomerId;
import org.springframework.data.repository.CrudRepository;

/**
 * Create, read and update access to the {@code custmast} table, as the Spring Data JDBC
 * repository of the {@link Customer} aggregate keyed by {@link CustomerId}.
 *
 * <p>It replaces MTNCUSTR's {@code ReadRecd} [5250_Subfile/MTNCUSTR.SQLRPGLE:315-338],
 * {@code AddRecd} [5250_Subfile/MTNCUSTR.SQLRPGLE:548-566] and {@code UpdateRecd}
 * [5250_Subfile/MTNCUSTR.SQLRPGLE:567-607] with {@code findById}, {@code save} of a new aggregate
 * and {@code save} of a loaded one; the USPS variant runs the same logic
 * [USPS_Address/MTNCUSTR.SQLRPGLE:331-350,615-676]. Search lives in
 * {@code CustomerSearchRepository} and id allocation in {@code CustomerIdAllocator}. No delete is
 * called: the source has none, and customers are deactivated with {@code active = 'N'}.
 *
 * <p>The interface declares no method. Spring Data JDBC generates every statement from the
 * {@code @Column} names of {@link Customer} and its embedded {@code domain.Address}, with no schema
 * qualifier, so the schema comes from the connection ({@code DB_SCHEMA}); the {@link CustomerId}
 * key is bound and read through the converters of {@link JdbcConfig}.
 *
 * <p><b>Contract callers rely on.</b> It is Spring Data JDBC's own behaviour for an aggregate
 * whose {@code @Version} property is the wrapper {@link Long}; nothing here implements it.
 * <ul>
 *   <li><b>New aggregate.</b> {@code save(customer)} with {@code rowVersion() == null} and an id
 *       already assigned by {@code CustomerIdAllocator.next()} issues an {@code INSERT} with the
 *       assigned id and version 0, and returns a copy carrying version 0. Spring Data JDBC orders
 *       the insert columns by name ({@code "acctmgr", "acctphone", "active", "addr", "chgtime",
 *       "chguser", "city", "corpphone", "custid", "name", "row_version", "state", "zip"}), not in
 *       V3 declaration order; the list is explicit, never positional.</li>
 *   <li><b>Loaded aggregate.</b> {@code save(customer)} with {@code rowVersion() == v} issues
 *       {@code UPDATE "custmast" SET ..., "row_version" = v + 1 WHERE "custid" = :id
 *       AND "row_version" = v} and returns a copy carrying {@code v + 1}. The version token
 *       replaces UpdateRecd's {@code CHGTIME = :Orig_CHGTIME} test.</li>
 *   <li><b>No row matched.</b> When that {@code UPDATE} changes no row, whether the version is
 *       stale or the row is gone, {@code save} throws
 *       {@link org.springframework.dao.OptimisticLockingFailureException}, unwrapped, in place of
 *       UpdateRecd's {@code SQLNODATA} branch. The caller re-reads in the same transaction: an
 *       absent row gives 404 DEM0599, a present one 409 DEM1002.</li>
 *   <li><b>Other write failures</b> are wrapped in
 *       {@link org.springframework.data.relational.core.conversion.DbActionExecutionException}.
 *       Spring's PostgreSQL error codes leave {@code 55P03} uncategorized, so a lock timeout
 *       inside {@code save} arrives as an {@link org.springframework.jdbc.UncategorizedSQLException}
 *       carrying SQLSTATE {@code 55P03}, not as a
 *       {@link org.springframework.dao.CannotAcquireLockException}; callers therefore test the
 *       SQLSTATE in the cause chain and answer {@code CustomerLockedException}, 409 DEM1001,
 *       UpdateRecd's {@code SQLROWLOCKED} branch. A
 *       {@link org.springframework.dao.DuplicateKeyException}, possible only when a row was
 *       written outside the allocator, becomes 500 DEM9999, as {@code SQLProblem} ends
 *       AddRecd.</li>
 *   <li><b>Missing row on read.</b> {@code findById} returns an empty {@link java.util.Optional}
 *       where ReadRecd continued with {@code SQLNODATA}; the caller answers 404 DEM0599.</li>
 *   <li><b>Transactions.</b> Callers own the transaction, which Spring Data's per-method defaults
 *       join, with its {@code SET LOCAL lock_timeout} and the allocation guard, and keep it short;
 *       no lock is ever held across a user's think time.</li>
 * </ul>
 *
 * <p>Spring Data creates one thread-safe proxy of this interface; it holds no state.
 */
public interface CustomerRepository extends CrudRepository<Customer, CustomerId> {
}
