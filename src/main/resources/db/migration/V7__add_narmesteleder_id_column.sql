DELETE FROM nl_behov;

ALTER TABLE nl_behov ADD COLUMN narmesteleder_fnr UUID not null;
