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
 * of {@code CustomerSearchRepository} and the {@code COPY} column list of
 * {@code CustomerCopyWriter} use. The {@link CustomerId} key is stored as {@code char(4)}
 * through the reading and writing converters of {@code repository/JdbcConfig}; this type
 * holds no conversion code. {@link Address} is embedded with an empty prefix and
 * {@link Embedded.OnEmpty#USE_EMPTY}, so its columns keep their own names and a row always
 * reads back with a non-null address.
 *
 * <p><b>Newness and the row version.</b> {@code rowVersion} is the {@link Version} property
 * and is deliberately the wrapper {@link Long}, so Spring Data JDBC decides newness from it
 * even though the id is assigned before the first save:
 * <ul>
 *   <li>{@code rowVersion == null}: the aggregate is new. {@code save} issues an
 *       {@code INSERT} that includes the assigned {@code custid} and stores
 *       {@code row_version} 0.</li>
 *   <li>{@code rowVersion != null}: {@code save} issues the versioned
 *       {@code UPDATE ... WHERE custid = ? AND row_version = ?}. When no row matches, Spring
 *       Data raises {@code OptimisticLockingFailureException}, which
 *       {@code CustomerMaintenanceService} turns into 404 DEM0599 (absent) or 409 DEM1002
 *       (changed), as {@code UpdateRecd} answers DEM1002 when its timestamp condition finds
 *       no row.</li>
 * </ul>
 * A primitive {@code long} would start new aggregates at 1 and treat version 0 as new, so it
 * must not be used. Nothing here implements {@code Persistable} or declares an
 * {@code isNew()} that would override that logic.
 *
 * <p><b>Values are taken as given.</b> This type normalizes and validates nothing, and
 * accepts {@code null} in every property, so a draft can describe an incomplete request
 * until the field rules report it. Normalization and the change stamp are applied by
 * {@code CustomerMaintenanceService}, or by the generator, which builds its rows normalized
 * and stamped {@code *SYSTEM*}; the field rules by {@code service/CustomerValidator},
 * and id allocation by {@code repository/CustomerIdAllocator}. The one exception is the
 * address: a {@code null} address is replaced by an {@link Address} whose four components
 * are {@code null}, which is what {@link Embedded.OnEmpty#USE_EMPTY} reads for four
 * {@code null} columns. {@link #address()} is therefore never {@code null}, and an absent
 * address reaches the field rules as blank fields (DEM0502, DEM0503) instead of failing as
 * a {@code NullPointerException}.
 *
 * <p><b>Lifecycle.</b> The id never changes after the insert, and no delete exists:
 * customers are deactivated with {@code active = 'N'}.
 *
 * <p><b>Logging.</b> {@link #toString()} names only the id and the row version, never
 * customer data, because Spring Data JDBC quotes it in the exception of a failed write. The
 * write log line of {@code CustomerMaintenanceService} likewise carries only the id, the user
 * and the version; a log line must not add the field values through the accessors.
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
     * Compares every property, the address by value.
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
     * Hashes every property, consistent with {@link #equals(Object)}.
     *
     * @return the hash code
     */
    @Override
    public int hashCode() {
        return Objects.hash(custId, name, address, corpPhone, acctMgr, acctPhone, active,
                chgTime, chgUser, rowVersion);
    }

    /**
     * Identifies the aggregate by its id and row version only; no customer data (name,
     * address, phones, account manager, active flag, change stamp) is ever included.
     * Spring Data JDBC writes this text into the message of the
     * {@code DbActionExecutionException} of a failed insert or update, so anything it
     * contained would reach every log and tool that prints that exception. Both values are
     * system-assigned and printable: the id is four characters of {@code [A-Z0-9]} and the
     * version a number, either one {@code null} in a draft.
     *
     * @return a text such as {@code Customer[custId=EEEF, rowVersion=0]}, or
     *     {@code Customer[custId=null, rowVersion=null]} for a draft
     */
    @Override
    public String toString() {
        return "Customer[custId=" + custId + ", rowVersion=" + rowVersion + "]";
    }
}
