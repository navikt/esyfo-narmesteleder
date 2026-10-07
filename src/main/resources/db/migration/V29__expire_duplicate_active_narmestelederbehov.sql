UPDATE nl_behov b
SET behov_status = 'BEHOV_FULFILLED'
FROM nl_behov anchor
WHERE anchor.id = '16cac3ed-b483-4a26-b329-a809c310c0bd'
  AND anchor.behov_status IN ('BEHOV_CREATED', 'DIALOGPORTEN_STATUS_SET_REQUIRES_ATTENTION')
  AND b.sykemeldt_fnr = anchor.sykemeldt_fnr
  AND b.orgnummer = anchor.orgnummer
  AND b.behov_status IN ('BEHOV_CREATED', 'DIALOGPORTEN_STATUS_SET_REQUIRES_ATTENTION');

UPDATE nl_behov
SET behov_status = 'BEHOV_EXPIRED'
WHERE id IN (
    '0438ed71-7326-4cf8-a414-21c66b0be39e',
    '48d0956a-558b-479e-86a4-87c84b0fda19'
)
  AND behov_status IN ('BEHOV_CREATED', 'DIALOGPORTEN_STATUS_SET_REQUIRES_ATTENTION');
