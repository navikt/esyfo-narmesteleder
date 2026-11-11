CREATE TABLE sykmeldingsregister_dead_letter
(
    source_partition INTEGER     NOT NULL,
    source_offset    BIGINT      NOT NULL,
    reason           TEXT        NOT NULL,
    created          TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT sykmeldingsregister_dead_letter_pkey PRIMARY KEY (source_partition, source_offset)
);

CREATE TABLE sykmelding
(
    sykmelding_id    UUID        NOT NULL,
    person_id        UUID        NOT NULL,
    fnr              VARCHAR(11) NOT NULL,
    orgnummer        VARCHAR(9)  NOT NULL,
    sent_at          TIMESTAMPTZ NOT NULL,
    max_tom          DATE        NOT NULL,
    source_partition INTEGER     NOT NULL,
    source_offset    BIGINT      NOT NULL,
    created          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated          TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT sykmelding_pkey PRIMARY KEY (sykmelding_id),
    CONSTRAINT sykmelding_person_fkey FOREIGN KEY (person_id) REFERENCES person (id)
);

CREATE INDEX sykmelding_person_orgnummer_max_tom_idx ON sykmelding (person_id, orgnummer, max_tom);

CREATE TRIGGER update_sykmelding_updated
    BEFORE UPDATE
    ON sykmelding
    FOR EACH ROW
EXECUTE FUNCTION update_updated_column();

CREATE TABLE narmesteleder_confirmation
(
    sykmelding_id UUID        NOT NULL,
    person_id     UUID        NOT NULL,
    orgnummer     VARCHAR(9)  NOT NULL,
    confirmed_at  TIMESTAMPTZ NOT NULL,
    created       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated       TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT narmesteleder_confirmation_pkey PRIMARY KEY (sykmelding_id),
    CONSTRAINT narmesteleder_confirmation_person_fkey FOREIGN KEY (person_id) REFERENCES person (id)
);

CREATE INDEX narmesteleder_confirmation_person_orgnummer_idx ON narmesteleder_confirmation (person_id, orgnummer);

CREATE TRIGGER update_narmesteleder_confirmation_updated
    BEFORE UPDATE
    ON narmesteleder_confirmation
    FOR EACH ROW
EXECUTE FUNCTION update_updated_column();
