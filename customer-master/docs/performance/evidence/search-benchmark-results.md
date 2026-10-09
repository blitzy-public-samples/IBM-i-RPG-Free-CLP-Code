# Customer search benchmark: 1,000,000 customers

Run started 2026-10-08T15:00:04Z (UTC).

## Environment

| Item | Value |
|---|---|
| Java runtime | 21.0.12.1+1-LTS |
| Processors available to the JVM | 12 |
| JVM max heap | 30,688 MiB |
| Operating system | Linux amd64 |
| Host total memory | 262,144 MiB |
| Docker server | version 29.7.2, NCPU 112, MemTotal 2,999,665 MiB |
| PostgreSQL | PostgreSQL 18.6 (Debian 18.6-1.pgdg13+2) on x86_64-pc-linux-gnu, compiled by gcc (Debian 14.2.0-19) 14.2.0, 64-bit |
| shared_buffers | 128MB |

## Data

| Item | Value |
|---|---|
| Seed | 42 |
| Rows | 1,000,000 |
| Id range | AAAA..VPV1 |
| CSZ rows retained | 200 from classpath:generator/csz-sample.csv |
| Load (TRUNCATE, COPY, sequence restart, commit) | 14269.5 ms |
| ANALYZE custmast | 460.3 ms |
| Load rate | 70,080 rows/s |
| custmast total size (table and indexes) | 347 MB |

## Filters

Chosen from the first row at or after id KZ26 whose value starts with three letters A to Z.

| Filter | Value | Matching active rows |
|---|---|---|
| none (first page, deep page) | - | 857,143 |
| name prefix, 1 letter | U | 31,997 |
| name prefix, 3 letters | UEG | 135 |
| city prefix, 3 letters | HAR | 8,769 |
| state | CA | 73,342 |
| include inactive | true | 1,000,000 (all rows) |

## Cursor chain

Pages 1 to 50 (12 rows each) matched, in order, rows 1 to 600 of the independent list `SELECT custid, name, city, state FROM custmast WHERE active = 'Y' ORDER BY name, city, state, custid LIMIT :rows` with rows = 600. Each page's nextCursor carried the keys of its last row and served = 12 x page.

| Item | Value |
|---|---|
| Page 49 boundary row (row 588): custid | MP2D |
| Page 49 boundary row (row 588): name | AADWDF EWAXKZGDHE IIMIOE QFNEAUBCGE PART |
| Page 49 boundary row (row 588): city | SPRINGFIELD |
| Page 49 boundary row (row 588): state | IL |
| served carried by the page-50 cursor | 588 |
| Page 50 first custid (row 589) | PCX5 |
| Page 50 last custid (row 600) | ANQV |

## Latency

50 warm-up calls (discarded) and 200 measured calls per operation, sequential on one thread, HTTP Basic as user inq (role INQUIRY), page size 12. Percentiles are nearest-rank.

| Operation | Request | Calls | p50 ms | p95 ms | max ms | p95 threshold ms | Verdict |
|---|---|---:|---:|---:|---:|---:|---|
| first-page | GET /api/customers?size=12 | 200 | 3.7 | 4.5 | 6.5 | 250 | PASS |
| name-prefix-1 | GET /api/customers?size=12&name=U | 200 | 50.8 | 60.3 | 63.6 | 250 | PASS |
| name-prefix-3 | GET /api/customers?size=12&name=UEG | 200 | 3.1 | 3.5 | 7.6 | 250 | PASS |
| city-prefix-3 | GET /api/customers?size=12&city=HAR | 200 | 12.4 | 13.6 | 14.9 | 250 | PASS |
| state-CA | GET /api/customers?size=12&state=CA | 200 | 2.7 | 3.1 | 3.4 | 250 | PASS |
| include-inactive | GET /api/customers?size=12&includeInactive=true | 200 | 2.6 | 3.1 | 3.3 | 250 | PASS |
| page-50 | GET /api/customers?size=12&cursor=eyJuYW1lIjoiQUFEV0RGIEVXQVhLWkdESEUgSUlNSU9FIFFGTkVBVUJDR0UgUEFSVCIsImNpdHkiOiJTUFJJTkdGSUVMRCIsInN0YXRlIjoiSUwiLCJjdXN0aWQiOiJNUDJEIiwic2VydmVkIjo1ODh9 | 200 | 2.7 | 3.0 | 4.9 | 250 | PASS |
| get-by-id | GET /api/customers/{custId}, a seeded random id per call | 200 | 2.9 | 3.4 | 4.1 | 50 | PASS |

## Plans

EXPLAIN (FORMAT JSON) of the production statement, LIMIT 13, plan_cache_mode = force_custom_plan. PASS means no Seq Scan on custmast.

### first-page: PASS

```
Limit
  Index Scan using custmast_search_keyset on custmast Filter: (active = 'Y'::bpchar)
```

### page-50: PASS

```
Limit
  Index Scan using custmast_search_keyset on custmast Index Cond: (ROW((name)::text, (city)::text, state, custid) > ROW('AADWDF EWAXKZGDHE IIMIOE QFNEAUBCGE PART'::text, 'SPRINGFIELD'::text, 'IL'::character(2), 'MP2D'::character(4))) Filter: (active = 'Y'::bpchar)
```

### name-prefix-3: PASS

```
Limit
  [CTE candidates] Index Scan using custmast_name on custmast Index Cond: (((name)::text ~>=~ 'UEG'::text) AND ((name)::text ~<~ 'UEH'::text)) Filter: (((name)::text ~~ 'UEG%'::text) AND (active = 'Y'::bpchar))
  Sort Sort Key: candidates.name COLLATE customer_sort, candidates.city COLLATE customer_sort, candidates.state COLLATE customer_sort, candidates.custid COLLATE customer_sort
    CTE Scan on candidates
```

