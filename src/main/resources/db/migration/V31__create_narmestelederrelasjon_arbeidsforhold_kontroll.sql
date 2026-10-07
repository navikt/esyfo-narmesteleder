create table narmestelederrelasjon_arbeidsforhold_kontroll (
    narmeste_leder_id         uuid        not null,
    status                    text        not null,
    neste_kontroll            timestamptz not null,
    claim_token               uuid,
    sist_kontrollert          timestamptz,
    sist_utfall               text,
    skygge_ville_brutt        timestamptz,
    observert_brudd_fra_kilde timestamptz,
    opprettet                 timestamptz not null default now(),
    constraint nlrel_arbeidsforhold_kontroll_pkey primary key (narmeste_leder_id),
    constraint nlrel_arbeidsforhold_kontroll_status_check
        check (status in ('KLAR', 'CLAIMED')),
    constraint nlrel_arbeidsforhold_kontroll_utfall_check
        check (sist_utfall is null or sist_utfall in ('GYLDIG', 'VILLE_BRUTT', 'BRUTT', 'FEILET')),
    constraint nlrel_arbeidsforhold_kontroll_claim_token_check
        check ((status = 'CLAIMED') = (claim_token is not null))
);

create index nlrel_arbeidsforhold_kontroll_neste_kontroll_idx
    on narmestelederrelasjon_arbeidsforhold_kontroll (neste_kontroll);
