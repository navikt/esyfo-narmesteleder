# Data flow

How data enters the service, moves between capabilities and leaves through
HTTP or Kafka. Modules and dependency rules: [architecture](architecture.md).
Domain terms: [`CONTEXT.md`](../CONTEXT.md).

## Overview

- **In:** HTTP callers; Kafka `teamsykmelding.syfo-sendt-sykmelding`,
  `teamsykmelding.syfo-narmesteleder-leesah` (source Leesah) and
  `pdl.leesah-v1`; background tasks.
- **Out:** commands to `teamsykmelding.syfo-narmesteleder`, republished
  relations to `team-esyfo.syfo-narmesteleder-leesah`, dialogs in Dialogporten.
- **Upstream:** PDL, Aareg, Ereg, Dinesykmeldte, Altinn Tilganger/PDP. Valkey
  caches Ereg organizations and PDL person details.
- **Capability calls:** `sykmelding` → `narmestelederbehov`,
  `narmestelederrelasjon`; `narmestelederbehov` → `organisasjonstilgang`,
  `narmestelederrelasjon`; `narmestelederrelasjon` and
  `narmestelederstatistikk` → `organisasjonstilgang`.
- **Database:** one PostgreSQL database; table ownership follows capability
  boundaries. `narmestelederstatistikk`, relation search and behov expiry read
  other capabilities' tables (`nl_behov`, `narmeste_leder`,
  `sendt_sykmelding`) only through private read-only projections.

## Entry points

### HTTP

| Route | Use case | Flow |
| --- | --- | --- |
| `GET /api/v1/access/organizations` | `ListAccessibleOrganizationsUseCase` | Organizations the personnel manager can access, from Altinn Tilganger. |
| `GET /api/v1/linemanager/requirement` | `ListNarmestelederbehovUseCase` | Check access, read open needs, resolve and persist missing employee names (PDL). |
| `GET /api/v1/linemanager/requirement/{id}` | `GetNarmestelederbehovUseCase` | Read need, resolve and persist missing name (PDL), **then** check access. |
| `PUT /api/v1/linemanager/requirement/{id}` | `FulfillNarmestelederbehovUseCase` | See [Fulfil a need](#fulfil-a-need). |
| `POST /api/v1/linemanager` | `SubmitNarmestelederrelasjonUseCase` | See [Submit a relation](#submit-a-relation). |
| `POST /api/v1/linemanager/revoke` | `RevokeActiveNarmestelederrelasjonUseCase` | Check access, resolve employee (PDL), validate last name, read active relation, publish. No active relation is success. |
| `POST /internal/api/v1/lookup` | `LookupActiveNarmestelederUseCase` | Active relation for employee and organization. Entra ID role `linemanager-lookup`. |
| `POST /internal/api/v1/linemanager/search` | `SearchActiveNarmestelederrelasjonerUseCase` | Check access, search active relations. |
| `GET /internal/api/v1/employee/linemanager` | `ListActiveNarmesteledereForEmployeeUseCase` | Active relations for the authenticated employee; no organization access check. |
| `GET /internal/api/v1/linemanager/{id}` | `GetNarmestelederrelasjonUseCase` | Read relation, check access, require active sykmelding (else 404), add Ereg name. |
| `DELETE /internal/api/v1/linemanager/{id}` | `RevokeNarmestelederrelasjonUseCase` | Read relation, publish revocation. Employee or manager in the relation skip the access check. |
| `GET /internal/api/v1/linemanager/statistics` | `GetNarmestelederstatistikkUseCase` | Check access, aggregate over behov, relation and sykmelding projections. |

Health, metrics and Swagger belong to `platform`.

### Kafka consumers

| Topic | Consumer | Processing |
| --- | --- | --- |
| sendt sykmelding | `SendtSykmeldingKafkaConsumer` | No `riktigNarmesteLeder` answer: create a need. Answer `NEI`: publish revocation. |
| sendt sykmelding | `PersistSendtSykmeldingConsumer` | Normalize, deduplicate and store records for search, statistics, expiry and retention. |
| source Leesah | `LeesahNLKafkaConsumer` | Behov lifecycle, see [Leesah](#ingest-and-republish-leesah). |
| source Leesah | `PersistNarmestelederRegisterFromLeesahConsumer` | Store the local relation register and republish. |
| `pdl.leesah-v1` | `PdlLeesahConsumer` | Refresh names in the relation-person projection on `NAVN_V1`. |

All except the first and third are leader-controlled. Flags:
`PERSIST_SENDT_SYKMELDING`, `PERSIST_NARMESTELEDER_REGISTER`,
`PDL_LEESAH_CONSUMER_ENABLED`. Despite its name, `PERSIST_LEESAH_NL_BEHOV`
controls behov creation from every source, including sendt sykmelding.

### Background work

| Runs on | Entry point | Processing |
| --- | --- | --- |
| Leader | `SendDialogTask` | `SendPendingNarmestelederbehovDialogsUseCase` creates missing dialogs. |
| Leader | `UpdateDialogTask` | `CompleteFulfilledNarmestelederbehovDialogsUseCase` retries completion; `ExpireNarmestelederbehovDialogsUseCase` expires dialogs for closed needs. |
| Leader | `ExpireNarmestelederbehovTask` | `ExpireNarmestelederbehovUseCase` expires needs after the sykmelding-based cutoff. |
| Leader | `PersonEnrichmentTask` | `EnrichPendingRelationPersonsUseCase` fills missing names from PDL. |
| Leader | `DeleteOldSykmeldingerTask` | `DeleteOldSykmeldinger` applies sykmelding retention. |
| Every pod, claim/lease | `employment-check-seed` loop | `SeedNarmestelederrelasjonEmploymentChecksUseCase` creates work for active relations. |
| Every pod, claim/lease | `employment-check` loop | `CheckNarmestelederrelasjonEmploymentUseCase`, see [Revoke](#revoke-a-relation). |

Flags: `DIALOGPORTEN_TASK_ENABLED` (dialog tasks), plus
`PERSON_ENRICHMENT_TASK_ENABLED` for enrichment, which starts inside the
Dialogporten branch; `BEHOV_MAINTENANCE_TASK_ENABLED` (expiry and retention).
Employment checks have no flag. Claims and leases:
[ADR-0003](adr/ADR-0003-planlagt-bakgrunnsarbeid-bruker-claim-med-lease.md).

## Write flows

### Sendt sykmelding creates a need

1. `SendtSykmeldingHandler` skips if `HasActiveNarmestelederrelasjon` finds an
   active relation.
2. `CreateNarmestelederbehov` stops if an open need exists. If no
   sykmeldingsperiode covers today, it verifies an active sykmelding in
   Dinesykmeldte.
3. Insert the need. The main organization is the sykmelding's
   `juridiskOrgnummer`, not Aareg. A partial unique index stops concurrent
   duplicates. Without a main organization the need gets an error status and no
   dialog.
4. Resolve person details, create the Dialogporten dialog and store its state.
   The behov id is the `idempotentKey`, so `SendDialogTask` retries reuse a
   dialog that was created but not stored.

### Fulfil a need

1. Normalize contact details, read the need, check organization access.
2. Establish the relation: validate active sykmelding, employment and persons
   (Dinesykmeldte, Aareg, PDL), then publish to
   `teamsykmelding.syfo-narmesteleder`.
3. Mark the need fulfilled, then complete its dialog. `UpdateDialogTask`
   retries a failed completion.

Publishing comes before marking fulfilled; a failure stops later steps.

### Submit a relation

Normalize contact details, check access, then the same establishment as
fulfilment. Neither flow writes the register; the relation is stored when it
arrives on Leesah, so all validation happens before publishing. Name-validation
metrics are recorded right after matching, before any side effect can fail.

### Revoke a relation

All triggers publish `NlAvbrutt` to `teamsykmelding.syfo-narmesteleder`; the
register becomes inactive later through Leesah.

- **API:** checks access and submitted or stored relation data (see HTTP table
  for the `DELETE` exception).
- **Sendt sykmelding `NEI`:** trusts the event, publishes with
  `ARBEIDSTAGER_SYKMELDING_REVOKE`, then stores the processed brudd. A failure
  between publish and store can publish twice.
- **Employment check:** replaces team sykmelding's `narmesteleder-arbeidsforhold`.
  Keeps the relation while an employment at its organization number has not
  ended or ended at most four months ago; otherwise publishes with source
  `narmesteleder-arbeidsforhold`. Never revokes after a failed Aareg lookup.
  `NlAvbrutt` deactivates all active relations for orgnr and fnr, so a relation
  established between lookup and publish can be revoked; the reference app has
  the same race.

### Ingest and republish Leesah

`PersistNarmestelederRegisterFromLeesahConsumer`, per batch:

1. `PersistNarmestelederrelasjonerFromLeesahUseCase` rejects invalid records
   and upserts relations and involved persons (as `PENDING`).
2. Republish valid records to `team-esyfo.syfo-narmesteleder-leesah`, then
   commit offsets. A failed publish retries the batch, so downstream must
   tolerate duplicates. Tombstones are forwarded, not stored.

Contract and status mapping: [syfo-narmesteleder-leesah](kafka/syfo-narmesteleder-leesah.md).

`LeesahNLKafkaConsumer`:

- an active relation fulfils the employee's open needs and completes their
  dialogs;
- deactivation by employee, manager, personnel manager or LPS requests a new
  need; employment-ended and new-manager deactivations do not. It is skipped
  when behov persistence is off, a need is open or there is no active
  sykmelding.

### Relation-person names

`PENDING` persons get names from PDL either on `pdl.leesah-v1` `NAVN_V1`
events (`UpdateRelationPersonNamesUseCase`) or through `PersonEnrichmentTask`.

## Read flows

- Employer-facing reads check `organisasjonstilgang` first.
- Employee-facing reads use the authenticated identity, no organization access.
- Internal lookup uses an Entra ID client-credentials token with the
  `linemanager-lookup` role, granted by Nais only to configured inbound apps.
- The behov GET endpoints write: they persist missing employee names in
  `nl_behov`. The single-behov endpoint does this before its access check.

## Consistency

- Edges (HTTP, Kafka, tasks) decide how typed upstream failures are exposed or
  retried: [ADR-0004](adr/ADR-0004-feil-fra-eksterne-systemer-er-resultater-ikke-exceptions.md).
- Transactions never include Kafka or HTTP calls.
- Establishment and revocation publish first; the register follows through
  Leesah.
- Dialogporten can lag behind behov state; background tasks repair it.

## Planned: source Leesah decommissioning

When `teamsykmelding.syfo-narmesteleder-leesah` goes silent, both source Leesah
consumers are removed, and establishment and revocation publish directly to
`team-esyfo.syfo-narmesteleder-leesah` (produce-only today). Open decision on
how the register is updated:

- **A: consume our own topic.** Keeps publish-first ordering and one event
  stream, but the register lags events that are already public.
- **B: write the register synchronously, then publish.** Lookups are
  consistent, but a failed publish after the write loses the event unless an
  outbox or retry is added; this breaks the publish-first ordering in
  [ADR-0002](adr/ADR-0002-organiser-backend-som-en-kapabilitetsbasert-modulaer-monolitt.md).

Until then the source Leesah consumers are the only writers of the register.
