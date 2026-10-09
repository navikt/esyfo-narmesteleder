create table narmestelederrelasjon_employment_check (
    narmeste_leder_id             uuid        not null,
    status                        text        not null,
    next_check_at                 timestamptz not null,
    claim_token                   uuid,
    last_checked_at               timestamptz,
    last_outcome                  text,
    shadow_would_revoke_at        timestamptz,
    source_revocation_observed_at timestamptz,
    created                       timestamptz not null default now(),
    constraint nlrel_employment_check_pkey primary key (narmeste_leder_id),
    constraint nlrel_employment_check_status_check
        check (status in ('READY', 'CLAIMED')),
    constraint nlrel_employment_check_outcome_check
        check (last_outcome is null or last_outcome in ('VALID', 'WOULD_REVOKE', 'REVOKED', 'FAILED')),
    constraint nlrel_employment_check_claim_token_check
        check ((status = 'CLAIMED') = (claim_token is not null))
);

create index nlrel_employment_check_next_check_at_idx
    on narmestelederrelasjon_employment_check (next_check_at);
