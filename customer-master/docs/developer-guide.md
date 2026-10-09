# Developer Guide: IBM i Customer Master to customer-master

This guide is for engineers who know the 5250 Customer Master and need to work on its re-implementation in `customer-master/`, built on Java 21 / Spring Boot 3.5.16 / PostgreSQL 18.6 / React 19 + TypeScript. It explains where every IBM i construct went and why.

The IBM i members, unchanged in the folders beside `customer-master/`, are the behavioural specification. Their readmes are [5250_Subfile/README.md](../../5250_Subfile/README.md), [USPS_Address/Readme.md](../../USPS_Address/Readme.md), [Service_Pgms/README.md](../../Service_Pgms/README.md), [Copy_Mbrs/README.md](../../Copy_Mbrs/README.md) and [BASE36/README.MD](../../BASE36/README.MD). Running, testing and loading data are in the [README](../README.md); every departure from the source is in [Deviations and open questions](deviations-and-open-questions.md); requirement-to-test coverage is in the [Traceability matrix](traceability-matrix.md).

A citation `[path:lines]` names an IBM i member, relative to the repository root, and the lines that specify the behaviour. Target files are named relative to `customer-master/`, with this path key:

- `{api}` = `backend/customer-api/src/main/java/com/democorp/customermaster/`
- `{adr}` = `backend/address-validation/src/main/java/com/democorp/customermaster/address/`
- `{web}` = `frontend/src/`
- `resources/` = `backend/customer-api/src/main/resources/`

**Contents**

1. [Code layout](#code-layout)
2. [Screens to components](#screens-to-components)
3. [Programs to services](#programs-to-services)
4. [CUSTNEXT and BASE36ADD to the id strategy](#custnext-and-base36add-to-the-id-strategy)
5. [CUSTMSGF to the message catalog](#custmsgf-to-the-message-catalog)
6. [Optimistic concurrency](#optimistic-concurrency)
7. [Db2 for i to PostgreSQL translation notes](#db2-for-i-to-postgresql-translation-notes)
8. [Mechanism replacement table](#mechanism-replacement-table)
9. [Configuration](#configuration)
10. [USPS address validation](#usps-address-validation)
11. [Platform notes and operations](#platform-notes-and-operations)

## Code layout

The backend has two Maven modules. `backend/address-validation` knows only the USPS contract and has no dependency on `customer-api`. `backend/customer-api` holds the packages `config`, `domain`, `repository`, `service`, `service/exception`, `controller`, `controller/dto`, `security`, `messages` and `generator`. `frontend/src` holds `api`, `auth`, `errors`, `messages`, `keyboard`, `components`, `features/customers`, `features/states`, `features/demo`, `pages`, `styles` and `test`. Beside them sit `e2e` (Playwright), `perf/k6` (load test) and `openapi/customer-master-api.yaml` (the committed API contract). Layer rules: controllers import services and DTOs only, never repositories; business rules live in services; `features/*` call the server through `api/*` only; `components/*` import nothing from `api/`, `errors/` or `features/`. Contract flow: springdoc serves `/v3/api-docs.yaml`; `OpenApiSnapshotIT` fails when it differs from `openapi/customer-master-api.yaml`; `-Dopenapi.snapshot.update=true` rewrites the file; `npm run gen:api` regenerates `{web}api/schema.d.ts`. Local development: the Vite dev server proxies `/api` to `http://localhost:8081`.

## Screens to components

| Source screen | Component | Notes |
|---------------|-----------|-------|
| PMTCUSTD, full-screen subfile [5250_Subfile/PMTCUSTD.DSPF:34-39,78] | `CustomerSearchPage` = `ScreenHeader` + `SearchFilters` + `ResultsTable` + `FunctionKeyBar` + `ToastRegion` (`{web}features/customers/`) | Labels "Name starts with:", "City starts with:", "State +", "Including Inactives" (source typo "Inctives" corrected [5250_Subfile/PMTCUSTD.DSPF:99]); columns Opt, Customer Name, City, St, ZIP (first five characters); footer "Demo Corp of America" [5250_Subfile/PMTCUSTD.DSPF:128-129]; 12 rows per page with "More..."/"Bottom" [5250_Subfile/PMTCUSTD.DSPF:78,88]; F9 toggles inactive customers, off on entry and after F5 [5250_Subfile/PMTCUSTR.SQLRPGLE:394,741]: off sends `includeInactive=false` (`active = 'Y'` only), on sends `true` (no active predicate) [5250_Subfile/PMTCUSTR.SQLRPGLE:647-653]; the legend reads "F9=Include Inactive" while off and "F9=Exclude Inactive" while on [5250_Subfile/PMTCUSTR.SQLRPGLE:695-700], "Including Inactives" shows while on, and each press reloads the first page with the criteria on screen [5250_Subfile/PMTCUSTR.SQLRPGLE:406-413] |
| MTNCUSTD, 17×54 window [5250_Subfile/MTNCUSTD.DSPF:44] | `CustomerDetailDialog` containing `CustomerForm`, `ConfirmationPanel`, `ConflictCompareDialog` | Headers "Displaying Customer" / "Change Customer" / "Add Customer"; "Last Change … by …" stamp [5250_Subfile/MTNCUSTD.DSPF:128-132] via `formatChangeStamp.ts` |
| PMTSTATED, 16×40 window [5250_Subfile/PMTSTATED.DSPF:39] | `StatePicker` (`{web}features/states/`) | Title "USA States", "Name Contains" (10 chars [5250_Subfile/PMTSTATED.DSPF:88]), Code/Name columns, "Sorted by:"; opened with F4 from a State field [5250_Subfile/PMTCUSTR.SQLRPGLE:376-377], [5250_Subfile/MTNCUSTR.SQLRPGLE:372-374]; on open it loads all 58 states at once, sorted by name, with one `GET /api/states?nameContains=&sort=name` (a blank filter returns every row), as `Init` sets the name order and the first ProcessSearchCriteria/SflLoadAll loads the whole table [5250_Subfile/PMTSTATER.SQLRPGLE:173-177,395-397,442-448]; typing a filter and pressing Enter re-queries with it [5250_Subfile/PMTSTATER.SQLRPGLE:196-203]; rows page six at a time on the client [5250_Subfile/PMTSTATED.DSPF:74-75]; F7 toggles By Name/By Code [5250_Subfile/PMTSTATER.SQLRPGLE:262-273]; option 1 or Select returns the code to the calling State field, which the host fills before focus returns there [5250_Subfile/PMTSTATER.SQLRPGLE:298-304]; cancel (F3, F12, Escape) returns no code and the picker writes nothing (the detail dialog then puts its working State back [5250_Subfile/MTNCUSTR.SQLRPGLE:369-375]) |
| PMTCUSTR Selection mode [5250_Subfile/PMTCUSTR.SQLRPGLE:756-759] | `CustomerPicker` | Options 1 and 5 only; hosted by `HostFormDemoPage` (`/demo/selection`); F9 toggles inactive as on the search page, which the picker renders in selection mode |
| Calling menu (outside the repository) | `HomePage` | Links to customer search and to the Selection demo host form, plus sign-out |

### Screen mechanics

- **Indicators** become component state and `aria-invalid` [5250_Subfile/MTNCUSTR.SQLRPGLE:76-99].
- **The message subfile** becomes `ToastRegion` (`role="status"` or `role="alert"`) [5250_Subfile/PMTCUSTD.DSPF:136-155]; a toast lasts until the next user action.
- **AID bytes** [Copy_Mbrs/AIDBYTES.RPGLE:3-35] become `useFunctionKeys` + `KeyScopeProvider` (one capture-phase listener, topmost scope only, Escape mirrors F12, unbound function keys show DEM0003) and visible `FunctionKeyBar` buttons with `aria-keyshortcuts`.
- **Modes I/M/S** [5250_Subfile/PMTCUSTR.SQLRPGLE:76-79] become the roles INQUIRY and MAINTENANCE (MAINTENANCE implies INQUIRY) plus the picker context, read from `GET /api/session`.
- **Reference screenshots:** [Inquiry_Subfile.png](../../5250_Subfile/Images/Inquiry_Subfile.png), [Inquiry_Display.png](../../5250_Subfile/Images/Inquiry_Display.png), [Maintenance_Display.png](../../5250_Subfile/Images/Maintenance_Display.png), [State_Prompt.png](../../5250_Subfile/Images/State_Prompt.png).

## Programs to services

| IBM i member / procedure | Target | Endpoint or entry point |
|--------------------------|--------|-------------------------|
| PMTCUSTR ItemCur, SflFirstPage/SflFillPage, ProcessSearchCriteria [5250_Subfile/PMTCUSTR.SQLRPGLE:208-223,532-603,625-665] | `{api}controller/CustomerController`, `{api}service/CustomerSearchService`, `{api}repository/CustomerSearchRepository` | `GET /api/customers?name=&city=&state=&includeInactive=&size=&cursor=` |
| MTNCUSTR ReadRecd [5250_Subfile/MTNCUSTR.SQLRPGLE:315-338] | `CustomerMaintenanceService.get`, `CustomerRepository.findById` | `GET /api/customers/{custId}` |
| MTNCUSTR EditUpdData [5250_Subfile/MTNCUSTR.SQLRPGLE:387-434] | `{api}service/CustomerValidator` (nine rules, source order, first error stops) | Used by `POST /api/customers/review`, `POST /api/customers`, `PUT /api/customers/{custId}` |
| MTNCUSTR AddRecd [5250_Subfile/MTNCUSTR.SQLRPGLE:548-566] | `CustomerMaintenanceService.add` + `{api}repository/CustomerIdAllocator` | `POST /api/customers` (201, `Location`) |
| MTNCUSTR UpdateRecd [5250_Subfile/MTNCUSTR.SQLRPGLE:574-607] | `CustomerMaintenanceService.update` (versioned UPDATE) | `PUT /api/customers/{custId}` |
| USPS MTNCUSTR Edit_Address [USPS_Address/MTNCUSTR.SQLRPGLE:471-499] | `{api}service/AddressStandardizationService` | Inside `POST /api/customers/review` |
| USADRVAL [USPS_Address/USADRVAL.SQLRPGLE:49-139] | `{adr}UspsWebToolsAddressValidationClient`, `{adr}UspsXmlCodec`; `{adr}StubAddressValidationClient` (default) | Interface `{adr}AddressValidationClient` (`validate`) |
| PMTSTATER [5250_Subfile/PMTSTATER.SQLRPGLE:150-162,395-400] | `{api}controller/StateController`, `{api}service/StateService`, `{api}repository/StateRepository` | `GET /api/states?nameContains=&sort=name\|code` |
| StateVal [Service_Pgms/StateVal.sqlrpgle:16-81] | `StateService.exists(code)` (immutable cache loaded on first use) | The State rule in `CustomerValidator` |
| SRV_MSG SndMsgPgmQ/ClrMsgPgmQ [Service_Pgms/SRV_MSG.RPGLE:69-177] | `{api}controller/ProblemFactory`, `{api}messages/MessageCatalog`; client `{web}errors/useProblemPresenter.ts`, `{web}components/ToastRegion.tsx` | Every problem+json response and `notice` |
| SRV_SQL SQLProblem [Service_Pgms/SRV_SQL.SQLRPGLE:20-57] | `{api}controller/ApiExceptionHandler`, `{api}controller/ProblemErrorController`, `{api}service/exception/*` | Every error response, including `/error` dispatch |
| SRV_STR CenterStr [Service_Pgms/SRV_STR.RPGLE:38-48] | CSS centring in `{web}components/ScreenHeader.tsx` | Every screen header |
| SRV_RANDOM Rand_Int [Service_Pgms/SRV_RANDOM.SQLRPGLE:21-33] | `{api}generator/NameGenerator.randInt` (inclusive [0,1] unit source) | The generator |
| SRV_BASE36 BASE36ADD [BASE36/SRV_BASE36.RPGLE:29-55] | `{api}domain/CustomerId` (`successor`) | `CustomerIdAllocator` and the generator |
| LOADCUSTR [5250_Subfile/LOADCUSTR.SQLRPGLE:95-207] | `{api}generator/` (`CustomerGeneratorRunner`, `CustomerLoader`, `CustomerDataGenerator`, `NameGenerator`, `CszSource`, `CustomerCopyWriter`, `GeneratorProperties`, `LoadCheckpoints`) | Profile `generator` of the API image |
| LOADCUST / LOADCUST2 (CHKOBJ, ALCOBJ WAIT(5), SBMJOB) [5250_Subfile/LOADCUST.CLLE:3-18], [5250_Subfile/LOADCUST2.CLLE:5-19] | Flyway seed V5 / Compose `generator` service | `docker compose --profile tools run --rm generator --count=N` |
| CRTMSGF [5250_Subfile/CRTMSGF.CLLE:10-47] | `resources/messages/messages.properties`, `MessageController` | `GET /api/messages` |
| CRTDTAARA [5250_Subfile/CRTDTAARA.clle:1-9] | `resources/db/migration/V4__create_custid_sequence.sql` | Flyway at startup |
| Custmast2.sql / Custmast.sql / States.sql [5250_Subfile/Custmast2.sql:6-31], [5250_Subfile/Custmast.sql:28-339], [5250_Subfile/States.sql:6-80] | V3 / V5 seed / V2 (V1 creates the collation) | Flyway at startup |
| New, no source counterpart | `{api}controller/SessionController`, `{api}security/*` | `GET /api/session` |

## CUSTNEXT and BASE36ADD to the id strategy

- **Codec.** `{api}domain/CustomerId` uses BASE36ADD's alphabet, `A..Z` = 0..25 and `0..9` = 26..35, and increments from the right with carry [BASE36/SRV_BASE36.RPGLE:29-55]. `successor(id) = fromOrdinal(toOrdinal(id) + 1)` reproduces BASE36ADD below `9999` (`1009 → 101A`, `101Z → 1010`, `AAAZ → AAA0`, `EEEE → EEEF`). The format is `^[A-Z0-9]{4}$`: 1,679,616 ids.
- **Sequence.** `resources/db/migration/V4__create_custid_sequence.sql` creates `custmast_id_seq AS integer MINVALUE 0 MAXVALUE 1679615 START WITH 191957 NO CYCLE`. 191,957 is `EEEF`, the first id the source issues after CRTDTAARA sets `EEEE`, because AddRecd increments before use [5250_Subfile/CRTDTAARA.clle:1-9], [5250_Subfile/MTNCUSTR.SQLRPGLE:549-555].
- **Allocation.** `CustomerIdAllocator.next()` takes `LOCK TABLE custmast IN ROW EXCLUSIVE MODE`, then `nextval`. The generator load takes `ACCESS EXCLUSIVE`, truncates, COPYs and runs `ALTER SEQUENCE … RESTART WITH <start+count>` in the same transaction, with the exhausted branch when the load ends at `9999`. Lock order: table first, sequence second; nothing else touches the sequence. Rolled-back adds leave gaps, as in the source [5250_Subfile/MTNCUSTR.SQLRPGLE:549-565].
- **Exhaustion.** `NO CYCLE` → SQLSTATE 2200H → `CustomerIdExhaustedException` → 503 APP0503. The source rolled over to `AAAA` [BASE36/SRV_BASE36.RPGLE:9-13]; see Section 3 of [Deviations and open questions](deviations-and-open-questions.md).
- **Generator defaults.** The start id is `1001`, as in LOADCUSTR [5250_Subfile/LOADCUSTR.SQLRPGLE:132-136], or `AAAA` when the count exceeds 385,245; `--start-id` overrides it, and the capacity is checked before any write.

### Ordering

The source sorts in EBCDIC order, letters before digits [BASE36/SRV_BASE36.RPGLE:15-16]; PostgreSQL `"C"` sorts digits first. `resources/db/migration/V1__create_collation.sql` creates `customer_sort`, a rules-based ICU collation: the root locale `und` with one tailoring rule.

```sql
CREATE COLLATION customer_sort (
  provider = icu,
  locale = 'und',
  rules = '&[before 1]α<0<1<2<3<4<5<6<7<8<9'
);
```

It is applied to `custid`, `name`, `city`, `state`, `states.state` and `states.name`, so allocation order equals sort order (`AAAA < AAAZ < AAA0 < ZZZZ < 0000 < 101A < 1010`) and Name/City with leading digits sort after letters. Punctuation order differs from EBCDIC (deviation). Tested by `CustomerIdTest`, `CustomerIdCollationIT`, `CustomerIdAllocationIT`.

- **One order everywhere.** The rule gives the ASCII digits `0` to `9`, in that order, primary weights after every Latin letter and before Greek and Cyrillic. Comparisons (`=`, `<`, `>`), row comparisons, B-tree index order and ORDER BY all give this one order. Keyset pagination, the row comparison `(name, city, state, custid) > (…)` over `custmast_search_keyset`, and index range scans depend on that agreement. The collation is deterministic (the default), as the `custid` regex CHECK and the LIKE prefix scans over the `varchar_pattern_ops` indexes require.
- **Why not the reorder locale.** The design's ICU reorder locale `und-u-kr-latn-digit` is not used, because on the pinned `postgres:18.6` image (ICU 76.1) its comparisons disagree with its sort order. Under a collation with that locale, `'A' < '0'`, `'ZZZZ' < '0000'` and `'101A' < '1010'` are all false although ORDER BY puts the left value first, and `WHERE custid > 'ZZZZ'` over the primary key returns no rows instead of `0000`, `101A`, `1010`. It would break comparisons, B-tree range scans and keyset pagination. The rules-based definition is recorded as an intentional difference in [Deviations and open questions](deviations-and-open-questions.md).
- **Verification.** A check of this collation must cover `<` comparisons and an index range scan, not only ORDER BY, because the reorder locale passes an ORDER BY check and fails the others. `CustomerIdCollationIT` and `FlywayMigrationIT` do so; `CustomerIdCollationIT` also asserts that the order equals the `CustomerId` comparator and contrasts it with `COLLATE "C"`.

## CUSTMSGF to the message catalog

- **One file.** `resources/messages/messages.properties` is one UTF-8 file whose keys are the CUSTMSGF ids [5250_Subfile/CRTMSGF.CLLE:12-46], plus five APP keys for conditions the source never reaches. The public `GET /api/messages` serves it whole; `MessageCatalogProvider` fetches it once, before sign-in, and the frontend bundles no message text.
- **Substitution.** `&1` becomes `{0}`. `{n}` is replaced literally, not through `java.text.MessageFormat`, identically on server and client.

All 22 keys, as `messages.properties` holds them:

| Key | Text | Raised by |
|-----|------|-----------|
| DEM0000 | `Press Enter to update. F12 to Cancel.` | review notice (EDIT) |
| DEM0002 | `No records match the selection criteria` | search notice |
| DEM0003 | `Key is not active now` | client |
| DEM0004 | `{0} is not a valid option at this time.` | client |
| DEM0005 | `Use F4 only if + is on field` | client |
| DEM0006 | `Too many records. Change the selection criteria.` | search notice, `limitReached` at 9,999 |
| DEM0007 | `State selection field is invalid.` | 400 (typo fixed) |
| DEM0008 | `Use F4 only in field followed by +` | carried, never raised |
| DEM0009 | `Press Enter to add. Press F12 to cancel` | review notice (ADD) (double space fixed) |
| DEM0501 | `{0}: Must be Y or N` | 422 |
| DEM0502 | `{0}: Must not be blank` | 422 |
| DEM0503 | `State invalid. Can use F4 to prompt.` | 422 |
| DEM0599 | `Customer deleted. Exit & redo search.` | 404 |
| DEM1001 | `Customer being updated by another user or job.` | 409 lock timeout |
| DEM1002 | `Someone else changed record. Review data.` | 409 with `current` (typo fixed) |
| DEM9898 | `USPS: {0}` | 422 address not standardized |
| DEM9999 | `Program Error! Please contact IT now.` | 500 with `errorId` |
| APP0400 | `Request is not valid: {0}` | 400 invalid request; framework 4xx keep their own status |
| APP0401 | `Sign in required.` | 401 no or bad credentials |
| APP0403 | `You are not authorized to perform this action.` | 403 role insufficient |
| APP0502 | `Address service is unavailable. Try again later.` | 502 USPS transport or response fault |
| APP0503 | `No customer ids are left. Contact IT.` | 503 id space exhausted (SQLSTATE 2200H) |

**Error model.** Every error the API sends is RFC 9457 `application/problem+json` built by `ProblemFactory`: `type` (`urn:customer-master:problem:<code>`), `title`, `status`, `instance`, `detail`, `code`, `args`, `errors[{field, code, message}]`, `current` on DEM1002, `stateAccepted` on review failures after the State rule, and `errorId` on 500. No SQL text, SQLSTATE, stack trace or class name ever reaches a client; `ProblemErrorController` covers `/error` dispatch. A 4xx that framework code answers with a status and no body, such as the actuator's 404 for an unknown health component or group member (`/actuator/health/db`, `/actuator/health/readiness/xyz`), is handed to that dispatch by `StatusOnlyErrorFilter`, so it becomes 404 APP0400 "Request is not valid: no such resource", the answer of an unknown API route; a 5xx (the 503 of a DOWN health probe keeps the actuator's body) and a response with a body are left alone. The form-content filter is disabled (`spring.mvc.formcontent.filter.enabled: false`) because no endpoint reads form data, so a malformed form body on `PUT`, `PATCH` or `DELETE`, such as `a=%ZZ`, is never parsed: security, routing and content negotiation answer it with 401, 403, 405 or 415, never 500. The `/actuator` discovery page is not served (`management.endpoints.web.discovery.enabled: false`): a signed-in caller gets 404 APP0400, an anonymous one 401 APP0401. Requests Tomcat's connector rejects before any filter runs never reach `/error`: a request line plus headers over Tomcat's 8 KB `server.max-http-request-header-size` (for example a `cursor`, `name` or `nameContains` of about 8,100 characters or more), a raw `|` or `{` in the URL, a method that is not a token, an encoded `/`, `\` or NUL or an invalid `%` escape in the path, and `TRACE`. `ProblemErrorReportValve`, installed on the Tomcat host by `ProblemErrorReportValveCustomizer`, answers them with the same status map and the default security headers: 400 APP0400 "Request is not valid: bad request", and 405 APP0400 "method not allowed" with `Allow` for `TRACE`. A client of the API port (`API_PORT`) gets that answer for each of them. Through the Compose `frontend` (`FRONTEND_PORT`), nginx forwards a raw `|` or `{`, an encoded `/` or `\`, and a request line plus headers over 8 KB in which no line exceeds 8 KB, so these still reach the valve; it rejects the others before Tomcat sees them: `TRACE`, a method not made of upper-case letters, `_` and `-` (a lower-case one included), an encoded NUL, an invalid `%` escape and a request line over 8 KB. For a request under `/api/`, nginx answers its own rejections, and the requests the API never answers, with problem+json of its own, carrying nginx's security headers (`Content-Security-Policy` with `frame-ancestors 'none'`, `X-Content-Type-Options: nosniff`, `X-Frame-Options: DENY`, `Referrer-Policy: no-referrer`) rather than the API's: 502 DEM9999 when `app` is stopped, refuses connections or cannot be resolved; 504 DEM9999 when the connect outlasts 5 seconds or the answer the 300-second `proxy_read_timeout`; APP0400 at nginx's status, with the lower-case reason phrase as its argument, for 400 (a malformed request line, URI, header or chunked body, such as the method, NUL and `%` cases above, a path above the root, or a missing or repeated `Host`), 405 (`TRACE`; no `Allow`), 413 (a body over 1 MB) and 414 (a request line over 8 KB), and 400 for a header line over 8 KB (nginx's 494); DEM9999 at 501 (an unknown `Transfer-Encoding`) and 505 (an HTTP major version above 1). These bodies carry no `errorId`, and `instance` only when the request path is usable as it is; they are text in `frontend/nginx.conf`, which `NginxGatewayProblemTest` (customer-api tests) holds equal to `ProblemFactory`'s output. A request outside `/api/` that nginx rejects keeps nginx's own HTML page.

**Browser security headers.** nginx in the Compose `frontend` sets these four headers at server level, unconditionally, on every response it writes itself: the SPA, its assets, `/healthz`, its error pages, and its own 502, 504 and rejection bodies, a 502 after the API sent part of its headers included. `location /api/` adds each to the API's answers only where the API did not send it, so the API's own headers are never replaced or duplicated (`frontend/nginx.conf`, held by `NginxGatewayProblemTest`).

## Optimistic concurrency

- **Source.** UpdateRecd's UPDATE is conditional on the CHGTIME read earlier; zero rows → DEM1002 and a re-read; a row lock (57033) → DEM1001; no lock is held across think time [5250_Subfile/MTNCUSTR.SQLRPGLE:567-607].
- **Target.** `custmast.row_version bigint` with Spring Data JDBC `@Version`. `PUT /api/customers/{custId}` carries `version` and runs `UPDATE … WHERE custid = ? AND row_version = ?`. Zero rows → re-read in the same transaction: absent → 404 DEM0599, present → 409 DEM1002 with `current`. `SET LOCAL lock_timeout` (`DB_LOCK_TIMEOUT`, 5s) → SQLSTATE 55P03 → 409 DEM1001. Why a version token: it avoids timestamp precision and clock issues (JavaScript `Date` truncates to milliseconds); `chgtime` is still stamped for display. No `SELECT … FOR UPDATE` anywhere; transactions are short; connections return to the pool per request.
- **UI.** `ConflictCompareDialog` shows the DEM1002 text and a two-column comparison. "Refresh" loads `current` (the source behaviour); "Re-apply my changes" copies the edits onto `current` with its `version` and returns to review. Tests: `OptimisticConcurrencyIT`, `ConflictCompareDialog.test.tsx`, `concurrent-edit-conflict.spec.ts`.

## Db2 for i to PostgreSQL translation notes

Five differences recur:

- **Case.** Both engines are case-sensitive. `TextNormalizer` uppercases everything written through the API, length-preserving.
- **CHAR trailing blanks.** Db2 LIKE matches the blank-padded CHAR value [5250_Subfile/Custmast2.sql:11,13]. Target text columns are `varchar` without padding, so search uses `rpad(name, 40) LIKE :namePattern` and `rpad(city, 20) LIKE :cityPattern`, the state search `rpad(upper(name), 30) LIKE :pattern`, plus an index-narrowing `name LIKE :namePrefix`. Pure-prefix patterns whose lead does not end in a blank skip `rpad`.
- **LIKE escape.** PostgreSQL's default LIKE escape is `\` and Db2 has none, so every `\` in user input is doubled; the seed row `URNA \NUNC\ COMPANY` [5250_Subfile/Custmast.sql:333] exercises it.
- **Collation.** Db2 sorts in EBCDIC code-point order. The target uses `customer_sort`, the ICU root locale `und` with the tailoring rule `&[before 1]α<0<1<2<3<4<5<6<7<8<9`, which puts the ASCII digits after every Latin letter in comparisons, index order and ORDER BY alike (see [Ordering](#ordering)).
- **NULL.** Every column is `NOT NULL`.

Key statement mappings:

| Source statement | PostgreSQL form |
|------------------|-----------------|
| `set schema lennons1` / `lennonsb` [5250_Subfile/Custmast2.sql:6], [5250_Subfile/Custmast.sql:7], [5250_Subfile/States.sql:6] | No statement; the schema comes from `DB_SCHEMA` via `spring.flyway.schemas` and JDBC `currentSchema` |
| `DROP TABLE` [5250_Subfile/Custmast2.sql:7], [5250_Subfile/States.sql:7] | None: versioned migrations; `docker compose down -v` resets |
| `CHAR` text columns [5250_Subfile/Custmast2.sql:9-18] | `varchar` with the same lengths; codes stay `char` |
| `DEFAULT ' '` [5250_Subfile/Custmast2.sql:16-18] | `NOT NULL DEFAULT ''` |
| `DEFAULT USER` [5250_Subfile/Custmast2.sql:21] | `DEFAULT CURRENT_USER`; the service writes the principal |
| `TIMESTAMP` [5250_Subfile/Custmast2.sql:20] | `timestamptz(6)` |
| Indexes `custmast_name`, `custmast_city`, `custmast_state` [5250_Subfile/Custmast2.sql:26-31] | `custmast_name`/`custmast_city` with `varchar_pattern_ops`, `custmast_state`, plus the new `custmast_search_keyset (name, city, state, custid)` |
| ItemCur with `OPTIMIZE FOR 13 ROWS` [5250_Subfile/PMTCUSTR.SQLRPGLE:208-223] | Keyset query with `LIMIT size+1`, row-value comparison `(name, city, state, custid) > (…)` and `ORDER BY name, city, state, custid`, in a read-only transaction with `SET LOCAL plan_cache_mode = force_custom_plan` |
| Integer seed ids [5250_Subfile/Custmast.sql:28-327] | Re-keyed `AAAB`..`AAIM` in the V5 seed |
| LOADCUSTR row INSERTs [5250_Subfile/LOADCUSTR.SQLRPGLE:203] | One `COPY … FROM STDIN (FORMAT csv)` |
| Db2 `RANDOM()` (0 ≤ r ≤ 1) [Service_Pgms/SRV_RANDOM.SQLRPGLE:30] | `nextLong(0, 2^53 + 1) × 2^-53` |
| QSYS2.HTTP_GET / XMLTABLE [USPS_Address/USADRVAL.SQLRPGLE:74-133] | Java: `RestClient`, StAX writer, XPath over a hardened DOM |

**Removed IBM i names.** `lennons1` [5250_Subfile/Custmast.sql:7], [5250_Subfile/Custmast2.sql:6], [5250_Subfile/LOADCUSTR.SQLRPGLE:95,102,112,203]; `lennonsb` [5250_Subfile/States.sql:6]; `LENNONS1` [5250_Subfile/CRTMSGF.CLLE:4]; `/home/LENNONS/…` [5250_Subfile/States.sql:3], [5250_Subfile/LOADCUST.CLLE:16]. None appears outside `docs/`.

## Mechanism replacement table

Each mechanism is replaced, not emulated.

| Mechanism | Target equivalent |
|-----------|-------------------|
| Indicators / INDARA [5250_Subfile/PMTCUSTR.SQLRPGLE:114-129], [5250_Subfile/MTNCUSTR.SQLRPGLE:76-99] | React state + `aria-invalid` |
| Program lifecycle (LR, Init) [5250_Subfile/PMTCUSTR.SQLRPGLE:102-104,706-767], [5250_Subfile/MTNCUSTR.SQLRPGLE:141-147] | Stateless REST; component mount/unmount |
| Subfile paging, SFLPAG 12 / MAXSFLRECDS 9999 [5250_Subfile/PMTCUSTR.SQLRPGLE:134,180,208-223,287-297], [5250_Subfile/PMTCUSTD.DSPF:77-78] | Keyset `GET /api/customers` with size 12, `LIMIT 13`, opaque cursor; DEM0006 at 9,999 |
| Function keys / AID bytes [Copy_Mbrs/AIDBYTES.RPGLE:3-35] | `useFunctionKeys` + `FunctionKeyBar` |
| List options 1/2/5 + DEM0004 [5250_Subfile/PMTCUSTR.SQLRPGLE:427-515] | Per-row Opt input and buttons |
| Message subfile, CUSTMSGF; SndMsgPgmQ / ClrMsgPgmQ over QMHSNDPM / QMHRMVPM [Service_Pgms/SRV_MSG.RPGLE:69-177], [Copy_Mbrs/SRV_MSG_P.RPGLE:4-13], [5250_Subfile/CRTMSGF.CLLE:12-46], [5250_Subfile/PMTCUSTD.DSPF:136-155] | `resources/messages/messages.properties` catalog + `GET /api/messages`; problem+json built by `ProblemFactory` and mapped by `ApiExceptionHandler`; `ToastRegion` live region. No program message queue: messages are data |
| CPF9898 escape and info messages (SndEscMsg, SndInfMsg over QMHSNDPM); JobLogMsg over Qp0zLprintf [Service_Pgms/SRV_MSG.RPGLE:179-284], [Copy_Mbrs/SRV_MSG_P.RPGLE:15-32] | Escape → problem+json from `ProblemFactory` / `ApiExceptionHandler`; job log → SLF4J/Logback server log lines. SndInfMsg, JobLogMsg and Show are unreachable and not carried |
| SQLProblem: GET DIAGNOSTICS, DUMP(A), CPF9898 escape; DEM9999 [Service_Pgms/SRV_SQL.SQLRPGLE:20-57], [5250_Subfile/MTNCUSTR.SQLRPGLE:298-304,697-709] | Typed exceptions in `{api}service/exception/*` mapped by `ApiExceptionHandler`; anything else is 500 DEM9999 with an `errorId`. The SQLSTATE (GET DIAGNOSTICS' RETURNED_SQLSTATE) is logged at ERROR with the `errorId` through SLF4J, never sent in a response; no dump |
| CUSTNEXT data area [5250_Subfile/MTNCUSTR.SQLRPGLE:548-566], [5250_Subfile/CRTDTAARA.clle:1-9] | `custmast_id_seq` + `CustomerId` |
| USPS_ID / USPS_PWD data areas [USPS_Address/USADRVAL.SQLRPGLE:43-46] | `USPS_USER_ID` / `USPS_PASSWORD` |
| Service programs and binding directories UTIL_BND (SRV_MSG, SRV_STR), SQL_BND (SRV_SQL), SRV_BASE36, ADRVAL_BND (USADRVAL), STATE_BND (SRV_STE) [Service_Pgms/CRTBNDDIR.CLLE:12-16], [Service_Pgms/SRV_SQL.SQLRPGLE:8], [BASE36/CRTBNDDIR.CLLE:4-7], [USPS_Address/CRTBNDDIR.CLLE:4-7], [Service_Pgms/StateVal.sqlrpgle:9-11] | Maven modules and Spring dependency injection, not bind-time resolution; Java classes and Spring beans. UTIL_BND → `ProblemFactory` and `MessageCatalog` beans, CSS in `ScreenHeader`; SQL_BND → `ApiExceptionHandler`; SRV_BASE36 → `{api}domain/CustomerId`; STATE_BND → the `StateService` bean; ADRVAL_BND → the `backend/address-validation` module, the Maven dependency customer-api → address-validation, auto-configured via `AutoConfiguration.imports` |
| Copy members [Copy_Mbrs/USADRVAL_P.RPGLE:2-4], [Copy_Mbrs/USADRVALDS.RPGLE:3-13] | Java interfaces and records |
| StateVal static cache [Service_Pgms/StateVal.sqlrpgle:34-65] | `StateService` singleton cache |
| CL wrappers LOADCUST / LOADCUST2, ALCOBJ WAIT(5) [5250_Subfile/LOADCUST.CLLE:3-18], [5250_Subfile/LOADCUST2.CLLE:7-19]; CRTMSGF, CRTDTAARA [5250_Subfile/CRTMSGF.CLLE:10-47], [5250_Subfile/CRTDTAARA.clle:1-9] | Generator CLI + `LOCK TABLE` under `lock_timeout 5s` ("Cannot allocate CUSTMAST", exit 1); the catalog file and Flyway V4 |
| RUNSQLSTM scripts [5250_Subfile/Custmast2.sql:6], [5250_Subfile/States.sql:3-6] | Flyway V1–V5 |
| Commitment control `*NONE` [5250_Subfile/MTNCUSTR.SQLRPGLE:137-139], [5250_Subfile/LOADCUSTR.SQLRPGLE:90-92] | `@Transactional` short transactions |
| Job user / PSDS, `DEFAULT USER` [5250_Subfile/MTNCUSTR.SQLRPGLE:548-557], [5250_Subfile/Custmast2.sql:21] | Authenticated principal |
| Mode I/M/S and function code E/D/A [5250_Subfile/PMTCUSTR.SQLRPGLE:76-79,715-767], [5250_Subfile/MTNCUSTR.SQLRPGLE:141-166] | Roles + picker context |
| EBCDIC / CCSID 273 [BASE36/SRV_BASE36.RPGLE:15-16], [5250_Subfile/States.sql:8-13] | UTF-8 + `customer_sort` |
| SBMJOB [5250_Subfile/LOADCUST.CLLE:15-18], [5250_Subfile/LOADCUST2.CLLE:19] | One-shot Compose service |
| CenterStr [Service_Pgms/SRV_STR.RPGLE:38-48] | CSS |
| 5250 uppercase translation (no `CHECK(LC)`) [5250_Subfile/PMTCUSTD.DSPF:95-98], [5250_Subfile/MTNCUSTD.DSPF:54-133] | `TextNormalizer` server-side + `{web}components/upperField.ts` |
| QSYS2.HTTP_GET, `url_encode`, XMLTABLE [USPS_Address/USADRVAL.SQLRPGLE:74-133] | Spring `RestClient`; StAX writer and hardened DOM/XPath reader in `UspsXmlCodec` |

QTEMP: no in-scope member uses it, so there is nothing to replace.

## Configuration

All settings live in `resources/application.yml` (plus `application-generator.yml` for the generator profile) as `${ENV:default}` placeholders. `.env.example` documents each variable, and `docker-compose.yml` supplies the demo defaults through `${VAR:-default}`.

| Variable | Default | Consumer |
|----------|---------|----------|
| `DB_HOST`, `DB_PORT`, `DB_NAME` | `db`, `5432`, `customermaster` | Datasource URL |
| `DB_USER`, `DB_PASSWORD` | `customermaster`, `customermaster-demo` (Compose only) | Datasource, Flyway, generator, `db` service |
| `DB_SCHEMA` | `customer_master` | `currentSchema`, `spring.flyway.schemas` |
| `DB_LOCK_TIMEOUT` (`customer-master.db.lock-timeout`) | `5s` | Add/update transactions; the re-check interval of searches and gets waiting on a table lock. Must stay below the 45-second socket bound, or startup fails |
| `SPRING_FLYWAY_LOCATIONS` | `classpath:db/migration` (app default); `classpath:db/migration,classpath:db/seed` in Compose | Flyway |
| `CM_INQUIRY_USER`/`CM_INQUIRY_PASSWORD` | `inquiry`/`inquiry-demo` (Compose only, demo) | Role INQUIRY |
| `CM_MAINTENANCE_USER`/`CM_MAINTENANCE_PASSWORD` | `sales`/`sales-demo` (Compose only, demo) | Role MAINTENANCE |
| `ADDRESS_VALIDATION_ENABLED`, `ADDRESS_VALIDATION_CLIENT` | `true`, `stub` | Address auto-configuration |
| `USPS_BASE_URL`, `USPS_USER_ID`, `USPS_PASSWORD`, `USPS_CONNECT_TIMEOUT`, `USPS_READ_TIMEOUT` | `https://secure.shippingapis.com/ShippingAPI.dll`, empty, empty, `5s`, `10s` | `AddressValidationProperties` (startup fails when `client=usps` and the user id is empty) |
| `GENERATOR_COUNT`, `GENERATOR_START_ID`, `GENERATOR_CSZ_FILE`, `GENERATOR_SEED` (flags `--count`, `--start-id`, `--csz-file`, `--seed`) | `300`, empty (automatic rule), `classpath:generator/csz-sample.csv`, empty (random) | `GeneratorProperties`; flag beats variable beats default; besides the four flags and `--spring.*`, only the fully qualified `--customer-master.generator.count`, `.start-id` (or `.startId`, `.start_id`), `.csz-file` (or `.cszFile`, `.csz_file`) and `.seed` are accepted, matched exactly; any other option, a mistyped qualified name such as `--customer-master.generator.cuont` included, exits 1 with `Unknown option --<name>` followed by a `Usage:` line naming the four flags and the `GENERATOR_*` variables (`--help` prints the same), and a flag or qualified option given without a value (a bare `--seed`, unlike `--seed=`) exits 1 with `Option --<name> requires a value` before any read or write; a database that cannot be reached or rejects the role exits 1 with `Cannot connect to database <host:port>: <reason>`, an empty `DB_USER` or `DB_PASSWORD` with the one-line `Database credentials missing: …` message, an empty, blank or padded `DB_SCHEMA` with the one-line `Database schema invalid: …` message, and a `DB_LOCK_TIMEOUT` under 1 ms or not below the 45-second socket bound with the one-line `Database lock timeout invalid: …` message |
| `FRONTEND_PORT`, `API_PORT` | `8080`, `8081` | Host ports |
| `FRONTEND_BIND_ADDRESS`, `API_BIND_ADDRESS` | `127.0.0.1`, `127.0.0.1` | Host address of each published port: loopback by default, so only this machine reaches the demo users; `0.0.0.0` (every interface) or one interface's address opts in to sharing, after the demo users and `DB_PASSWORD` are overridden |
| `COMPOSE_PROJECT_NAME` | Unset: the folder's name, `customer-master` (described in `.env.example`, not set there) | Compose project: prefix of the containers, the network and the `pgdata` volume; backend image `<name>-app:local`. An exported value or `docker compose -p` wins over `.env` |
| `MEM_LIMIT_DB`, `MEM_LIMIT_APP`, `MEM_LIMIT_FRONTEND`, `MEM_LIMIT_GENERATOR`, `MEM_LIMIT_K6`, `MEM_LIMIT_E2E` | `3g`, `1g`, `32m`, `2g`, `128m`, `1536m` | Compose `mem_limit` of each service, at least 1.75 times its measured peak (`.env.example`); the app and generator JVMs take a quarter of theirs as heap |
| `BASE_URL` | `http://frontend` in Compose `e2e`, else `http://localhost:8080` | Playwright |
| `CI` | Unset on host runs (optional; a CI system or the developer's shell may set it); `true`, fixed in Compose `e2e` (not read from `.env`) | Playwright `forbidOnly` (`e2e/playwright.config.ts`): only emptiness is tested, so any nonempty value, `false` and `0` included, makes a stray `test.only` or `test.describe.only` fail the run before any test runs; unset or empty, `.only` runs just the focused tests |
| `E2E_TRACE` | Unset: traces off; `off`, `on` or `retain-on-failure` (trimmed); any other value fails at config load | Playwright `trace` (`e2e/playwright.config.ts`); any mode but `off` is refused at config load unless the `CM_*` users are the demo defaults, because a trace records entered passwords and `Authorization` headers; `--trace` and UI mode are refused the same way, before any test signs in. Compose `e2e` takes it only from the run command, `docker compose --profile e2e run --rm -e E2E_TRACE=retain-on-failure e2e` (not read from `.env`) |
| `K6_BASE_URL`, `K6_USER`, `K6_PASSWORD` | `http://app:8080`, the inquiry user | k6 |

No USPS credential default exists anywhere. The demo users are demo-only and are overridden through `.env`.

Two database connection bounds are fixed in `application.yml` (`spring.datasource.hikari.data-source-properties`), with no variable, and the generator inherits both. pgjdbc's `socketTimeout` of 45 seconds bounds each wait for PostgreSQL's answer on an open connection, that is, each socket read, not the request: when the database stops answering mid-statement, the read fails after 45 seconds without a byte, Hikari evicts the connection and the request answers 500 DEM9999 with an `errorId`. The `options` server-side TCP keepalives (`tcp_keepalives_idle=5`, `tcp_keepalives_interval=2`, `tcp_keepalives_count=3`) let PostgreSQL end a session whose client vanished within about 11 seconds. A request also waits outside the socket bound, before and between its reads:

- **Borrowing a connection.** Hikari gives up after its `connection-timeout` of 5000 ms. A pooled connection not used in the last 500 ms (Hikari's bypass window) is first checked for aliveness, each check bounded by `validation-timeout` (2000 ms); a dead one is closed and the borrow tries again within what remains of the 5 seconds. A check that starts just before those 5 seconds expire still runs to its end, so a borrow can end up to about 2 seconds past them. What a failed borrow answers is in **Connection-pool saturation** under [Platform notes and operations](#platform-notes-and-operations).
- **Lock waits.** An add or update waits at most `DB_LOCK_TIMEOUT` (5 s by default) for a lock, then answers 409 DEM1001. A search or get behind a table lock, such as a generator load's `ACCESS EXCLUSIVE`, is answered by PostgreSQL every `DB_LOCK_TIMEOUT`, re-checks and waits again (`repository.LockWaitingReads`), so it lasts as long as the lock is held. Each lock wait is one socket wait that PostgreSQL ends after `DB_LOCK_TIMEOUT`, and each re-check starts a new one, so `DB_LOCK_TIMEOUT` must stay below 45 seconds; startup fails otherwise.
- **Total latency.** The API sets no single bound on a request: its time is the borrow, any lock waits (unbounded for a read behind a load) and the statements' own time. Through the Compose frontend, nginx's `/api/` `proxy_read_timeout` of 300 seconds (`frontend/nginx.conf`) bounds the wait for the API's answer, after which nginx answers its own 504 DEM9999; it is sized above the longest load, so the UI normally receives the API's own answer. A client calling the API port directly has no such cutoff.

## USPS address validation

**Contract the source uses** [USPS_Address/USADRVAL.SQLRPGLE:74-137]:

- **Request.** `GET …/ShippingAPI.dll?API=Verify&XML=<AddressValidateRequest USERID="…" PASSWORD="…"><Revision>1</Revision><Address ID="0">…</Address></AddressValidateRequest>`, the address holding `Address1`, `Address2` (the street, at most 30 characters), `City`, `State`, `Zip5` and `Zip4`.
- **Success.** `City` is non-blank [USPS_Address/USADRVAL.SQLRPGLE:118-121].
- **Address error.** `Address/Error` (`Number`, `Source`, `Description`) is read when `City` is blank and becomes DEM9898 `USPS: <Description>`. The DEM9898 text, in `detail`, `args` and the four field messages, is the `Description` with any echoed request URL, query or document replaced by `[request URL]`, `[request query]` or `[request document]` and every configured credential by `****` in its raw, XML-escaped and any URL-encoded form (hex digits in either case, a space as `+`, `%20` or itself, wholly or partly encoded). A word holding `%` (a run of characters between whitespace) is also decoded, up to four times: when it or one of its decodings holds the request document (`AddressValidateRequest`), the Verify query (`API=Verify&XML=`, its `&` also written `&amp;`) or the base URL, or a decoding holds a credential form, the whole word becomes the matching marker or `****`, and a word still percent-encoded after four decodings becomes `[encoded text]`, because what it hides cannot be checked. An ordinary description, a word such as `100%` or `%41BC` included, is shown as sent. A credential shorter than four characters (Unicode code points) is never masked in place there, because it occurs by chance in ordinary words, where masks would garble the text and the letters left around them would reveal it: when such a credential occurs in the description in any of those forms, a documented USPS description (the list under **Faults**) is shown as sent, because it echoes nothing, and any other description is replaced whole by `[description withheld]`. The log lines carry the `Description` and the `Number` by the same rule (see **Faults**).
- **Faults.** Transport or response faults become 502 APP0502. Each fault writes one WARN line `USPS address validation failed` with the host, the HTTP status when one arrived and the kind of fault. When the response carries a USPS `Error` element (a Web Tools root `<Error>` such as an authorization failure, or the first `Error` of the response's one `Address`), the line also carries its `Number` and `Description`, logged as `-` when absent. An address-level error (DEM9898) is logged at INFO with the same two values. Both values are logged as the service sent them, with these replacements: those of the DEM9898 text, in every form listed under **Address error**, so an echoed request URL, query or document becomes `[request URL]`, `[request query]` or `[request document]`, a configured credential `****` and a word still percent-encoded after four decodings `[encoded text]`; a value the call submitted, raw, XML-escaped or URL-encoded once, becomes `[address]`; and any other URL, `mailto:` and `tel:` URIs included, becomes `[URL]`, as does a whole word holding `%` one of whose decodings, up to four, holds a URL, such as `https%3A%2F%2Fhost`, `https:%2F%2Fhost` or `login?return%3Dhttps%3A%2F%2Fhost`. Control and line-separator characters are escaped. The `Number` and the `Description` both follow the short-credential rule of the DEM9898 text (see **Address error**), so each is logged exactly as DEM9898 would show it apart from `[address]`, `[URL]` and the escaping: when a credential shorter than four characters occurs in it, a documented USPS description (`Address Not Found.`, `Invalid Address.`, `Invalid City.`, `Invalid State Code.`, `Invalid Zip Code.` and the Web Tools authorization-failure and XML-syntax errors) is logged as sent and anything else, an error code such as `-2147219401` or `80040B1A` included, as `[description withheld]`. The URL, the query string and the credentials are never logged, nor is the submitted address in any of the forms above; the returned result keeps the service's text.
- **Re-send on a dropped connection.** When the connection is reset or closed before any byte of the response arrives, the JDK HTTP client re-sends the Verify GET once, on another connection, so USPS can receive the request twice, credentials included. The review still gets one answer: the outcome of the re-sent request, or, when that connection drops too, one 502 APP0502 with one WARN line. Any response, a response cut short after its first bytes, a timeout and a refused connection each send the request at most once.
- **Edit_Address mapping** [USPS_Address/MTNCUSTR.SQLRPGLE:471-499]. The street, cut to 30 characters, goes in `Address2`. On success the service overwrites `addr` (at most 30), `city` (cut to 20), `state`, and `zip` with `Zip5-Zip4` or `Zip5`.
- **Stub (default).** Deterministic and offline, answering in this order: (1) an address line containing `BADADDR`, in any case, returns the error triple `-2147219401` / `clsAMS` / `Address Not Found.`, which review reports as 422 DEM9898 "USPS: Address Not Found."; (2) otherwise, a street as sent (its first 30 characters), city, state and five-digit ZIP that match an entry in `backend/address-validation/src/main/resources/stub/usps-stub-fixtures.json` after trimming and uppercasing return that entry's standardized address, city, state, Zip5 and, where the entry has one, Zip4, so the form shows `Zip5-Zip4`; (3) anything else is echoed in uppercase with a blank Zip4.

The USPS documentation URLs in [USPS_Address/USADRVAL.SQLRPGLE:8-9] are left unchanged in the source; the endpoint behind them was retired on 2026-01-25.

> **Important — verify before enabling the real USPS client.**
>
> **What must be verified before setting `ADDRESS_VALIDATION_CLIENT=usps`.**
>
> - **Endpoint.** USPS retired the Web Tools APIs, including `secure.shippingapis.com` AddressValidateRequest, on 2026-01-25. Confirm whether the endpoint answers for the account at all. If it does not, the real client cannot be used as built, and `USPS_BASE_URL` only helps if a compatible gateway exists.
> - **Registration and licensing.** The replacement USPS APIs require an account on the USPS developer portal with OAuth 2.0 client credentials. Web Tools user ids are not accepted, and since 2026-08-01 the Addresses API requires a signed licence.
> - **Contract differences for any future adapter.**
>   - Token: `POST https://apis.usps.com/oauth2/v3/token` (client credentials, 8-hour tokens).
>   - Lookup: `GET https://apis.usps.com/addresses/v3/address` with `streetAddress`, `secondaryAddress`, `city`, `state`, `ZIPCode`, `ZIPPlus4`, returning a JSON body. Web Tools' `Address2` (street) corresponds to `streetAddress`.
>   - Test host: `apis-tem.usps.com`.
>   - Default quota: 60 requests per hour. Errors arrive as HTTP status codes and JSON rather than an `Error` element.
> - **Credential exposure.** Web Tools puts `USERID`/`PASSWORD` in the URL, visible to proxies and access logs. Confirm that this is acceptable or use the v3 bearer flow.
> - **Success test.** Confirm that "City returned" remains a valid success signal for the chosen API.

To switch, set `ADDRESS_VALIDATION_CLIENT=usps`, `USPS_USER_ID`, `USPS_PASSWORD` and optionally `USPS_BASE_URL` in `.env`. To disable standardization, set `ADDRESS_VALIDATION_ENABLED=false`; the flow then becomes exactly the 5250 variant. A v3 client is out of scope; `AddressValidationClient` is the seam for one.

## Platform notes and operations

These are also recorded in [Deviations and open questions](deviations-and-open-questions.md).

- **ICU collation version remedy.** PostgreSQL records the ICU version behind `customer_sort` (`pg_collation.collversion`, 153.128 on `postgres:18.6`); an image with a different ICU library can report a collation version mismatch. Remedy, in order: `REINDEX` the indexes on `customer_sort` columns (`custmast_pkey`, `custmast_search_keyset`, `custmast_state`, `state_primary_key`, `state_name_unique`, and any other index on those columns; `REINDEX TABLE custmast` and `REINDEX TABLE states` cover them all), then `ALTER COLLATION customer_sort REFRESH VERSION`, run as `DB_USER` with `search_path` set to the `DB_SCHEMA` schema. Pinning `postgres:18.6` prevents unplanned drift. `customer_sort` is the rules-based ICU definition, the root locale `und` with the tailoring rule `&[before 1]α<0<1<2<3<4<5<6<7<8<9`, not the reorder locale `und-u-kr-latn-digit` (see [Ordering](#ordering)). Only the ASCII digits move: non-ASCII digits (fullwidth, Arabic-Indic, superscript) keep their ICU root position before Latin letters. Measured on `postgres:18.6`, `customer_sort` orders `０ ١ ² ９ A Z 0 Ω`, while ORDER BY under the reorder locale gives `A Z 0 ０ ١ ² ９ Ω`. This difference is recorded in the deviations document.
- **Spring Boot 3.5 end of life.** 3.5.16 is the last open-source 3.x release; 3.5 open-source support ended 2026-06-30 and 4.1 is current. Boot 3 is mandated, so moving to 4.x is a separate upgrade.
- **USPS Web Tools retirement** (2026-01-25). The stub is the default; see the box above.
- **Browser-reserved keys.** Some browsers keep F3/F5/F6/F12 despite `preventDefault`; every key is a visible button, and Escape mirrors F12.
- **Docker Engine 29.** Testcontainers 1.21.4 (Boot-managed) negotiates its API; do not pin an older Testcontainers.
- **Connection-pool saturation.** A request that waits on a lock holds one of the 10 pooled connections for the whole wait: a read behind a generator load until the load commits, an add or update up to the 5-second lock timeout. Once such requests hold all 10, an add or update that cannot get a connection within the 5-second `connection-timeout` answers 409 DEM1001 when PostgreSQL answers a direct probe (one unpooled connection, validated within 2 seconds), and 500 DEM9999 otherwise; the WARN line `Connection pool stayed saturated` marks each such 409. A read that cannot get a connection still answers 500 DEM9999. Readiness stays UP while PostgreSQL answers that probe.
- **Benchmark portability.** Latency is host-dependent; the `EXPLAIN` plan assertions in `SearchBenchmarkIT` are the hardware-independent evidence. Results are in the [search benchmark](performance/search-benchmark.md).
- **Generator lock effect.** A load holds `ACCESS EXCLUSIVE` on `custmast`, so searches and reads wait until it commits, re-checking every `DB_LOCK_TIMEOUT`.
- **Interrupted generator load.** A generator stopped during its load (Ctrl-C, `docker stop` or `docker kill`) can vanish without closing its database connection. The server-side TCP keepalives set in `application.yml` end that orphaned session within about 11 seconds, and PostgreSQL rolls the load back, so the previous rows and the previous next id return and the lock on `custmast` is released. If a session still holds the lock (the next load prints `Cannot allocate CUSTMAST`, reads wait), find it in `pg_stat_activity` joined with `pg_locks` on `pid` (`relation = 'customer_master.custmast'::regclass` with the `DB_SCHEMA` schema, mode `AccessExclusiveLock`) and end it with `SELECT pg_terminate_backend(<pid>)`, run as `DB_USER`.
