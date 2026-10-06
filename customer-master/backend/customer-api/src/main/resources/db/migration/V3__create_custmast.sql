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
-- Columns keep the source order and the source names in lowercase, with no
-- underscores, plus row_version last. CustomerCopyWriter's COPY and the V5
-- seed list them explicitly in this order, and Customer maps each one by name
-- with @Column, so they must not be reordered or renamed.
--
-- Intentional differences from the source (recorded in
-- docs/deviations-and-open-questions.md):
--   * Text columns are varchar with the source lengths instead of CHAR, so no
--     trailing padding is stored. Customer search matches LIKE against
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
-- (name, city, state, custid) > (:kName, :kCity, :kState, :kId): the first
-- page stops after 13 index entries, and a deep page costs the same as the
-- first. custid is the unique tiebreaker keyset pagination needs.

CREATE INDEX custmast_name ON custmast (name varchar_pattern_ops);
CREATE INDEX custmast_city ON custmast (city varchar_pattern_ops);
CREATE INDEX custmast_state ON custmast (state);
CREATE INDEX custmast_search_keyset ON custmast (name, city, state, custid);
