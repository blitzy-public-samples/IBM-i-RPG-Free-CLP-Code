# Search benchmark: 1,000,000 customers

This is the benchmark record for the customer search. It holds the measured evidence that the target search stays fast with 1,000,000 rows in `custmast`. Every measured number below is copied from a file in [`evidence/`](evidence/), which holds the artifacts of the runs listed under [Run date](#11-run-date): raw copies of the `SearchBenchmarkIT` report, its Failsafe summary and the k6 summary export, and marked excerpts of the Maven, generator and k6 output and of the Compose environment capture. Nothing is estimated or extrapolated.

Contents:

1. [Purpose and the claim it replaces](#1-purpose-and-the-claim-it-replaces)
2. [Design under test](#2-design-under-test)
3. [Method](#3-method)
4. [Thresholds](#4-thresholds)
5. [Environment](#5-environment)
6. [Load time](#6-load-time)
7. [Results](#7-results)
8. [Plan excerpts](#8-plan-excerpts)
9. [k6 summary](#9-k6-summary)
10. [Verdict](#10-verdict)
11. [Run date](#11-run-date)
12. [Re-running](#12-re-running)

## 1. Purpose and the claim it replaces

The [source readme](../../../5250_Subfile/README.md) reports that its author tried the search program on PUB400.COM with 1 million records and saw "no discernable performance hit" [5250_Subfile/README.md:42]. It records no method, environment or timing. The schema behind the claim has no index that matches the search's full sort order: PMTCUSTR's cursor `ItemCur` orders by `NAME, CITY, STATE` with `OPTIMIZE FOR 13 ROWS` [5250_Subfile/PMTCUSTR.SQLRPGLE:208-223], while Custmast2.sql creates only the single-column indexes `custmast_name`, `custmast_city` and `custmast_state` [5250_Subfile/Custmast2.sql:26-31]. No composite index serves that `ORDER BY`.

This is readme discrepancy D9 in [Deviations and open questions](../deviations-and-open-questions.md#6-readme-discrepancies). The claim is not carried over. It is re-measured on the target, and this document records the result.

## 2. Design under test

The search is implemented by [`CustomerSearchRepository`](../../backend/customer-api/src/main/java/com/democorp/customermaster/repository/CustomerSearchRepository.java) over the indexes of [`V3__create_custmast.sql`](../../backend/customer-api/src/main/resources/db/migration/V3__create_custmast.sql).

- **Keyset pagination.** Rows are ordered `ORDER BY name, city, state, custid`; `custid` is the unique tiebreaker. Each page after the first adds the row comparison `(name, city, state, custid) > (…)` taken from the opaque cursor. The composite index `custmast_search_keyset (name, city, state, custid)` matches both the order and the comparison. No `OFFSET` is used.
- **Page size.** 12 rows per page, the source's subfile page, fetched with `LIMIT size + 1` (13). The extra row decides whether the page has a `nextCursor`.
- **Prefix and state indexes.** `custmast_name (name varchar_pattern_ops)` and `custmast_city (city varchar_pattern_ops)` turn a literal prefix such as `name LIKE 'UEG%'` into an index range under the ICU column collation. `custmast_state (state)` serves the state filter. The three keep the names and columns of Custmast2.sql.
- **Fenced candidate set.** When the name or city filter has a literal lead, on the first page and on every cursor page alike, the statement puts all its predicates, the keyset position included, in `WITH candidates AS MATERIALIZED (…)`. Inside the fence the planner chooses the access path, for example an index or bitmap scan of `custmast_name` or `custmast_city`; outside it a top-N sort orders the candidates and keeps 13. A search without a literal lead keeps one ordered statement: no filter, a state filter, `includeInactive=true`, a keyset position alone, or a pattern that starts with `%` or `_`.
- **Planning.** Each search runs in a read-only transaction that first issues `SET LOCAL plan_cache_mode = force_custom_plan`, so every execution is planned with its actual prefix and never with a cached generic plan.
- **Statistics.** `ANALYZE custmast` runs after every load.

By design, the unfiltered first page and every page of the unfiltered cursor chain walk `custmast_search_keyset` in order, a cursor page starting at its keyset position through the index condition, and stop once 13 matching entries are found, so page 50 of that chain costs what its page 1 costs. That unfiltered chain is the one `SearchBenchmarkIT` and k6 measure, to page 50.

Filtered pages carry no such promise. With a literal name or city lead, the candidate set holds every row that satisfies all of the page's predicates (on a cursor page, only those after the keyset position), so a page's cost follows the number of candidates, not a fixed 13-entry walk. Without a literal lead, the single ordered statement applies the filters during the walk (for a state filter the planner may read `custmast_state` instead), and the cost depends on the plan chosen and on where the matches fall in the list order. The benchmark measures filtered searches on their first page only. [Section 8](#8-plan-excerpts) shows the plans PostgreSQL actually chose in the measured run.

## 3. Method

### 3.1 Loading

From `customer-master/`:

```sh
docker compose --profile tools run --rm generator --count=1000000
```

- **Start id.** A count above 385,245 takes the automatic start id `AAAA`, so 1,000,000 rows occupy `AAAA..VPV1`.
- **Write.** `CustomerLoader` runs one transaction under an `ACCESS EXCLUSIVE` lock on `custmast`: `TRUNCATE`, a `COPY` streamed through `CustomerCopyWriter`, the id-sequence restart, then the commit. `ANALYZE custmast` follows the commit.
- **Load time.** The generator prints `Loaded <n> customers <first>..<last> in <s> s`. The elapsed time covers reading the city/state/ZIP sample, generating the rows, the load transaction and `ANALYZE`. That printed time is the Compose load time in [section 6](#6-load-time).

`SearchBenchmarkIT` loads its own copy of the data (below) and times the load transaction and `ANALYZE` separately.

### 3.2 SearchBenchmarkIT

- **Class.** [`SearchBenchmarkIT`](../../backend/customer-api/src/test/java/com/democorp/customermaster/benchmark/SearchBenchmarkIT.java), JUnit tag `benchmark`.
- **Command.** From `customer-master/backend`: `./mvnw -B verify -Pbenchmark`. The `benchmark` profile sets the Failsafe tag filter to `benchmark`, so this command runs this IT and no other. A plain `./mvnw -B verify` excludes it.
- **Database.** Its own Testcontainers `postgres:18.6` container, from the shared base class `AbstractPostgresIT`, with Flyway migrations V1–V4 and no seed rows. It needs no Compose stack.
- **Data.** 1,000,000 rows from `AAAA`, produced by `CustomerDataGenerator` with the fixed seed 42 over the bundled sample `classpath:generator/csz-sample.csv`, written by `CustomerLoader` through `CustomerCopyWriter`, then `ANALYZE custmast`.
- **Filter values.** Taken from the loaded rows. The first row at or after the middle id whose name starts with three letters A–Z gives the three-letter name prefix, and its first letter gives the one-letter prefix; the city prefix follows the same rule. The fixed seed makes the same choice on every run.
- **Cursor chain.** Before anything is measured, the class reads the first 600 active rows with the plain statement `SELECT custid, name, city, state FROM custmast WHERE active = 'Y' ORDER BY name, city, state, custid LIMIT :rows` (`rows` = 600), which uses no keyset and no cursor, then walks pages 1 to 50 through the API. Page *k* must hold exactly rows 12(*k* − 1) + 1 to 12*k* of that list, in order, and its `nextCursor` must decode (base64url JSON with exactly `name`, `city`, `state`, `custid` and `served`) to the keys of its last row with `served` = 12*k*. A repeated, skipped or reordered page fails the run before any measurement. The page-49 cursor is the page-50 request of operation 7, its decoded position (row 588, `served` 588) is the keyset of the page-50 plan check, and the first warm-up response of operation 7 must hold rows 589 to 600.
- **Calls.** For each operation, 50 warm-up calls (discarded; the first is checked for correct content), then 200 measured calls, sequentially on one thread, over HTTP Basic as the test user `inq` (role `INQUIRY`) against the random-port server. A sample spans sending the request to reading the whole body, so it includes the security filter chain, JSON rendering and the loopback transfer. Percentiles are nearest-rank.

The eight operations, in report order:

| # | Operation | Request |
|---|-----------|---------|
| 1 | First page, no filter | `GET /api/customers?size=12` |
| 2 | One-letter name prefix | `GET /api/customers?size=12&name=<letter>` |
| 3 | Three-letter name prefix | `GET /api/customers?size=12&name=<three letters>` |
| 4 | Three-letter city prefix | `GET /api/customers?size=12&city=<three letters>` |
| 5 | State filter | `GET /api/customers?size=12&state=CA` |
| 6 | Inactive rows included | `GET /api/customers?size=12&includeInactive=true` |
| 7 | Page 50, reached through the cursor chain | `GET /api/customers?size=12&cursor=<nextCursor of page 49>` |
| 8 | Get by id | `GET /api/customers/{custId}`, a seeded random id per call |

- **Plan check.** `EXPLAIN (FORMAT JSON)` of the exact production statement, built by `CustomerSearchRepository.buildQuery(...)` with `LIMIT 13`, in a read-only transaction that first sets `plan_cache_mode = force_custom_plan`. It covers the no-filter first page, the page-50 keyset position and the three-letter name prefix. None of the three plans may contain a `Seq Scan` on `custmast`.
- **Output.** The class prints a Markdown report and writes it to `target/search-benchmark-results.md` of the `customer-api` module, that is `customer-api/target/search-benchmark-results.md` from `customer-master/backend`. The file is build output and is not committed; the report of the recorded run is copied to [`evidence/search-benchmark-results.md`](evidence/search-benchmark-results.md). After writing the report, the class asserts every threshold with soft assertions, so a failing run still reports every number it measured, and any violation fails the build.

### 3.3 k6

- **Script.** [`perf/k6/search-1m.js`](../../perf/k6/search-1m.js).
- **Command.** From `customer-master/`, after the 1,000,000-row load: `docker compose --profile perf run --rm k6`.
- **Load.** Scenario `search_mix`: a constant arrival rate of 20 requests per second for 2 minutes (20 pre-allocated VUs, at most 100), one request per iteration, rotating the same eight operations in equal shares. One-letter name prefixes rotate through A–Z. The three-letter name and city prefixes, the customer ids and the page-50 cursor are sampled from real rows in `setup()`. The setup requests (session check, 26 one-letter searches, page 1 and 49 cursor hops) count toward the global metrics.
- **Target.** The API directly at `http://app:8080` inside the Compose network, not through nginx, signed in as the inquiry user (`CM_INQUIRY_USER`, default `inquiry`).
- **Output.** Compose passes `--summary-export=/perf/results/search-1m-summary.json`, which is `perf/results/search-1m-summary.json` from `customer-master/` on the host and is git-ignored; the summary of the recorded run is copied to [`evidence/search-1m-summary.json`](evidence/search-1m-summary.json). The per-operation trends `op_first_page` … `op_get_by_id` report latency only and carry no threshold.
- **Network.** The script imports only k6's built-in modules and formats its end-of-test text summary itself, so a run needs network access only to `K6_BASE_URL`.

## 4. Thresholds

| Check | Threshold | Enforced by |
|-------|-----------|-------------|
| Each of the seven search operations (1–7) | p95 ≤ 250 ms | `SearchBenchmarkIT` |
| Get by id (8) | p95 ≤ 50 ms | `SearchBenchmarkIT` |
| Plans of the no-filter first page, page 50 and the three-letter name prefix | No `Seq Scan` on `custmast` | `SearchBenchmarkIT` |
| k6 `http_req_duration` | `p(95) < 300` ms | k6 |
| k6 `http_req_failed` | `rate < 0.01` | k6 |

Latency depends on the host: its CPU, memory, storage and concurrent load. The plan-shape assertions are the hardware-independent evidence, because they show that no checked statement reads `custmast` sequentially, whatever the hardware.

## 5. Environment

All runs used the same host on 2026-10-08 (UTC): first the Compose generator and k6 runs, then `SearchBenchmarkIT`. The host and Compose values come from [`environment-compose.txt`](evidence/environment-compose.txt), captured just before the Compose runs; the benchmark JVM's values come from section "Environment" of [`search-benchmark-results.md`](evidence/search-benchmark-results.md).

| Item | Value |
|------|-------|
| CPU | INTEL(R) XEON(R) PLATINUM 8581C CPU @ 2.10GHz; `lscpu`: 2 sockets × 28 cores × 2 threads, 112 logical CPUs on the host; the Docker server reported NCPU 112 |
| CPUs available to the runs | 12 (`nproc`); the benchmark JVM reported 12 available processors |
| Memory | 2.9 TiB host total (`free -h`; the Docker server reported MemTotal 2,999,665 MiB); 262,144 MiB visible to the benchmark JVM; JVM maximum heap 30,688 MiB |
| Operating system | Ubuntu 25.10 container (`/etc/os-release`) on Linux 6.12.85+ x86_64 (`uname -srmo`); the benchmark JVM reported `Linux amd64` |
| Docker | Client and server 29.7.2, API 1.55 (`docker version`); Compose v5.4.0 |
| Container limits in the Compose run | `app`: memory 1 GiB (`Memory=1073741824`), no CPU limit (`NanoCpus=0`), `/dev/shm` 64 MiB (`ShmSize=67108864`). `db`: memory 3 GiB (`Memory=3221225472`), no CPU limit, `/dev/shm` 128 MiB (`ShmSize=134217728`). From `docker inspect` |
| PostgreSQL settings | `shared_buffers` 128MB, the PostgreSQL default, in both runs |
| Host load | A shared host on which other builds run concurrently. 1-minute load average (`/proc/loadavg`): 4.93 at the environment capture, 3.89 before k6, 10.00 before the benchmark |

Stack versions:

| Component | Version | Source |
|-----------|---------|--------|
| PostgreSQL | `PostgreSQL 18.6 (Debian 18.6-1.pgdg13+2)`, image `postgres:18.6` | Server version reported in both runs; the image tag is pinned in `docker-compose.yml` and `AbstractPostgresIT` |
| JDK | Temurin 21.0.12.1+1 (`21.0.12.1+1-LTS`) | The `app` JVM in `environment-compose.txt`; the benchmark JVM in its report |
| Spring Boot | 3.5.16 | The generator's start-up banner in [`generator-run.txt`](evidence/generator-run.txt) |
| k6 | `grafana/k6:2.3.0` | The image pinned in `docker-compose.yml`; the k6 output does not print its version |

## 6. Load time

| Run | Rows and id range | Measured time | Artifact |
|-----|-------------------|---------------|----------|
| Compose generator | 1,000,000, `AAAA..VPV1` | 17.0 s, covering the sample read, row generation, the load transaction and `ANALYZE` | Printed line `Loaded 1000000 customers AAAA..VPV1 in 17.0 s` in [`generator-run.txt`](evidence/generator-run.txt) |
| `SearchBenchmarkIT` | 1,000,000, `AAAA..VPV1`, seed 42 | Load transaction (`TRUNCATE`, `COPY`, sequence restart, commit) 14269.5 ms, that is 70,080 rows/s; `ANALYZE custmast` 460.3 ms | Section "Data" of [`search-benchmark-results.md`](evidence/search-benchmark-results.md) |

After the Compose load, `select count(*), min(custid), max(custid) from customer_master.custmast` returned `1000000|AAAA|VPV1`, 857143 of the rows were active, and `custmast` with its indexes measured 347 MB (`pg_total_relation_size`). The generator logged `VPV2` as the next interactive id and kept all 200 rows of its city/state/ZIP sample (`read=200 kept=200`). In the benchmark database, `custmast` with its indexes also measured 347 MB, and the sample also kept all 200 rows.

## 7. Results

Measured by `SearchBenchmarkIT`: 200 measured calls per operation after 50 warm-up calls, nearest-rank percentiles, copied from section "Latency" of [`search-benchmark-results.md`](evidence/search-benchmark-results.md). The last column names the k6 trend of the same operation, whose figures under concurrent load are in [section 9](#9-k6-summary).

| Operation | p50 (ms) | p95 (ms) | max (ms) | Threshold | Verdict | k6 metric |
|-----------|---------:|---------:|---------:|-----------|---------|-----------|
| 1. First page, no filter | 3.7 | 4.5 | 6.5 | p95 ≤ 250 ms | Pass | `op_first_page` |
| 2. One-letter name prefix (`U`) | 50.8 | 60.3 | 63.6 | p95 ≤ 250 ms | Pass | `op_name_prefix_1` |
| 3. Three-letter name prefix (`UEG`) | 3.1 | 3.5 | 7.6 | p95 ≤ 250 ms | Pass | `op_name_prefix_3` |
| 4. Three-letter city prefix (`HAR`) | 12.4 | 13.6 | 14.9 | p95 ≤ 250 ms | Pass | `op_city_prefix_3` |
| 5. `state=CA` | 2.7 | 3.1 | 3.4 | p95 ≤ 250 ms | Pass | `op_state_ca` |
| 6. `includeInactive=true` | 2.6 | 3.1 | 3.3 | p95 ≤ 250 ms | Pass | `op_include_inactive` |
| 7. Page 50 through the cursor chain | 2.7 | 3.0 | 4.9 | p95 ≤ 250 ms | Pass | `op_deep_page` |
| 8. `GET /api/customers/{custId}` | 2.9 | 3.4 | 4.1 | p95 ≤ 50 ms | Pass | `op_get_by_id` |

The filter values `SearchBenchmarkIT` chose (from the first qualifying row at or after id `KZ26`), and the active rows each one matches, from section "Filters" of the report:

| Filter | Value | Matching active rows |
|--------|-------|---------------------:|
| None (first page, page 50) | — | 857,143 |
| Name prefix, one letter | `U` | 31,997 |
| Name prefix, three letters | `UEG` | 135 |
| City prefix, three letters | `HAR` | 8,769 |
| State | `CA` | 73,342 |
| Inactive rows included | `true` | 1,000,000 (all rows) |

The cursor chain matched the independent list on all 50 pages (section "Cursor chain" of the report). Page 49 ended at row 588, `MP2D` (`AADWDF EWAXKZGDHE IIMIOE QFNEAUBCGE PART`, `SPRINGFIELD`, `IL`); the page-50 cursor carried those keys with `served` 588; page 50 held rows 589 (`PCX5`) to 600 (`ANQV`).

The two slowest searches are the name and city prefixes with the most matches, which is consistent with the design: the fenced candidate set holds every matching row (31,997 for name `U`, 8,769 for city `HAR`) before the top-N sort keeps 13. Page 50 of the unfiltered chain measured no slower than the unfiltered first page (p95 3.0 ms against 4.5 ms). No filtered cursor page was measured.

## 8. Plan excerpts

Captured by `SearchBenchmarkIT` from `EXPLAIN (FORMAT JSON)` of the production statement with `LIMIT 13` and `plan_cache_mode = force_custom_plan`, and copied from section "Plans" of [`search-benchmark-results.md`](evidence/search-benchmark-results.md). Each line is one plan node, indented by depth: node type, index, relation, `Index Cond`, `Filter` and `Sort Key`. All three plans pass the check; none contains a `Seq Scan` on `custmast`.

No-filter first page:

```text
Limit
  Index Scan using custmast_search_keyset on custmast Filter: (active = 'Y'::bpchar)
```

Page 50, keyset position decoded from the page-50 cursor: row 588, the last row of page 49:

```text
Limit
  Index Scan using custmast_search_keyset on custmast Index Cond: (ROW((name)::text, (city)::text, state, custid) > ROW('AADWDF EWAXKZGDHE IIMIOE QFNEAUBCGE PART'::text, 'SPRINGFIELD'::text, 'IL'::character(2), 'MP2D'::character(4))) Filter: (active = 'Y'::bpchar)
```

Three-letter name prefix `UEG`:

```text
Limit
  [CTE candidates] Index Scan using custmast_name on custmast Index Cond: (((name)::text ~>=~ 'UEG'::text) AND ((name)::text ~<~ 'UEH'::text)) Filter: (((name)::text ~~ 'UEG%'::text) AND (active = 'Y'::bpchar))
  Sort Sort Key: candidates.name COLLATE customer_sort, candidates.city COLLATE customer_sort, candidates.state COLLATE customer_sort, candidates.custid COLLATE customer_sort
    CTE Scan on candidates
```

The first two plans walk `custmast_search_keyset`, the second starting at the keyset position through its `Index Cond`. The third reads the `UEG` range of `custmast_name` into the candidate set, then sorts it. No plan of a filtered cursor page is captured.

## 9. k6 summary

Measured by the Compose `k6` service (profile `perf`, see [section 3.3](#33-k6)) against the 1,000,000 rows the Compose generator loaded. The scenario ran 2m0s at 20.00 iterations per second with 2,401 iterations completed and none interrupted, and k6 exited with status 0 ([`k6-run.txt`](evidence/k6-run.txt)). The figures are the values in [`search-1m-summary.json`](evidence/search-1m-summary.json), cut to two decimals, which is how k6's end-of-test summary in `k6-run.txt` prints them. The verdicts rest on that summary's ✓ mark for each threshold and on the exit status 0; in the export, both threshold entries of this passing run read `false`, and `http_req_failed` reads `value` 0 with `passes` 0 and `fails` 2478, the console's `✓ 0 ✗ 2478`: no request failed.

| Metric | Measured | Threshold | Verdict |
|--------|----------|-----------|---------|
| `http_req_duration` p(95) | 68.35 ms (median 2.99 ms, max 135.15 ms) | `p(95) < 300` ms | Pass |
| `http_req_failed` rate | 0.00% (0 of 2,478 requests) | `rate < 0.01` | Pass |
| `http_reqs` | 2,478 (20.29 per second): 2,401 load iterations plus the 77 setup requests (2,478 − 2,401) | — | — |
| `checks` | 4,802 passed, 0 failed | — | — |

Per-operation trends (reporting only, no thresholds):

| k6 metric | Requests | Median (ms) | p95 (ms) | max (ms) |
|-----------|---------:|------------:|---------:|---------:|
| `op_first_page` | 301 | 2.56 | 3.58 | 9.38 |
| `op_name_prefix_1` | 300 | 66.05 | 91.02 | 116.95 |
| `op_name_prefix_3` | 300 | 3.03 | 4.18 | 8.63 |
| `op_city_prefix_3` | 300 | 14.97 | 42.16 | 70.39 |
| `op_state_ca` | 300 | 2.67 | 3.69 | 6.53 |
| `op_include_inactive` | 300 | 2.57 | 3.54 | 4.86 |
| `op_deep_page` | 300 | 2.77 | 3.88 | 5.96 |
| `op_get_by_id` | 300 | 3.00 | 4.37 | 62.61 |

One request in eight is a one-letter name prefix, the slowest operation, so the global p95 falls inside that operation's distribution.

## 10. Verdict

| Threshold | Measured | Verdict |
|-----------|----------|---------|
| p95 ≤ 250 ms for each of the seven search operations | Highest p95 60.3 ms (one-letter name prefix) | Pass |
| p95 ≤ 50 ms for get by id | 3.4 ms | Pass |
| No `Seq Scan` on `custmast` in the three checked plans | None in the first-page, page-50 and three-letter-prefix plans | Pass |
| k6 `http_req_duration p(95) < 300` ms | 68.35 ms | Pass |
| k6 `http_req_failed rate < 0.01` | 0.00% | Pass |

Every threshold was met on the host in [section 5](#5-environment). The thresholds stay enforced on every run: under `-Pbenchmark`, `SearchBenchmarkIT` fails the build when one is missed, and k6 exits non-zero when a threshold is crossed. A run on other hardware must be recorded afresh. These are measurements of the target only. The IBM i program was not run, so no comparison with the source's own performance is claimed.

## 11. Run date

All measurements ran on 2026-10-08 (UTC). The Compose runs used commit `bbe857b` with no tracked change. `SearchBenchmarkIT` ran on the production code of `bbe857b` with `SearchBenchmarkIT.java` blob `a1f3e6e` and no other change in the backend tree. [`benchmark-maven-run.txt`](evidence/benchmark-maven-run.txt) records that tree as commit `4125b2f`, a local commit that is not in the published history: its parent is `bbe857b`, and its only change is that version of `SearchBenchmarkIT.java`. Both runs therefore measured the same production code, that of `bbe857b`. The start and finish times are the UTC timestamps the evidence files record around each command; the report's run start comes from the benchmark's own clock.

The `SearchBenchmarkIT.java` shipped here differs from the measured blob `a1f3e6e` only in comments. From the repository root, `git hash-object customer-master/backend/customer-api/src/test/java/com/democorp/customermaster/benchmark/SearchBenchmarkIT.java` prints `0ed7e4789566a0a0ddb70a4398f14cb0ed8429e4` for the shipped version, and every line that `git show a1f3e6e | diff - customer-master/backend/customer-api/src/test/java/com/democorp/customermaster/benchmark/SearchBenchmarkIT.java` reports added or removed is blank or part of a comment. The shipped `perf/k6/search-1m.js` differs from its measured version in `bbe857b` only in its comments and in that the remote `jslib.k6.io` k6-summary import is replaced by a local formatter that prints the same end-of-test text summary: in `git diff bbe857b -- customer-master/perf/k6/search-1m.js`, its options, scenario, setup, operations, checks, thresholds and `handleSummary` outputs are unchanged.

From the repository root, `git diff --stat bbe857b -- customer-master/backend/address-validation/src/main customer-master/backend/customer-api/src/main` lists the production code changed since `bbe857b`: 71 files. Seven change behaviour: four only in log output, `SecurityConfig` in authentication, and `AddressStandardizationService` with the new `UspsTextRedactor` in the text of the DEM9898 address error. `UspsWebToolsAddressValidationClient` now logs the error `Number` and `Description`, on its INFO line for an address-level USPS error and on its WARN line for a rejected response that holds a USPS `Error`, as a projection of what the service sent: request echoes, credentials, the address values the call submitted and URLs become markers, and every other stretch of words becomes a marker that gives its length, except a documented USPS sentence and a `Number` that is an error code. It also masks a credential in more of its encodings, in the fault reasons it logs and in the messages of the exceptions it builds, which `customer-api` only logs; its results are unchanged. `RedactedThrowable`, the value-free copy of an exception that is logged in place of the exception, moved from `controller/` to `config/` of `customer-api` (`git diff -M` reports the rename with 94% similarity), and the class and its `of` method became `public`; it copies an exception as before, and its copies now show the class name `com.democorp.customermaster.config.RedactedThrowable` in a log. `CustomerGeneratorRunner` now passes `RedactedThrowable.of(failure)` instead of the exception to its two ERROR lines, and `GeneratorStartupFailureReporter` does the same for its two DEBUG lines, the first of which now logs the same one-line summary it prints; all four lines are on failure paths, and the exit statuses and printed lines are unchanged. `SecurityConfig` of `customer-api` installs its new nested `StrictBasicAuthenticationConverter` on the HTTP Basic filter through `withObjectPostProcessor`; its other changes are the imports the converter and its wiring need, and comments. For a request whose `Authorization` header, trimmed, starts with `Basic` in any case, the converter rejects the request as bad credentials, a 401 `APP0401`, when the character after `Basic` is not a space; otherwise it has Spring Security's `BasicAuthenticationConverter` decode the header, as before, and rejects the request in the same way when the decoded password is longer than `UsersProperties.MAX_PASSWORD_BYTES`, 72 UTF-8 bytes, the most BCrypt verifies. A request with no `Authorization` header or another scheme stays anonymous, as before. The new `UspsTextRedactor` of `address-validation`, which the client now uses in place of its own redaction and masking, replaces each request echo it recognizes, the configured base URL included, with a marker, and masks each configured USPS credential, written literally or URL-encoded, wholly or in part. `AddressStandardizationService` of `customer-api` builds one `UspsTextRedactor` from the bound USPS settings in its constructor, and the DEM9898 it raises when the address client returns an address error now takes the description as that redactor returns it, so the 422's `args`, `detail` and four field messages carry the description with its request echoes and credentials replaced; other text is kept as the service sent it, and an absent description still reads as empty. When a credential shorter than four characters occurs in the description, a documented USPS description is kept as sent and any other becomes `[description withheld]`. Three files only gain the import of the moved class: `ApiExceptionHandler` and `ProblemErrorController`, which otherwise changed only in comments, and `ErrorDispatchFilter`, which changed in nothing else. The new `controller/dto/package-info.java` of `customer-api` adds the package's documentation and its `package` declaration, with no annotation, and compiles to an empty `package-info` class. `UspsXmlCodec` of `address-validation` now selects the response elements with XPath over the same hardened DOM, each step matching the element name exactly as written, in place of walking child elements; its other changes are the imports the XPath reader needs, and comments, and it accepts and rejects the same responses with the same results and fault messages, so it changes no behaviour. The other 59 files changed only in comments: the other seven Java sources of `address-validation`, `AddressServiceUnavailableException`, `AddressValidationAutoConfiguration`, `AddressValidationClient`, `AddressValidationProperties`, `AddressValidationRequest`, `AddressValidationResult` and `StubAddressValidationClient`; 50 Java sources of `customer-api` in `config/`, `controller/`, `controller/dto/`, `domain/`, `generator/`, `messages/`, `repository/`, `security/`, `service/` and `service/exception/`, the seven in `generator/` being `CszSource`, `CustomerCopyWriter`, `CustomerDataGenerator`, `CustomerLoader`, `GeneratorProperties`, `LoadCheckpoints` and `NameGenerator`, and the three in `security/` being `CurrentUser`, `Role` and `UsersProperties`; `application.yml`, which parses to the same configuration; and `V3__create_custmast.sql`, whose SQL statements are unchanged.

Of the seven behaviour changes, only the new converter in `SecurityConfig` lies on the request paths the measured runs took. `UspsWebToolsAddressValidationClient` and the DEM9898 change of `AddressStandardizationService`, the only code that has a `UspsTextRedactor` redact or mask text, are reached only through address standardization, which runs only when a customer is reviewed before a save, and so is the XPath reader of `UspsXmlCodec`, which only `UspsWebToolsAddressValidationClient` calls; neither measured run reviewed a customer, as both sent only `GET` requests, so neither ran any of this code. The API logs a `RedactedThrowable` copy only while it handles a failed request, and every request of the k6 run and of `SearchBenchmarkIT` succeeded: `SearchBenchmarkIT` requires status 200 for every call and passed, and k6 counted no failed request (`http_req_failed` 0.00%). The changed generator lines run only when the generator fails to start or a run fails; the Compose load of 1,000,000 rows exited 0 after printing its `Loaded` line, so it took the runner's success path, whose code and printed output are unchanged. `SearchBenchmarkIT` loads its rows through `CszSource`, `NameGenerator`, `CustomerDataGenerator` and `CustomerLoader`, which changed only in comments. The measured code read every Basic header with Spring Security's `BasicAuthenticationConverter` alone, so neither the k6 run nor `SearchBenchmarkIT` ran the strict Basic check. The shipped code runs it on every request that carries a Basic header, and every request of both runs carries one. Both clients' headers pass it unchanged. k6 builds its header as `` 'Basic ' + encoding.b64encode(`${USER}:${PASSWORD}`) ``, and `SearchBenchmarkIT` sets it through `AbstractPostgresIT` with `headers.setBasicAuth(username, password, StandardCharsets.UTF_8)`; each is `Basic`, one space and the Base64 token. Each client sends the password of a configured user, and the application already refused to start with a configured password longer than 72 UTF-8 bytes: `SearchBenchmarkIT` sends `inq-pw`, and k6 sends `K6_PASSWORD`, which Compose takes from `CM_INQUIRY_PASSWORD` (default `inquiry-demo`) unless it is set. The shipped code therefore authenticates both clients as the measured code did, through the same Spring decoding and BCrypt comparison, and adds to each of their requests a second read of the header, with a test of its scheme and of the character after it, and a UTF-8 encoding of the decoded password. That added cost was not measured, and no number in this document includes it. At startup, besides installing that converter, the shipped code runs the changed constructor of `AddressStandardizationService`, which each application context calls once when it creates the service, before the API serves a request; it now also builds a `UspsTextRedactor` from the bound USPS settings. The measured code built none, and the cost of building it was not measured.

| Run | Commit | Started (UTC) | Outcome | Evidence |
|-----|--------|---------------|---------|----------|
| Environment capture before the Compose runs | `bbe857b` | 2026-10-08T14:47:30Z | Captured | [`environment-compose.txt`](evidence/environment-compose.txt) |
| `docker compose --profile tools run --rm generator --count=1000000` from `customer-master/` | `bbe857b` | 2026-10-08T14:47:38Z | Exit 0, finished 2026-10-08T14:47:58Z; `Loaded 1000000 customers AAAA..VPV1 in 17.0 s` | [`generator-run.txt`](evidence/generator-run.txt) |
| `docker compose --profile perf run --rm k6` from `customer-master/` | `bbe857b` | 2026-10-08T14:48:10Z | Exit 0, finished 2026-10-08T14:50:14Z; both thresholds passed | [`k6-run.txt`](evidence/k6-run.txt), [`search-1m-summary.json`](evidence/search-1m-summary.json) |
| `./mvnw -B -ntp verify -Pbenchmark` from `customer-master/backend` (`SearchBenchmarkIT`) | `bbe857b` production code with `SearchBenchmarkIT.java` blob `a1f3e6e`; the evidence records it as local commit `4125b2f` | 2026-10-08T14:59:33Z; the report records its run start as 2026-10-08T15:00:04Z | Exit 0, BUILD SUCCESS, finished 2026-10-08T15:00:41Z; Failsafe: 1 test, 0 failures, 0 errors, 0 skipped, 41.90 s | [`benchmark-maven-run.txt`](evidence/benchmark-maven-run.txt), [`failsafe-summary.xml`](evidence/failsafe-summary.xml), [`search-benchmark-results.md`](evidence/search-benchmark-results.md) |

## 12. Re-running

From `customer-master/`, in this order:

1. `docker compose up --build -d --wait`
2. `docker compose --profile tools run --rm generator --count=1000000`. The load replaces the seed rows, so run the e2e flows before this step.
3. `docker compose --profile perf run --rm k6`, then read `perf/results/search-1m-summary.json`. The 1,000,000-row load of step 2 is the operator's prerequisite for a run worth recording; the script does not count rows. Its `setup()` follows the unfiltered `nextCursor` chain from page 1 to page 50 and aborts with "Data set too small: …" when the chain ends before page 50, when page 50 comes back empty, or when one of its sampled pools is empty: the three-letter name prefixes and city prefixes, taken from the first page of each one-letter name search, and the customer ids, taken from those pages and page 50. The 300 seed rows (226 active) end the chain on page 19, so a run on the seed data aborts. Any data set with at least 589 active rows, enough for one row on page 50, and with sampled names and cities that start with three letters A–Z passes the guard.
4. From `customer-master/backend`: `./mvnw -B verify -Pbenchmark`, then read `customer-api/target/search-benchmark-results.md` (relative to `customer-master/backend`). This step uses its own database and does not need the stack.
5. Back in `customer-master/`: `docker compose down -v` removes the stack and its data.

Then replace the files in [`evidence/`](evidence/) with the new run's artifacts: raw copies of `search-benchmark-results.md`, `failsafe-summary.xml` and `search-1m-summary.json`, and marked excerpts of the Maven, generator and k6 output and of the environment capture, each with its command, commit, UTC start and finish and exit status. Update [section 11](#11-run-date) to list those runs, and record only numbers taken from those files. A cell whose measurement could not run reads `Not measured: <reason>`.

Related documents: [README](../../README.md), [developer guide](../developer-guide.md), [deviations and open questions](../deviations-and-open-questions.md) and [traceability matrix](../traceability-matrix.md).

