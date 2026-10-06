-- V2: table states, the US state codes and names, and its 58 rows.
--
-- Source: 5250_Subfile/States.sql (a RUNSQLSTM script): table STATES with
-- primary key state_primary_key, unique constraint state_name_unique, the table
-- label 'US States' and 58 rows.
--
-- Purpose: the codes behind the State prompt (StatePicker, GET /api/states),
-- the State field rule (DEM0503) and the foreign key custmast_state_fk from
-- custmast.state (V3). StateService loads the rows once into its cache, and the
-- generator keeps only CSZ rows whose state is listed here.
--
-- Translation:
--   The source columns use EBCDIC code page 273; the database is UTF-8, and
--   ordering comes from the customer_sort collation (V1), so the "By Code" and
--   "By Name" sorts of the prompt follow the same rules as the customer columns.
--   The source keeps STATES and CUSTMAST in different libraries; both now live
--   in the one migration schema (DB_SCHEMA, through spring.flyway.schemas), so
--   the foreign key from custmast can exist.
--   NAME CHAR(30) becomes varchar(30), which stores no trailing padding.
--   StateRepository.search matches rpad(upper(name), 30), so a user '_' still
--   matches a pad blank as it does in Db2: nameContains=texas_ finds Texas.
--   The table label becomes COMMENT ON TABLE. The record format name, the
--   schema statement and the script's table re-creation are not carried:
--   Flyway applies each versioned migration once.
--   Names keep the source's mixed case, because the search compares
--   upper(name). The rows are in source order.
--
-- Names are unqualified and lowercase; the consumers (State, StateRepository,
-- StateService, V3) use them verbatim.

CREATE TABLE states (
  state char(2)     COLLATE customer_sort NOT NULL CONSTRAINT state_primary_key PRIMARY KEY,
  name  varchar(30) COLLATE customer_sort NOT NULL,
  CONSTRAINT state_name_unique UNIQUE (name)
);

COMMENT ON TABLE states IS 'US States';

INSERT INTO states (state, name) VALUES
  ('AA', 'Armed Forces America'),
  ('AE', 'Armed Forces'),
  ('AK', 'Alaska'),
  ('AL', 'Alabama'),
  ('AS', 'American Samoa'),
  ('AZ', 'Arizona'),
  ('AR', 'Arkansas'),
  ('CA', 'California'),
  ('CO', 'Colorado'),
  ('CT', 'Connecticut'),
  ('DE', 'Delaware'),
  ('DC', 'District of Columbia'),
  ('FL', 'Florida'),
  ('GA', 'Georgia'),
  ('GU', 'Guam'),
  ('HI', 'Hawaii'),
  ('ID', 'Idaho'),
  ('IL', 'Illinois'),
  ('IN', 'Indiana'),
  ('IA', 'Iowa'),
  ('KS', 'Kansas'),
  ('KY', 'Kentucky'),
  ('LA', 'Louisiana'),
  ('ME', 'Maine'),
  ('MD', 'Maryland'),
  ('MA', 'Massachusetts'),
  ('MI', 'Michigan'),
  ('MN', 'Minnesota'),
  ('MP', 'Northern Mariana Islands'),
  ('MS', 'Mississippi'),
  ('MO', 'Missouri'),
  ('MT', 'Montana'),
  ('NE', 'Nebraska'),
  ('NV', 'Nevada'),
  ('NH', 'New Hampshire'),
  ('NJ', 'New Jersey'),
  ('NM', 'New Mexico'),
  ('NY', 'New York'),
  ('NC', 'North Carolina'),
  ('ND', 'North Dakota'),
  ('OH', 'Ohio'),
  ('OK', 'Oklahoma'),
  ('OR', 'Oregon'),
  ('PA', 'Pennsylvania'),
  ('PR', 'Puerto Rico'),
  ('RI', 'Rhode Island'),
  ('SC', 'South Carolina'),
  ('SD', 'South Dakota'),
  ('TN', 'Tennessee'),
  ('TX', 'Texas'),
  ('UT', 'Utah'),
  ('VT', 'Vermont'),
  ('VA', 'Virginia'),
  ('WA', 'Washington'),
  ('WV', 'West Virginia'),
  ('WI', 'Wisconsin'),
  ('VI', 'Virgin Islands'),
  ('WY', 'Wyoming');
