-- V4: customer id sequence custmast_id_seq, replacing the CUSTNEXT data area.
--
-- Source: 5250_Subfile/CRTDTAARA.clle creates CUSTNEXT with VALUE('EEEE').
-- AddRecd in 5250_Subfile/MTNCUSTR.SQLRPGLE locks the data area, advances it
-- with BASE36ADD (BASE36/SRV_BASE36.RPGLE) and uses the advanced value, so the
-- first interactive id the source issues is EEEF.
--
-- Purpose: atomic allocation of the 4-character base-36 custid. Between
-- restarts, nextval hands out no value twice, whatever the isolation level,
-- and a rolled-back add only leaves a gap. The only restart is the generator's
-- guarded, transactional ALTER SEQUENCE ... RESTART WITH, taken under the
-- ACCESS EXCLUSIVE lock on custmast in the same transaction that replaces the
-- rows. It takes effect or rolls back together with them, so no remaining row
-- holds a value that nextval hands out again.
-- CustomerId.fromOrdinal turns the ordinal into the id using BASE36ADD's digit
-- alphabet: A..Z = 0..25, 0..9 = 26..35.
--
--   START WITH 191957  EEEF, the successor of CUSTNEXT's initial EEEE
--                      (ordinal 191956; despite the data-area comment calling
--                      it "a really high number", it lies below LOADCUSTR's
--                      first id 1001, ordinal 1294371).
--   MINVALUE 0         AAAA.
--   MAXVALUE 1679615   9999, the last of the 36^4 = 1679616 ids.
--   NO CYCLE           Exhaustion raises SQLSTATE 2200H, which becomes
--                      CustomerIdExhaustedException and HTTP 503 APP0503,
--                      instead of BASE36ADD's rollover from 9999 to AAAA,
--                      which made the source reissue existing ids.
--   CACHE 1            No per-session blocks of values, so ids strictly
--                      increase in allocation order.
--
-- Access protocol: only CustomerIdAllocator calls nextval or ALTER SEQUENCE on
-- this sequence, and always after taking a lock on table custmast in the same
-- transaction (ROW EXCLUSIVE for an add, ACCESS EXCLUSIVE for a generator load):
-- table lock first, sequence second, so adds and loads cannot deadlock. The
-- restart never uses setval. The sequence belongs to the migration role
-- (DB_USER), which ALTER SEQUENCE requires.
--
-- The name is unqualified: the schema comes from DB_SCHEMA through
-- spring.flyway.schemas. custmast.custid has no column default, because ids are
-- assigned explicitly by the allocator; the V5 seed rows AAAB..AAIM lie below
-- the start value and leave the sequence untouched.

CREATE SEQUENCE custmast_id_seq
  AS integer
  INCREMENT BY 1
  MINVALUE 0
  MAXVALUE 1679615
  START WITH 191957
  CACHE 1
  NO CYCLE;
