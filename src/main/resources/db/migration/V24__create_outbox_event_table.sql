CREATE TABLE outbox_event
(
    id              UUID PRIMARY KEY         NOT NULL DEFAULT uuidv7(),
    destination     TEXT                     NOT NULL,
    event_type      TEXT                     NOT NULL,
    kafka_key       TEXT                     NOT NULL,
    payload         TEXT                     NOT NULL,
    payload_version INTEGER                  NOT NULL,
    status          TEXT                     NOT NULL DEFAULT 'PENDING',
    attempt_count   INTEGER                  NOT NULL DEFAULT 0,
    claim_id        UUID,
    locked_at       TIMESTAMP WITH TIME ZONE,
    locked_by       TEXT,
    next_attempt_at TIMESTAMP WITH TIME ZONE,
    sent_at         TIMESTAMP WITH TIME ZONE,
    last_error      TEXT,
    created         TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated         TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);

CREATE TRIGGER update_outbox_event_updated
    BEFORE UPDATE
    ON outbox_event
    FOR EACH ROW
EXECUTE FUNCTION update_updated_column();

CREATE INDEX outbox_event_status_next_attempt_at_created_idx ON outbox_event (status, next_attempt_at, created);
CREATE INDEX outbox_event_status_locked_at_idx ON outbox_event (status, locked_at);
