CREATE TABLE nl_dialog_dispatch (
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

CREATE INDEX nl_dialog_dispatch_due_idx ON nl_dialog_dispatch (available_at, behov_id)
    WHERE status IN ('READY', 'CLAIMED');

CREATE INDEX nl_dialog_dispatch_observable_idx ON nl_dialog_dispatch (status, pending_since)
    WHERE status IN ('READY', 'CLAIMED', 'PARKED');

-- Scheduling in the writer's transaction also covers existing ingestion and maintenance writers.
CREATE FUNCTION schedule_nl_dialog_dispatch() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'UPDATE' THEN
        IF NEW.behov_status IS NOT DISTINCT FROM OLD.behov_status
           AND NEW.dialog_id IS NOT DISTINCT FROM OLD.dialog_id THEN
            RETURN NEW;
        END IF;
    END IF;

    INSERT INTO nl_dialog_dispatch (behov_id, available_at)
    SELECT NEW.id, GREATEST(clock_timestamp(), NEW.created + interval '10 seconds')
    WHERE NEW.behov_status = 'BEHOV_CREATED'
       OR NEW.dialog_id IS NOT NULL
       OR EXISTS (SELECT 1 FROM nl_dialog_dispatch WHERE behov_id = NEW.id)
    ON CONFLICT (behov_id) DO UPDATE SET
        revision = nl_dialog_dispatch.revision + 1,
        status = CASE WHEN nl_dialog_dispatch.status = 'CLAIMED' THEN 'CLAIMED' ELSE 'READY' END,
        available_at = CASE WHEN nl_dialog_dispatch.status = 'CLAIMED'
                            THEN nl_dialog_dispatch.available_at ELSE clock_timestamp() END,
        pending_since = COALESCE(nl_dialog_dispatch.pending_since, clock_timestamp()),
        attempts = 0,
        last_failure = NULL;
    RETURN NEW;
END;
$$;

CREATE TRIGGER nl_behov_schedule_dialog_dispatch
    AFTER INSERT OR UPDATE OF behov_status, dialog_id ON nl_behov
    FOR EACH ROW EXECUTE FUNCTION schedule_nl_dialog_dispatch();
