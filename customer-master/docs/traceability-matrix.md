# Traceability Matrix

This matrix traces every feature (F-nnn), use case (UC-nn) and in-scope IBM i source member of the Customer Master to the target code that carries it and to the tests that cover it. Read each row from left to right: the item, the classes, components or files that implement it, the tests that exercise it, and where that code lives. A row whose cells read "As F-001" (or another identifier) has the targets and tests of that identifier's row. Source members are named relative to the repository root; every target path is relative to `customer-master/`. Test names are the class and spec names of the suites described under [Running the test suites](../README.md#running-the-test-suites), and each one resolves to the file listed under [Test locations](#test-locations). What the target corrects, changes, preserves or adds relative to the source is recorded in [Deviations and open questions](deviations-and-open-questions.md); how each IBM i construct maps to its replacement is explained in the [developer guide](developer-guide.md).

**Contents**

1. [Path key](#path-key)
2. [Identifiers](#identifiers)
3. [Matrix](#matrix)
4. [Test locations](#test-locations)
5. [Supporting suites](#supporting-suites)
6. [Evidence statement](#evidence-statement)

## Path key

Every target path in the tables below is one of these locations or lies under one, written out in full in each table. The prefixes are the ones the developer guide uses; "—" marks a location the guide has no prefix for.

| Prefix | Location (relative to `customer-master/`) | Holds |
|--------|-------------------------------------------|-------|
| `{api}` | `backend/customer-api/src/main/java/com/democorp/customermaster/` | API code: `domain`, `repository`, `service`, `controller`, `security`, `messages`, `generator` |
| — | `backend/customer-api/src/test/java/com/democorp/customermaster/` | API unit tests (`*Test`) and Testcontainers integration tests (`*IT`) |
| `{adr}` | `backend/address-validation/src/main/java/com/democorp/customermaster/address/` | The address-validation module's code |
| — | `backend/address-validation/src/test/java/com/democorp/customermaster/address/` | The address-validation module's tests |
| — | `backend/address-validation/` | The address-validation module as a whole: its `pom.xml`, code, tests and resources, including the stub fixtures |
| `resources/` | `backend/customer-api/src/main/resources/` | Flyway `db/migration/` and `db/seed/`, the `messages/` catalog |
| `{web}` | `frontend/src/` | The React application and its colocated Vitest specs |
| — | `e2e/tests/` | The Playwright specs |
| — | `openapi/` | The committed API contract |
| — | `docker-compose.yml` | The Compose services, among them the `generator` service (profile `tools`) |

## Identifiers

| Id | Definition | Source members covered |
|----|------------|------------------------|
| F-001 | Customer Master Prompt and Search: filtered, paged customer list with mode-dependent options | `5250_Subfile/PMTCUSTR.SQLRPGLE`, `5250_Subfile/PMTCUSTD.DSPF` |
| F-002 | Customer Master Detail Maintenance: display, change and add one customer with validation, confirmation and concurrency control | `5250_Subfile/MTNCUSTR.SQLRPGLE`, `5250_Subfile/MTNCUSTD.DSPF`, `USPS_Address/MTNCUSTR.SQLRPGLE`, `USPS_Address/MTNCUSTD.DSPF`, `BASE36/SRV_BASE36.RPGLE` |
| F-003 | USA State Prompt Window: filterable, re-sortable state list that returns a code | `5250_Subfile/PMTSTATER.SQLRPGLE`, `5250_Subfile/PMTSTATED.DSPF`, `Service_Pgms/StateVal.sqlrpgle` |
| F-004 | Customer Master Data and Test-Data Provisioning: schema, states, messages, key store, seed and random data load | `5250_Subfile/Custmast2.sql`, `5250_Subfile/Custmast.sql`, `5250_Subfile/States.sql`, `5250_Subfile/CRTMSGF.CLLE`, `5250_Subfile/CRTDTAARA.clle`, `5250_Subfile/LOADCUSTR.SQLRPGLE`, `5250_Subfile/LOADCUST.CLLE`, `5250_Subfile/LOADCUST2.CLLE`, `Service_Pgms/SRV_RANDOM.SQLRPGLE` |
| UC-01 | Search the customer master by name, city and state, optionally including inactive rows | `5250_Subfile/PMTCUSTR.SQLRPGLE`, `5250_Subfile/PMTCUSTD.DSPF` |
| UC-02 | Display one customer read-only | `5250_Subfile/MTNCUSTR.SQLRPGLE` (function code `D`), `5250_Subfile/MTNCUSTD.DSPF` |
| UC-03 | Change one customer with field validation and a confirmation pass | `5250_Subfile/MTNCUSTR.SQLRPGLE` (function code `E`), `5250_Subfile/MTNCUSTD.DSPF` |
| UC-04 | Add a customer, taking the next key automatically | `5250_Subfile/MTNCUSTR.SQLRPGLE` (function code `A`), `5250_Subfile/MTNCUSTD.DSPF`, `5250_Subfile/CRTDTAARA.clle`, `BASE36/SRV_BASE36.RPGLE` |
| UC-05 | Return a selected customer id to a calling program (Selection mode, option 1) | `5250_Subfile/PMTCUSTR.SQLRPGLE` (mode `S`) |
| UC-06 | Find and return a USA state code, re-sequencing the list | `5250_Subfile/PMTSTATER.SQLRPGLE`, `5250_Subfile/PMTSTATED.DSPF` |
| UC-07 | Validate and standardize a keyed address during maintenance | `USPS_Address/USADRVAL.SQLRPGLE`, `USPS_Address/MTNCUSTR.SQLRPGLE`, `Copy_Mbrs/USADRVAL_P.RPGLE`, `Copy_Mbrs/USADRVALDS.RPGLE` |

Messaging and the shared services (`Service_Pgms/SRV_MSG.RPGLE`, `Service_Pgms/SRV_SQL.SQLRPGLE`, `Service_Pgms/SRV_STR.RPGLE`) support every feature; each has its own row in the matrix.

## Matrix

"Compile-time types" means the replacing Java interface or record is checked by the module compile in `./mvnw -B verify`, and a replacing TypeScript component by `tsc` in `npm run build`, so a change to its shape fails the build.

| Item | Target modules | Covering tests | Path |
|------|----------------|----------------|------|
| F-001 / UC-01 | `CustomerSearchService`, `CustomerSearchRepository`, `CustomerSearchPage`, `SearchFilters`, `ResultsTable`; the endpoint `GET /api/customers` in `CustomerController` | `CustomerSearchRepositoryIT`, `CustomerSearchApiIT`, `SearchBenchmarkIT`, `CustomerSearchPage.test.tsx`, `search-and-display.spec.ts` | `backend/customer-api/src/main/java/com/democorp/customermaster/service/`<br>`backend/customer-api/src/main/java/com/democorp/customermaster/repository/`<br>`backend/customer-api/src/main/java/com/democorp/customermaster/controller/`<br>`frontend/src/features/customers/` |
| UC-05 | `CustomerPicker`, `HostFormDemoPage` | `CustomerPicker.test.tsx`, `selection-picker.spec.ts` | `frontend/src/features/customers/`<br>`frontend/src/features/demo/` |
| F-002 / UC-02, UC-03 | `CustomerMaintenanceService`, `CustomerValidator`, `CustomerRepository`, `CustomerDetailDialog`, `ConfirmationPanel`, `ConflictCompareDialog`; the endpoints `GET /api/customers/{custId}`, `POST /api/customers/review` and `PUT /api/customers/{custId}` in `CustomerController` | `CustomerValidatorTest`, `CustomerRepositoryIT`, `CustomerMaintenanceApiIT`, `OptimisticConcurrencyIT`, `CustomerDetailDialog.test.tsx`, `ConflictCompareDialog.test.tsx`, `edit-with-confirmation.spec.ts`, `concurrent-edit-conflict.spec.ts` | `backend/customer-api/src/main/java/com/democorp/customermaster/service/`<br>`backend/customer-api/src/main/java/com/democorp/customermaster/repository/`<br>`backend/customer-api/src/main/java/com/democorp/customermaster/controller/`<br>`frontend/src/features/customers/` |
| UC-04 | `CustomerId`, `CustomerIdAllocator`, `V4__create_custid_sequence.sql` | `CustomerIdTest`, `CustomerIdAllocationIT`, `CustomerIdCollationIT`, `add-with-address-standardization.spec.ts` | `backend/customer-api/src/main/java/com/democorp/customermaster/domain/`<br>`backend/customer-api/src/main/java/com/democorp/customermaster/repository/`<br>`backend/customer-api/src/main/resources/db/migration/` |
| F-003 / UC-06 | `StateService`, `StateRepository`, `StateController`, `StatePicker` | `StateApiIT`, `StatePicker.test.tsx`, `add-with-address-standardization.spec.ts` | `backend/customer-api/src/main/java/com/democorp/customermaster/service/`<br>`backend/customer-api/src/main/java/com/democorp/customermaster/repository/`<br>`backend/customer-api/src/main/java/com/democorp/customermaster/controller/`<br>`frontend/src/features/states/` |
| UC-07 | `address-validation` module (`AddressValidationClient`, `UspsWebToolsAddressValidationClient`, `UspsXmlCodec`, `StubAddressValidationClient`, `AddressValidationAutoConfiguration`), `AddressStandardizationService` | `AddressStandardizationServiceTest`, `UspsXmlCodecTest`, `UspsWebToolsAddressValidationClientTest`, `StubAddressValidationClientTest`, `AddressValidationAutoConfigurationTest`, `add-with-address-standardization.spec.ts` | `backend/address-validation/`<br>`backend/customer-api/src/main/java/com/democorp/customermaster/service/` |
| F-004 | Flyway `V1__create_collation.sql`, `V2__create_states.sql`, `V3__create_custmast.sql`, `V4__create_custid_sequence.sql` and the seed `V5__seed_customers.sql`; `generator/*` (`CustomerGeneratorRunner`, `CustomerLoader`, `CustomerDataGenerator`, `NameGenerator`, `CszSource`, `CustomerCopyWriter`, `GeneratorProperties`, `LoadCheckpoints`); `messages.properties` | `FlywayMigrationIT`, `CustomerDataGeneratorTest`, `CustomerGeneratorIT`, `GeneratorProcessIT`, `MessageCatalogIT` | `backend/customer-api/src/main/resources/db/migration/`<br>`backend/customer-api/src/main/resources/db/seed/`<br>`backend/customer-api/src/main/java/com/democorp/customermaster/generator/`<br>`backend/customer-api/src/main/resources/messages/` |
| `5250_Subfile/PMTCUSTR.SQLRPGLE`, `5250_Subfile/PMTCUSTD.DSPF` | As F-001 and UC-05 | As F-001 and UC-05 | — |
| `5250_Subfile/MTNCUSTR.SQLRPGLE`, `5250_Subfile/MTNCUSTD.DSPF` | As F-002 and UC-04 | As F-002 and UC-04 | — |
| `USPS_Address/MTNCUSTR.SQLRPGLE`, `USPS_Address/MTNCUSTD.DSPF`, `USPS_Address/USADRVAL.SQLRPGLE`, `USPS_Address/USADRVAL_T.RPGLE` | As UC-07; the sample addresses of the eight `USADRVAL_T` calls model the stub fixtures | As UC-07 | `backend/address-validation/src/main/resources/stub/usps-stub-fixtures.json` |
| `Copy_Mbrs/USADRVAL_P.RPGLE`, `Copy_Mbrs/USADRVALDS.RPGLE` | `AddressValidationClient`, `AddressValidationRequest`, `AddressValidationResult` with the USADRVALDS widths | `UspsXmlCodecTest` (width rejection), `AddressStandardizationServiceTest`; compile-time types | `backend/address-validation/src/main/java/com/democorp/customermaster/address/` |
| `5250_Subfile/PMTSTATER.SQLRPGLE`, `5250_Subfile/PMTSTATED.DSPF`; `Service_Pgms/StateVal.sqlrpgle` | As F-003 | As F-003 | — |
| `5250_Subfile/Custmast2.sql`, `5250_Subfile/Custmast.sql`, `5250_Subfile/States.sql`, `5250_Subfile/CRTDTAARA.clle` | V1–V5 | `FlywayMigrationIT`, `CustomerIdAllocationIT` | `backend/customer-api/src/main/resources/db/migration/`<br>`backend/customer-api/src/main/resources/db/seed/` |
| `5250_Subfile/CRTMSGF.CLLE`; `Service_Pgms/SRV_MSG.RPGLE`, `Service_Pgms/SRV_SQL.SQLRPGLE` | `MessageCatalog` (over `messages.properties`), `ProblemFactory`, `ApiExceptionHandler`, `ProblemErrorController`, `useProblemPresenter`, `ToastRegion`, `MessageCatalogProvider` | `MessageCatalogIT`, `ErrorModelIT`, `MessageCatalogProvider.test.tsx`, `client.test.ts`, `useProblemPresenter.test.tsx` | `backend/customer-api/src/main/java/com/democorp/customermaster/messages/`<br>`backend/customer-api/src/main/resources/messages/`<br>`backend/customer-api/src/main/java/com/democorp/customermaster/controller/`<br>`frontend/src/messages/`<br>`frontend/src/errors/`<br>`frontend/src/components/` |
| `5250_Subfile/LOADCUSTR.SQLRPGLE`, `5250_Subfile/LOADCUST.CLLE`, `5250_Subfile/LOADCUST2.CLLE`; `Service_Pgms/SRV_RANDOM.SQLRPGLE` | `generator/*` and the Compose `generator` service (profile `tools`); LOADCUST's seed path is the Flyway seed `V5__seed_customers.sql` | `CustomerDataGeneratorTest`, `CustomerGeneratorIT`, `GeneratorProcessIT`; `FlywayMigrationIT` (seed location) | `backend/customer-api/src/main/java/com/democorp/customermaster/generator/`<br>`docker-compose.yml`<br>`backend/customer-api/src/main/resources/db/seed/` |
| `BASE36/SRV_BASE36.RPGLE`, `BASE36/BASE36_P.RPGLE` | `CustomerId`, `V1__create_collation.sql` | `CustomerIdTest`, `CustomerIdCollationIT` | `backend/customer-api/src/main/java/com/democorp/customermaster/domain/`<br>`backend/customer-api/src/main/resources/db/migration/` |
| `Service_Pgms/SRV_STR.RPGLE` | `ScreenHeader` | `CustomerSearchPage.test.tsx` (header text per mode) | `frontend/src/components/` |
| `Copy_Mbrs/AIDBYTES.RPGLE` | `KeyScopeProvider`, `useFunctionKeys`, `FunctionKeyBar` key names | `useFunctionKeys.test.tsx` | `frontend/src/keyboard/`<br>`frontend/src/components/` |
| `Copy_Mbrs/BASE36_P.RPGLE` (the same declaration as `BASE36/BASE36_P.RPGLE`) | `CustomerId.successor` replaces the BASE36ADD call interface | `CustomerIdTest`; compile-time types | `backend/customer-api/src/main/java/com/democorp/customermaster/domain/` |
| `Copy_Mbrs/SRV_MSG_P.RPGLE` | `ProblemFactory` and `MessageCatalog` (server), `useProblemPresenter` and `ToastRegion` (client) replace the message procedures it declares | `MessageCatalogIT`, `ErrorModelIT`, `useProblemPresenter.test.tsx`; compile-time types | `backend/customer-api/src/main/java/com/democorp/customermaster/controller/`<br>`backend/customer-api/src/main/java/com/democorp/customermaster/messages/`<br>`frontend/src/errors/`<br>`frontend/src/components/` |
| `Copy_Mbrs/SRV_SQL_P.RPGLE` | `ApiExceptionHandler` and `ProblemErrorController` replace SQLProblem | `ErrorModelIT`; compile-time types | `backend/customer-api/src/main/java/com/democorp/customermaster/controller/` |
| `Copy_Mbrs/SRV_STR_P.RPGLE` | `ScreenHeader` props replace CenterStr | `CustomerSearchPage.test.tsx` (header text per mode); `tsc` in `npm run build` | `frontend/src/components/` |
| `Copy_Mbrs/SRV_RAND_P.RPGLE` | `NameGenerator.randInt(int low, int high)` with Rand_Int's inclusive bounds | `CustomerDataGeneratorTest` (endpoint cases) | `backend/customer-api/src/main/java/com/democorp/customermaster/generator/` |
| `Copy_Mbrs/SRV_STE_P.RPGLE` (no in-scope includer) | `StateService.exists(code)` carries StateVal's interface | `CustomerValidatorTest` (DEM0503), `StateApiIT` | `backend/customer-api/src/main/java/com/democorp/customermaster/service/` |

## Test locations

Every test named in the matrix, with its file. The backend suites run with `./mvnw -B verify` from `backend/` (`SearchBenchmarkIT` only with `-Pbenchmark`), the Vitest specs with `npm test` from `frontend/`, and the Playwright specs with `docker compose --profile e2e run --rm e2e` from `customer-master/`; see [Running the test suites](../README.md#running-the-test-suites).

| Test | Suite | File |
|------|-------|------|
| `CustomerIdTest` | JUnit 5 unit | `backend/customer-api/src/test/java/com/democorp/customermaster/domain/CustomerIdTest.java` |
| `CustomerValidatorTest` | JUnit 5 unit | `backend/customer-api/src/test/java/com/democorp/customermaster/service/CustomerValidatorTest.java` |
| `AddressStandardizationServiceTest` | JUnit 5 unit | `backend/customer-api/src/test/java/com/democorp/customermaster/service/AddressStandardizationServiceTest.java` |
| `CustomerDataGeneratorTest` | JUnit 5 unit | `backend/customer-api/src/test/java/com/democorp/customermaster/generator/CustomerDataGeneratorTest.java` |
| `CustomerRepositoryIT` | JUnit 5, Testcontainers PostgreSQL 18.6 | `backend/customer-api/src/test/java/com/democorp/customermaster/repository/CustomerRepositoryIT.java` |
| `CustomerSearchRepositoryIT` | JUnit 5, Testcontainers PostgreSQL 18.6 | `backend/customer-api/src/test/java/com/democorp/customermaster/repository/CustomerSearchRepositoryIT.java` |
| `CustomerIdCollationIT` | JUnit 5, Testcontainers PostgreSQL 18.6 | `backend/customer-api/src/test/java/com/democorp/customermaster/repository/CustomerIdCollationIT.java` |
| `CustomerIdAllocationIT` | JUnit 5, Testcontainers PostgreSQL 18.6 | `backend/customer-api/src/test/java/com/democorp/customermaster/repository/CustomerIdAllocationIT.java` |
| `CustomerSearchApiIT` | JUnit 5, Testcontainers PostgreSQL 18.6 | `backend/customer-api/src/test/java/com/democorp/customermaster/controller/CustomerSearchApiIT.java` |
| `CustomerMaintenanceApiIT` | JUnit 5, Testcontainers PostgreSQL 18.6 | `backend/customer-api/src/test/java/com/democorp/customermaster/controller/CustomerMaintenanceApiIT.java` |
| `OptimisticConcurrencyIT` | JUnit 5, Testcontainers PostgreSQL 18.6 | `backend/customer-api/src/test/java/com/democorp/customermaster/controller/OptimisticConcurrencyIT.java` |
| `StateApiIT` | JUnit 5, Testcontainers PostgreSQL 18.6 | `backend/customer-api/src/test/java/com/democorp/customermaster/controller/StateApiIT.java` |
| `MessageCatalogIT` | JUnit 5, Testcontainers PostgreSQL 18.6 | `backend/customer-api/src/test/java/com/democorp/customermaster/controller/MessageCatalogIT.java` |
| `ErrorModelIT` | JUnit 5, Testcontainers PostgreSQL 18.6 | `backend/customer-api/src/test/java/com/democorp/customermaster/controller/ErrorModelIT.java` |
| `FlywayMigrationIT` | JUnit 5, Testcontainers PostgreSQL 18.6 | `backend/customer-api/src/test/java/com/democorp/customermaster/migration/FlywayMigrationIT.java` |
| `CustomerGeneratorIT` | JUnit 5, Testcontainers PostgreSQL 18.6 | `backend/customer-api/src/test/java/com/democorp/customermaster/generator/CustomerGeneratorIT.java` |
| `GeneratorProcessIT` | JUnit 5, Testcontainers PostgreSQL 18.6, packaged jar | `backend/customer-api/src/test/java/com/democorp/customermaster/generator/GeneratorProcessIT.java` |
| `SearchBenchmarkIT` | JUnit 5, Testcontainers PostgreSQL 18.6, tag `benchmark` | `backend/customer-api/src/test/java/com/democorp/customermaster/benchmark/SearchBenchmarkIT.java` |
| `UspsXmlCodecTest` | JUnit 5 unit (address-validation) | `backend/address-validation/src/test/java/com/democorp/customermaster/address/UspsXmlCodecTest.java` |
| `UspsWebToolsAddressValidationClientTest` | JUnit 5 unit (address-validation) | `backend/address-validation/src/test/java/com/democorp/customermaster/address/UspsWebToolsAddressValidationClientTest.java` |
| `StubAddressValidationClientTest` | JUnit 5 unit (address-validation) | `backend/address-validation/src/test/java/com/democorp/customermaster/address/StubAddressValidationClientTest.java` |
| `AddressValidationAutoConfigurationTest` | JUnit 5 unit (address-validation) | `backend/address-validation/src/test/java/com/democorp/customermaster/address/AddressValidationAutoConfigurationTest.java` |
| `client.test.ts` | Vitest | `frontend/src/api/client.test.ts` |
| `useProblemPresenter.test.tsx` | Vitest | `frontend/src/errors/useProblemPresenter.test.tsx` |
| `MessageCatalogProvider.test.tsx` | Vitest | `frontend/src/messages/MessageCatalogProvider.test.tsx` |
| `useFunctionKeys.test.tsx` | Vitest | `frontend/src/keyboard/useFunctionKeys.test.tsx` |
| `CustomerSearchPage.test.tsx` | Vitest | `frontend/src/features/customers/CustomerSearchPage.test.tsx` |
| `CustomerPicker.test.tsx` | Vitest | `frontend/src/features/customers/CustomerPicker.test.tsx` |
| `CustomerDetailDialog.test.tsx` | Vitest | `frontend/src/features/customers/CustomerDetailDialog.test.tsx` |
| `ConflictCompareDialog.test.tsx` | Vitest | `frontend/src/features/customers/ConflictCompareDialog.test.tsx` |
| `StatePicker.test.tsx` | Vitest | `frontend/src/features/states/StatePicker.test.tsx` |
| `search-and-display.spec.ts` | Playwright | `e2e/tests/search-and-display.spec.ts` |
| `edit-with-confirmation.spec.ts` | Playwright | `e2e/tests/edit-with-confirmation.spec.ts` |
| `add-with-address-standardization.spec.ts` | Playwright | `e2e/tests/add-with-address-standardization.spec.ts` |
| `concurrent-edit-conflict.spec.ts` | Playwright | `e2e/tests/concurrent-edit-conflict.spec.ts` |
| `selection-picker.spec.ts` | Playwright | `e2e/tests/selection-picker.spec.ts` |

## Supporting suites

These suites are not named in the matrix. They cover cross-cutting rules that the matrix items rely on, or platform behaviour that has no IBM i counterpart, and they run in the same commands as the suites above.

| Supports | Suites | Path |
|----------|--------|------|
| Modes and role enforcement (F-001, F-002, UC-05): the `I`/`M`/`S` parameter of `5250_Subfile/PMTCUSTR.SQLRPGLE` becomes the roles INQUIRY and MAINTENANCE plus the picker context | `SecurityRulesIT`, `UsersPropertiesTest`, `StrictBasicAuthenticationConverterTest`, `RequireRole.test.tsx` | `backend/customer-api/src/test/java/com/democorp/customermaster/controller/`<br>`backend/customer-api/src/test/java/com/democorp/customermaster/security/`<br>`frontend/src/auth/` |
| Calling menu and sign-on (F-001, UC-05): the menu outside the repository that called `5250_Subfile/PMTCUSTR.SQLRPGLE` with its mode, and the security left to it, become `HomePage`, the guarded route table and the sign-in page, mounted by `App` | `App.test.tsx` | `frontend/src/` |
| Uppercase input (F-001, F-002, F-003): no input field of `5250_Subfile/PMTCUSTD.DSPF`, `5250_Subfile/MTNCUSTD.DSPF` or `5250_Subfile/PMTSTATED.DSPF` declares `CHECK(LC)` | `TextNormalizerTest`, `upperField.test.ts`, `FormField.test.tsx` | `backend/customer-api/src/test/java/com/democorp/customermaster/domain/`<br>`frontend/src/components/` |
| Windows (F-002, F-003, UC-05): the `WINDOW` records of `5250_Subfile/MTNCUSTD.DSPF` and `5250_Subfile/PMTSTATED.DSPF`, modal by construction, become a modal dialog with a focus trap and focus return | `Dialog.test.tsx` | `frontend/src/components/` |
| Search filter rules (F-001): PMTCUSTR `ProcessSearchCriteria` | `CustomerSearchServiceTest` | `backend/customer-api/src/test/java/com/democorp/customermaster/service/` |
| Change stamp and write log (F-002): MTNCUSTR's "Last Change … by …" line and the after-commit `customer.write` log line | `formatChangeStamp.test.ts`, `CustomerMaintenanceServiceAfterCommitLogTest` | `frontend/src/features/customers/`<br>`backend/customer-api/src/test/java/com/democorp/customermaster/service/` |
| Request fields (F-002): MTNCUSTD field lengths as column widths, strict JSON binding, `errors[]` of APP0400 | `RequestTextLengthTest`, `StorableTextValidatorTest`, `PurposeDeserializerTest`, `StrictRequestBindingIT`, `RequestFieldErrorsIT` | `backend/customer-api/src/test/java/com/democorp/customermaster/controller/dto/`<br>`backend/customer-api/src/test/java/com/democorp/customermaster/controller/` |
| State list (F-003): PMTSTATER "Name Contains" filter, F7 sort and read-only statements | `StateServiceTest`, `StateRepositoryIT` | `backend/customer-api/src/test/java/com/democorp/customermaster/service/`<br>`backend/customer-api/src/test/java/com/democorp/customermaster/repository/` |
| Address client settings (UC-07): the USPS_ID and USPS_PWD data areas become environment variables | `AddressValidationPropertiesTest` | `backend/address-validation/src/test/java/com/democorp/customermaster/address/` |
| USPS text redaction (UC-07, no IBM i counterpart): `UspsTextRedactor` replaces echoes of the outbound request and every configured credential in USPS text before the Web Tools client logs it and before a `Description` becomes the DEM9898 message | `UspsTextRedactorTest` | `backend/address-validation/src/test/java/com/democorp/customermaster/address/` |
| Generator command line and CSZ input (F-004): LOADCUST2's row count and LOADCUSTR's CSZ read | `CszSourceTest`, `CustomerGeneratorRunnerOptionsTest`, `CustomerGeneratorRunnerOutputTest`, `CustomerGeneratorRunnerLoadFailureTest`, `GeneratorPropertiesBindingTest`, `GeneratorStartupFailureReporterTest` | `backend/customer-api/src/test/java/com/democorp/customermaster/generator/` |
| Migration locations and the load guard seen by readers (F-004) | `FlywayLocationsIT`, `ReadsWaitForLoadIT`, `LockWaitingReadsTest` | `backend/customer-api/src/test/java/com/democorp/customermaster/migration/`<br>`backend/customer-api/src/test/java/com/democorp/customermaster/controller/`<br>`backend/customer-api/src/test/java/com/democorp/customermaster/repository/` |
| Error model (`5250_Subfile/CRTMSGF.CLLE`, `Service_Pgms/SRV_MSG.RPGLE`, `Service_Pgms/SRV_SQL.SQLRPGLE`): framework and connector failures, SQLSTATE logging, redacted error logs, lock waits on a saturated pool, rejected CORS requests, and the problem bodies the Compose `frontend`'s nginx writes itself under `/api/` (502 and 504 gateway failures, requests it rejects before forwarding), held equal to what `ProblemFactory` builds | `ApiExceptionHandlerFieldErrorsTest`, `ApiExceptionHandlerPoolSaturationTest`, `ProblemFactorySqlStateTest`, `ProblemErrorReportValveTest`, `ConnectorErrorProblemIT`, `MalformedQueryParameterIT`, `ParameterParseFailureInterceptorTest`, `RedactedErrorLogIT`, `RedactedThrowableTest`, `ConnectionPoolSaturationIT`, `CorsPreflightProblemIT`, `ProblemCorsProcessorTest`, `NginxGatewayProblemTest` | `backend/customer-api/src/test/java/com/democorp/customermaster/controller/`<br>`backend/customer-api/src/test/java/com/democorp/customermaster/config/`<br>`backend/customer-api/src/test/java/com/democorp/customermaster/security/` |
| API contract (every feature): the committed `openapi/customer-master-api.yaml` | `OpenApiSnapshotIT`, `CodePointLengthSchemaCustomizerTest` | `backend/customer-api/src/test/java/com/democorp/customermaster/controller/` |
| Platform and configuration (no IBM i counterpart): settings validation, datasource guards and bounds, transactions, health probes | `AppPropertiesTest`, `DataSourceCredentialsGuardTest`, `DataSourceSchemaGuardTest`, `DatabaseConnectionBoundsIT`, `TransactionManagerConfigTest`, `BrokenConnectionTransactionManagerTest`, `ConnectionPoolSaturationTest`, `StrictJsonBindingConfigTest`, `HealthProbesIT`, `HealthProbesOutageTest` | `backend/customer-api/src/test/java/com/democorp/customermaster/config/`<br>`backend/customer-api/src/test/java/com/democorp/customermaster/controller/` |

## Evidence statement

> The tests are derived from reading the IBM i source members and the rules this plan restates. They are not executed against the original IBM i programs, which do not run in this environment. Passing them shows that the target meets the behaviour as read from the source; it does not establish verified behavioural equivalence with the IBM i application.
