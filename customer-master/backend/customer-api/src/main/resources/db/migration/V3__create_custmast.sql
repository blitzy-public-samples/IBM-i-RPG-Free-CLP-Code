-- V3: table custmast, the customer master, with its constraints and indexes.
--
-- Source: 5250_Subfile/Custmast2.sql governs the definition: twelve columns
-- including ChgTime and ChgUser, the primary key on CustID, and the indexes
-- custmast_name, custmast_city and custmast_state. The table definition in
-- 5250_Subfile/Custmast.sql lacks ChgTime and ChgUser and is superseded; that
-- script contributes only the seed rows, which db/seed/V5__seed_customers.sql
-- owns.
--
-- Purpose: the customers behind search (CustomerSearchRepository), display,
-- edit and add (CustomerRepository, CustomerIdAllocator) and the test-data
-- generator (CustomerLoader, CustomerCopyWriter).
--
-- Columns keep the source order of 5250_Subfile/Custmast2.sql for
-- traceability, plus row_version last. Their names are the contract: the
-- source names in lowercase, with no underscores, plus row_version.
-- CustomerCopyWriter's COPY, the V5 seed and Customer's @Column mappings
-- refer to each column by name, so no column may be renamed.
--
-- Intentional differences from the source (recorded in
-- docs/deviations-and-open-questions.md):
--   * Text columns are varchar with the source lengths instead of CHAR, so no
--     automatic padding is added. Values written by the application and the
--     generator carry no trailing blanks, because TextNormalizer.field strips
--     them. Customer search matches LIKE against
--     rpad(name, 40) and rpad(city, 20), which reproduces the Db2 match on the
--     blank-padded CHAR value. The code columns custid, state and active stay
--     char(n).
--   * corpphone, acctmgr and acctphone are NOT NULL DEFAULT '' instead of
--     nullable with DEFAULT ' '.
--   * active is NOT NULL and checked to Y or N; the source enforces Y/N only
--     in the maintenance program.
--   * custid is checked to the 4-character base-36 format. The regex ranges
--     are code-point ranges, unaffected by the collation; a char(4) 'ABC' reads
--     as text 'ABC' and fails, and so does lowercase 'ab12'.
--   * state references states (V2). The source keeps STATES and CUSTMAST in
--     different libraries; both now live in the one migration schema
--     (DB_SCHEMA, through spring.flyway.schemas), so the foreign key can exist.
--   * chgtime is timestamptz, an instant shown in browser-local time; the
--     service always sets it from the injected Clock.
--   * chguser defaults to CURRENT_USER, the database role, instead of the
--     job's USER. The default serves only inserts made outside the
--     application, as DEFAULT USER did; the service always writes the
--     authenticated principal. Such an insert needs a role name of at most 18
--     characters (the default role customermaster qualifies).
--   * row_version is new: the optimistic-concurrency token that replaces
--     ChgTime equality. It is 0 on insert, and Spring Data JDBC @Version
--     increments it through UPDATE ... WHERE custid = ? AND row_version = ?.
--   * The record format name is not carried.
--   * One new index, custmast_search_keyset (see below).
--
-- Collation: custid, name, city and state use customer_sort (V1), so digits
-- sort after Latin letters as in EBCDIC and base-36 allocation order equals
-- sort order. addr and zip are never sorted and keep the database default.
--
-- custid has no column default: CustomerIdAllocator assigns it from
-- custmast_id_seq (V4).
--
-- Names are unqualified: Flyway creates them in the migration schema.

CREATE TABLE custmast (
  custid      char(4)        COLLATE customer_sort NOT NULL,
  name        varchar(40)    COLLATE customer_sort NOT NULL,
  addr        varchar(40)    NOT NULL,
  city        varchar(20)    COLLATE customer_sort NOT NULL,
  state       char(2)        COLLATE customer_sort NOT NULL,
  zip         varchar(10)    NOT NULL,
  corpphone   varchar(20)    NOT NULL DEFAULT '',
  acctmgr     varchar(40)    NOT NULL DEFAULT '',
  acctphone   varchar(20)    NOT NULL DEFAULT '',
  active      char(1)        NOT NULL DEFAULT 'Y'
              CONSTRAINT custmast_active_ck CHECK (active IN ('Y','N')),
  chgtime     timestamptz(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
  chguser     varchar(18)    NOT NULL DEFAULT CURRENT_USER,
  row_version bigint         NOT NULL DEFAULT 0,
  CONSTRAINT custmast_pkey PRIMARY KEY (custid),
  CONSTRAINT custmast_custid_ck CHECK (custid ~ '^[A-Z0-9]{4}$'),
  CONSTRAINT custmast_state_fk FOREIGN KEY (state) REFERENCES states (state)
);

-- Indexes.
--
-- custmast_name, custmast_city and custmast_state keep the names and columns
-- of Custmast2.sql. The varchar_pattern_ops operator class lets a prefix
-- predicate such as name LIKE 'ABC%' (the namePrefix and cityPrefix predicates
-- of the search) use an index range under the ICU column collation, as the
-- plain Db2 index does.
--
-- custmast_search_keyset uses the column collations and no operator class, so
-- it matches ORDER BY name, city, state, custid and the keyset row comparison
-- (name, city, state, custid) > (:kName, :kCity, :kState, :kId), which is its
-- index condition: a cursor page starts at the keyset position, with no
-- OFFSET. custid is the unique tiebreaker keyset pagination needs. A search
-- with no name, city or state filter walks this index in order from any keyset
-- position and stops once 13 matching rows are found (the active = 'Y' filter
-- can make it examine more entries), so a deep page of that walk costs what
-- the first page costs. Name, city and state filters have no such bound (see
-- CustomerSearchRepository): a literal name or city lead sorts a materialized
-- candidate set; other filters can examine many entries before 13 match.

CREATE INDEX custmast_name ON custmast (name varchar_pattern_ops);
CREATE INDEX custmast_city ON custmast (city varchar_pattern_ops);
CREATE INDEX custmast_state ON custmast (state);
CREATE INDEX custmast_search_keyset ON custmast (name, city, state, custid);
