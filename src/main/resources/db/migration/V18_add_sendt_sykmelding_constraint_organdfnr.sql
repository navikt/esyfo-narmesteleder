ALTER TABLE sendt_sykmelding ADD CONSTRAINT unique_fnr_and_orgnr UNIQUE (fnr, orgnummer);
