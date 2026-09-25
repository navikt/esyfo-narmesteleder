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
- validate the employer's response;
- check employment and active sykmelding through ports;
- authorize access through `organisasjonstilgang`;
- fulfill, expire and query needs;
- own `nl_behov`;
- coordinate Dialogporten state and retry behavior.

It establishes a relation through a small application contract owned by
`narmestelederrelasjon`. It does not use relation repositories, Kafka models or
Kafka producers directly.

### `narmestelederrelasjon`

Owns established and revoked narmesteleder relations:

- establish and revoke relations;
- publish relation messages;
- ingest and republish relation events;
- own the local relation register;
- provide lookup, search and statistics;
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
- trigger revocation of narmestelederrelasjon.

The initial dependency is one-way:

```text
sykmelding -> narmestelederbehov
sykmelding -> narmestelederrelasjon
```

`narmestelederbehov` continues to use Dinesykmeldte through its own
`ActiveSykmeldingLookup` port. Replacing that adapter with the local sykmelding
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

business modules -> ident value types
business infrastructure -> focused platform mechanisms
bootstrap -> all modules
```

Rules:

- `domain` has no Ktor, Kafka, Exposed, JDBC, Koin, HTTP-client or environment
  dependencies.
- `application` depends on its domain and small ports, never its
  `infrastructure`.
- Other modules may use only explicitly exposed application contracts.
- A module never imports another module's repositories, tables, transport
  models, adapters or Koin registration.
- `platform` and `ident` never import business modules.
- Architecture tests enforce rules for each migrated flow. Rules expand as the
  migration progresses.

## Application design

Use one small class for one meaningful business action or query:

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
`infrastructure` package. Name ports by capability and adapters by mechanism.
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

The fulfillment foundation and its remaining activation work are described in
[Fulfillment migration status](narmestelederbehov-fulfillment-migration.md).

The first implementation slice is fulfillment of a narmestelederbehov. It
creates only the target packages, Koin seams, application contracts and
architecture rules required by that complete flow.

Subsequent slices migrate one business action or query at a time. A slice must
preserve external HTTP and Kafka contracts, database behavior, authentication
and authorization order, configuration names/defaults, logging safety and
side-effect ordering.

The following are separate changes and must not be hidden inside structural
pull requests:

- outbox or changed relation-delivery guarantees;
- removing Kafka leader gating or changing consumer topology;
- #508 and the switch from Dinesykmeldte to local sykmelding lookup;
- JDBC/Exposed DAO migration to Exposed DSL;
- splitting requirement and Dialogporten state in the database;
- separate Gradle modules or new deployable services.
