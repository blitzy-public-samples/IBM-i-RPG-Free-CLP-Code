package com.democorp.customermaster.controller.dto;

import com.democorp.customermaster.domain.Customer;
import java.time.Instant;
import java.util.Objects;

/**
 * One customer as stored, the JSON body of {@code GET /api/customers/{custId}} (200),
 * {@code POST /api/customers} (201) and {@code PUT /api/customers/{custId}} (200), and the
 * {@code current} member of a 409 DEM1002 problem: the nine {@code CustomerFields} plus
 * {@code {custId, chgTime, chgUser, version}}.
 *
 * <p>Replaces the MTNCUSTD {@code DETAILS} window [5250_Subfile/MTNCUSTD.DSPF:60-133], which
 * {@code FillScreenFields} filled from the CUSTMAST row [5250_Subfile/MTNCUSTR.SQLRPGLE:340-363],
 * over the columns of [5250_Subfile/Custmast2.sql:9-21]:
 *
 * <table>
 *   <caption>Screen field, source column and JSON property</caption>
 *   <tr><th>Property</th><th>MTNCUSTD field</th><th>Custmast2.sql column</th><th>Content</th></tr>
 *   <tr><td>{@code custId}</td><td>{@code SD_CUSTID 4}, output only</td>
 *       <td>{@code CustID CHAR(4)}</td><td>The 4-character base-36 id, for example
 *       {@code "EEEF"}; assigned on add and never changed afterwards</td></tr>
 *   <tr><td>{@code name}</td><td>{@code SD_NAME 40}</td><td>{@code Name CHAR(40)}</td>
 *       <td>Stored name</td></tr>
 *   <tr><td>{@code addr}</td><td>{@code SD_ADDR 40}</td><td>{@code Addr CHAR(40)}</td>
 *       <td>Stored street line</td></tr>
 *   <tr><td>{@code city}</td><td>{@code SD_CITY 20}</td><td>{@code City CHAR(20)}</td>
 *       <td>Stored city</td></tr>
 *   <tr><td>{@code state}</td><td>{@code SD_STATE 2}</td><td>{@code State CHAR(2)}</td>
 *       <td>Stored 2-character state code</td></tr>
 *   <tr><td>{@code zip}</td><td>{@code SD_ZIP 10}</td><td>{@code Zip CHAR(10)}</td>
 *       <td>Stored ZIP, five digits or ZIP+4; the whole value, not the list's five
 *       characters</td></tr>
 *   <tr><td>{@code corpPhone}</td><td>{@code SD_CORPPH 20}</td><td>{@code CorpPhone CHAR(20)}</td>
 *       <td>Stored corporate phone</td></tr>
 *   <tr><td>{@code acctMgr}</td><td>{@code SD_ACCTMGR 40}</td><td>{@code AcctMgr CHAR(40)}</td>
 *       <td>Stored account manager name</td></tr>
 *   <tr><td>{@code acctPhone}</td><td>{@code SD_ACCTPH 20}</td><td>{@code AcctPhone CHAR(20)}</td>
 *       <td>Stored account manager phone</td></tr>
 *   <tr><td>{@code active}</td><td>{@code SD_ACTIVE 1}</td><td>{@code Active CHAR(1)}</td>
 *       <td>{@code "Y"} or {@code "N"}</td></tr>
 *   <tr><td>{@code chgTime}</td><td>{@code SD_CHGTIME 23}</td>
 *       <td>{@code ChgTime TIMESTAMP}</td><td>When the row was last written, as an
 *       instant</td></tr>
 *   <tr><td>{@code chgUser}</td><td>{@code SD_CHGUSER 15}</td>
 *       <td>{@code ChgUser varchar(18)}</td><td>Who last wrote the row: the authenticated
 *       principal, or {@code *SYSTEM*} for the seed and the generator</td></tr>
 *   <tr><td>{@code version}</td><td>none</td><td>none; V3 {@code row_version}</td>
 *       <td>The optimistic-concurrency token</td></tr>
 * </table>
 *
 * <p><b>Property order is a contract.</b> The component order is the JSON and OpenAPI property
 * order. The committed OpenAPI snapshot, checked by {@code OpenApiSnapshotIT}, and the frontend's
 * generated {@code src/api/schema.d.ts} both follow it, so it must not change without regenerating
 * them. The nine data fields come first, in {@code CustomerFields} order, followed by the stored
 * id, the change stamp and the version. The record is flat by declaration rather than through
 * {@code @JsonUnwrapped}, so the springdoc schema and the serialized body list the same thirteen
 * properties.
 *
 * <p><b>Change stamp.</b>
 * <ul>
 *   <li>{@code chgTime} is a {@link Instant}. With Spring Boot's Jackson defaults (the JSR-310
 *       module registered and {@code WRITE_DATES_AS_TIMESTAMPS} off) it serializes as an ISO-8601
 *       instant, for example {@code "2026-10-05T14:03:09.123456Z"}, and the OpenAPI schema
 *       declares it {@code string}/{@code date-time}. The source displayed a zone-less local
 *       timestamp formatted on the server; the client's {@code formatChangeStamp} now renders the
 *       instant in browser-local time as {@code YYYY-Mon-DD at HH:mm:ss}.</li>
 *   <li>{@code chgUser} carries the full stored value of up to 18 characters, where the source's
 *       {@code SD_CHGUSER} showed only 15 [5250_Subfile/MTNCUSTD.DSPF:132]. This is an
 *       intentional difference recorded in the Deviations document.</li>
 *   <li>Both are always sent. Hiding the stamp for {@code *SYSTEM*} or a blank user, which
 *       {@code FillScreenFields} did through indicator 61, is presentation and stays with the
 *       client.</li>
 * </ul>
 *
 * <p><b>Version.</b> {@code version} is the stored {@code row_version}: 0 after the insert and
 * one higher after each update. The client sends it back on {@code PUT}, where a mismatch answers
 * 409 DEM1002. It replaces the source's {@code CHGTIME = :Orig_CHGTIME} condition
 * [5250_Subfile/MTNCUSTR.SQLRPGLE:576-591]. It is a primitive {@code long}, so it is never
 * {@code null} on the wire and the OpenAPI schema declares it {@code integer}/{@code int64}.
 *
 * <p>The record carries values only. It holds no serialization, validation or OpenAPI
 * annotations, because it is a response body that is never deserialized from a client: the
 * fields a client may send are {@code CustomerFields} and {@code CustomerUpdateRequest}.
 *
 * <p>Example, the first customer added on a fresh database by user {@code sales}:
 * <pre>{@code
 * CustomerResponse.from(saved);
 * // serializes as
 * // {"custId":"EEEF","name":"ACME INC","addr":"1 MAIN ST","city":"AUBURN","state":"ME",
 * //  "zip":"04210","corpPhone":"(207) 555-0100","acctMgr":"JANE ROE",
 * //  "acctPhone":"(207) 555-0101","active":"Y","chgTime":"2026-10-05T14:03:09.123456Z",
 * //  "chgUser":"sales","version":0}
 * }</pre>
 *
 * <p>Instances are immutable and therefore thread-safe.
 *
 * @param custId    the 4-character base-36 customer id
 * @param name      stored customer name
 * @param addr      stored street line
 * @param city      stored city
 * @param state     stored 2-character state code
 * @param zip       stored ZIP code
 * @param corpPhone stored corporate phone
 * @param acctMgr   stored account manager name
 * @param acctPhone stored account manager phone
 * @param active    stored active flag, {@code "Y"} or {@code "N"}
 * @param chgTime   when the row was last written, or {@code null} only if the stored aggregate
 *                  carries no time
 * @param chgUser   who last wrote the row
 * @param version   the stored row version, sent back on {@code PUT}
 */
public record CustomerResponse(
        String custId,
        String name,
        String addr,
        String city,
        String state,
        String zip,
        String corpPhone,
        String acctMgr,
        String acctPhone,
        String active,
        Instant chgTime,
        String chgUser,
        long version) {

    /**
     * Maps a stored customer to its JSON body, property for property.
     *
     * <p>Values pass through unchanged: nothing is trimmed, padded, uppercased or reformatted,
     * so the client sees exactly what is stored. The four address properties come from the
     * embedded {@code Address}, which is never {@code null}. {@code chgTime} keeps the stored
     * instant and drops only the offset, which does not change the moment it denotes.
     *
     * <p>The customer must be persisted: read from the repository, or the result of
     * {@code save}. Its id and row version are then present. A draft, which has neither, is a
     * programming error and fails here with a named {@link NullPointerException}, which the
     * error model answers as 500 DEM9999, rather than producing a body without an id or with an
     * invented version.
     *
     * @param customer the stored customer
     * @return the response body
     * @throws NullPointerException if {@code customer} is {@code null}, or it has no id or no
     *                              row version
     */
    public static CustomerResponse from(Customer customer) {
        Objects.requireNonNull(customer, "customer");
        return new CustomerResponse(
                Objects.requireNonNull(customer.custId(), "custId").value(),
                customer.name(),
                customer.address().addr(),
                customer.address().city(),
                customer.address().state(),
                customer.address().zip(),
                customer.corpPhone(),
                customer.acctMgr(),
                customer.acctPhone(),
                customer.active(),
                customer.chgTime() == null ? null : customer.chgTime().toInstant(),
                customer.chgUser(),
                Objects.requireNonNull(customer.rowVersion(), "rowVersion"));
    }
}
