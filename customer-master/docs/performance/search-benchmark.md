# Search benchmark: 1,000,000 customers

This is the benchmark record for the customer search. It holds the measured evidence that the target search stays fast with 1,000,000 rows in `custmast`. Every number below is copied from an artifact of the runs listed under [Run date](#11-run-date): the `SearchBenchmarkIT` report, the generator's output line and the k6 summary. Nothing is estimated or extrapolated.

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
- **Fenced candidate set.** When the name or city filter has a literal lead, the statement selects the matching rows in `WITH candidates AS MATERIALIZED (…)`, which the prefix index can serve, then sorts and limits the page outside it. A search without a literal lead keeps one ordered statement.
- **Planning.** Each search runs in a read-only transaction that first issues `SET LOCAL plan_cache_mode = force_custom_plan`, so every execution is planned with its actual prefix and never with a cached generic plan.
- **Statistics.** `ANALYZE custmast` runs after every load.

By design, the no-filter first page and every deep page walk `custmast_search_keyset` in order and stop once 13 matching entries are found, so page 50 costs what page 1 costs. [Section 8](#8-plan-excerpts) shows the plans PostgreSQL actually chose in the measured run.

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
- **Output.** The class prints a Markdown report and writes it to `backend/customer-api/target/search-benchmark-results.md`, which is build output and not committed. It then asserts every threshold with soft assertions, so a failing run still reports every number it measured, and any violation fails the build.

### 3.3 k6

- **Script.** [`perf/k6/search-1m.js`](../../perf/k6/search-1m.js).
- **Command.** From `customer-master/`, after the 1,000,000-row load: `docker compose --profile perf run --rm k6`.
- **Load.** Scenario `search_mix`: a constant arrival rate of 20 requests per second for 2 minutes (20 pre-allocated VUs, at most 100), one request per iteration, rotating the same eight operations in equal shares. One-letter name prefixes rotate through A–Z. The three-letter name and city prefixes, the customer ids and the page-50 cursor are sampled from real rows in `setup()`. The setup requests (session check, 26 one-letter searches, page 1 and 49 cursor hops) count toward the global metrics.
- **Target.** The API directly at `http://app:8080` inside the Compose network, not through nginx, signed in as the inquiry user (`CM_INQUIRY_USER`, default `inquiry`).
- **Output.** Compose passes `--summary-export=/perf/results/search-1m-summary.json`, which is `perf/results/search-1m-summary.json` on the host and is git-ignored. The per-operation trends `op_first_page` … `op_get_by_id` report latency only and carry no threshold.
- **Network.** The script imports its text summary from `jslib.k6.io` when k6 starts, so the container needs outbound HTTPS.

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

Both runs used the same host on the same day, one after the other.

| Item | Value |
|------|-------|
| CPU | INTEL(R) XEON(R) PLATINUM 8581C CPU @ 2.10GHz; `lscpu`: 2 sockets × 28 cores × 2 threads, 112 logical CPUs on the host |
| CPUs available to the runs | 12 (`nproc`; the benchmark JVM also reported 12 available processors) |
| Memory | 2.9 TiB host total (`free -h`); 262,144 MiB visible to the benchmark JVM; JVM maximum heap 30,688 MiB |
| Operating system | Ubuntu 25.10 container on Linux 6.12.85+ x86_64 (`uname -a`) |
| Docker | Server 29.7.2 (`docker version`), Compose 5.4.0 |
| Container limits in the Compose run | None on `app` or `db`; `db` has `shm_size` 128 MB |
| PostgreSQL settings | `shared_buffers` 128MB, the PostgreSQL default, in both runs |
| Host load | A shared host on which other builds run concurrently; 1-minute load average 1.89 before the benchmark and 2.87 before the Compose run (`/proc/loadavg`) |

Fixed stack versions:

| Component | Version |
|-----------|---------|
| PostgreSQL | Image `postgres:18.6`; the server reported `PostgreSQL 18.6 (Debian 18.6-1.pgdg13+2)` |
| JDK | `eclipse-temurin` 21.0.12.1_1 (the Compose `app` image is `21.0.12.1_1-jre-noble`); the benchmark JVM reported `21.0.12.1+1-LTS` |
| Spring Boot | 3.5.16 |
| k6 | `grafana/k6:2.3.0` |

## 6. Load time

| Run | Rows and id range | Measured time | Artifact |
|-----|-------------------|---------------|----------|
| Compose generator | 1,000,000, `AAAA..VPV1` | 16.8 s, covering the sample read, row generation, the load transaction and `ANALYZE` | Printed line `Loaded 1000000 customers AAAA..VPV1 in 16.8 s` |
| `SearchBenchmarkIT` | 1,000,000, `AAAA..VPV1`, seed 42 | Load transaction (`TRUNCATE`, `COPY`, sequence restart, commit) 14,635.5 ms, that is 68,327 rows/s; `ANALYZE custmast` 476.1 ms | `search-benchmark-results.md`, section "Data" |

After the Compose load, `select count(*), min(custid), max(custid) from customer_master.custmast` returned `1000000|AAAA|VPV1`, and the generator logged `VPV2` as the next interactive id. In the benchmark database, `custmast` with its indexes measured 347 MB (`pg_total_relation_size`). The sample kept all 200 of its city/state/ZIP rows.

## 7. Results

Measured by `SearchBenchmarkIT`: 200 measured calls per operation after 50 warm-up calls, nearest-rank percentiles. The last column names the k6 trend of the same operation, whose figures under concurrent load are in [section 9](#9-k6-summary).

| Operation | p50 (ms) | p95 (ms) | max (ms) | Threshold | Verdict | k6 metric |
|-----------|---------:|---------:|---------:|-----------|---------|-----------|
| 1. First page, no filter | 3.8 | 4.3 | 6.4 | p95 ≤ 250 ms | Pass | `op_first_page` |
| 2. One-letter name prefix (`U`) | 50.4 | 56.2 | 60.8 | p95 ≤ 250 ms | Pass | `op_name_prefix_1` |
| 3. Three-letter name prefix (`UEG`) | 3.0 | 3.4 | 7.3 | p95 ≤ 250 ms | Pass | `op_name_prefix_3` |
| 4. Three-letter city prefix (`HAR`) | 11.5 | 12.8 | 15.8 | p95 ≤ 250 ms | Pass | `op_city_prefix_3` |
| 5. `state=CA` | 2.6 | 3.2 | 3.4 | p95 ≤ 250 ms | Pass | `op_state_ca` |
| 6. `includeInactive=true` | 2.5 | 3.1 | 3.9 | p95 ≤ 250 ms | Pass | `op_include_inactive` |
| 7. Page 50 through the cursor chain | 2.5 | 3.0 | 3.5 | p95 ≤ 250 ms | Pass | `op_deep_page` |
| 8. `GET /api/customers/{custId}` | 2.7 | 3.2 | 3.9 | p95 ≤ 50 ms | Pass | `op_get_by_id` |

The filter values `SearchBenchmarkIT` chose (from the first qualifying row at or after id `KZ26`), and the active rows each one matches:

| Filter | Value | Matching active rows |
|--------|-------|---------------------:|
| None (first page, page 50) | — | 857,143 |
| Name prefix, one letter | `U` | 31,997 |
| Name prefix, three letters | `UEG` | 135 |
| City prefix, three letters | `HAR` | 8,769 |
| State | `CA` | 73,342 |
| Inactive rows included | `true` | 1,000,000 (all rows) |

The two slowest searches are the name and city prefixes with the most matches, which is consistent with the design: the fenced candidate set holds every matching row (31,997 for name `U`, 8,769 for city `HAR`) before the top-N sort keeps 13. Page 50 measured no slower than the first page (p95 3.0 ms against 4.3 ms).

## 8. Plan excerpts

Captured by `SearchBenchmarkIT` from `EXPLAIN (FORMAT JSON)` of the production statement with `LIMIT 13` and `plan_cache_mode = force_custom_plan`. Each line is one plan node, indented by depth: node type, index, relation, `Index Cond`, `Filter` and `Sort Key`. All three plans pass the check; none contains a `Seq Scan` on `custmast`.

No-filter first page:

```text
Limit
  Index Scan using custmast_search_keyset on custmast Filter: (active = 'Y'::bpchar)
```

Page 50, keyset position after the last row of page 49:

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

The first two plans walk `custmast_search_keyset`, the second starting at the keyset position through its `Index Cond`. The third reads the `UEG` range of `custmast_name` into the candidate set, then sorts it.

## 9. k6 summary

Measured by `docker compose --profile perf run --rm k6` against the 1,000,000 rows the Compose generator loaded. The scenario ran 2m0s at 20.00 iterations per second with 2,401 iterations completed and none interrupted, and k6 exited with status 0. The figures are the values in `perf/results/search-1m-summary.json`, rounded to two decimals.

| Metric | Measured | Threshold | Verdict |
|--------|----------|-----------|---------|
| `http_req_duration` p(95) | 63.84 ms (median 3.07 ms, max 125.15 ms) | `p(95) < 300` ms | Pass |
| `http_req_failed` rate | 0.00% (0 of 2,478 requests) | `rate < 0.01` | Pass |
| `http_reqs` | 2,478 (20.32 per second): 2,401 load iterations plus 77 setup requests | — | — |
| `checks` | 4,802 passed, 0 failed | — | — |

Per-operation trends (reporting only, no thresholds):

| k6 metric | Requests | Median (ms) | p95 (ms) | max (ms) |
|-----------|---------:|------------:|---------:|---------:|
| `op_first_page` | 301 | 2.60 | 3.70 | 4.95 |
| `op_name_prefix_1` | 300 | 61.22 | 87.68 | 116.30 |
| `op_name_prefix_3` | 300 | 3.08 | 4.24 | 8.72 |
| `op_city_prefix_3` | 300 | 13.87 | 36.05 | 65.93 |
| `op_state_ca` | 300 | 2.68 | 3.73 | 6.17 |
| `op_include_inactive` | 300 | 2.57 | 3.53 | 4.57 |
| `op_deep_page` | 300 | 2.77 | 3.77 | 5.30 |
| `op_get_by_id` | 300 | 3.18 | 4.32 | 58.19 |

One request in eight is a one-letter name prefix, the slowest operation, so the global p95 falls inside that operation's distribution.

## 10. Verdict

| Threshold | Measured | Verdict |
|-----------|----------|---------|
| p95 ≤ 250 ms for each of the seven search operations | Highest p95 56.2 ms (one-letter name prefix) | Pass |
| p95 ≤ 50 ms for get by id | 3.2 ms | Pass |
| No `Seq Scan` on `custmast` in the three checked plans | None in the first-page, page-50 and three-letter-prefix plans | Pass |
| k6 `http_req_duration p(95) < 300` ms | 63.84 ms | Pass |
| k6 `http_req_failed rate < 0.01` | 0.00% | Pass |

Every threshold was met on the host in [section 5](#5-environment). The thresholds stay enforced on every run: under `-Pbenchmark`, `SearchBenchmarkIT` fails the build when one is missed, and k6 exits non-zero when a threshold is crossed. A run on other hardware must be recorded afresh. These are measurements of the target only. The IBM i program was not run, so no comparison with the source's own performance is claimed.

## 11. Run date

All measurements ran on 2026-10-08 (UTC), on commit `b4adaf9` of the working branch.

| Run | Started (UTC) | Outcome |
|-----|---------------|---------|
| `./mvnw -B -ntp verify -Pbenchmark` (`SearchBenchmarkIT`) | 2026-10-08T07:52:45Z | BUILD SUCCESS; Failsafe: 1 test, 0 failures, 0 errors, 41.67 s |
| `docker compose --profile tools run --rm generator --count=1000000` | 2026-10-08T07:54:19Z | Exit 0 |
| `docker compose --profile perf run --rm k6` | 2026-10-08T07:54:44Z | Exit 0 |

## 12. Re-running

From `customer-master/`, in this order:

1. `docker compose up --build -d --wait`
2. `docker compose --profile tools run --rm generator --count=1000000`. The load replaces the seed rows, so run the e2e flows before this step.
3. `docker compose --profile perf run --rm k6`, then read `perf/results/search-1m-summary.json`. Without the 1,000,000-row load, the script's setup aborts with "Data set too small".
4. From `backend/`: `./mvnw -B verify -Pbenchmark`, then read `backend/customer-api/target/search-benchmark-results.md`. This step uses its own database and does not need the stack.
5. `docker compose down -v` removes the stack and its data.

Record only numbers taken from these artifacts. A cell whose measurement could not run reads `Not measured: <reason>`.

Related documents: [README](../../README.md), [developer guide](../developer-guide.md), [deviations and open questions](../deviations-and-open-questions.md) and [traceability matrix](../traceability-matrix.md).

