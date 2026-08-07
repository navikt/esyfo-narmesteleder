-- Søk mot person sto for omtrent 83 % av bufferbruken og 99,5 % av I/O-en; denne dekkende
-- indeksen unngår tilfeldige heap-oppslag for fornavn, mellomnavn og etternavn ved fnr-oppslag.
DROP INDEX CONCURRENTLY IF EXISTS person_fnr_names_idx;
CREATE INDEX CONCURRENTLY IF NOT EXISTS person_fnr_names_idx ON person (fnr) INCLUDE (fornavn, mellomnavn, etternavn);
