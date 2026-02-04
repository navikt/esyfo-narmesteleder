CREATE INDEX idx_sendt_sykmelding_fnr
    ON sendt_sykmelding (fnr);
CREATE INDEX idx_sendt_sykmelding_orgnummer
    ON sendt_sykmelding (orgnummer);
CREATE INDEX idx_sendt_sykmelding_fnr_orgnummer_tom
    ON sendt_sykmelding (fnr, orgnummer, tom);
