-- V1: ICU collation customer_sort, the sort order of the customer and state
-- key and sort columns.
--
-- Source: BASE36/SRV_BASE36.RPGLE (lines 15-16, 37-38) increments ids over the
-- alphabet A..Z then 0..9 and states that this sequence follows the EBCDIC raw
-- sorting order, in which letters precede digits. The STATES columns of
-- 5250_Subfile/States.sql are likewise EBCDIC (code page 273).
--
-- Purpose: digits sort after Latin letters, as in EBCDIC, so base-36 id
-- allocation order equals sort order:
--   AAAA < AAAZ < AAA0 < ZZZZ < 0000 < 101A < 1010
-- PostgreSQL "C" order would put digits first (1010 before 101A, although 1010
-- is allocated after 101A). V2 (states) and V3 (custmast) declare their key and
-- sort columns COLLATE customer_sort.
--
-- Definition: the ICU root collation ('und') with one tailoring rule,
--   &[before 1]α<0<1<2<3<4<5<6<7<8<9
-- which gives the ASCII digits 0..9, in that order, primary weights after
-- every Latin letter and before Greek (α) and Cyrillic. Only the ASCII digits
-- move: other digit forms (superscript, Arabic-Indic, fullwidth) keep their
-- ICU root position before letters. The α stays a literal character, because
-- ICU tailoring rules read the escape \u03B1 as the text u03B1.
--
-- The reorder locale 'und-u-kr-latn-digit' is not used: on this PostgreSQL/ICU
-- build its digit reorder applies only to sort keys (ORDER BY), not to
-- comparisons (<, >, row comparisons) or B-tree index order, so index scans
-- and keyset predicates would put digits before letters. The tailoring rule
-- places ASCII digits after Latin letters consistently on every path.
--
-- Deterministic on purpose (the default): the regex CHECK on custid (V3) and
-- LIKE prefix scans over the varchar_pattern_ops indexes need a deterministic
-- collation.
--
-- ICU version: PostgreSQL records the ICU version behind this collation. After
-- an ICU library change (a new database image), REINDEX the indexes on
-- customer_sort columns, then run ALTER COLLATION customer_sort REFRESH VERSION.
-- The pinned postgres:18.6 image prevents unplanned drift.
--
-- Known difference: among punctuation, ICU order differs from EBCDIC code-point
-- order (recorded in docs/deviations-and-open-questions.md).
--
-- The name is unqualified: Flyway creates it in the migration schema (DB_SCHEMA).

CREATE COLLATION customer_sort (
  provider = icu,
  locale = 'und',
  rules = '&[before 1]α<0<1<2<3<4<5<6<7<8<9'
);
