DROP INDEX CONCURRENTLY IF EXISTS narmeste_leder_aktiv_orgnummer_idx;
CREATE INDEX CONCURRENTLY IF NOT EXISTS narmeste_leder_aktiv_orgnummer_idx
    ON narmeste_leder (orgnummer, id) WHERE aktiv_tom IS NULL;
