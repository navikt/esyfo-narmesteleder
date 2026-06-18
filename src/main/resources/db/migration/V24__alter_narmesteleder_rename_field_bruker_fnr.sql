DROP INDEX narmeste_leder_fnr_idx;
ALTER TABLE narmeste_leder RENAME COLUMN bruker_fnr TO sykmeldt_fnr;
CREATE INDEX narmeste_leder_fnr_idx ON narmeste_leder (sykmeldt_fnr);
