-- Run cleanup repeatedly until it returns zero, then run seeding repeatedly until zero.
-- New workers must be disabled and old Dialogporten processors drained.
BEGIN;
WITH candidates AS (
    SELECT b.id
    FROM nl_behov b
    WHERE b.behov_status IN ('BEHOV_CREATED', 'DIALOGPORTEN_STATUS_SET_REQUIRES_ATTENTION')
      AND EXISTS (
          SELECT 1 FROM narmeste_leder r
          WHERE r.sykmeldt_fnr = b.sykemeldt_fnr
            AND r.orgnummer = b.orgnummer
            AND r.aktiv_tom IS NULL
            AND r.aktiv_fom <= clock_timestamp()
            AND r.narmeste_leder_id IS DISTINCT FROM b.avbrutt_narmesteleder_id
      )
    ORDER BY b.created, b.id
    LIMIT 500
    FOR UPDATE OF b SKIP LOCKED
), fulfilled AS (
    UPDATE nl_behov b SET behov_status = 'BEHOV_FULFILLED'
    FROM candidates c WHERE b.id = c.id
    RETURNING b.id
)
SELECT count(*) AS fulfilled FROM fulfilled;
COMMIT;

BEGIN;
WITH candidates AS (
    SELECT b.id, b.behov_status, b.dialog_id
    FROM nl_behov b
    LEFT JOIN nl_dialog_dispatch q ON q.behov_id = b.id
    WHERE (q.behov_id IS NULL
           OR (b.behov_status = 'BEHOV_CREATED' AND b.dialog_id IS NULL AND NOT q.create_started))
      AND (
          b.behov_status = 'BEHOV_CREATED'
          OR (b.behov_status = 'BEHOV_FULFILLED' AND b.dialog_id IS NOT NULL)
          OR (b.behov_status IN ('BEHOV_EXPIRED', 'ERROR')
              AND b.dialog_id IS NOT NULL AND b.expired_in_dialogporten IS NULL)
      )
    ORDER BY b.created, b.id
    LIMIT 500
    FOR UPDATE OF b SKIP LOCKED
), seeded AS (
    INSERT INTO nl_dialog_dispatch (behov_id, create_started)
    SELECT id, behov_status = 'BEHOV_CREATED' AND dialog_id IS NULL FROM candidates
    ON CONFLICT (behov_id) DO UPDATE SET create_started = true
    WHERE NOT nl_dialog_dispatch.create_started AND EXCLUDED.create_started
    RETURNING behov_id
)
SELECT count(*) AS seeded FROM seeded;
COMMIT;
