BEGIN;
ALTER TABLE sendt_sykmelding
    DROP CONSTRAINT IF EXISTS unique_fnr_and_orgnr;

ALTER TABLE sendt_sykmelding
    ADD CONSTRAINT  unique_fnr_and_orgnr UNIQUE (fnr, orgnummer);
COMMIT;
