# Data flow

This document shows how data enters `esyfo-narmesteleder`, moves between
business capabilities, reaches external systems and PostgreSQL, and leaves
through HTTP responses or Kafka events.

For module responsibilities and dependency rules, see
[Application architecture](architecture.md). For canonical domain terms, see
[`CONTEXT.md`](../CONTEXT.md).

The diagrams describe runtime behavior, not package or dependency injection
structure. Solid arrows are synchronous calls. Dashed arrows are asynchronous
Kafka flows or later background processing.

## System overview

```mermaid
flowchart LR
    callers[API consumers]
    sendtTopic[teamsykmelding.syfo-sendt-sykmelding]
    sourceLeesah[teamsykmelding.syfo-narmesteleder-leesah]
    pdlLeesah[pdl.leesah-v1]
    tasks[Background tasks]

    subgraph app[esyfo-narmesteleder]
        access[organisasjonstilgang]
        behov[narmestelederbehov]
        relation[narmestelederrelasjon]
        sykmelding[sykmelding]
        statistics[narmestelederstatistikk]
        db[(PostgreSQL)]
    end

    external[PDL / Aareg / Ereg / Dinesykmeldte]
    altinn[Altinn Tilganger / PDP]
    dialogporten[Dialogporten]
    commandTopic[teamsykmelding.syfo-narmesteleder]
    esyfoLeesah[team-esyfo.syfo-narmesteleder-leesah]

    callers --> access
    callers --> behov
    callers --> relation
    callers --> statistics

    sendtTopic -.-> sykmelding
    sourceLeesah -.-> behov
    sourceLeesah -.-> relation
    pdlLeesah -.-> relation
    tasks --> behov
    tasks --> relation
    tasks --> sykmelding

    behov --> access
    behov --> relation
    sykmelding --> behov
    sykmelding --> relation
    relation --> access
    statistics --> access

    access --> altinn
    access --> external
    behov --> external
    relation --> external
    behov --> dialogporten

    behov --> db
    relation --> db
    sykmelding --> db
    statistics --> db

    relation --> commandTopic
    relation -.-> esyfoLeesah
```

The database is shared physically, but table ownership follows the capability
boundaries described in [Application architecture](architecture.md). In
particular, `narmestelederstatistikk` reads private projections over tables
owned by other capabilities and never writes to them.

## Runtime entry points

### HTTP APIs

| Trigger                                       | Capability                | Application entry point                      | Main data flow                                                                                      |
| --------------------------------------------- | ------------------------- | -------------------------------------------- | --------------------------------------------------------------------------------------------------- |
| `GET /api/v1/access/organizations`            | `organisasjonstilgang`    | `ListAccessibleOrganizationsUseCase`         | Resolve organizations available to the authenticated personnel manager through Altinn Tilganger.    |
| `GET /api/v1/linemanager/requirement`         | `narmestelederbehov`      | `ListNarmestelederbehovUseCase`              | Check organization access, read open needs and resolve employee names.                              |
| `GET /api/v1/linemanager/requirement/{id}`    | `narmestelederbehov`      | `GetNarmestelederbehovUseCase`               | Read one need, resolve the employee name and check organization access.                             |
| `PUT /api/v1/linemanager/requirement/{id}`    | `narmestelederbehov`      | `FulfillNarmestelederbehovUseCase`           | Check access, establish a relation, mark the need fulfilled and complete its Dialogporten dialog.   |
| `POST /api/v1/linemanager`                    | `narmestelederrelasjon`   | `SubmitNarmestelederrelasjonUseCase`         | Check access, validate the proposed relation and publish an establishment command.                  |
| `POST /api/v1/linemanager/revoke`             | `narmestelederrelasjon`   | `RevokeActiveNarmestelederrelasjonUseCase`   | Check access and submitted identity data, then publish a revocation command.                        |
| `POST /internal/api/v1/lookup`                | `narmestelederrelasjon`   | `LookupActiveNarmestelederUseCase`           | Read the active relation for an employee and organization.                                          |
| `POST /internal/api/v1/linemanager/search`    | `narmestelederrelasjon`   | `SearchActiveNarmestelederrelasjonerUseCase` | Check organization access and search active relations.                                              |
| `GET /internal/api/v1/employee/linemanager`   | `narmestelederrelasjon`   | `ListActiveNarmesteledereForEmployeeUseCase` | Read active relations for the authenticated employee.                                               |
| `GET /internal/api/v1/linemanager/{id}`       | `narmestelederrelasjon`   | `GetNarmestelederrelasjonUseCase`            | Read a relation, check access and enrich the response with organization and active-sykmelding data. |
| `DELETE /internal/api/v1/linemanager/{id}`    | `narmestelederrelasjon`   | `RevokeNarmestelederrelasjonUseCase`         | Read the relation, check access and publish a revocation command.                                   |
| `GET /internal/api/v1/linemanager/statistics` | `narmestelederstatistikk` | `GetNarmestelederstatistikkUseCase`          | Check organization access and aggregate a read model across behov, relation and sykmelding tables.  |

Operational health, metrics, OpenAPI and Swagger endpoints belong to
`platform` and are not business data flows.

### Kafka consumers

| Topic                                      | Consumer                                         | Processing                                                                                                                                                                              |
| ------------------------------------------ | ------------------------------------------------ | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `teamsykmelding.syfo-sendt-sykmelding`     | `SendtSykmeldingKafkaConsumer`                   | Handles the employee's `riktigNarmesteLeder` answer. It creates a need when no answer exists, or publishes a relation revocation when the answer is `NEI`.                              |
| `teamsykmelding.syfo-sendt-sykmelding`     | `PersistSendtSykmeldingConsumer`                 | Normalizes, deduplicates and persists sendt sykmelding records used by searches, statistics, expiry and retention. Leader-controlled and feature-flagged.                               |
| `teamsykmelding.syfo-narmesteleder-leesah` | `LeesahNLKafkaConsumer`                          | Creates a need when a relation is deactivated and fulfils open needs when an active relation is observed.                                                                               |
| `teamsykmelding.syfo-narmesteleder-leesah` | `PersistNarmestelederRegisterFromLeesahConsumer` | Validates and stores the local relation register, registers persons for enrichment and republishes valid records to the team-esyfo Leesah topic. Leader-controlled and feature-flagged. |
| `pdl.leesah-v1`                            | `PdlLeesahConsumer`                              | Refreshes names in the relation-person projection after relevant PDL name events. Leader-controlled and feature-flagged.                                                                |

### Background work

| Execution                              | Entry point                    | Processing                                                                                                                                            |
| -------------------------------------- | ------------------------------ | ----------------------------------------------------------------------------------------------------------------------------------------------------- |
| Elected leader                         | `SendDialogTask`               | `SendPendingNarmestelederbehovDialogsUseCase` creates missing Dialogporten dialogs for pending needs.                                                 |
| Elected leader                         | `UpdateDialogTask`             | `CompleteFulfilledNarmestelederbehovDialogsUseCase` retries completion and `ExpireNarmestelederbehovDialogsUseCase` expires dialogs for closed needs. |
| Elected leader                         | `ExpireNarmestelederbehovTask` | `ExpireNarmestelederbehovUseCase` expires needs after the configured sykmelding-based cutoff.                                                         |
| Elected leader                         | `PersonEnrichmentTask`         | `EnrichPendingRelationPersonsUseCase` resolves missing person names through PDL.                                                                      |
| Elected leader                         | `DeleteOldSykmeldingerTask`    | `DeleteOldSykmeldinger` removes sendt sykmelding records according to the retention policy.                                                           |
| Every pod, database claims with leases | `employment-check-seed` loop   | `SeedNarmestelederrelasjonEmploymentChecksUseCase` creates reconciliation work for active relations.                                                  |
| Every pod, database claims with leases | `employment-check` loop        | `CheckNarmestelederrelasjonEmploymentUseCase` checks employment through Aareg and optionally publishes revocations.                                   |

## Main write flows

### Sendt sykmelding creates a need

```mermaid
sequenceDiagram
    participant Kafka as sendt sykmelding topic
    participant Consumer as SendtSykmeldingKafkaConsumer
    participant Sykmelding as SendtSykmeldingHandler
    participant Relation as HasActiveNarmestelederrelasjon
    participant Behov as CreateNarmestelederbehov
    participant Upstream as Dinesykmeldte / Aareg / PDL
    participant DB as PostgreSQL
    participant Dialog as Dialogporten

    Kafka-->>Consumer: Sendt sykmelding without riktigNarmesteLeder answer
    Consumer->>Sykmelding: handleNarmestelederbehov
    Sykmelding->>Relation: Is there an active relation?
    Relation->>DB: Read local relation register
    DB-->>Relation: Active relations
    alt No active relation
        Sykmelding->>Behov: Create need
        Behov->>Upstream: Verify active sykmelding and resolve main organization
        Behov->>DB: Insert active need if it does not already exist
        opt Dialogporten creation succeeds immediately
            Behov->>Upstream: Resolve optional person details
            Behov->>Dialog: Create dialog
            Behov->>DB: Store dialog state
        end
    else Active relation exists
        Sykmelding-->>Consumer: Skip creation
    end
```

The database unique constraint prevents concurrent sources from creating more
than one active need for the same employee and organization. Dialog creation
can be completed later by `SendDialogTask`.

### Fulfil a need and establish a relation

```mermaid
sequenceDiagram
    participant Caller
    participant API as FulfillNarmestelederbehovApi
    participant Behov as FulfillNarmestelederbehovUseCase
    participant Access as OrganizationAccess
    participant Relation as EstablishNarmestelederrelasjon
    participant Upstream as Dinesykmeldte / Aareg / PDL
    participant Command as teamsykmelding.syfo-narmesteleder
    participant DB as PostgreSQL
    participant Dialog as Dialogporten

    Caller->>API: PUT requirement/{id}
    API->>Behov: Fulfil command
    Behov->>Behov: Normalize manager contact details
    Behov->>DB: Find need
    Behov->>Access: Evaluate organization access
    Access-->>Behov: Granted
    Behov->>Relation: Establish relation
    Relation->>Upstream: Validate active sykmelding, employment and persons
    Relation->>Command: Publish establishment
    Command-->>Relation: Acknowledged
    Relation-->>Behov: Published
    Behov->>DB: Mark need fulfilled
    opt Need has a Dialogporten dialog
        Behov->>Dialog: Complete dialog
        Behov->>DB: Mark dialog completed
    end
    Behov-->>API: Fulfilled
    API-->>Caller: HTTP response
```

Publishing happens before the need is marked fulfilled. A failure stops later
effects. If Dialogporten completion or its status update fails after the need
is fulfilled, `UpdateDialogTask` retries it.

### Direct relation submission

```mermaid
sequenceDiagram
    participant Caller
    participant API as SubmitNarmestelederrelasjonApi
    participant Submit as SubmitNarmestelederrelasjonUseCase
    participant Access as OrganizationAccess
    participant Establish as EstablishNarmestelederrelasjonUseCase
    participant Upstream as Dinesykmeldte / Aareg / PDL
    participant Command as teamsykmelding.syfo-narmesteleder

    Caller->>API: POST /api/v1/linemanager
    API->>Submit: Submit relation
    Submit->>Submit: Normalize manager contact details
    Submit->>Access: Evaluate organization access
    Access-->>Submit: Granted
    Submit->>Establish: Establish relation
    Establish->>Upstream: Validate active sykmelding, employment and names
    Establish->>Command: Publish establishment
    Establish-->>Submit: Published
    Submit-->>API: Established
    API-->>Caller: HTTP response
```

Submission and fulfilment share the relation capability's establishment
contract. Neither flow writes the relation register directly. The resulting
relation is stored when it later arrives on Leesah.

### Revoke a relation

```mermaid
flowchart LR
    explicit[Explicit API request]
    answer[Sendt sykmelding answer NEI]
    employment[Employment check]

    explicit --> authorize[Access and flow-specific validation]
    answer --> deduplicate[Deduplicate by sykmelding]
    employment --> aareg[Check employment history in Aareg]

    authorize --> publish[Publish NlAvbrutt]
    deduplicate --> publish
    aareg -->|Employment ended and publishing enabled| publish

    publish --> command[teamsykmelding.syfo-narmesteleder]
    command -.-> source[source Leesah processing]
    source -.-> register[Local relation register becomes inactive]
    source -.-> behov[Create a new behov for deactivation events]
```

The three triggers intentionally have different validation:

- explicit revocation checks organization access and the relevant submitted or
  stored relation data;
- sendt sykmelding revocation trusts the event and records the processed brudd
  to avoid duplicate publication;
- employment reconciliation never revokes after a failed Aareg lookup and
  publishes only when the revocation feature flag is enabled.

### Ingest and republish Leesah relations

```mermaid
sequenceDiagram
    participant Source as teamsykmelding.syfo-narmesteleder-leesah
    participant Consumer as PersistNarmestelederRegisterFromLeesahConsumer
    participant UseCase as PersistNarmestelederrelasjonerFromLeesahUseCase
    participant DB as PostgreSQL
    participant Compare as Employment reconciliation
    participant Target as team-esyfo.syfo-narmesteleder-leesah

    Source-->>Consumer: Batch of relation events
    Consumer->>UseCase: Validated transport records
    UseCase->>UseCase: Reject invalid identifiers and fields
    UseCase->>DB: Upsert relations and involved persons
    opt Source employment revocation is observed
        UseCase->>Compare: Record source revocation for shadow comparison
    end
    UseCase-->>Consumer: Valid persisted records
    Consumer->>Target: Republish records
    Consumer->>Source: Commit offsets
```

Persistence commits before republishing. If publication fails after the
database commit, the batch is retried; downstream consumers must tolerate
duplicates.

The separate `LeesahNLKafkaConsumer` reads the same source topic for the behov
lifecycle:

- an active relation fulfils the employee's open needs and completes their
  dialogs;
- a deactivated relation requests a new need.

### Maintain the relation-person projection

```mermaid
flowchart LR
    relationBatch[Persisted Leesah relations] --> pending[(relation_person pending)]
    pdlEvent[PDL NAVN_V1 event] --> refresh[UpdateRelationPersonNamesUseCase]
    pending --> enrich[EnrichPendingRelationPersonsUseCase]
    refresh --> pdl[PDL bulk lookup]
    enrich --> pdl
    pdl --> projection[(relation_person names)]
```

Leesah ingestion registers involved employees and managers. Names are filled
either reactively from PDL Leesah events or by the leader-controlled enrichment
task.

## Read flows

Read-oriented APIs follow one of three patterns:

```mermaid
flowchart LR
    caller[Authenticated caller] --> api[HTTP adapter]
    api --> usecase[Query use case]
    usecase -.-> access[Optional organisasjonstilgang]
    access --> altinn[Altinn Tilganger / PDP / Ereg]
    usecase --> db[(PostgreSQL read model)]
    usecase --> upstream[Optional PDL / Ereg / Dinesykmeldte enrichment]
    usecase --> api
```

1. Employer-facing queries check `organisasjonstilgang` before returning
   organization-scoped data.
2. Employee-facing relation lookup uses the authenticated employee identity
   and does not use employer organization access.
3. Pre-authorized internal machine lookup uses Azure AD client authorization
   and reads the relation register directly.

`GetNarmestelederstatistikkUseCase` is the exceptional cross-table read model:
it checks organization access, then reads private table projections over
`nl_behov`, `narmeste_leder` and `sendt_sykmelding`.

## Failure and consistency boundaries

- HTTP, Kafka and scheduled edges decide how typed upstream failures are
  exposed, retried or postponed. See
  [ADR-0004](adr/ADR-0004-feil-fra-eksterne-systemer-er-resultater-ikke-exceptions.md).
- PostgreSQL transactions never include Kafka or external HTTP calls.
- Relation establishment and revocation publish commands first; the local
  relation register changes later through Leesah ingestion.
- Need fulfilment publishes the relation before marking the need fulfilled.
- Dialogporten synchronization can lag behind the behov state and is repaired
  by background tasks.
- The sendt sykmelding and source Leesah topics currently have separate
  consumers for business reactions and local persistence.
- Leader-controlled consumers and tasks run only on the elected pod.
  Employment reconciliation runs on every pod and coordinates work through
  database claims with leases; see
  [ADR-0003](adr/ADR-0003-planlagt-bakgrunnsarbeid-bruker-claim-med-lease.md).
