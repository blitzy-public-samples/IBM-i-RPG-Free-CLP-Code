# Pull Request: Customer Master on Java 21 / Spring Boot 3, PostgreSQL and React

This file holds the commit sequence and the pull-request description for the one pull request that adds `customer-master/`; see also the [README](../README.md), [Deviations and open questions](deviations-and-open-questions.md), [Traceability matrix](traceability-matrix.md), [Developer guide](developer-guide.md) and [Search benchmark](performance/search-benchmark.md).

## Commit sequence

The work is delivered in one pull request as eleven [Conventional Commits](https://www.conventionalcommits.org/), applied in the order below. Each commit builds on its own: after it, the checks of its boundary pass on the tree as it then stands, and every later boundary repeats them. At commits 1 to 7 the check is `./mvnw -B verify` from `backend/`, which compiles both modules, packages `app.jar` and runs the tests present. Commit 8 adds `npm ci`, `npm run lint`, `npm test` and `npm run build` from `frontend/`; commit 9 adds `npm ci` and `npm run typecheck` from `e2e/`; commit 10 adds `docker compose up --build -d --wait` from `customer-master/`, and the backend verify then also runs `NginxGatewayProblemTest`; commit 11 changes documents only. `backend/Dockerfile` copies the sources of both modules, so its image first builds at commit 4, and Compose first builds and runs it at commit 10. If the working branch holds finer-grained development commits, rebase them into this sequence before requesting review.

Paths are relative to `customer-master/` unless marked as the repository root. `{api}` stands for `backend/customer-api/src/main/java/com/democorp/customermaster/` and `resources/` for `backend/customer-api/src/main/resources/`.

1. `build(backend): scaffold Maven multi-module project, wrapper and Dockerfile`
   - `backend/pom.xml`, `backend/mvnw`, `backend/mvnw.cmd`, `backend/.mvn/wrapper/maven-wrapper.properties`, `backend/Dockerfile`, `backend/.dockerignore`, `backend/address-validation/pom.xml`, `backend/customer-api/pom.xml`, `{api}CustomerMasterApplication.java` (the minimal application entry point: the main class that the Boot repackage execution inherited by `customer-api/pom.xml` needs), `{api}config/AppProperties.java` (the properties class it registers), `.gitattributes`, `.gitignore`
2. `feat(db): Flyway migrations V1–V4 and seed V5`
   - `resources/db/migration/V1__create_collation.sql` .. `V4__create_custid_sequence.sql`, `resources/db/seed/V5__seed_customers.sql`
3. `feat(api): domain model, repositories and customer id allocation`
   - the remaining `{api}config/` classes (`AppProperties` is in commit 1), `{api}domain/`, `{api}repository/` (including `CustomerIdAllocator` and `JdbcConfig`), `{api}service/exception/`, `resources/application.yml`
4. `feat(address): address-validation module with stub and Web Tools client`
   - `backend/address-validation/src/main/**` (client interface, records, stub, Web Tools client, `UspsXmlCodec`, `UspsTextRedactor`, auto-configuration, stub fixtures) and `backend/address-validation/src/test/**`
5. `feat(api): search, maintenance, states, messages, security and problem details`
   - `{api}service/`, `{api}controller/` (including `dto/`), `{api}security/`, `{api}messages/`, `resources/messages/messages.properties`
6. `feat(generator): LOADCUSTR-equivalent data generator`
   - `{api}generator/`, `resources/application-generator.yml`, `resources/generator/csz-sample.csv`, `resources/META-INF/spring.factories`
7. `test(api): unit, Testcontainers, OpenAPI snapshot and benchmark suites`
   - `backend/customer-api/src/test/**` (unit tests, `*IT`, `support/` base classes, `benchmark/SearchBenchmarkIT.java`, test resources; except `controller/NginxGatewayProblemTest.java`, which commit 10 adds), `openapi/customer-master-api.yaml`
8. `feat(web): React frontend with Vitest suites`
   - `frontend/package.json`, `frontend/package-lock.json`, `frontend/tsconfig.json`, `frontend/tsconfig.node.json`, `frontend/vite.config.ts`, `frontend/eslint.config.js`, `frontend/index.html`, `frontend/src/**` (including `src/api/schema.d.ts` and the colocated `*.test.ts(x)` specs)
9. `test(e2e): Playwright flows`
   - `e2e/package.json`, `e2e/package-lock.json`, `e2e/tsconfig.json`, `e2e/playwright.config.ts`, `e2e/fixtures/auth.ts`, `e2e/tests/*.spec.ts`
10. `build: Docker Compose, nginx, environment template and k6 script`
    - `docker-compose.yml`, `frontend/Dockerfile`, `frontend/nginx.conf`, `frontend/.dockerignore`, `.env.example`, `perf/k6/search-1m.js`, `perf/results/.gitkeep`, and `backend/customer-api/src/test/java/com/democorp/customermaster/controller/NginxGatewayProblemTest.java`, which reads `frontend/nginx.conf` and holds its 502 and 504 gateway bodies and its `/api/` rejection bodies, the throttle's 429 included, to `ProblemFactory`, its throttle of credentialed `/api/` requests (keyed by client address only when an `Authorization` header is present, 10 per second with a burst of 20 of which 10 pass undelayed, 429 beyond it) to `location /api/`, `Retry-After: 1` to that 429 only, and its browser security headers to the server block, which sets them unconditionally on every response nginx writes itself, and to `location /api/`, which adds them to the API's answers only where the API did not send them
11. `docs: README, developer guide, deviations, traceability, benchmark; root README entry`
    - `README.md`, `docs/**`, `data/README.md`, and `README.md` at the repository root

## Pull-request description

The subsections below are the description to paste into the pull request. Its links are written relative to `customer-master/docs/`.

### Summary

This pull request re-implements the IBM i Customer Master (the search and list program PMTCUSTR, both variants of the detail maintenance program MTNCUSTR, the state prompt PMTSTATER, the USPS address standardization service USADRVAL, and the provisioning assets) as a containerized web application in the new folder `customer-master/`. It consists of a Java 21 / Spring Boot 3.5.16 API (`backend/customer-api`), a separate `backend/address-validation` module behind the `AddressValidationClient` interface (a deterministic stub by default, the USPS Web Tools XML client by configuration), PostgreSQL 18.6 managed by Flyway, and a React 19 + TypeScript SPA served by nginx. Documented business behaviour is preserved, with the IBM i source as the specification wherever a readme disagrees with it. The roles INQUIRY and MAINTENANCE, plus an embeddable Selection picker, replace the I/M/S mode parameter; search uses keyset pagination measured at 1,000,000 rows; and 4-character base-36 customer ids come from a concurrency-safe sequence whose first interactive id is `EEEF`. Errors are RFC 9457 problem+json responses carrying the CUSTMSGF message catalog, and a generator CLI replaces LOADCUSTR (default 300 rows).

### Scope

- **In scope:** the new top-level folder `customer-master/` (backend, frontend, e2e, perf, openapi, data, docs, the Compose file and the environment template), and one entry added to the folder catalog of the repository-root `README.md`. The IBM i members in `5250_Subfile/` and `USPS_Address/`, and the in-scope members of `Service_Pgms/`, `BASE36/` and `Copy_Mbrs/`, are read as the specification and left byte-for-byte unchanged.
- **Out of scope:**
  - the folders `APIs`, `APIs_SQL`, `GRP_JOB`, `PGM_REFS`, `PRT_CL`, `Printing`, `RcdLckDsp`, `Utils`, `SNGCHCFLD`, `SQL_SKELETON`, `Z_Exp1`, `DATEADJ` and `DATE_UDF`, none of which is converted or stubbed, because no in-scope member calls them;
  - IBM i build and toolchain assets: `.vscode/*`, the `CRTBNDDIR.CLLE` members and `SRV_MSGBND.BND`;
  - a DELETE function (the source has none; a customer is deactivated with Active `N`);
  - a client for the USPS APIs v3 (OAuth 2.0 / JSON);
  - single sign-on or an external identity provider;
  - migration of live Db2 data;
  - any change to an existing IBM i member.

### How to run

From `customer-master/` (prerequisite: Docker Engine with Compose v2):

- `docker compose up --build` starts the stack with the seed data and no manual step. The UI is at `http://localhost:8080` and the API docs at `http://localhost:8081/swagger-ui.html`. The demo users `inquiry`/`inquiry-demo` (INQUIRY) and `sales`/`sales-demo` (MAINTENANCE) are demo-only and are overridden through `.env` (copy [`.env.example`](../.env.example) to `.env`).
- Load test data with `docker compose --profile tools run --rm generator --count=1000000` (the default count is 300; a full city/state/ZIP file is read with `--csz-file=/data/csz.csv`, see [`data/README.md`](../data/README.md)).
- Reset all data with `docker compose down -v`.
- Enable the real USPS client only after the checklist in the [developer guide](developer-guide.md#usps-address-validation) (also Section 8 of [Deviations and open questions](deviations-and-open-questions.md#81-usps-pre-enablement-verification)), by setting `ADDRESS_VALIDATION_CLIENT=usps`, `USPS_USER_ID` and `USPS_PASSWORD` in `.env`.

### How to test

The backend and frontend commands run on the host and need these tools (see also the README's [Prerequisites](../README.md#prerequisites)): JDK 21, set as `JAVA_HOME` or with `java` and `javac` on `PATH` (the Maven Wrapper downloads Maven 3.9.16 itself and checks the ZIP against the SHA-256 pinned in `backend/.mvn/wrapper/maven-wrapper.properties`, so on Linux and macOS that first download also needs `unzip` and `sha256sum` or `shasum`: without `unzip` the wrapper fetches the `.tar.gz`, which fails the check; `mvnw.cmd` on Windows needs nothing extra); a running Docker Engine for the Testcontainers PostgreSQL 18.6 that the backend `*IT` start; and Node.js 24.21.0 or later with npm, plus git for the schema diff. The end-to-end and load commands need only Docker Engine with Compose v2.

- **Backend** (from `customer-master/backend`): `./mvnw -B verify` runs the unit tests and every `*IT` except the benchmark, against Testcontainers PostgreSQL 18.6; `./mvnw -B verify -Pbenchmark` runs `SearchBenchmarkIT` on 1,000,000 rows.
- **Frontend** (from `customer-master/frontend`): `npm ci`, `npm run lint`, `npm test`, `npm run build`; then `npm run gen:api` followed by `git diff --exit-code src/api/schema.d.ts`.
- **End-to-end** (from `customer-master/`): `docker compose --profile e2e run --rm e2e` runs the five Playwright flows `search-and-display`, `edit-with-confirmation`, `add-with-address-standardization`, `concurrent-edit-conflict` and `selection-picker`.
- **Load** (from `customer-master/`): `docker compose --profile perf run --rm k6` after the 1,000,000-row load; results are recorded in the [search benchmark](performance/search-benchmark.md).

### Evidence statement

> The tests are derived from reading the IBM i source members and the rules this plan restates. They are not executed against the original IBM i programs, which do not run in this environment. Passing them shows that the target meets the behaviour as read from the source; it does not establish verified behavioural equivalence with the IBM i application.

### Further reading

- [Deviations and open questions](deviations-and-open-questions.md): the defect rule, corrected text defects, behaviour changed under the rule, preserved source defects, intentional differences, the readme discrepancies D1–D9, open questions, and platform risks such as the Spring Boot 3.5 end of open-source support on 2026-06-30 and the USPS Web Tools retirement on 2026-01-25.
- [Traceability matrix](traceability-matrix.md): features, use cases and IBM i members mapped to target modules and covering tests.
- [Developer guide](developer-guide.md): the IBM i to target mapping, configuration and the USPS verification checklist.
- [Search benchmark](performance/search-benchmark.md): method, environment, measured results and verdict for 1,000,000 rows.

### Reviewer checklist

- [ ] From `customer-master/backend`: `./mvnw -B verify` passes.
- [ ] From `customer-master/backend`: `./mvnw -B verify -Pbenchmark` passes on the 1,000,000-row run.
- [ ] From `customer-master/frontend`: `npm ci`, `npm run lint`, `npm test`, `npm run build` pass.
- [ ] From `customer-master/frontend`: `npm run gen:api` followed by `git diff --exit-code src/api/schema.d.ts` shows no change.
- [ ] From `customer-master/` (in this order, because the e2e specs need the seed rows that the generator replaces):
  - [ ] `docker compose up --build -d --wait --wait-timeout 300` returns 0 with no manual step before it; `docker compose ps --format json` shows `"Health":"healthy"` for `db`, `app` and `frontend`.
  - [ ] `curl -s -u inquiry:inquiry-demo -H 'X-Requested-With: XMLHttpRequest' 'http://localhost:8081/api/customers?size=1'` returns 200.
  - [ ] `docker compose --profile e2e run --rm e2e` passes all five specs.
  - [ ] `docker compose --profile tools run --rm generator --count=500 --start-id=B000 --seed=7` exits 0 and prints `Loaded 500 customers B000..`; `docker compose exec db psql -U customermaster -d customermaster -tAc "select count(*), min(custid) from customer_master.custmast"` returns `500|B000`; a second identical run yields the same `md5(string_agg(name, ',' ORDER BY custid))`.
  - [ ] `docker compose --profile tools run --rm generator --count=1000000`, then `docker compose --profile perf run --rm k6` meets its thresholds (`http_req_duration p(95) < 300` ms, `http_req_failed rate < 0.01`).
- [ ] From the repository root: `grep -rniE 'lennons1|lennonsb' customer-master --exclude-dir=docs --exclude-dir=node_modules` returns nothing. The gate passes only when grep prints nothing and exits 1, its status for no match; exit 2 is an error, such as `customer-master: No such file or directory` when run from another directory, and fails the gate.
- [ ] No source, config or env template contains a USPS credential value.
- [ ] No IBM i member is modified; the only existing file edited is the root `README.md`.
