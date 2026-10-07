# Deviations and Open Questions

The IBM i source members are the specification for the Java 21 / Spring Boot 3 / PostgreSQL / React re-implementation of the Customer Master. Every item below either keeps the source behaviour, and says so, or changes it under the defect rule. Where a readme disagrees with the source, the discrepancy is resolved for the source. Citations such as [5250_Subfile/CRTMSGF.CLLE:24-25] give a repository-root-relative path and line numbers. The [README](../README.md) explains how to run the application and its test suites, the [developer guide](developer-guide.md) maps each IBM i member to its target, and the [traceability matrix](traceability-matrix.md) maps each requirement to its tests.

Contents:

1. [Defect rule](#1-defect-rule)
2. [Corrected text defects](#2-corrected-text-defects)
3. [Behaviour changed under the rule](#3-behaviour-changed-under-the-rule)
4. [Preserved source defects](#4-preserved-source-defects)
5. [Intentional differences](#5-intentional-differences)
6. [Readme discrepancies (D1–D9)](#6-readme-discrepancies)
7. [Open questions](#7-open-questions)
8. [Platform notes](#8-platform-notes)

## 1. Defect rule

The request asks to preserve all documented behaviour and to fix obvious defects only when documented, naming "typos in message text" as acceptable and "behavior changes" as not. Those two directives conflict when a source defect is behavioural. Decision:

- Text defects (message and label typos) are corrected.
- Behavioural defects keep the source behaviour and are documented as preserved.
- A behavioural defect changes only where the target platform cannot reproduce it (program termination, dumps) or where another directive of the request requires the change. Each such change names its directive in Section 3.

## 2. Corrected text defects

| Source text | Target text | Test |
|-------------|-------------|------|
| DEM0007 `State selection field in invalid.` [5250_Subfile/CRTMSGF.CLLE:24-25] | `State selection field is invalid.` | `MessageCatalogIT` |
| DEM1002 `Someone else changed record.  Rewiew data.` (double space) [5250_Subfile/CRTMSGF.CLLE:40-41] | `Someone else changed record. Review data.` | `MessageCatalogIT`, `OptimisticConcurrencyIT` |
| DEM0009 `Press Enter to add.  Press F12 to cancel` (double space) [5250_Subfile/CRTMSGF.CLLE:28-29] | `Press Enter to add. Press F12 to cancel` | `MessageCatalogIT` |
| Label `Including Inctives` [5250_Subfile/PMTCUSTD.DSPF:89-101] (`Including` sits at line 92, the misspelt `Inctives` at line 99) | `Including Inactives` | `CustomerSearchPage.test.tsx` |

### 2.1 DEM1002 wording decision

The request quotes DEM1002 as "someone else changed the record, review data"; the source text is "Someone else changed record.  Rewiew data." [5250_Subfile/CRTMSGF.CLLE:40]. They differ in case, the article "the" and punctuation; the request also says source prevails and only typos may be corrected. Decision: the catalog (`backend/customer-api/src/main/resources/messages/messages.properties`) carries the source text with the typo corrected, "Someone else changed record. Review data."; the user's phrase identifies the message. Tests assert `code = DEM1002` and `detail` equal to the catalog text (`OptimisticConcurrencyIT`, `MessageCatalogIT`).

## 3. Behaviour changed under the rule

"Required by" names the platform constraint, or the directive of the migration request (by its behaviour number), that requires the change.

| Source behaviour | Target | Required by |
|------------------|--------|-------------|
| BASE36ADD rolls `9999` over to `AAAA` and leaves the limit to its caller; AddRecd never checks, so ids are reissued from `AAAA`; LOADCUSTR keeps a second counter; an add fails through SQLProblem whenever its id was already issued by the other counter or before the rollover [BASE36/SRV_BASE36.RPGLE:9-13], [5250_Subfile/MTNCUSTR.SQLRPGLE:550-565], [5250_Subfile/LOADCUSTR.SQLRPGLE:133-137]. From CRTDTAARA's `EEEE` [5250_Subfile/CRTDTAARA.clle:1-9], the source's two counters collide after any default load of 577,203 rows or more | Sequence `custmast_id_seq` is `NO CYCLE` → 503 APP0503; one shared sequence, restarted inside the load transaction under the allocation guard (`CustomerIdAllocator`, `CustomerLoader`). Tests `CustomerIdAllocationIT`, `CustomerGeneratorIT` | Behaviour 5: "a safe, concurrent-proof id strategy" |
| F5 in Edit re-reads the record, resetting the saved CHGTIME, but does not refresh the screen, so a later save silently overwrites changes never seen [5250_Subfile/MTNCUSTR.SQLRPGLE:209-216,622-637] | F5 reloads the form and its `version`; the "F5=Refresh" legend is honoured; a vanished row shows DEM0599 and clears the form. Test `CustomerDetailDialog.test.tsx` | Behaviour 4: updates "conditional on the original ChgTime (or a version column)" |
| Displaying a row deleted meanwhile ends the program through SQLProblem [5250_Subfile/MTNCUSTR.SQLRPGLE:181-185] | 404 DEM0599. Test `CustomerMaintenanceApiIT` | Platform (a stateless request cannot end a program); Behaviour 8: typed exceptions mapped to HTTP codes |
| SQLProblem sends an escape message with SQLSTATE and SQL text and dumps the program [Service_Pgms/SRV_SQL.SQLRPGLE:20-57] | 500 DEM9999 with `errorId`; details logged only. Test `ErrorModelIT` | Behaviour 8: structured problem+json errors |
| A USPS HTTP or XML failure ends the program [USPS_Address/USADRVAL.SQLRPGLE:94-96,114-116,134-136] | 502 APP0502. Tests `UspsWebToolsAddressValidationClientTest`, `UspsXmlCodecTest` | Behaviour 8; platform |
| The USPS request XML is built by concatenation without escaping, so `&` or `<` in an address produces a malformed request [USPS_Address/USADRVAL.SQLRPGLE:79-91] | StAX writer with escaping in `UspsXmlCodec`. Test `UspsXmlCodecTest` | Behaviour 7: a working real HTTP client for the documented XML format |
| The USPS display file defines `SD_CUSTID` as zoned numeric `4 0`, stale against the CHAR(4) id [USPS_Address/MTNCUSTD.DSPF:62] | One 4-character base-36 id field. Test `CustomerDetailDialog.test.tsx` | Behaviour 5: "Preserve the 4-character base-36 CustID format" |

## 4. Preserved source defects

Each is built as the source behaves and is covered by a test.

| Source behaviour | Where the target carries it | Test |
|------------------|-----------------------------|------|
| A full 13-character name or city filter loses the appended `%` (varchar(13) truncation) [5250_Subfile/PMTCUSTR.SQLRPGLE:201-202,633-634]; Db2 matches LIKE against the padded `CHAR(40)`/`CHAR(20)` value [5250_Subfile/Custmast2.sql:11,13], so such a filter finds nothing unless it contains `%` itself | Same pattern construction matched against `rpad(name, 40)` / `rpad(city, 20)` in `CustomerSearchRepository` | `CustomerSearchRepositoryIT` (padding parity cases) |
| F12 or F5 at the edit confirmation reloads the stored record and discards the entries [5250_Subfile/MTNCUSTR.SQLRPGLE:226-247] | `CustomerDetailDialog` | `CustomerDetailDialog.test.tsx`, `edit-with-confirmation.spec.ts` |
| F12 at the add confirmation clears the form to Active `Y`; F5 there gives DEM0003 [5250_Subfile/MTNCUSTR.SQLRPGLE:281-291,297] | `CustomerDetailDialog` | `CustomerDetailDialog.test.tsx` |
| Cancelling the state prompt sets State back to the program's working STATE and discards what was typed since; that value is the stored State (blank in Add) until a prompt selects a code or a review passes the State rule, each of which replaces it [5250_Subfile/MTNCUSTR.SQLRPGLE:369-375,488-494] | `CustomerDetailDialog` working State (fed by the review's `stateAccepted`); `CustomerForm` holds the input | `CustomerDetailDialog.test.tsx` |
| Edit_Address sends only the first 30 characters of the 40-character street and, on success, replaces the street with the returned Address2 of at most 30 characters [USPS_Address/MTNCUSTR.SQLRPGLE:473,481], [Copy_Mbrs/USADRVALDS.RPGLE:5] | `AddressStandardizationService` | `AddressStandardizationServiceTest` |
| Enter with unchanged criteria and no option jumps to the last loaded page [5250_Subfile/PMTCUSTR.SQLRPGLE:506-512] | `CustomerSearchPage` | `CustomerSearchPage.test.tsx` |

## 5. Intentional differences

These are not defects; each is recorded with its reason.

- **Identity and modes.** HTTP Basic authentication and roles INQUIRY/MAINTENANCE replace the caller-asserted I/M/S parameter (the source itself notes production would need "a tested menu or some program that enforced security" [5250_Subfile/PMTCUSTR.SQLRPGLE:230-231]); Selection is the `CustomerPicker` context. The "--> Bad Parm 1 <--" header [5250_Subfile/PMTCUSTR.SQLRPGLE:729] is not carried, because Selection always has a return slot. `chguser` stamps the authenticated principal, not the job user or database role.
- **One maintenance flow.** All nine 5250 field rules in 5250 order, then standardization, where the USPS variant skips the addr, city, state and ZIP rules [USPS_Address/MTNCUSTR.SQLRPGLE:405-459]. As in the source, standardization runs before the confirmation (in `POST /api/customers/review`) and not again at commit; `POST`/`PUT` re-run the field rules only as an API guard. With `ADDRESS_VALIDATION_ENABLED=false` the flow is exactly the 5250 variant.
- **Uppercase everywhere.** `TextNormalizer` uppercases (length-preserving: `ß` stays `ß`). The USPS variant's `CHECK(LC)` fields [USPS_Address/MTNCUSTD.DSPF:71,78,85,114] are uppercased; V5 keeps Custmast.sql's mixed-case `addr` and `acctmgr` [5250_Subfile/Custmast.sql:329], which become uppercase when saved through the API.
- **Schema hardening.** State foreign key `custmast_state_fk`; CHECK constraints on `active` (Y/N) and `custid` (`^[A-Z0-9]{4}$`); `NOT NULL DEFAULT ''` for `corpphone`, `acctmgr`, `acctphone` (source `DEFAULT ' '`, nullable [5250_Subfile/Custmast2.sql:16-18]); `varchar` text columns; `timestamptz` change time; `chguser DEFAULT CURRENT_USER` replaces `DEFAULT USER`.
- **Concurrency token.** `row_version` (`@Version`) replaces CHGTIME equality [5250_Subfile/MTNCUSTR.SQLRPGLE:576-607]; DEM1001 arises after a 5-second lock wait (`DB_LOCK_TIMEOUT`, SQLSTATE 55P03) rather than the Db2 row-lock condition 57033; DEM1001 carries no SQLERRMC data (its text never had `&1` [5250_Subfile/CRTMSGF.CLLE:38-39]).
- **Generated INSERT column order.** AddRecd's positional `insert into custmast values(:CUSTMAST_ds)` [5250_Subfile/MTNCUSTR.SQLRPGLE:558-560] becomes `CustomerRepository.save` of a new aggregate, which Spring Data JDBC generates with an explicit 13-column list. Spring Data JDBC 3.5 (managed by Spring Boot 3.5.16) orders those columns alphabetically by name: `INSERT INTO "custmast" ("acctmgr", "acctphone", "active", "addr", "chgtime", "chguser", "city", "corpphone", "custid", "name", "row_version", "state", "zip") VALUES (…)`, where the plan illustrates the statement as `(custid, name, …, row_version)`, the V3 declaration order. Column names, bound values and semantics are the same; only the textual order differs, and it cannot be configured without replacing the generated statement with handwritten SQL. The generator's `COPY custmast (custid, name, addr, city, state, zip, corpphone, acctmgr, acctphone, active, chgtime, chguser, row_version)` keeps the V3 order.
- **Seed and ordering.** Seed ids are re-keyed `AAAB`..`AAIM` (integer ids 1..300 do not fit the 4-character base-36 format; the original id stays as a trailing comment in V5). `custid` is the final sort key (the source cursor orders by NAME, CITY, STATE only [5250_Subfile/PMTCUSTR.SQLRPGLE:220]). The ICU collation `customer_sort`, which `backend/customer-api/src/main/resources/db/migration/V1__create_collation.sql` defines as the ICU root locale `und` with the tailoring rule `&[before 1]α<0<1<2<3<4<5<6<7<8<9`, keeps EBCDIC's letters-before-digits order [BASE36/SRV_BASE36.RPGLE:15-16]: the rule gives the ASCII digits 0–9, in that order, primary weights after every Latin letter, for comparisons, row comparisons, B-tree index order and ORDER BY alike. So `AAAA < AAAZ < AAA0 < ZZZZ < 0000 < 101A < 1010`, base-36 allocation order equals sort order, and digit-led names and cities sort after letters. Among punctuation, ICU's order differs from EBCDIC code-point order. The definition replaces the reorder locale the design named; see [5.1](#51-collation-definition).
- **Change stamp.** Shown in browser-local time with the full 18-character user (the source shows 15 [5250_Subfile/MTNCUSTD.DSPF:132]), format `YYYY-Mon-DD at HH:mm:ss`, hidden for `*SYSTEM*` or blank.
- **Keys and API surface.** A function key the source screen does not enable (e.g. F3 in the detail dialog) shows DEM0003 and leaves the screen as it was; on the 5250 the workstation rejects such a key before the program sees it [5250_Subfile/MTNCUSTD.DSPF:33-35], a browser delivers every key, so DEM0003 stands in. API clients may request page sizes up to 100; the UI uses the source's 12.
- **Generator.** (a) One transaction under a table lock, where the source loads row by row with no commit control; (b) start id switches to `AAAA` automatically when the count exceeds 385,245; (c) CSZ rows with states outside STATES are dropped because the foreign key requires it — selection then indexes only retained rows with the source arithmetic, and since the source never draws an empty array element no draw is rejected or redrawn; (d) `chgtime` is the load time, not LOADCUSTR's cleared (lowest) timestamp [5250_Subfile/LOADCUSTR.SQLRPGLE:135,200]; (e) randomness is seedable (`--seed`), and the unit value keeps Db2 `RANDOM()`'s inclusive [0, 1] range and Rand_Int's truncating scale, so every range keeps its rarely drawn high end.

### 5.1 Collation definition

The design named the ICU reorder locale `und-u-kr-latn-digit` for `customer_sort`. The build uses the rules-based definition above instead, because on the pinned `postgres:18.6` image (ICU 76.1) that locale's comparisons skip the digit reorder its sort keys apply. Measured with a collation created from that locale:

- `'A' < '0'`, `'ZZZZ' < '0000'` and `'101A' < '1010'` are all false, although an ORDER BY of these values puts the left value first.
- Across all 1,679,616 ids, an ORDER BY that sorts in memory agrees with allocation order, yet 47,989 pairs of consecutive ids fail `<`. When the sort exceeds `work_mem` (the default 4MB) and spills to disk, every one of the 1,679,616 ids lands out of allocation position.
- The primary-key range `WHERE custid > 'ZZZZ'`, served by an index-only scan, returns no rows instead of `0000`, `101A`, `1010`. The row comparison `(name, custid) > ('ZETA CORP', 'B001')` over a composite index likewise returns no rows instead of `1ST NATIONAL`, `9 LIVES`.

Keyset pagination (the row comparison over `custmast_search_keyset`) and index range scans would therefore break. The committed definition passes the same probes: the three comparisons are true, the 1,679,616 ids show 0 ORDER BY mismatches and 0 `<` violations whether the sort runs in memory or on disk, and both range scans return the expected rows.

Consequence for verification: `CustomerIdCollationIT` and `FlywayMigrationIT` assert `<` comparisons and an index range scan as well as ORDER BY, because the reorder locale passes a small ORDER BY check and fails the others.

Secondary difference: only the ASCII digits move. Non-ASCII digits (fullwidth, Arabic-Indic, superscript) keep their ICU root position before Latin letters, whereas ORDER BY under the reorder locale puts them after. Measured: `customer_sort` orders `０ ١ ² ９ A Z 0 Ω`; the reorder locale orders `A Z 0 ０ ١ ² ９ Ω`. The `custid` CHECK (`^[A-Z0-9]{4}$`) admits only ASCII, so this affects only name, city and state text that contains such digits.

## 6. Readme discrepancies

In every case the source behaviour is adopted.

| Id | Readme or comment says | Source does | Adopted |
|----|------------------------|-------------|---------|
| D1 | City and State selection use a "between" predicate [5250_Subfile/README.md:42] | City uses `LIKE` prefix; only State uses BETWEEN [5250_Subfile/PMTCUSTR.SQLRPGLE:216-219] | Prefix LIKE for city |
| D2 | CUSTNEXT "contains the next available customer id" [5250_Subfile/README.md:66] | AddRecd increments first, so CUSTNEXT holds the last issued id [5250_Subfile/MTNCUSTR.SQLRPGLE:549-555] | First interactive id `EEEF` |
| D3 | `'EEEE'` is a "really high number" not expected in test data [5250_Subfile/CRTDTAARA.clle:1-9] | In BASE36ADD order `EEEE` has ordinal 191,956, below LOADCUSTR's `1001` (1,294,371) [BASE36/SRV_BASE36.RPGLE:38-39] | Ordinal order; generator and sequence share one counter |
| D4 | "If ADDRESS2 is non blank, then you have a valid address" [USPS_Address/Readme.md:31] | Success means `City` is non-blank [USPS_Address/USADRVAL.SQLRPGLE:118-121], [USPS_Address/MTNCUSTR.SQLRPGLE:480] | City test |
| D5 | Ignoring the standardized address needs additional coding [USPS_Address/Readme.md:19] | No bypass exists; a standardized address always overwrites | No bypass; open question |
| D6 | Modes: I gives 5, M gives 2 and 5, S gives 1 and 5 [5250_Subfile/README.md:33-37] | M also enables F6=Add; S offers 1 only when the return parameter is passed [5250_Subfile/PMTCUSTR.SQLRPGLE:678-702,756-759] | Source mode behaviour |
| D7 | CSZ data is "a USPS file … downloaded from the USPS website" [5250_Subfile/README.md:80,88] | The linked download is the third-party unitedstateszipcodes.org database [5250_Subfile/README.md:92] | [`../data/README.md`](../data/README.md) names the actual source and layout |
| D8 | Lowercase allowed on name, address and city [USPS_Address/MTNCUSTD.DSPF:27] | `CHECK(LC)` is also on the account manager name [USPS_Address/MTNCUSTD.DSPF:114] | Not carried; everything uppercased |
| D9 | 1 million records showed "no discernable performance hit" [5250_Subfile/README.md:42] | Unmeasured claim; the cursor has no composite index for its ORDER BY [5250_Subfile/Custmast2.sql:26-31] | Re-measured on the target: [`performance/search-benchmark.md`](performance/search-benchmark.md) |

## 7. Open questions

No answer is awaited; each row states the course the build takes.

| Question | Course taken |
|----------|--------------|
| Should users be able to keep their own address instead of the USPS one (D5)? | No bypass, as in the source. |
| The USPS city (30 characters) is cut to 20 | Truncate, as in the source (`SD_CITY` and the column are 20 [USPS_Address/MTNCUSTD.DSPF:85], [5250_Subfile/Custmast2.sql:13]). |
| Does USADRVAL end on a response value longer than its XMLTABLE column, or receive it truncated? Db2 documents a truncating cast to CHAR as a warning, which the source's `'00000'` test rejects [USPS_Address/USADRVAL.SQLRPGLE:107-116] | Treated as a service fault (502 APP0502) under the `UspsXmlCodec` response rules; the real client stays disabled by default. |
| The DEM1002 wording quoted in the request differs from the source text | Source text with the typo fixed (see Section 2). |
| Should `POST`/`PUT` re-run standardization for API clients that skip review? | No; review is the standardization step. |
| Should the preserved source defects (13-character filter, discarded entries at confirmation, state-prompt cancel, 30-character street sent for standardization) be fixed? | No; preserved under the defect rule (Section 4). |
| Which USPS API will production use? | Neither is enabled. The Web Tools client is configurable (`USPS_BASE_URL`), and the v3 checklist is in Section 8. |

## 8. Platform notes

- **Spring Boot 3 support.** 3.5.16 is the last open-source 3.x release; 3.5 open-source support ended 2026-06-30 (4.1 is current). The request mandates Boot 3, so 3.5.16 is used; moving to Boot 4.x is a separate upgrade.
- **USPS API retirement.** Web Tools, which the source calls, was retired on 2026-01-25. The real client (`UspsWebToolsAddressValidationClient`) is built to the source's contract but cannot be assumed to reach a live endpoint. The stub stays the default, and the checklist below ([8.1](#81-usps-pre-enablement-verification)) gates enabling the real client.
- **ICU collation version.** PostgreSQL records the ICU version behind `customer_sort`; a future image with a different ICU library can report a collation version mismatch. Remedy: `REINDEX` the indexes on `customer_sort` columns, then `ALTER COLLATION customer_sort REFRESH VERSION`. Pinning `postgres:18.6` prevents unplanned drift. `customer_sort` is the rules-based definition of [5.1](#51-collation-definition) (ICU root locale `und` plus the digit tailoring rule; recorded version 153.128 on `postgres:18.6`), and the `REINDEX` covers every index on its columns: `state_primary_key` and `state_name_unique` (V2), and `custmast_pkey`, `custmast_name`, `custmast_city`, `custmast_state` and `custmast_search_keyset` (V3). The [developer guide](developer-guide.md) carries the same remedy.
- **Browser-reserved keys.** Some browsers and operating systems keep F3, F5, F6 or F12 even after `preventDefault`; every key is also a visible `FunctionKeyBar` button, and Escape mirrors F12.
- **Docker Engine 29.** It raised its minimum API version; Testcontainers 1.21.4, managed by Boot 3.5.16, negotiates it; older Testcontainers versions must not be pinned.
- **Benchmark portability.** Latency depends on the host; the `EXPLAIN` plan-shape assertions in `SearchBenchmarkIT` are the hardware-independent evidence ([`performance/search-benchmark.md`](performance/search-benchmark.md)).

### 8.1 USPS pre-enablement verification

**What must be verified before setting `ADDRESS_VALIDATION_CLIENT=usps`.**

- **Endpoint.** USPS retired the Web Tools APIs, including `secure.shippingapis.com` AddressValidateRequest, on 2026-01-25. Confirm whether the endpoint answers for the account at all. If it does not, the real client cannot be used as built, and `USPS_BASE_URL` only helps if a compatible gateway exists.
- **Registration and licensing.** The replacement USPS APIs require an account on the USPS developer portal with OAuth 2.0 client credentials. Web Tools user ids are not accepted, and since 2026-08-01 the Addresses API requires a signed licence.
- **Contract differences for any future adapter.**
  - Token: `POST https://apis.usps.com/oauth2/v3/token` (client credentials, 8-hour tokens).
  - Lookup: `GET https://apis.usps.com/addresses/v3/address` with `streetAddress`, `secondaryAddress`, `city`, `state`, `ZIPCode`, `ZIPPlus4`, returning a JSON body. Web Tools' `Address2` (street) corresponds to `streetAddress`.
  - Test host: `apis-tem.usps.com`.
  - Default quota: 60 requests per hour. Errors arrive as HTTP status codes and JSON rather than an `Error` element.
- **Credential exposure.** Web Tools puts `USERID`/`PASSWORD` in the URL, visible to proxies and access logs. Confirm that this is acceptable or use the v3 bearer flow.
- **Success test.** Confirm that "City returned" remains a valid success signal for the chosen API.

Building a v3 client is out of scope; `AddressValidationClient` is the seam where such an adapter would plug in.
