# Application architecture

## Purpose

`esyfo-narmesteleder` is a modular monolith. It is one deployable Ktor
application and initially one Gradle module, organized around business
capabilities rather than technical layers.

The architecture should make a business flow readable in one module, keep
framework details outside business code, and allow each area to change without
depending on another area's persistence or transport implementation.

The decision and trade-offs are recorded in
[ADR-0002](adr/ADR-0002-organiser-backend-som-en-kapabilitetsbasert-modulaer-monolitt.md).
Canonical domain terms are defined in [`CONTEXT.md`](../CONTEXT.md).

## Target structure

```text
no.nav.syfo
├── bootstrap
├── platform
│   ├── auth
│   ├── database
│   ├── kafka
│   ├── observability
│   └── scheduling
├── integration                   # shared external clients, not a module
│   ├── aareg
│   ├── dinesykmeldte
│   ├── ereg
│   └── pdl
├── ident                         # shared value types, not a module
├── organisasjonstilgang
│   ├── application
│   ├── domain
│   └── infrastructure
├── narmestelederbehov
│   ├── api
│   ├── application
│   ├── domain
│   └── infrastructure
├── narmestelederrelasjon
│   ├── api
│   ├── application
│   ├── domain
│   └── infrastructure
├── narmestelederstatistikk
│   ├── api
│   ├── application
│   └── infrastructure
└── sykmelding
    ├── application
    ├── domain
    └── infrastructure
```

Start with shallow packages. Add subpackages by business feature when a
package becomes difficult to navigate. Do not create fixed
`command/query/port/handler/impl` trees with one file in each directory.

## Module responsibilities

### `narmestelederbehov`

Owns the temporary lifecycle of a need for an employer to report a
narmesteleder:

- create and prevent duplicate needs;
- verify that the need exists and is not already fulfilled;
- authorize access through `organisasjonstilgang`;
- fulfill, expire and query needs;
- own `nl_behov`;
- coordinate Dialogporten state and retry behavior.

It establishes a relation through an application contract owned by
`narmestelederrelasjon`, which validates the relation and returns a typed
result. Fulfillment parses the manager's contact details with that contract
first, so invalid input is still rejected before the need lookup and access
check. It does not validate the relation itself and does not use relation
repositories, ports, domain rules, Kafka models or Kafka producers directly.

### `narmestelederrelasjon`

Owns established and revoked narmesteleder relations:

- establish and revoke relations;
- validate a relation before establishing it: the manager's contact details,
  active sykmelding, employment, persons in PDL and submitted last names.
  Establishing and revoking only publish a message; the relation is stored when
  the resulting Leesah event is consumed, so validation must happen before
  publishing;
- accept direct POST relation submissions, normalizing contact details before
  checking organization access and establishing through the relation contract;
- handle `POST /api/v1/linemanager/revoke` by checking organization access,
  resolving the employee in PDL, validating the last name and active relation,
  then publishing a revocation without requiring employment or sick leave;
- revoke the relation for a sendt sykmelding without validation, publishing
  with the `ARBEIDSTAGER_SYKMELDING_REVOKE` Kafka source;
- publish relation messages;
- ingest and republish relation events;
- own the local relation register;
- provide lookup and search;
- maintain the relation-owned `RelationPerson` projection.

`RelationPerson` is the code-level name for person details needed by relation
lookup and search. It is a local, incomplete projection and not an
authoritative person registry. The existing `person` table remains unchanged.

### `sykmelding`

Owns processing and local persistence of sendt sykmelding:

- normalize and validate Kafka input;
- deduplicate records;
- apply retention rules;
- persist and revoke individual sykmeldinger;
- trigger creation of narmestelederbehov;
- trigger revocation of narmestelederrelasjon through the
  `RevokeNarmestelederrelasjonFromSendtSykmelding` contract. `sykmelding` owns
  the `kilde` it stores for each processed brudd.

The initial dependency is one-way:

```text
sykmelding -> narmestelederbehov
sykmelding -> narmestelederrelasjon
```

`narmestelederrelasjon` uses Dinesykmeldte through its `ActiveSykmeldingLookup`
port, the single owner. Replacing that adapter with the local sykmelding
projection belongs to #508 and follow-up work.

`ShadowActiveSykmeldingService` remains dormant until that migration resumes.
It is not dead code and must not shape the initial production dependency graph.

### `organisasjonstilgang`

Owns reusable business authorization for acting on behalf of an organization:

- evaluate access through Altinn Tilganger;
- perform PDP decisions;
- resolve relevant Ereg organization hierarchy;
- return typed allow/deny results without HTTP exceptions.

It does not load narmestelederbehov or narmestelederrelasjon records. The
calling use case owns resource lookup, authorization order and non-disclosure
rules.

### `narmestelederstatistikk`

Owns the read model for narmesteleder statistics per organization
(`GET /api/v1/linemanager/statistics`):

- check organization access through `organisasjonstilgang`;
- count employees on sick leave with and without a narmesteleder, and
  employees with a narmesteleder who are not on sick leave.

The counts span tables owned by other modules (`nl_behov`, `narmeste_leder`
and `sendt_sykmelding`). The module defines its own private, read-only Exposed
DSL table definitions over those tables in its `infrastructure` instead of
importing another module's tables or repositories. It never writes, and the
owning modules keep ownership of the tables and schema. It has no `domain`
package because it has no business rules beyond the query. It depends only on
`organisasjonstilgang`, and no other module depends on it.

### `integration`

`integration` is a supporting package, not a module. It holds clients for
external systems used by more than one module, one package per system:
`integration/aareg`, `integration/dinesykmeldte`, `integration/ereg` and
`integration/pdl`.

A client is transport only: HTTP, token exchange, caching, error handling and
response DTOs. It contains no business rules and no module's ports or domain
types.

Each module keeps its own port in `application` and its own adapter in
`infrastructure`. The adapter calls the client directly and maps the response
to the module's own types. Modules never share ports or adapters for external
systems; for example, `narmestelederbehov` has its own PDL adapter instead of
reusing one from `narmestelederrelasjon`.

A client used by only one module lives in that module's `infrastructure`. It
moves to `integration` when a second module needs it.

The shared Ereg, PDL, Aareg and Dinesykmeldte clients live in `integration`.
Ereg caching is provided by the `CachedEregClient` decorator; module adapters
own the mapping from client results and failures to their ports.

### `ident`

`ident` is a small shared value-type package, not a deep module. It contains
only official identifiers whose representation and validation are the same
across modules:

- `PersonIdent`;
- `OrganizationNumber`.

Module-specific IDs, statuses, actors, commands, DTOs and errors do not belong
here. The package has no `api`, `application`, `domain` or `infrastructure`
layers because it has no business workflow to hide.

### `platform`

Contains reusable technical mechanisms without domain rules:

- `auth`: Ktor/Texas authentication and principal construction;
- `database`: data source, Flyway and shared transaction infrastructure;
- `kafka`: producer/consumer construction and reusable lifecycle mechanics;
- `observability`: metrics, health and common structured logging mechanisms;
- `scheduling`: reusable scheduling and leader-election mechanics;
- `application`: small use-case mechanics such as `Step`, which lets private
  use-case steps continue with a value or stop with the use case's result.
  `Step` never appears in public use-case contracts or ports.

`platform` must not depend on a business module.

### `bootstrap`

Owns application assembly:

- parse environment variables into small typed settings;
- select production or local adapters;
- combine Koin modules;
- start Ktor, Kafka consumers and scheduled work;
- aggregate routes and operational endpoints.

Business classes do not depend on Koin or bootstrap.

## Dependency rules

```text
sykmelding -> narmestelederbehov -> narmestelederrelasjon
     └──────────────────────────> narmestelederrelasjon

narmestelederbehov -> organisasjonstilgang
narmestelederrelasjon -> organisasjonstilgang
narmestelederstatistikk -> organisasjonstilgang

business modules -> ident value types
business infrastructure -> focused platform mechanisms
business infrastructure -> integration clients
bootstrap -> all modules and integration
```

Rules:

- Module dependencies follow the arrows above and never form a cycle, also not
  indirectly through legacy or root packages. Existing cycles through legacy
  code disappear as flows are migrated and are tracked in #544.
- `domain` has no Ktor, Kafka, Exposed, JDBC, Koin, HTTP-client or environment
  dependencies.
- `application` depends on its domain and small ports, never its
  `infrastructure`.
- Other modules may use only explicitly exposed application contracts, the
  domain types those contracts name, and the parsing of the contracts' input
  types (for example `ManagerContactInput.normalize()`). Parsing lets a caller
  reject invalid input before its own lookups without repeating the rule.
- Validation lives next to what it validates: the caller in
  `organisasjonstilgang`, a need in `narmestelederbehov` and a relation in
  `narmestelederrelasjon`. A calling module does not repeat another module's
  validation.
- A module never imports another module's repositories, tables, transport
  models, adapters or Koin registration. When a read model or adapter must
  query a table owned by another module, it defines its own private, read-only
  table definition.
- Only `*.infrastructure` packages and `bootstrap` import `integration`.
  `api`, `application` and `domain` never do.
- `platform`, `integration` and `ident` never import business modules.
- Architecture tests enforce rules for each migrated flow. Rules expand as the
  migration progresses.

## Application design

Use one small class for one meaningful business action or query. Name it
`<Action><Concept>UseCase`, also for queries, for example
`GetNarmestelederrelasjonUseCase`:

```kotlin
class FulfillNarmestelederbehovUseCase(
    private val repository: NarmestelederbehovRepository,
    private val organizationAccess: OrganizationAccess,
    private val establishRelation: EstablishNarmestelederrelasjon,
    private val dialogporten: NarmestelederbehovDialog,
) {
    suspend fun execute(
        command: FulfillNarmestelederbehovCommand,
    ): FulfillNarmestelederbehovResult
}
```

Inside a module, adapters may inject the concrete use-case class. Add a small
interface when another module calls the operation:

```kotlin
fun interface CreateNarmestelederbehov {
    suspend fun execute(
        command: CreateNarmestelederbehovCommand,
    ): CreateNarmestelederbehovResult
}
```

Do not add:

- a common `UseCase<I, O>` abstraction;
- a command bus or service locator;
- an interface for every class;
- a broad `*Service`, `Commands` or `Queries` facade containing unrelated
  operations.

Keep a port's input and output types in the port's file, for example
`RevocableNarmestelederrelasjon` in `NarmestelederrelasjonRepository.kt`.

## Validation and authorization

Separate three concerns:

1. Input adapters parse request/event fields and create validated identifier
   types.
2. Use cases perform business validation in an explicit order.
3. `organisasjonstilgang` evaluates reusable organization-access rules.

Ktor/Texas authentication only validates credentials and constructs the
caller principal. It must not hide PDP, Ereg, database or other business calls
inside a Ktor authorization plugin.

Identifier types validate only their representation. They do not call
databases or external services.

## Errors and HTTP mapping

Use cases return typed results for expected outcomes:

```kotlin
sealed interface FulfillNarmestelederbehovResult {
    data object Fulfilled : FulfillNarmestelederbehovResult
    data object NotFound : FulfillNarmestelederbehovResult
    data object AccessDenied : FulfillNarmestelederbehovResult
    data object NoActiveSykmelding : FulfillNarmestelederbehovResult
}
```

Capability HTTP adapters map these results to the established
`ApiErrorException` and `ErrorType` contract. Central Ktor `StatusPages`
produces the final `ApiError` response, handles malformed requests and
authentication errors, rethrows coroutine cancellation and maps unexpected
failures safely to HTTP 500.

Use cases do not import Ktor or HTTP error types. Kafka adapters classify
failures using Kafka-specific retry, permanent-error and fatal-error rules.

## Ports and adapters

A port is an interface requested by application code:

```kotlin
interface NarmestelederbehovRepository {
    suspend fun findForFulfillment(
        id: RequirementId,
    ): Narmestelederbehov?
}
```

An adapter implements the port using a mechanism:

```kotlin
class PostgresNarmestelederbehovRepository(
    private val database: Database,
) : NarmestelederbehovRepository
```

Ports live in `application`. Adapters live in the owning module's
`infrastructure` package. An adapter for an external system used by several
modules calls the shared client in `integration`; see
[`integration`](#integration). Name ports by capability and adapters by mechanism.
Do not use `I` prefixes or generic `Impl` suffixes in migrated code.

## Persistence and transactions

Repositories own Exposed/JDBC transactions. Use cases never open database
transactions or expose `Transaction`, `ResultRow`, DAO entities or JDBC
connections.

Split repository contracts by business purpose, not mechanically by table or
SQL statement. For example, write flows, API queries and maintenance may have
separate focused contracts over `nl_behov`.

New and migrated repository adapters use Exposed DSL (`Table` and
`suspendTransaction`), not new raw JDBC (`DatabaseInterface`) or Exposed DAO
code. Existing JDBC/DAO implementations migrate when their adapter is touched.
The separate broad migration remains follow-up work; moving a flow alone does
not imply a schema, query-semantics or transaction change.

A database transaction does not include Kafka, Dialogporten or another remote
system. The use case owns and tests the order between those effects.

## Narmestelederbehov transitions

A small domain object or transition function owns valid status changes. It
does not call repositories or remote systems.

The current `BehovStatus` values and persisted Dialogporten-related states
remain unchanged during structural migration. Separating business state from
Dialogporten synchronization state requires a later schema and rollout
decision.

## Configuration and dependency injection

Bootstrap parses environment variables once into small typed settings.
Adapters and tasks receive only the settings they need. Existing environment
variable names and defaults remain compatible.

Each business capability provides its own Koin registration module. Focused
platform modules register shared technical mechanisms. Root bootstrap only
combines modules and selects environment-specific adapters.

Business and domain classes remain normal constructor-injected Kotlin classes
and do not depend on Koin.

Route functions take their dependencies as explicit parameters. Bootstrap
resolves them from Koin and registers each capability's routes directly under
the shared API path; there is no aggregating route function that only forwards
dependencies. `DependencyInjectionTest` verifies the complete Koin graph for
both local and NAIS configuration.

## Testing

Each migrated vertical flow includes:

1. focused use-case tests for business decisions and important side-effect
   ordering;
2. adapter/endpoint tests for PostgreSQL, Kafka, HTTP clients and Ktor
   contracts where applicable;
3. architecture tests for the packages introduced by that flow.

Keep existing endpoint, OpenAPI, Kafka and PostgreSQL coverage. Remove broad
fixtures such as `FakesWrapper` incrementally as individual flows migrate.

Do not create architecture tests for empty target packages or large exception
lists for legacy code. Add rules when real code moves, then expand them until
the final repository-wide rules can replace the migration-specific checks.

## Migration approach

`GET /api/v1/access/organizations` now lives in `organisasjonstilgang` as `ListAccessibleOrganizationsUseCase`.

`GET /internal/api/v1/employee/linemanager` now lives in `narmestelederrelasjon` as `ListActiveNarmesteledereForEmployeeUseCase`.

`POST /internal/api/v1/linemanager/search` now lives in `narmestelederrelasjon` as `SearchActiveNarmestelederrelasjonerUseCase`.

The fulfillment foundation and its remaining activation work are described in
[Fulfillment migration status](narmestelederbehov-fulfillment-migration.md).

The first implementation slice is fulfillment of a narmestelederbehov. It
creates only the target packages, Koin seams, application contracts and
architecture rules required by that complete flow.

Subsequent slices migrate one business action or query at a time. A slice must
preserve external HTTP and Kafka contracts, database behavior, authentication
and authorization order, configuration names/defaults, logging safety and
side-effect ordering.

The direct submission and employee-and-organization revoke routes now live in
`narmestelederrelasjon`; the requirement routes live in `narmestelederbehov`.
Revocation triggered by sendt sykmelding goes through the
`RevokeNarmestelederrelasjonFromSendtSykmelding` contract, so no legacy code
publishes relation revocations any more.

The following are separate changes and must not be hidden inside structural
pull requests:

- outbox or changed relation-delivery guarantees;
- removing Kafka leader gating or changing consumer topology;
- #508 and the switch from Dinesykmeldte to local sykmelding lookup;
- JDBC/Exposed DAO migration to Exposed DSL;
- splitting requirement and Dialogporten state in the database;
- separate Gradle modules or new deployable services.
