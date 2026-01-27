CREATE UNIQUE INDEX sendt_sykmelding_index_sykmelding_id ON sendt_sykmelding (sykmelding_id);
CREATE INDEX sendt_sykmelding_index_fnr_orgnummer_tom ON sendt_sykmelding (fnr, orgnummer, tom);
CREATE INDEX sendt_sykmelding_index_tom ON sendt_sykmelding (tom);
