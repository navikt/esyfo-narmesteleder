-- One-time backfill of dispatch work for behov last changed before writers scheduled their own changes (V33).
-- Both statements are idempotent, and their result does not depend on their order. To repeat the backfill
-- by hand, for example after a rollback to a binary without explicit scheduling, run this file in one transaction.

-- Fulfil open behov that already have an active relation, and schedule every row it fulfils.
-- A row without a dialog ID may have had a CREATE attempted by the legacy task, so it is tracked as create_started.
WITH fulfilled AS (
    UPDATE nl_behov b
    SET behov_status = 'BEHOV_FULFILLED'
    WHERE b.behov_status IN ('BEHOV_CREATED', 'DIALOGPORTEN_STATUS_SET_REQUIRES_ATTENTION')
      AND EXISTS (
          SELECT 1
          FROM narmeste_leder r
          WHERE r.sykmeldt_fnr = b.sykemeldt_fnr
            AND r.orgnummer = b.orgnummer
            AND r.aktiv_tom IS NULL
            AND r.aktiv_fom <= clock_timestamp()
            AND r.narmeste_leder_id IS DISTINCT FROM b.avbrutt_narmesteleder_id
      )
    RETURNING b.id, b.dialog_id, b.created
)
INSERT INTO dialog_dispatch (behov_id, available_at, create_started)
SELECT id, GREATEST(clock_timestamp(), created + interval '10 seconds'), dialog_id IS NULL
FROM fulfilled
ON CONFLICT (behov_id) DO UPDATE SET
    revision = dialog_dispatch.revision + 1,
    status = CASE WHEN dialog_dispatch.status = 'CLAIMED' THEN 'CLAIMED' ELSE 'READY' END,
    available_at = CASE WHEN dialog_dispatch.status = 'CLAIMED'
                        THEN dialog_dispatch.available_at ELSE clock_timestamp() END,
    pending_since = COALESCE(dialog_dispatch.pending_since, clock_timestamp()),
    attempts = 0,
    last_failure = NULL,
    create_started = dialog_dispatch.create_started OR EXCLUDED.create_started;

-- Seed work for legacy behov that still need a CREATE, a completion or an expiry, and track a possibly attempted
-- CREATE on open rows without a dialog ID. Closed rows without a dialog ID are deliberately excluded.
INSERT INTO dialog_dispatch (behov_id, create_started)
SELECT b.id, b.behov_status = 'BEHOV_CREATED' AND b.dialog_id IS NULL
FROM nl_behov b
LEFT JOIN dialog_dispatch q ON q.behov_id = b.id
WHERE (q.behov_id IS NULL
       OR (b.behov_status = 'BEHOV_CREATED' AND b.dialog_id IS NULL AND NOT q.create_started))
  AND (
      b.behov_status = 'BEHOV_CREATED'
      OR (b.behov_status = 'BEHOV_FULFILLED' AND b.dialog_id IS NOT NULL)
      OR (b.behov_status IN ('BEHOV_EXPIRED', 'ERROR')
          AND b.dialog_id IS NOT NULL AND b.expired_in_dialogporten IS NULL)
  )
ON CONFLICT (behov_id) DO UPDATE SET create_started = true
WHERE NOT dialog_dispatch.create_started AND EXCLUDED.create_started;
