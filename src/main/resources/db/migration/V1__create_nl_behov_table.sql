create type BEHOV_STATUS as enum ('RECEIVED', 'PENDING', 'COMPLETED', 'ERROR');

create table nl_behov
(
    -- TODO: Hvis vi ikke får brukt postgres18 og endret til uuid7,
    --  burde vi kanskje ha noe annet som PK for indekseringens del
    id                 UUID PRIMARY KEY         NOT NULL DEFAULT gen_random_uuid(),
    orgnummer          VARCHAR(9)               NOT NULL,
    sykemeldt_fnr      VARCHAR(11)              NOT NULL,
    narmeste_leder_fnr VARCHAR(11)              NOT NULL,
    leesah_status      VARCHAR(255)             NOT NULL,
    behov_status       BEHOV_STATUS             NOT NULL DEFAULT 'RECEIVED',
    created            TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);
