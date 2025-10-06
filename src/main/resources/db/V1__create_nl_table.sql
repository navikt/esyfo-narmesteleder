create table nl_avbrutt(
    id uuid DEFAULT uuidv4() primary key not null,
    orgnummer VARCHAR(9) not null,
    sykemeldt_fnr VARCHAR(11) not null,
    narmeste_leder_fnr VARCHAR(11) not null,
    status VARCHAR(56) not null,
    aktiv_fom TIMESTAMP with time zone not null,
    aktiv_tom TIMESTAMP with time zone not null,
    created TIMESTAMP with time zone not null default now(),
)
