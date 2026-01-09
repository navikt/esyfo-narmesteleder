CREATE INDEX IF NOT EXISTS idx_nl_behov__orgnummer_sykemeldt_fnr_behov_status
    ON nl_behov (orgnummer, sykemeldt_fnr, behov_status);

CREATE INDEX IF NOT EXISTS idx_nl_behov__orgnummer_behov_status_created
    ON nl_behov (orgnummer, behov_status, created);

CREATE INDEX IF NOT EXISTS idx_nl_behov__behov_status_created
    ON nl_behov (behov_status, created);
