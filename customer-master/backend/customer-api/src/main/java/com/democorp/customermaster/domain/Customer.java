package com.democorp.customermaster.domain;

import java.time.OffsetDateTime;
import java.util.Objects;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.PersistenceCreator;
import org.springframework.data.annotation.Version;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Embedded;
import org.springframework.data.relational.core.mapping.Table;

/**
 * One customer of the customer master: the aggregate root behind display, edit and add,
 * mapped to table {@code custmast}.
 *
 * <p>Replaces MTNCUSTR's {@code CUSTMAST_ds extname('CUSTMAST')}
 * [5250_Subfile/MTNCUSTR.SQLRPGLE:61], the record that {@code ReadRecd} selects into,
 * {@code AddRecd} inserts and {@code UpdateRecd} rewrites
 * [5250_Subfile/MTNCUSTR.SQLRPGLE:315-338,548-607]; the USPS variant runs the same logic
 * [USPS_Address/MTNCUSTR.SQLRPGLE:331-350,615-676]. The columns are those of
 * [5250_Subfile/Custmast2.sql:8-24], as migration V3 creates them.
 *
 * <p><b>Mapping.</b> Every persistent field names its V3 column with {@link Column}.
 * Spring Data JDBC's default {@code NamingStrategy} would derive {@code cust_id},
 * {@code corp_phone}, {@code acct_mgr}, {@code acct_phone}, {@code chg_time} and
 * {@code chg_user}, while V3 keeps the source names in lowercase with no underscores. The
 * generated INSERT, SELECT and UPDATE therefore use exactly the names the handwritten SQL
 * of {@code CustomerSearchRepository}, {@code CustomerIdAllocator} and
 * {@code CustomerCopyWriter} uses. Fields are declared in V3 column order:
 *
 * <table>
 *   <caption>Property, V3 column and source column</caption>
 *   <tr><th>Property</th><th>V3 column</th><th>Custmast2.sql</th></tr>
 *   <tr><td>{@code custId}</td><td>{@code custid char(4)}, primary key</td>
 *       <td>{@code CustID CHAR(4)}</td></tr>
 *   <tr><td>{@code name}</td><td>{@code name varchar(40)}</td><td>{@code Name CHAR(40)}</td></tr>
 *   <tr><td>{@code address}</td><td>{@code addr}, {@code city}, {@code state},
 *       {@code zip}, through {@link Address}'s own columns</td>
 *       <td>{@code Addr}, {@code City}, {@code State}, {@code Zip}</td></tr>
 *   <tr><td>{@code corpPhone}</td><td>{@code corpphone varchar(20)}</td>
 *       <td>{@code CorpPhone CHAR(20)}</td></tr>
 *   <tr><td>{@code acctMgr}</td><td>{@code acctmgr varchar(40)}</td>
 *       <td>{@code AcctMgr CHAR(40)}</td></tr>
 *   <tr><td>{@code acctPhone}</td><td>{@code acctphone varchar(20)}</td>
 *       <td>{@code AcctPhone CHAR(20)}</td></tr>
 *   <tr><td>{@code active}</td><td>{@code active char(1)}, {@code Y} or {@code N}</td>
 *       <td>{@code Active CHAR(1)}</td></tr>
 *   <tr><td>{@code chgTime}</td><td>{@code chgtime timestamptz(6)}</td>
 *       <td>{@code ChgTime TIMESTAMP}</td></tr>
 *   <tr><td>{@code chgUser}</td><td>{@code chguser varchar(18)}</td>
 *       <td>{@code ChgUser varchar(18)}</td></tr>
 *   <tr><td>{@code rowVersion}</td><td>{@code row_version bigint}</td>
 *       <td>none; replaces the {@code CHGTIME = :Orig_CHGTIME} check</td></tr>
 * </table>
 *
 * <p>The {@link CustomerId} key is stored as {@code char(4)} through the reading and writing
 * converters of {@code repository/JdbcConfig}; this type holds no conversion code.
 * {@link Address} is embedded with an empty prefix and {@link Embedded.OnEmpty#USE_EMPTY},
 * the mapping {@code @Embedded.Empty} stands for, so its columns keep their own names and a
 * row always reads back with a non-null address.
 *
 * <p><b>Newness and the row version.</b> {@code rowVersion} is the {@link Version} property
 * and is deliberately the wrapper {@link Long}, so Spring Data JDBC decides newness from it
 * even though the id is assigned before the first save:
 * <ul>
 *   <li>{@code rowVersion == null}: the aggregate is new. {@code save} issues an
 *       {@code INSERT} that includes the assigned {@code custid} and stores
 *       {@code row_version} 0, then returns a copy carrying version 0
 *       (through {@link #withRowVersion(Long)}).</li>
 *   <li>{@code rowVersion != null}: {@code save} issues
 *       {@code UPDATE custmast SET ..., row_version = :v + 1 WHERE custid = ? AND row_version = :v}
 *       and returns a copy carrying {@code v + 1}. When no row matches, Spring Data raises
 *       {@code OptimisticLockingFailureException}; {@code CustomerMaintenanceService}
 *       re-reads the row and answers 404 DEM0599 (absent) or 409 DEM1002 (changed), as
 *       {@code UpdateRecd} answers DEM1002 when its timestamp condition finds no row.</li>
 * </ul>
 * A primitive {@code long} would start new aggregates at 1 and treat version 0 as new, so it
 * must not be used. Nothing here implements {@code Persistable} or declares an
 * {@code isNew()} that would override that logic.
 *
 * <p><b>Values are taken as given.</b> This type normalizes and validates nothing, and
 * accepts {@code null} in every property, so a draft can describe an incomplete request
 * until the field rules report it. The responsibilities sit elsewhere:
 * <ul>
 *   <li>uppercasing and trailing-blank removal: {@link TextNormalizer#field(String)}, applied
 *       by {@code CustomerMaintenanceService};</li>
 *   <li>the nine field rules, including the {@code Y}/{@code N} check through
 *       {@link ActiveStatus}: {@code service/CustomerValidator};</li>
 *   <li>id allocation: {@code repository/CustomerIdAllocator};</li>
 *   <li>the change stamp ({@code chgtime} from the injected {@code Clock}, {@code chguser}
 *       from the authenticated principal, or {@code *SYSTEM*} in the generator):
 *       {@code CustomerMaintenanceService} and the generator, through
 *       {@link #withStamp(OffsetDateTime, String)}.</li>
 * </ul>
 * The one exception is the address: a {@code null} address is replaced by an
 * {@link Address} whose four components are {@code null}, which is what
 * {@link Embedded.OnEmpty#USE_EMPTY} reads for four {@code null} columns. {@link #address()} is
 * therefore never {@code null}, and an absent address reaches the field rules as blank
 * fields (DEM0502, DEM0503) instead of failing as a {@code NullPointerException}.
 *
 * <p><b>Lifecycle.</b> The id never changes after the insert, and no delete exists:
 * customers are deactivated with {@code active = 'N'}.
 * <pre>{@code
 * // Add: draft from the request, then id and stamp, then save (version null -> INSERT).
 * Customer draft = Customer.draft("ACME INC", new Address("1 MAIN ST", "AUBURN", "ME", "04210"),
 *         "(207) 555-0100", "JANE DOE", "(207) 555-0101", "Y");
 * Customer added = repository.save(draft
 *         .withCustId(allocator.next())                 // EEEF on a fresh database
 *         .withStamp(OffsetDateTime.now(clock), "sales"));
 * added.rowVersion();                                    // 0
 *
 * // Edit: the same draft shape with the path id and the client's version (-> UPDATE).
 * Customer edited = repository.save(draft
 *         .withCustId(CustomerId.parse("EEEF"))
 *         .withRowVersion(0L)
 *         .withStamp(OffsetDateTime.now(clock), "sales"));
 * edited.rowVersion();                                   // 1
 * }</pre>
 *
 * <p><b>Logging.</b> {@link #toString()} lists every field, customer data included. The
 * write log line of {@code CustomerMaintenanceService} carries only the id, the user and the
 * version, so services must not log this object.
 *
 * <p>Instances are immutable: every {@code with...} method returns a new instance and leaves
 * this one unchanged. Every property type ({@link CustomerId}, {@link Address},
 * {@link String}, {@link OffsetDateTime}, {@link Long}) is itself immutable, so instances are
 * thread-safe.
 */
@Table("custmast")
public final class Customer {

    /** The 4-character base-36 key; {@code null} in a draft until an id is assigned. */
    @Id
    @Column("custid")
    private final CustomerId custId;

    /** The customer name, at most 40 characters as stored. */
    @Column("name")
    private final String name;

    /**
     * Street, city, state and ZIP; never {@code null}.
     *
     * <p>Spelled as the full {@code @Embedded(onEmpty = USE_EMPTY)} rather than its alias
     * {@code @Embedded.Empty}: both give the empty prefix and the same mapping, but the alias
     * is meta-annotated with JSR-305 {@code @Nonnull(when = NEVER)}, which is not on the
     * compile classpath, so using it makes javac warn "unknown enum constant
     * javax.annotation.meta.When.NEVER" on every build.
     */
    @Embedded(onEmpty = Embedded.OnEmpty.USE_EMPTY)
    private final Address address;

    /** The corporate phone, at most 20 characters; no format rule, as in the source. */
    @Column("corpphone")
    private final String corpPhone;

    /** The account manager's name, at most 40 characters. */
    @Column("acctmgr")
    private final String acctMgr;

    /** The account manager's phone, at most 20 characters; no format rule. */
    @Column("acctphone")
    private final String acctPhone;

    /** The one-character active code, {@code Y} or {@code N} once validated. */
    @Column("active")
    private final String active;

    /** When the row was last written; {@code null} in a draft until stamped. */
    @Column("chgtime")
    private final OffsetDateTime chgTime;

    /** Who last wrote the row, at most 18 characters; {@code null} in a draft until stamped. */
    @Column("chguser")
    private final String chgUser;

    /** The optimistic-concurrency token; {@code null} marks a new aggregate. */
    @Version
    @Column("row_version")
    private final Long rowVersion;

    /**
     * Creates a customer from all of its properties. Spring Data JDBC uses this constructor
     * to materialize rows, matching its parameter names to the property names (the build
     * compiles with {@code -parameters}), and to apply property changes it has no wither
     * for.
     *
     * @param custId     the id, or {@code null} for an aggregate not yet assigned one
     * @param name       the customer name
     * @param address    the address; {@code null} is replaced by an {@link Address} with four
     *                   {@code null} components
     * @param corpPhone  the corporate phone
     * @param acctMgr    the account manager's name
     * @param acctPhone  the account manager's phone
     * @param active     the active code, {@code Y} or {@code N} once validated
     * @param chgTime    the last-change time, or {@code null} until stamped
     * @param chgUser    the last-change user, or {@code null} until stamped
     * @param rowVersion the row version, or {@code null} for an aggregate never saved
     */
    @PersistenceCreator
    public Customer(CustomerId custId, String name, Address address, String corpPhone,
            String acctMgr, String acctPhone, String active, OffsetDateTime chgTime,
            String chgUser, Long rowVersion) {
        this.custId = custId;
        this.name = name;
        this.address = address != null ? address : new Address(null, null, null, null);
        this.corpPhone = corpPhone;
        this.acctMgr = acctMgr;
        this.acctPhone = acctPhone;
        this.active = active;
        this.chgTime = chgTime;
        this.chgUser = chgUser;
        this.rowVersion = rowVersion;
    }

    /**
     * Creates a new customer from the nine data fields a user enters, with no id, no change
     * stamp and no version. Controllers build drafts from request bodies; the maintenance
     * service then normalizes and validates them, assigns the allocated id (add) or the path
     * id and the client's version (update), and stamps them.
     *
     * @param name      the customer name
     * @param address   the address; {@code null} is replaced by an {@link Address} with four
     *                  {@code null} components
     * @param corpPhone the corporate phone
     * @param acctMgr   the account manager's name
     * @param acctPhone the account manager's phone
     * @param active    the active code
     * @return a draft whose {@code custId}, {@code chgTime}, {@code chgUser} and
     *         {@code rowVersion} are {@code null}
     */
    public static Customer draft(String name, Address address, String corpPhone,
            String acctMgr, String acctPhone, String active) {
        return new Customer(null, name, address, corpPhone, acctMgr, acctPhone, active,
                null, null, null);
    }

    /**
     * Returns the customer id.
     *
     * @return the 4-character base-36 id, or {@code null} in a draft
     */
    public CustomerId custId() {
        return custId;
    }

    /**
     * Returns the customer name.
     *
     * @return the name as held, possibly {@code null} in a draft
     */
    public String name() {
        return name;
    }

    /**
     * Returns the address: street, city, state and ZIP.
     *
     * @return the address, never {@code null}
     */
    public Address address() {
        return address;
    }

    /**
     * Returns the corporate phone.
     *
     * @return the corporate phone as held, possibly {@code null} in a draft
     */
    public String corpPhone() {
        return corpPhone;
    }

    /**
     * Returns the account manager's name.
     *
     * @return the account manager's name as held, possibly {@code null} in a draft
     */
    public String acctMgr() {
        return acctMgr;
    }

    /**
     * Returns the account manager's phone.
     *
     * @return the account manager's phone as held, possibly {@code null} in a draft
     */
    public String acctPhone() {
        return acctPhone;
    }

    /**
     * Returns the one-character active code. {@link ActiveStatus#fromCode(String)} reads it
     * as a status.
     *
     * @return {@code Y} or {@code N} once validated; any value, or {@code null}, in a draft
     */
    public String active() {
        return active;
    }

    /**
     * Returns when the row was last written.
     *
     * @return the last-change instant with its offset, or {@code null} until stamped
     */
    public OffsetDateTime chgTime() {
        return chgTime;
    }

    /**
     * Returns who last wrote the row: the authenticated user, or {@code *SYSTEM*} for the
     * seed and the generator.
     *
     * @return the last-change user, or {@code null} until stamped
     */
    public String chgUser() {
        return chgUser;
    }

    /**
     * Returns the optimistic-concurrency token.
     *
     * @return the row version, 0 after the insert and one higher after each update, or
     *         {@code null} for an aggregate never saved
     */
    public Long rowVersion() {
        return rowVersion;
    }

    /**
     * Returns a copy with the id replaced: the allocated id before an insert, or the path id
     * before an update.
     *
     * @param custId the id
     * @return a new {@code Customer}; this instance is unchanged
     */
    public Customer withCustId(CustomerId custId) {
        return new Customer(custId, name, address, corpPhone, acctMgr, acctPhone, active,
                chgTime, chgUser, rowVersion);
    }

    /**
     * Returns a copy with the customer name replaced.
     *
     * @param name the name, taken as given
     * @return a new {@code Customer}; this instance is unchanged
     */
    public Customer withName(String name) {
        return new Customer(custId, name, address, corpPhone, acctMgr, acctPhone, active,
                chgTime, chgUser, rowVersion);
    }

    /**
     * Returns a copy with the address replaced, for example by the standardized address.
     *
     * @param address the address; {@code null} is replaced by an {@link Address} with four
     *                {@code null} components
     * @return a new {@code Customer}; this instance is unchanged
     */
    public Customer withAddress(Address address) {
        return new Customer(custId, name, address, corpPhone, acctMgr, acctPhone, active,
                chgTime, chgUser, rowVersion);
    }

    /**
     * Returns a copy with the corporate phone replaced.
     *
     * @param corpPhone the corporate phone, taken as given
     * @return a new {@code Customer}; this instance is unchanged
     */
    public Customer withCorpPhone(String corpPhone) {
        return new Customer(custId, name, address, corpPhone, acctMgr, acctPhone, active,
                chgTime, chgUser, rowVersion);
    }

    /**
     * Returns a copy with the account manager's name replaced.
     *
     * @param acctMgr the account manager's name, taken as given
     * @return a new {@code Customer}; this instance is unchanged
     */
    public Customer withAcctMgr(String acctMgr) {
        return new Customer(custId, name, address, corpPhone, acctMgr, acctPhone, active,
                chgTime, chgUser, rowVersion);
    }

    /**
     * Returns a copy with the account manager's phone replaced.
     *
     * @param acctPhone the account manager's phone, taken as given
     * @return a new {@code Customer}; this instance is unchanged
     */
    public Customer withAcctPhone(String acctPhone) {
        return new Customer(custId, name, address, corpPhone, acctMgr, acctPhone, active,
                chgTime, chgUser, rowVersion);
    }

    /**
     * Returns a copy with the active code replaced.
     *
     * @param active the active code, taken as given
     * @return a new {@code Customer}; this instance is unchanged
     */
    public Customer withActive(String active) {
        return new Customer(custId, name, address, corpPhone, acctMgr, acctPhone, active,
                chgTime, chgUser, rowVersion);
    }

    /**
     * Returns a copy with the last-change time replaced.
     *
     * @param chgTime the last-change time
     * @return a new {@code Customer}; this instance is unchanged
     */
    public Customer withChgTime(OffsetDateTime chgTime) {
        return new Customer(custId, name, address, corpPhone, acctMgr, acctPhone, active,
                chgTime, chgUser, rowVersion);
    }

    /**
     * Returns a copy with the last-change user replaced.
     *
     * @param chgUser the last-change user
     * @return a new {@code Customer}; this instance is unchanged
     */
    public Customer withChgUser(String chgUser) {
        return new Customer(custId, name, address, corpPhone, acctMgr, acctPhone, active,
                chgTime, chgUser, rowVersion);
    }

    /**
     * Returns a copy carrying a new change stamp, set together as {@code AddRecd} and
     * {@code UpdateRecd} set {@code CHGTIME} and {@code CHGUSER} in the statement that writes
     * the row [5250_Subfile/MTNCUSTR.SQLRPGLE:556-557,587-588].
     *
     * @param chgTime the write time, from the injected {@code Clock}
     * @param chgUser the writing user: the authenticated principal, or {@code *SYSTEM*}
     * @return a new {@code Customer}; this instance is unchanged
     */
    public Customer withStamp(OffsetDateTime chgTime, String chgUser) {
        return new Customer(custId, name, address, corpPhone, acctMgr, acctPhone, active,
                chgTime, chgUser, rowVersion);
    }

    /**
     * Returns a copy with the row version replaced. Spring Data JDBC calls this after each
     * save to carry the stored version; the maintenance service calls it with the client's
     * version before an update, which makes {@code save} issue the versioned {@code UPDATE}.
     *
     * @param rowVersion the row version, or {@code null} to mark the aggregate as new
     * @return a new {@code Customer}; this instance is unchanged
     */
    public Customer withRowVersion(Long rowVersion) {
        return new Customer(custId, name, address, corpPhone, acctMgr, acctPhone, active,
                chgTime, chgUser, rowVersion);
    }

    /**
     * Compares all ten properties, the address by value.
     *
     * @param other the object to compare with
     * @return {@code true} if {@code other} is a {@code Customer} with equal properties
     */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof Customer that)) {
            return false;
        }
        return Objects.equals(custId, that.custId)
                && Objects.equals(name, that.name)
                && Objects.equals(address, that.address)
                && Objects.equals(corpPhone, that.corpPhone)
                && Objects.equals(acctMgr, that.acctMgr)
                && Objects.equals(acctPhone, that.acctPhone)
                && Objects.equals(active, that.active)
                && Objects.equals(chgTime, that.chgTime)
                && Objects.equals(chgUser, that.chgUser)
                && Objects.equals(rowVersion, that.rowVersion);
    }

    /**
     * Hashes all ten properties, consistent with {@link #equals(Object)}.
     *
     * @return the hash code
     */
    @Override
    public int hashCode() {
        return Objects.hash(custId, name, address, corpPhone, acctMgr, acctPhone, active,
                chgTime, chgUser, rowVersion);
    }

    /**
     * Lists all ten properties, customer data included, for tests and debugging. Not for
     * logs: the write log line carries only the id, the user and the version.
     *
     * @return a text such as {@code Customer[custId=EEEF, name=ACME INC, ...]}
     */
    @Override
    public String toString() {
        return "Customer[custId=" + custId
                + ", name=" + name
                + ", address=" + address
                + ", corpPhone=" + corpPhone
                + ", acctMgr=" + acctMgr
                + ", acctPhone=" + acctPhone
                + ", active=" + active
                + ", chgTime=" + chgTime
                + ", chgUser=" + chgUser
                + ", rowVersion=" + rowVersion
                + "]";
    }
}
