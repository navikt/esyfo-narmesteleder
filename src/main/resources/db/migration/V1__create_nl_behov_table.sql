create type BEHOV_STATUS as enum ('RECEIVED', 'PENDING', 'COMPLETED', 'ERROR');

create table nl_behov
(
    id                 UUID PRIMARY KEY         NOT NULL DEFAULT gen_random_uuid(),
    orgnummer          VARCHAR(9)               NOT NULL,
    sykemeldt_fnr      VARCHAR(11)              NOT NULL,
    narmeste_leder_fnr VARCHAR(11)              NOT NULL,
    behov_status       BEHOV_STATUS             NOT NULL DEFAULT 'RECEIVED',
    created            TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);
