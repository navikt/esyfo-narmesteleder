ALTER TABLE nl_behov
    ADD COLUMN expired_in_dialogporten timestamptz DEFAULT NULL;
