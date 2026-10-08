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
- **Field length constraint.** The design named `@Size` per column for the nine `CustomerFields` limits. The request records `CustomerFields`, `CustomerUpdateRequest` and `ReviewRequest` declare Hibernate Validator's `@CodePointLength` with the same maxima instead, because the `varchar` and `char` columns count characters (code points) while `@Size` counts UTF-16 code units: `@Size(max = 40)` would reject a 40-character name of supplementary characters, such as U+1F600, that `varchar(40)` stores. `CodePointLengthSchemaCustomizer` publishes each limit as the `maxLength` and `minLength: 0` that `@Size` produced, so the OpenAPI contract is the same, and a value one character over its column is still 400 APP0400 with an `errors[]` entry on the property. Tests: `RequestTextLengthTest`, `CodePointLengthSchemaCustomizerTest`, `RequestFieldErrorsIT`.
- **Generator.** (a) One transaction under a table lock, where the source loads row by row with no commit control; (b) start id switches to `AAAA` automatically when the count exceeds 385,245; (c) CSZ rows with states outside STATES are dropped because the foreign key requires it — selection then indexes only retained rows with the source arithmetic, and since the source never draws an empty array element no draw is rejected or redrawn; (d) `chgtime` is the load time, not LOADCUSTR's cleared (lowest) timestamp [5250_Subfile/LOADCUSTR.SQLRPGLE:135,200]; (e) randomness is seedable (`--seed`), and the unit value keeps Db2 `RANDOM()`'s inclusive [0, 1] range and Rand_Int's truncating scale, so every range keeps its rarely drawn high end.
- **USPS description in DEM9898.** The source sends the USPS `Description` unchanged as the DEM9898 argument [USPS_Address/MTNCUSTR.SQLRPGLE:492] of "USPS: &1" [5250_Subfile/CRTMSGF.CLLE:43], and the design's text is `USPS: <description>`. `AddressStandardizationService` passes it through `UspsTextRedactor` first: in the 422 `detail`, `args` and the four field messages (`addr`, `city`, `state`, `zip`), any echoed request URL, query or document becomes `[request URL]`, `[request query]` or `[request document]`, and every configured USPS credential becomes `****` in its raw, XML-escaped and URL-encoded forms (the encodings are listed in the [developer guide](developer-guide.md#usps-address-validation)). An ordinary description, such as "Address Not Found.", is shown as sent. When a credential shorter than four characters occurs in the description, it is not masked in place, which would garble the text and reveal the credential: a documented USPS description is shown as sent and any other is replaced whole by `[description withheld]`, while log lines keep their masking. The reason: Web Tools carries `USERID` and `PASSWORD` in the request, so a service that echoes its request would show them to the user, and USPS credentials never reach logs or responses (CWE-200). Tests: `AddressStandardizationServiceTest`, `UspsTextRedactorTest`.
- **End-to-end setup requests.** The design named the frontend URL as Playwright's reach: the browser, plus the API through that same origin for setup. The `api` fixture in `e2e/fixtures/auth.ts`, which `createCustomer` and `getCustomer` use, sends its requests instead to a loopback forwarder on `127.0.0.1`, started for each test, which relays them only to the origin of `baseURL` (the frontend) and adds the MAINTENANCE user's Basic `Authorization` header there. Playwright's request context carries only `X-Requested-With`, `Accept` and a random token of that forwarder; a request without the token is answered 403, the token is removed before relaying, and an upstream failure is answered 502 with a message that holds no header value. The reason: Playwright writes every request header into its call logs, error messages, traces and HTML report and cannot redact them, so the credential is kept out of Playwright entirely. The setup requests still reach nginx `/api` on the frontend origin, and specs still create the customers they modify with `POST /api/customers` and never touch the database; only the request context's own URLs show `127.0.0.1`. Tests: `edit-with-confirmation.spec.ts`, `concurrent-edit-conflict.spec.ts`.

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

- **Spring Boot 3 support.** 3.5.16 is the last open-source 3.x release; 3.5 open-source support ended 2026-06-30 (4.1 is current). The request mandates Boot 3, so 3.5.16 is used; moving to Boot 4.x is a separate upgrade. The advisories OSV matches against the Boot-managed and transitive versions this build retains are registered, with their prerequisites and controls, in [8.2](#82-retained-dependency-advisories).
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

### 8.2 Retained dependency advisories

OSV ([osv.dev](https://osv.dev)) was queried on 2026-10-08 for every resolved Maven artifact of `customer-api`, on the runtime and the test classpath. It matches 21 advisories on 8 artifacts, plus one advisory on the SCRAM library bundled inside the PostgreSQL driver. Every affected version follows from the plan's pins. `spring-boot-starter-parent` 3.5.16 manages the version of every affected artifact but two: commons-compress 1.24.0, which Testcontainers 1.21.4 brings to the test classpath, and the SCRAM library, which the driver bundles. The managed versions include commons-lang3, which springdoc 2.9.1 pulls in. No version override is applied, and no POM repeats a Boot-managed version. Each advisory needs a feature the build does not use (its prerequisite), and the build keeps the invariants listed. No advisory is claimed exploitable here, and none is claimed safe beyond those invariants. A future override of a transitive version (for example commons-lang3 3.18.0 or commons-compress 1.26.0) is recorded in this register before it is applied and must pass `./mvnw -B -ntp verify`. Moving the Boot-managed lines (Jackson, Tomcat, Spring Framework, pgjdbc) means Boot servicing or the Boot 4.x upgrade named in the Spring Boot 3 support note above.

Scope "runtime" means packaged in `backend/customer-api/target/app.jar` under `BOOT-INF/lib`; "test only" means on the test classpath and absent from `app.jar`. Severity is the GitHub advisory database severity that OSV reports.

**Owner and revalidation.** Every row is owned by the customer-master backend maintainers, who own the POMs, `backend/customer-api/src/main/resources/application.yml` and the servicing decision. Every row is re-opened by:

- any change to the `spring-boot-starter-parent`, springdoc or Testcontainers version, or any dependency override;
- each production release, before it ships, and at least monthly while Boot 3.5 has no open-source support;
- a new or amended OSV record for a retained artifact version.

The last column gives each row's own trigger.

| Advisory | Artifact (scope) | Affected range | Severity | Prerequisite | Compensating invariant in this build | Revalidation trigger |
|----------|------------------|----------------|----------|--------------|--------------------------------------|----------------------|
| GHSA-7hhh-6rmp-j9qf (CVE-2026-89425) | `jackson-core` 2.21.4, runtime (Boot-managed; via `jackson-databind` and `flyway-core`'s `jackson-dataformat-toml`) | `>=2.19.0 <2.21.7` | HIGH | A parser created over `java.io.DataInput` (`UTF8DataInputJsonParser`) reading untrusted input | Spring MVC's message converter reads request bodies from an `InputStream`; `CustomerSearchService` reads the search cursor with `ObjectMapper.reader().readTree(byte[])`; no project code creates a `DataInput` parser | Any JSON read from a `DataInput` |
| GHSA-p6pp-m3f8-5c89 (CVE-2026-89407) | `jackson-core` 2.21.4, runtime (Boot-managed) | `>=2.19.0 <2.21.7` | HIGH | `NumberInput.looksLikeValidNumber` on an attacker string: a JSON string coerced to a number, or a direct call | `spring.jackson.mapper.allow-coercion-of-scalars: false` (`application.yml`), so a JSON string is never coerced to a number (`StrictRequestBindingIT` rejects `"version":"5"` with 400 APP0400); no direct call | Re-enabling scalar coercion, or a numeric request field read from text |
| GHSA-5gvw-p9qm-jgwh (CVE-2026-59889) | `jackson-databind` 2.21.4, runtime (Boot-managed) | `>=2.21.0 <2.21.5` | MODERATE | `@JsonView` used as a write guard on a `@JsonUnwrapped` property | No `@JsonView` or `@JsonUnwrapped` on any type; the request records are flat by declaration | Either annotation added |
| GHSA-5jmj-h7xm-6q6v (CVE-2026-54515) | `jackson-databind` 2.21.4, runtime (Boot-managed) | `>=2.19.0 <2.21.5` | MODERATE | Case-insensitive property matching combined with per-property `@JsonIgnoreProperties` | Neither is used; `fail-on-unknown-properties: true` (`application.yml`) | Case-insensitive matching or `@JsonIgnoreProperties` added |
| GHSA-mhm7-754m-9p8w (no CVE) | `jackson-databind` 2.21.4, runtime (Boot-managed) | `>=2.21.0 <2.21.5` | MODERATE | A creator property with both `@JsonView` and `@JsonTypeInfo(include = EXTERNAL_PROPERTY)` | No `@JsonView`, no `@JsonTypeInfo` | Either annotation added |
| GHSA-gx83-3vf8-gh7j (CVE-2026-83557) | `jackson-databind` 2.21.4, runtime (Boot-managed) | `>=2.19.0 <2.21.6` | MODERATE | Polymorphic typing: `@JsonTypeInfo` on a `Comparable`-typed property without a custom validator, or default typing | No `@JsonTypeInfo`, no default typing, no `PolymorphicTypeValidator` | Polymorphic typing introduced |
| GHSA-cxp5-3px4-pw24 (CVE-2026-91777) | `jackson-databind` 2.21.4, runtime (Boot-managed) | `>=2.19.0 <2.21.7` | HIGH | An `@JsonIdentityInfo` collection or map bound from untrusted JSON | No `@JsonIdentityInfo` | Identity references introduced |
| GHSA-q4xh-88c3-wmh7 (CVE-2026-68497) | `jackson-databind` 2.21.4, runtime (Boot-managed) | `>=2.19.0 <2.21.6` | HIGH | A `javax.xml.datatype.Duration` or `XMLGregorianCalendar` request field | Request DTOs bind only strings, integers and the `purpose` enum | Such a field added |
| GHSA-vvgp-rfg2-7rr6 (CVE-2026-77310) | `jackson-databind` 2.21.4, runtime (Boot-managed) | `>=2.19.0 <2.21.5` | MODERATE | A `java.net.InetAddress` request field (eager DNS lookup, SSRF) | No such field | Such a field added |
| GHSA-wjgm-6hv5-3cvf (CVE-2026-19032) | `jackson-databind` 2.21.4, runtime (Boot-managed) | `>=2.19.0 <2.21.6` | MODERATE | A `java.nio.file.Path` request field plus a side-effecting `FileSystemProvider` | No JSON-bound `Path`; the generator's `--csz-file` path comes from the command line or `GENERATOR_CSZ_FILE`, never from JSON | A `Path` request field, or a third-party `FileSystemProvider` on the classpath |
| GHSA-wv8q-qhhj-9h54 (CVE-2026-91776) | `jackson-databind` 2.21.4, runtime (Boot-managed) | `>=2.19.0 <2.21.7` | HIGH | `@JsonTypeInfo(use = NAME)` with a `defaultImpl` fallback and attacker type ids | No `@JsonTypeInfo` | Polymorphic typing introduced |
| GHSA-4265-ccf5-phj5 (CVE-2024-26308) | `commons-compress` 1.24.0, test only (via Testcontainers 1.21.4; absent from `app.jar`) | `>=1.21 <1.26.0` | MODERATE | Unpacking a hostile Pack200 archive | Test scope only; Testcontainers exchanges tar streams with the local Docker daemon only; no Pack200 input | `commons-compress` reaching the runtime classpath, or a test reading archives from outside the build |
| GHSA-4g9r-vxhx-9pgx (CVE-2024-25710) | `commons-compress` 1.24.0, test only (via Testcontainers 1.21.4; absent from `app.jar`) | `>=1.3 <1.26.0` | MODERATE | Reading a corrupted DUMP archive | Test scope only; Testcontainers exchanges tar streams with the local Docker daemon only; no DUMP input | `commons-compress` reaching the runtime classpath, or a test reading archives from outside the build |
| GHSA-j288-q9x7-2f5v (CVE-2025-48924) | `commons-lang3` 3.17.0, runtime (via `springdoc-openapi-starter-common` 2.9.1; version Boot-managed) | `>=3.0 <3.18.0` | MODERATE | `ClassUtils.getClass(...)` on a very long attacker-supplied class name (`StackOverflowError`) | No project code imports commons-lang3, and a bytecode scan of the `app.jar` libraries found no `ClassUtils` reference outside commons-lang3 itself (springdoc and swagger-core use other commons-lang3 classes) | A springdoc or swagger-core version change, or any code passing request input to `ClassUtils` |
| GHSA-qv9r-c865-cp47 (CVE-2026-49844) | `log4j-api` 2.24.3, runtime (Boot-managed; bridged to SLF4J by `log4j-to-slf4j`, with Logback as the logging backend) | `>=2.13.1 <2.25.5` | MODERATE | A `MapMessage` holding an attacker-controlled non-finite float, rendered by `JsonTemplateLayout` or `MapMessage.asJson()` | `log4j-core` and `JsonTemplateLayout` are absent; no project code creates a `MapMessage`; events go to Logback through SLF4J | Adding `log4j-core`, or a JSON layout over `MapMessage` |
| GHSA-9xv2-5v5q-p794 (CVE-2026-65905) | `tomcat-embed-core` 10.1.55, runtime (Boot-managed) | `>=10.1.0-M1 <10.1.58` | CRITICAL | Tomcat's DIGEST authenticator | Authentication is stateless Spring Security HTTP Basic in `SecurityConfig`; no Tomcat realm, login-config or `web.xml` | Any container-managed authentication |
| GHSA-gcx9-497g-6cp6 (CVE-2026-65182) | `tomcat-embed-core` 10.1.55, runtime (Boot-managed) | `>=10.1.0-M1 <10.1.58` | CRITICAL | Servlet security constraints where a constraint for a longer path precedes a more restrictive one for a shorter sub-path | No servlet security constraints; authorization is the Spring Security filter chain (`SecurityConfig`, tested by `SecurityRulesIT`) | Container security constraints added |
| GHSA-h3x4-894j-xpx5 (CVE-2026-68525) | `tomcat-embed-core` 10.1.55, runtime (Boot-managed) | `>=10.1.0-M1 <10.1.58` | CRITICAL | Tomcat's FORM authenticator with a POST-only constraint | No container FORM login, and no Spring form login | Any container-managed authentication |
| GHSA-j92g-9f8w-j867 (CVE-2026-54291) | `postgresql` (pgjdbc) 42.7.11, runtime (Boot-managed) | `>=42.7.4 <42.7.12` | HIGH | `channelBinding=require`, attacked by a TLS man in the middle | The JDBC URL (`application.yml`) sets only `currentSchema`, and the pooled data-source properties set only `socketTimeout` and `options`; `channelBinding` keeps pgjdbc's default `prefer`, which the advisory does not affect. Do not rely on `channelBinding=require` with this release | Enabling TLS or channel binding, or a database reached over an untrusted network (the advisory's interim defence there is `sslmode=verify-full` with a truststore holding only the server's CA) |
| GHSA-p9jg-fcr6-3mhf (CVE-2026-53712) | `scram-client`/`scram-common` 3.2 bundled in `postgresql` 42.7.11, runtime (shaded under `org.postgresql.shaded.com.ongres.scram`; related bundled-library evidence, not a separate resolved Maven artifact) | `<3.3` | HIGH | `channelBinding=require`, attacked by a TLS man in the middle | As for GHSA-j92g-9f8w-j867: `channelBinding` keeps the default `prefer`; do not rely on `channelBinding=require` with this driver release | Enabling TLS or channel binding, or a database reached over an untrusted network |
| GHSA-j9f9-w8pj-32f8 (CVE-2026-47890) | `spring-webmvc` 6.2.19, runtime (Boot-managed) | `>=6.2.0`, last affected 6.2.19 (OSV lists no fixed 6.2 release; fixed in 7.0.9) | CRITICAL | Server-Sent Events rendering view fragments | No SSE and no view rendering; controllers are `@RestController` JSON, and `ProblemErrorController` writes its problem+json body directly | SSE or any view technology added |
| GHSA-pc63-qcmh-9cmg (CVE-2026-47884) | `spring-webmvc` 6.2.19, runtime (Boot-managed) | `>=6.2.0`, last affected 6.2.19 (OSV lists no fixed 6.2 release; fixed in 7.0.9) | CRITICAL | `XsltView` with a `/**` mapping that renders an implicit view name | No `XsltView` and no view resolver configured by the project; routes return JSON or problem responses | SSE or any view technology added |

**Re-checking.** From `backend`:

- `./mvnw -B -ntp dependency:tree -pl customer-api -am -Dscope=test` lists the resolved versions of both classpaths (`-q` would hide the tree).
- `curl -s https://api.osv.dev/v1/query -d '{"package":{"ecosystem":"Maven","name":"<group>:<artifact>"},"version":"<version>"}'` lists one artifact's advisories, and `https://api.osv.dev/v1/vulns/<GHSA id>` returns each record with its affected ranges.
- `grep -rnE '@JsonView|@JsonTypeInfo|@JsonIdentityInfo|@JsonUnwrapped([^}]|$)|@JsonIgnoreProperties|ACCEPT_CASE_INSENSITIVE_PROPERTIES|DefaultTyping|looksLikeValidNumber|InetAddress|XMLGregorianCalendar|javax\.xml\.datatype|FileSystemProvider|DataInput|commons\.lang3|ClassUtils|MapMessage|channelBinding|LoginConfig|SecurityConstraint|formLogin|SseEmitter|XsltView|ViewResolver|ModelAndView' */src/main` must print nothing. A hit names a prerequisite from the table and re-opens its rows. The `@JsonUnwrapped` term skips the Javadoc `{@code @JsonUnwrapped}` that records why the request records are flat.
- `application.yml` must still set `fail-on-unknown-properties: true` and `allow-coercion-of-scalars: false`.
