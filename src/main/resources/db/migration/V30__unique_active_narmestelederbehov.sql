CREATE UNIQUE INDEX CONCURRENTLY uq_nl_behov_active_employee_org
    ON nl_behov (sykemeldt_fnr, orgnummer)
    WHERE behov_status IN ('BEHOV_CREATED', 'DIALOGPORTEN_STATUS_SET_REQUIRES_ATTENTION');
