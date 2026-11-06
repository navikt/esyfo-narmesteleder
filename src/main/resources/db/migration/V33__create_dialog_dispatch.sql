CREATE TABLE dialog_dispatch (
    behov_id UUID PRIMARY KEY REFERENCES nl_behov(id) ON DELETE CASCADE,
    status TEXT NOT NULL DEFAULT 'READY' CHECK (status IN ('READY', 'CLAIMED', 'IDLE', 'PARKED')),
    revision BIGINT NOT NULL DEFAULT 1 CHECK (revision > 0),
    available_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    claim_token UUID,
    create_started BOOLEAN NOT NULL DEFAULT false,
    intended_expiry TIMESTAMPTZ,
    attempts INTEGER NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    pending_since TIMESTAMPTZ DEFAULT now(),
    last_failure TEXT,
    CHECK ((status = 'CLAIMED') = (claim_token IS NOT NULL)),
    CHECK (last_failure IS NULL OR last_failure IN ('invalid_request', 'upstream', 'timeout', 'unexpected'))
);

CREATE INDEX dialog_dispatch_due_idx ON dialog_dispatch (available_at, behov_id)
    WHERE status IN ('READY', 'CLAIMED');

CREATE INDEX dialog_dispatch_observable_idx ON dialog_dispatch (status, pending_since)
    WHERE status IN ('READY', 'CLAIMED', 'PARKED');
