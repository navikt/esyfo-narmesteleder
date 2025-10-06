create table nl_behov
(
    -- TODO: Hvis vi ikke får brukt postgres18 og endret til uuid7,
    --  burde vi kanskje ha noe annet som PK for indekseringens del
    id                 uuid primary key         not null DEFAULT gen_random_uuid(),
    orgnummer          VARCHAR(9)               not null,
    sykemeldt_fnr      VARCHAR(11)              not null,
    narmeste_leder_fnr VARCHAR(11)              not null,
    status             VARCHAR(56)              not null,
    created            TIMESTAMP with time zone not null DEFAULT now()
);
