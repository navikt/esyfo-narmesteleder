DELETE FROM nl_behov;

ALTER TABLE nl_behov ADD COLUMN avbrutt_narmesteleder_id UUID not null;
