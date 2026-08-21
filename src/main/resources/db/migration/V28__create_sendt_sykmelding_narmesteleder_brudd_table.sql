CREATE TABLE sendt_sykmelding_narmesteleder_brudd
(
    id              UUID PRIMARY KEY NOT NULL DEFAULT uuidv7(),
    sykmelding_id   UUID UNIQUE      NOT NULL,
    fnr             TEXT             NOT NULL,
    orgnummer       TEXT             NOT NULL,
    kafka_topic     TEXT             NOT NULL,
    kafka_partition INTEGER          NOT NULL,
    kafka_offset    BIGINT           NOT NULL,
    kilde           TEXT             NOT NULL,
    created         TIMESTAMP WITH TIME ZONE  DEFAULT CURRENT_TIMESTAMP NOT NULL
);
