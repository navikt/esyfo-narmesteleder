ALTER TABLE nl_behov
    ALTER COLUMN narmeste_leder_fnr DROP NOT NULL,
    ALTER COLUMN avbrutt_narmesteleder_id DROP NOT NULL,
    ALTER COLUMN leesah_status DROP NOT NULL;
