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
Runtime entry points and end-to-end behavior are documented in
[Data flow](data-flow.md).

## Target structure

The `person` module is a proposed addition under
[ADR-0005](adr/ADR-0005-person-eier-lokale-personopplysninger.md), implemented
incrementally through #602. Its responsibilities still live in
`narmestelederrelasjon` until those implementation slices land.

```text
no.nav.syfo
├── bootstrap
├── platform
│   ├── api
│   ├── application
│   ├── auth
│   │   └── texas
│   ├── cache
│   ├── config
│   ├── database
│   ├── http
│   ├── kafka
│   ├── local
│   ├── observability
│   ├── scheduling
│   └── upstream
├── integration                   # shared external clients, not a module
│   ├── aareg
│   ├── dinesykmeldte
│   ├── ereg
│   └── pdl
├── ident                         # shared value types, not a module
├── organisasjonstilgang
│   ├── api
│   ├── application
│   └── infrastructure            # altinnauthorization, altinntilganger
├── narmestelederbehov
│   ├── api
│   ├── application
│   ├── domain
│   └── infrastructure
├── narmestelederrelasjon
│   ├── api
│   ├── application               # shared ports; employee, employmentcheck,
│   │                             # establish, get, leesah, lookup,
│   │                             # revoke, search, submit
│   ├── domain
│   ├── infrastructure
│   └── observability
├── person                        # proposed; ADR-0005 / #602
│   ├── application
│   ├── domain
│   └── infrastructure
├── narmestelederstatistikk
│   ├── api
│   ├── application
│   ├── infrastructure
│   └── observability
└── sykmelding
    ├── application
    ├── infrastructure
    └── retention                 # application, domain, infrastructure
```

Start with shallow packages. Add subpackages by business feature when a
package becomes difficult to navigate. Do not create fixed
`command/query/port/handler/impl` trees with one file in each directory.

When a layer is split, use function packages inside that layer, such as
`application/revoke`, not vertical slices across layers. Split only where a
function has several files; a package with a single file adds navigation
without clarity, so `api` stays flat.
Ports, repositories and metrics used by several functions stay in the root of
the layer. Keep full class names, for example
`revoke.RevokeNarmestelederrelasjonUseCase`, so searches, stack traces and
imports stay unambiguous. Loggers named after their class follow the new
package. `domain`, `infrastructure` and `observability` stay flat until they
become difficult to navigate. Tests mirror the package of the code they test.

## Module responsibilities

### `narmestelederbehov`

Owns the temporary lifecycle of a need for an employer to report a
narmesteleder:

- create and prevent duplicate needs;
- verify that the need exists and is not already fulfilled;
- authorize access through `organisasjonstilgang`;
- fulfill, expire and query needs;
- fulfill the employee's open needs when Leesah reports an active
  narmesteleder (`aktivTom` is empty), saving the status before completing
  the Dialogporten dialog;
- own `nl_behov`;
- consume Leesah in `infrastructure.kafka` and create needs from the events;
- coordinate Dialogporten state and retry behavior.

It establishes a relation through an application contract owned by
`narmestelederrelasjon`, which validates the relation and returns a typed
result. Fulfillment parses the manager's contact details with that contract
first, so invalid input is still rejected before the need lookup and access
check. It does not validate the relation itself and does not use relation
repositories, ports, domain rules, Kafka models or Kafka producers directly.

Fulfillment orders its effects: contact validation, then need lookup, then
access check, then relation establishment, and finally publication before
persistence before Dialogporten completion. An unexpected failure stops the
later effects. After publication, `markFulfilled` uses one status-only
`UPDATE ... RETURNING` to obtain the dialog ID; a missing row is the existing
404 response, and a null dialog ID skips completion. Dialog completion follows
ADR-0004: an already completed dialog counts as success, a revision conflict
is re-read once, and a failed completion or status update leaves the behov
fulfilled so the scheduled job can retry.

The target for #602 is to register referenced people through `person` and
read their names locally without PDL calls or writes on behov read paths.
The behov module retains ownership of behov statuses and dialog creation;
see [`person` (proposed)](#person-proposed).

### `narmestelederrelasjon`

Owns established and revoked narmesteleder relations:

- establish and revoke relations;
- validate a relation before establishing it: the manager's contact details,
  active sykmelding, employment, persons in PDL and submitted last names.
  Name-validation metrics are recorded immediately after matching, before
  publishing or other side effects can fail, so failure paths still record
  observations. Establishing and revoking only publish a message; the relation
  is stored when the resulting Leesah event is consumed, so validation must
  happen before publishing;
- accept direct POST relation submissions, normalizing contact details before
  checking organization access and establishing through the relation contract;
- handle `POST /api/v1/linemanager/revoke` by checking organization access,
  resolving the employee in PDL, validating the last name and active relation,
  then publishing a revocation without requiring employment or sick leave;
- revoke the relation for a sendt sykmelding without validation, publishing
  with the `ARBEIDSTAGER_SYKMELDING_REVOKE` Kafka source;
- revoke a relation when the employee's employment in the organization has
  ended, by checking active relations against Aareg at regular intervals (#473,
  being introduced). It takes over the behavior of team sykmelding's
  `narmesteleder-arbeidsforhold`: keep the relation while the employee has an
  employment whose workplace has the relation's organization number and that
  has not ended or ended no more than four months ago. Otherwise publish with the
  `narmesteleder-arbeidsforhold` Kafka source. A failed lookup never revokes. The
  check runs in shadow mode first (`EMPLOYMENT_CHECK_ENABLED`), is compared with
  team sykmelding's revocations on Leesah (`EMPLOYMENT_CHECK_OBSERVE_SOURCE_ENABLED`)
  and publishes only with `EMPLOYMENT_CHECK_REVOKE_ENABLED`. Startup fails if
  revocation and source observation are both enabled. Live mode freezes comparison.
  `NlAvbrutt` deactivates all active relations for orgnr+fnr, so a new relation
  established between lookup and publish could be hit (R2). The reference app
  has the same race; it is documented, not fixed here;
- publish relation messages;
- ingest and republish relation events from Leesah. The module owns the
  republished topic's message model; other modules that read Leesah keep their
  own model;
- own the local relation register;
- provide lookup and search, including the `HasActiveNarmestelederrelasjon`
  contract for checking active relations in the local register;
- maintain the relation-owned `RelationPerson` projection until its ownership
  moves to `person` through #602.

`RelationPerson` is the code-level name for person details needed by relation
lookup and search. It is a local, incomplete projection and not an
authoritative person registry. Its persistence, enrichment and PDL name-update
handling currently live in `narmestelederrelasjon`. The proposed extraction
below does not change relation validation, including its synchronous PDL
checks, or the current publish-before-Leesah-persistence flow.

### `person` (proposed)

[ADR-0005](adr/ADR-0005-person-eier-lokale-personopplysninger.md) proposes a
module for local details of people referenced by a narmestelederbehov or
narmestelederrelasjon, as employees or managers. It is not a general person
directory or a PDL mirror. PDL remains the source of the details.

The target module owns:

- the `person` table, its schema and all writes;
- an idempotent registration contract for missing persons, storing name and
  birth date when the writer already has them;
- asynchronous enrichment of persons registered without details, including
  status, attempts and backoff;
- PDL `NAVN_V1` updates for registered persons;
- bounded deletion of unreferenced person rows.

Registration and enrichment are separate operations. A writer that already
holds validated PDL details registers the person with them; otherwise
registration guarantees a `PENDING` row, not a usable name. See
[Person registration](#person-registration-proposed) for both paths and the
atomicity requirement. Enrichment runs independently of Dialogporten.
`narmestelederbehov` interprets the enrichment outcome and owns its own state
transitions. Only `SendDialogTask` creates behov dialogs, after a usable name
is available. `person` never writes behov or relation state.

Consumers may join person details through their own private, read-only table
definitions rather than importing `person` tables or repositories. This keeps
write ownership in `person` while retaining an explicit schema dependency.
The shared PDL client remains in `integration/pdl`, and official identifiers
remain in `ident`.

A person row is eligible for deletion only when neither `narmeste_leder` nor
`nl_behov` references the person in either role. Closed or expired behov and
inactive relations still retain the person. The cleanup adapter uses its own
read-only definitions of these reference tables, without a code dependency
back to their owners. Cleanup must coordinate with concurrent registration
and reference writes; a detached candidate list is not sufficient. It never
deletes behov or relations or defines their retention periods.

### `sykmelding`

Owns processing and local persistence of sendt sykmelding:

- normalize and validate Kafka input;
- deduplicate records;
- apply retention rules;
- persist and revoke individual sykmeldinger;
- trigger creation of narmestelederbehov only when the employee has not answered
  `riktigNarmesteLeder` and has no active narmestelederrelasjon in the organization,
  checked through the `HasActiveNarmestelederrelasjon` contract;
- trigger revocation of narmestelederrelasjon through the
  `RevokeNarmestelederrelasjonFromSendtSykmelding` contract. `sykmelding` owns
  the `kilde` it stores for each processed brudd.

The initial dependency is one-way:

```text
sykmelding -> narmestelederbehov
sykmelding -> narmestelederrelasjon
```

`narmestelederbehov` and `narmestelederrelasjon` each use Dinesykmeldte through
their own `ActiveSykmeldingLookup` port. Replacing those adapters with the local
sykmelding projection belongs to #508 and follow-up work.

The incoming sendt sykmelding model (`SendtSykmeldingKafkaMessage` and
`SykmeldingRecord`) lives in `sykmelding.application` as the module's input
model, so the application classes do not depend on `infrastructure`. The Kafka
consumers in `sykmelding.infrastructure.kafka` deserialize directly into it.

### `organisasjonstilgang`

Owns reusable business authorization for acting on behalf of an organization:

- evaluate access through Altinn Tilganger;
- perform PDP decisions;
- resolve relevant Ereg organization hierarchy;
- return typed allow/deny/unavailable results. Upstream failures in the
  personnel-manager path return `OrganizationAccessResult.Unavailable`;
  use cases return `UpstreamUnavailable`, and edges map it through
  `toUpstreamUnavailableException()` to 500 `UPSTREAM_SERVICE_UNAVAILABLE`.
  The system-user path follows the same rule: it stops at the first failure
  from Texas, the Altinn token exchange, the PDP or Ereg, without fallback
  (#687 removed the last Ereg exception bridge).

It does not load narmestelederbehov or narmestelederrelasjon records. The
calling use case owns resource lookup, authorization order and non-disclosure
rules.

### `narmestelederstatistikk`

Owns the read model for narmesteleder statistics per organization
(`GET /internal/api/v1/linemanager/statistics`):

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
types. Clients return `UpstreamResult<T>` instead of throwing when the external
system fails; see [Errors and HTTP mapping](#errors-and-http-mapping). The
Dinesykmeldte client is the remaining exception: it returns `Boolean` and
throws on failure
([#683](https://github.com/navikt/esyfo-narmesteleder/issues/683)).

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

- `PersonalIdentificationNumber`;
- `OrganizationNumber`.

Module-specific IDs, statuses, actors, commands, DTOs and errors do not belong
here. The package has no `api`, `application`, `domain` or `infrastructure`
layers because it has no business workflow to hide.

### `platform`

Contains reusable technical mechanisms without domain rules:

- `api`: shared HTTP paths, errors, API plugins and path/query parameter
  helpers;
- `auth`: Ktor authentication and principal construction;
- `auth/texas`: Texas-backed Ktor `Authentication` providers, token providers
  and client. `platformModule` installs the providers once and resolves
  `TexasHttpClient` from Koin, so routes only select a named provider;
- `cache`: generic Valkey cache and configuration;
- `config`: shared environment-variable lookup;
- `database`: data source, Flyway and shared transaction infrastructure;
- `http`: shared HTTP client construction;
- `kafka`: producer/consumer construction and reusable lifecycle mechanics,
  including `LeaderControlledKafkaConsumer`;
- `local`: JSON fixture loading for local adapters;
- `observability`: metrics, health and common structured logging mechanisms;
- `scheduling`: reusable scheduling mechanics. New scheduled work runs on
  all pods in a background loop and shares the work through database claims with a
  lease, not leader election. See
  [ADR-0003](adr/ADR-0003-planlagt-bakgrunnsarbeid-bruker-claim-med-lease.md).
  The claim table and its states belong to the module that owns the work.
  Existing `ScheduledLeaderTask` jobs still use leader election until #519;
- `application`: small use-case mechanics such as `Step`, which lets private
  use-case steps continue with a value or stop with the use case's result.
  `Step` never appears in public use-case contracts or ports;
- `upstream`: `UpstreamResult` and `UpstreamFailure`, the result types
  returned by clients for external systems. `UpstreamName` is a validated log
  name; each client defines its own constant, so `platform` does not know the
  concrete systems. See
  [ADR-0004](adr/ADR-0004-feil-fra-eksterne-systemer-er-resultater-ikke-exceptions.md).

`platform` must not depend on a business module.

### `bootstrap`

Owns application assembly:

- parse environment variables into small typed settings;
- select production or local adapters;
- combine Koin modules;
- start Ktor, Kafka consumers and scheduled work;
- aggregate routes and operational endpoints.

Business classes do not depend on Koin or bootstrap. Capabilities never see
the bootstrap `Environment`. Bootstrap maps it to capability-owned settings such
as `NarmestelederbehovSettings` and `NarmestelederrelasjonSettings` and passes
them as parameters to the capability module, which hands them on to its
`<capability>Dependencies(settings)`; settings are not registered in Koin.
Single feature flags are passed directly to consumers. `BootstrapBoundaryTest` fails if a
business module references `bootstrap`.

`Application.module()` delegates to `Application.bootstrap()`, which reads the
environment once, starts the platform module and then one Ktor module per
capability:

```kotlin
fun Application.bootstrap(isLocal: Boolean = isLocalEnv()) {
    val environment = loadEnvironment(isLocal)
    platformModule(applicationModules(environment, isLocal))
    bindJvmAndProcessMetrics()
    configureLifecycleHooks(get())
    configureLeaderMonitoring(get(), get())
    organisasjonstilgangModule()
    narmestelederrelasjonModule(environment.narmestelederrelasjonSettings())
    narmestelederbehovModule(environment.narmestelederbehovSettings())
    narmestelederstatistikkModule()
    sykmeldingModule()
    configureKafkaConsumers(environment)
    configureBackgroundTasks(
        dialogportenTaskSettings = environment.dialogportenTaskSettings,
        maintenanceTaskSettings = environment.maintenanceTaskSettings,
        personEnrichmentTaskSettings = environment.personEnrichmentTaskSettings,
    )
    configureEmploymentChecks(environment.otherProperties)
}
```

- `NaisEnvironment` and `LocalEnvironment` assemble typed integration,
  background-task, expiry, and leader-election settings. Bootstrap passes these
  settings only to their owners. Kafka consumer and capability configuration
  still use `OtherEnvironmentProperties`.
- `platformModule` installs Koin once with the shared Koin modules
  (`applicationModules`), configures Texas `Authentication` providers, installs
  CallId, content negotiation and StatusPages, and registers pod and metric
  endpoints, OpenAPI and Swagger. A capability module never installs Koin.
- Each capability has an `Application.<capability>Module()` in
  `<Capability>Module.kt` that owns its dependencies, routes, Kafka consumers
  and lifecycle. It registers its Koin definitions with
  `koinModules(<capability>Dependencies())`; the definitions live in
  `<Capability>Dependencies.kt` and are not listed in `applicationModules`.
  `DependencyInjectionTest` verifies each `<capability>Dependencies()` together
  with `applicationModules`, and fails if a type is defined twice (Koin would
  otherwise let the last definition win silently).
- All five capabilities (`organisasjonstilgang`, `narmestelederrelasjon`,
  `narmestelederbehov`, `narmestelederstatistikk` and `sykmelding`) follow this
  pattern for dependencies and routes where applicable. Consumer/task startup
  remains in `bootstrap` until moved into their capability modules.
- Koin definitions are not order-sensitive, but capability modules load their
  definitions with `koinModules` and eagerly resolve route dependencies at
  startup to fail fast. A module using another capability's contract must
  therefore run after that capability's module: organisasjonstilgang first
  because the other capabilities use `OrganizationAccess`, relasjon before
  behov, and sykmelding after behov and relasjon. Consumers, tasks and anything
  else resolving capability definitions run after all capability modules.
- Capability modules add routes under `apiV1 { }` or `internalApiV1 { }` from
  `platform.api`. Routes are denied by default: every endpoint must be inside
  `authenticateTokenX`, `authenticateMaskinportenOrTokenX` or
  `authenticateAzureAd`, or inside `publicRoutes { }`; startup fails
  otherwise, including for Ktor's `authenticate` used directly. The module
  wraps its `registerXApi` calls in the helper, so route functions do not
  choose their own provider; route tests wrap them the same way. Ktor merges
  nested `authenticate` blocks, so an optional block inside a Texas helper
  still requires a principal. Only `platformModule` declares public
  routes (probes, metrics, OpenAPI and Swagger UI), and `publicRoutes` has no
  effect under the API prefixes. The check cannot see provider `skipWhen`
  predicates, so do not configure them. Endpoints
  use the Texas `Authentication` providers installed once by `platformModule`:
  TokenX, Maskinporten, and Azure AD. Routes accepting either a user or system
  token use Ktor's `FirstSuccessful` strategy. Provider exceptions continue to
  `StatusPages`, and handlers read the authenticated domain principal through
  `getMyPrincipal()`.
- A capability module can start alone with the platform module in
  `testApplication`; the test registers only the dependencies the module gets
  from outside, such as other modules' contracts and shared clients.
- Route tests use the `testApiApplication` or `testRouteApplication` helpers.
  They install the same Texas auth providers as production, backed by the
  provided `TexasHttpClient`, and Koin with that test client. `testApiApplication`
  also installs the production request-handling plugins (CallId, content
  negotiation and StatusPages); `testRouteApplication` installs them without
  CallId.

## Dependency rules

The arrows to `person` are target dependencies from proposed ADR-0005, not
implemented dependencies yet.

```text
sykmelding -> narmestelederbehov -> narmestelederrelasjon
     └──────────────────────────> narmestelederrelasjon

narmestelederbehov -> organisasjonstilgang
narmestelederrelasjon -> organisasjonstilgang
narmestelederstatistikk -> organisasjonstilgang

narmestelederbehov -> person
narmestelederrelasjon -> person

business modules -> ident value types
business infrastructure -> focused platform mechanisms
business infrastructure -> integration clients
bootstrap -> all modules and integration
```

Rules:

- Module dependencies follow the arrows above and never form a cycle, also not
  indirectly through root packages. The only root packages are the business
  modules, `ident`, `integration`, `platform` and `bootstrap`.
- `domain` has no Ktor, Kafka, Exposed, JDBC, Koin, HTTP-client or environment
  dependencies.
- `application` depends on its domain and small ports, never its
  `infrastructure`.
- Other modules may use only explicitly exposed application contracts, the
  domain types those contracts name, and the parsing of the contracts' input
  types (for example `ManagerContactInput.normalize()`). Parsing lets a caller
  reject invalid input before its own lookups without repeating the rule.
  Other modules never depend on another module's `infrastructure`, in
  production code or in tests.
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
    private val repository: FulfillNarmestelederbehovRepository,
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

Every rejected token logs a `token_rejected` event with the provider as
`upstream` and a `reason`, and increments
`syfo-narmesteleder_token_rejected_total{provider,reason}`. Texas introspection
failures are server errors, not rejections.

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

Failures in external systems are results, not exceptions
([ADR-0004](adr/ADR-0004-feil-fra-eksterne-systemer-er-resultater-ikke-exceptions.md),
being introduced through #681):

- Clients catch Ktor and Texas exceptions once and return
  `UpstreamResult.Failure(UpstreamFailure)` with the upstream, failure stage,
  status and cause. `CancellationException` is always rethrown. Expected
  answers such as "not found" are part of the success value.
- Ports and use-case results have their own `Unavailable` variant carrying the
  `UpstreamFailure`. Use cases stop with it through `Step`.
- The edge decides the outcome and logs once. HTTP adapters map it to
  `ApiErrorException` with `UPSTREAM_SERVICE_UNAVAILABLE` and the failure
  attached; Kafka consumers and scheduled jobs apply their own retry or
  postponement rule.
- The ADR-0004 transition bridge has been removed: `getOrThrow()` and
  `UpstreamRequestException` no longer exist, and all callers propagate
  failures as values.

Database failures, Kafka publishing failures and programming errors remain
exceptions.

## Ports and adapters

A port is an interface requested by application code:

```kotlin
interface FulfillNarmestelederbehovRepository {
    suspend fun findForFulfillment(
        id: RequirementId,
    ): Narmestelederbehov?
}
```

An adapter implements the port using a mechanism:

```kotlin
class ExposedNarmestelederbehovFulfillmentRepository(
    private val database: Database,
) : FulfillNarmestelederbehovRepository,
    FulfillNarmestelederbehovFromLeesahRepository
```

Ports live in `application`. Adapters live in the owning module's
`infrastructure` package. An adapter for an external system used by several
modules calls the shared client in `integration`; see
[`integration`](#integration). Give each use case a role-specific port with only
the operations it needs. Place a port used by one use case in the same file,
immediately before that use case. Keep the port in a separate file when it is
shared by multiple application services or use cases. A persistence adapter may
implement several related ports when they form a cohesive workflow and use the
same table and transaction mechanism. Split table-wide adapters by
responsibility when unrelated read, fulfillment or lifecycle operations would
otherwise accumulate in one class. Name ports by capability and adapters by
mechanism. Do not use `I` prefixes or generic `Impl` suffixes in migrated code.

## Persistence and transactions

Repositories own Exposed/JDBC transactions. Use cases never open database
transactions or expose `Transaction`, `ResultRow`, DAO entities or JDBC
connections.

Split repository contracts by business purpose, not mechanically by table or
SQL statement. For example, write flows, API queries and maintenance may have
separate focused contracts over `nl_behov`.

New and migrated repository adapters use Exposed DSL (`Table` and
`Database.ioTransaction`), not new raw JDBC (`DatabaseInterface`) or Exposed
DAO code. The shared helper runs blocking JDBC work on `Dispatchers.IO` and
delegates transaction handling to Exposed `suspendTransaction`. Do not
duplicate `withContext(Dispatchers.IO)` around repository transactions.
Existing JDBC/DAO implementations migrate when their adapter is touched. The
separate broad migration remains follow-up work; moving a flow alone does not
imply a schema, query-semantics or transaction change.

A database transaction does not include Kafka, Dialogporten or another remote
system. The use case owns and tests the order between those effects.

### Person registration (proposed)

For #602, adding a person reference to a relation or behov and registering a
missing person must commit in one PostgreSQL transaction. Database failures
roll back all local writes in that transaction and propagate to the caller;
normal registration does not rely on separate commits followed by best-effort
repair.

The primary path registers the person with details. A writer that has already
looked up the person in PDL, as API establishment and fulfillment do for
validation, stores name and birth date as `ENRICHED` in the same transaction
as the reference. The PDL lookup happens before the transaction, never inside
it. The stored details must come from a fresh PDL lookup in the same request,
not from the cache: `NAVN_V1` only updates registered persons, so a name
change between a cached lookup and registration would never be corrected.
Today's `PersonLookup` reads through the Valkey cache and returns names only,
so establishment needs a fresh lookup that also carries the birth date. Once
the service receives all narmesteleder requests directly instead of through
team sykmelding's Kafka flow, this is the normal path for new relations.

Only a writer without the details registers a `PENDING` person for
asynchronous enrichment. After the takeover, that is in practice a behov from
a sendt sykmelding whose employee is not yet registered. Until then it also
covers relations from team sykmelding's Leesah flow, and it covers backfill.
The sendt sykmelding consumer does not call PDL to avoid `PENDING`: it stays
independent of PDL, and asynchronous enrichment remains the single fallback.

Registration stores details only when the person row is missing. Existing
details and enrichment state stay untouched, because enrichment and `NAVN_V1`
own changes to stored details. An idempotent replay must still ensure
registration when the relation or behov already exists.

The writing repository coordinates the local transaction through a
`person`-owned application contract, not direct writes to another module's
table. The implementation must prove shared commit and rollback without
exposing Exposed/JDBC types in the application contract or opening an
independently committed registration transaction. Registration itself calls
neither PDL nor Dialogporten. This does not extend the transaction to external
effects or change the existing relation publication order.

## Narmestelederbehov transitions

A small domain object or transition function owns valid status changes. It
does not call repositories or remote systems.

An employee has at most one active narmestelederbehov per organization. The
partial unique index `uq_nl_behov_active_employee_org` enforces this for
`BEHOV_CREATED` and `DIALOGPORTEN_STATUS_SET_REQUIRES_ATTENTION`, because
several sources (sendt sykmelding and narmesteleder Leesah events) can request
the same behov concurrently and each active behov becomes a Dialogporten
dialog for the employer. A check before insert cannot stop that race on its
own. A conflicting insert means the behov already exists and is skipped, not
an error. A write that moves a behov into an active status must require the
expected current status, so that a stale snapshot cannot reopen a closed behov.

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

Keep existing endpoint, OpenAPI, Kafka and PostgreSQL coverage. New tests use
small, explicitly seeded fakes instead of broad shared fixtures.

Architecture tests enforce the repository-wide rules, including the allowed
root packages. Do not add large exception lists.

## Migration approach

The migration from the legacy `narmesteleder` package is complete (#625). All
current business code lives in the implemented modules above, and shared
mechanisms live in `platform`. The notes below record where the migrated flows
ended up and which changes remain separate.

`GET /api/v1/access/organizations` now lives in `organisasjonstilgang` as `ListAccessibleOrganizationsUseCase`.

`GET /internal/api/v1/employee/linemanager` now lives in `narmestelederrelasjon` as `ListActiveNarmesteledereForEmployeeUseCase`.

`POST /internal/api/v1/linemanager/search` now lives in `narmestelederrelasjon` as `SearchActiveNarmestelederrelasjonerUseCase`.

`sykmelding` follows the module structure (`application`, `infrastructure` and
`retention`) and registers its dependencies through `sykmeldingDependencies()`
in its Ktor module. Its persistence adapters use Exposed DSL, and its
consumers still have to move to a shared consumer runner (#623).

The fulfillment migration is complete (#525, #541): the PUT route delegates to
`FulfillNarmestelederbehovUseCase` through capability-owned adapters and Koin
modules, and the legacy handler and transitional duplication are removed. The
durable fulfillment rules are part of [`narmestelederbehov`](#narmestelederbehov)
above.

Each slice migrated one business action or query at a time and preserved
external HTTP and Kafka contracts, database behavior, authentication and
authorization order, configuration names/defaults, logging safety and
side-effect ordering.

The direct submission and employee-and-organization revoke routes now live in
`narmestelederrelasjon`; the requirement routes live in `narmestelederbehov`.
Revocation triggered by sendt sykmelding goes through the
`RevokeNarmestelederrelasjonFromSendtSykmelding` contract. The Leesah consumer
that creates needs lives in `narmestelederbehov.infrastructure.kafka`.

The following are separate changes and must not be hidden inside structural
pull requests:

- outbox or changed relation-delivery guarantees;
- removing Kafka leader gating or changing consumer topology;
- #508 and the switch from Dinesykmeldte to local sykmelding lookup;
- JDBC/Exposed DAO migration to Exposed DSL;
- splitting requirement and Dialogporten state in the database;
- separate Gradle modules or new deployable services.

### Person ownership (#602, proposed)

ADR-0005 and this guide describe the target, not a completed extraction.
Introduce the ownership change in small slices, preserving relation reads
while moving person persistence, registration, enrichment and PDL name
updates. Existing input transports are not a prerequisite for the new
registration invariant; it applies to every path that adds a person reference.

Establish atomic registration on new writes before backfilling missing people
for existing readable behov, including closed behov. Backfill registers them
as `PENDING` without overwriting existing persons. Keep the legacy `nl_behov`
name as a read-only fallback until previously readable behov have usable
names in `person`; an empty pending queue alone does not prove that coverage.

Move behov reads and dialog creation to local person details before removing
the fallback and legacy name writes. Drop the name columns in a later Flyway
migration only after no running reader or writer uses them. Enable orphan
cleanup only once all reference writers and the cleanup path satisfy the
registration and concurrency guarantees.
