# Application architecture

`esyfo-narmesteleder` is a modular monolith: one Ktor application and one
Gradle module, organized by business capability. Decision:
[ADR-0002](adr/ADR-0002-organiser-backend-som-en-kapabilitetsbasert-modulaer-monolitt.md).
Domain terms: [`CONTEXT.md`](../CONTEXT.md). Routes, consumers, background work
and flow order: [Data flow](data-flow.md).

## Packages

Each business module under `no.nav.syfo` has the layers it needs: `api`,
`application`, `domain`, `infrastructure`.

- Start shallow. Split a layer into function packages (`application/revoke`)
  only when a function has several files. Never build
  `command/query/port/handler/impl` trees.
- Shared ports, repositories and metrics stay in the layer root.
- Keep full class names (`revoke.RevokeNarmestelederrelasjonUseCase`).
- Tests mirror the package of the code they test.

## Modules

| Module | Owns | Does not |
|---|---|---|
| `narmestelederbehov` | Need lifecycle (`nl_behov`): create without duplicates, fulfil, expire, query; Dialogporten state and retries; Leesah consumer that creates and fulfils needs | Validate relations or use relation repositories, ports, domain or Kafka models |
| `narmestelederrelasjon` | Establish, revoke and validate relations; publish relation messages; local register from Leesah and republishing; lookup, search, `HasActiveNarmestelederrelasjon`; `RelationPerson` projection (moving to `person`); employment check against Aareg | |
| `sykmelding` | Sendt sykmelding: normalize, deduplicate, persist, retention; trigger need creation and revocation through contracts; the stored `kilde` per brudd | Call Dinesykmeldte for other modules (each keeps its own `ActiveSykmeldingLookup` against Dinesykmeldte) |
| `organisasjonstilgang` | Organization access: Altinn Tilganger, PDP, Ereg hierarchy; returns allow, deny or `Unavailable` | Load behov or relations; the caller owns lookup, authorization order and non-disclosure |
| `narmestelederstatistikk` | Read-only statistics over `nl_behov`, `narmeste_leder`, `sendt_sykmelding` via private table definitions | Write; no `domain`; nothing depends on it |
| `person` (proposed) | `person` table, idempotent registration, enrichment, `NAVN_V1` updates, cleanup of unreferenced rows | Write behov or relation state. See [ADR-0005](adr/ADR-0005-person-eier-lokale-personopplysninger.md) |

Supporting packages:

- `integration`: transport-only clients (HTTP, tokens, caching, DTOs) used by
  several modules. No business rules, ports or domain types. A client used by
  one module lives in its `infrastructure` and moves here at the second user.
  Each module keeps its own port and adapter; adapters are never shared.
- `ident`: only `PersonalIdentificationNumber` and `OrganizationNumber`.
- `platform`: reusable mechanisms. `upstream` holds `UpstreamResult`,
  `UpstreamFailure` and `UpstreamName` (each client defines its own name).
  `application` holds `Step`, which never appears in public contracts or ports.
  `scheduling`: new scheduled work uses database claims with a lease on all
  pods ([ADR-0003](adr/ADR-0003-planlagt-bakgrunnsarbeid-bruker-claim-med-lease.md));
  existing `ScheduledLeaderTask` jobs still use leader election.

## Dependency rules

```text
sykmelding -> narmestelederbehov -> narmestelederrelasjon
sykmelding -> narmestelederrelasjon
narmestelederbehov, narmestelederrelasjon, narmestelederstatistikk -> organisasjonstilgang
narmestelederbehov, narmestelederrelasjon -> person        (target, ADR-0005)
business modules -> ident
business infrastructure -> platform, integration
bootstrap -> everything
```

- No cycles, also not through root packages. Root packages are the business
  modules, `ident`, `integration`, `platform` and `bootstrap`.
- `domain` has no Ktor, Kafka, Exposed, JDBC, Koin, HTTP-client or environment
  dependencies. `application` never depends on its `infrastructure`.
- Other modules use only exposed application contracts, the domain types they
  name and their input parsing (`ManagerContactInput.normalize()`).
- Never import another module's infrastructure, repositories, tables,
  transport models, adapters or Koin registration, also not in tests. To read
  another module's table, define a private read-only table.
- Validation lives with what it validates (caller in `organisasjonstilgang`,
  need in `narmestelederbehov`, relation in `narmestelederrelasjon`). Callers
  do not repeat it.
- Only `*.infrastructure` and `bootstrap` import `integration`.
- `platform`, `integration` and `ident` never import business modules.
- Architecture tests (`CapabilityArchitectureTest`, `BootstrapBoundaryTest`)
  enforce these rules. Do not add large exception lists.

## Use cases and ports

- One class per business action or query, named `<Action><Concept>UseCase`,
  with `suspend fun execute(command): Result`.
- Inject the concrete use case inside a module. Add a `fun interface` only when
  another module calls it.
- Never add a generic `UseCase<I, O>`, command bus, service locator, interface
  per class or broad `*Service`/`Commands`/`Queries` facade.
- Ports live in `application`, adapters in the owning module's
  `infrastructure`. Give each use case a role-specific port. Put a single-use
  port in its use case's file; keep the port's input and output types in the
  port's file.
- An adapter may implement several ports for one cohesive workflow over the
  same table. Name ports by capability and adapters by mechanism; no `I`
  prefix or `Impl` suffix.

## Validation and authorization

1. Input adapters parse fields into validated identifier types. Identifier
   types validate representation only.
2. Use cases validate business rules in an explicit order.
3. `organisasjonstilgang` evaluates organization access.

Ktor/Texas authentication only validates credentials and builds the principal;
never put PDP, Ereg, database or business calls in an auth plugin. A rejected
token logs `token_rejected` and increments
`syfo-narmesteleder_token_rejected_total{provider,reason}`; Texas introspection
failures are server errors.

Routes are denied by default. Every endpoint is inside `authenticateTokenX`,
`authenticateMaskinportenOrTokenX` or `authenticateAzureAd(role)`, or in
`publicRoutes { }` (only in `platformModule`); startup fails otherwise. The
capability module wraps its `registerXApi` calls in the helper. Do not
configure provider `skipWhen`. Routes accepting user or system tokens use
`FirstSuccessful`. Handlers read the principal with `getMyPrincipal()`.

## Errors

- Use cases return sealed results for expected outcomes and never import Ktor
  or HTTP types. HTTP adapters map results to `ApiErrorException`/`ErrorType`;
  `StatusPages` builds the `ApiError` response and maps unexpected failures to 500.
- External failures are values
  ([ADR-0004](adr/ADR-0004-feil-fra-eksterne-systemer-er-resultater-ikke-exceptions.md)):
  clients return `UpstreamResult.Failure(UpstreamFailure)` and always rethrow
  `CancellationException`. "Not found" is a success value.
- Ports and results carry an `Unavailable` variant; use cases stop with it via
  `Step`. HTTP edges map it to 500 `UPSTREAM_SERVICE_UNAVAILABLE`; Kafka
  consumers and jobs apply their own retry rule. The Dinesykmeldte client still
  throws.
- Database failures, Kafka publishing failures and programming errors remain
  exceptions. Kafka adapters classify them as retry, permanent or fatal.

## Persistence and transactions

- Repositories own transactions with Exposed DSL and `Database.ioTransaction`.
  Use cases never open transactions or see `Transaction`, `ResultRow`, DAO
  entities or connections.
- Split repository contracts by business purpose, not by table.
- Never write new raw JDBC (`DatabaseInterface`) or DAO code; migrate it when
  you touch the adapter.
- No transaction spans Kafka, Dialogporten or another remote system. The use
  case owns and tests the order of those effects.
- Narmestelederbehov status changes go through a domain transition without
  repository or remote calls. At most one active behov per employee and
  organization (`uq_nl_behov_active_employee_org`); a conflicting insert is
  skipped. Writes into an active status require the expected current status.

## Configuration and dependency injection

- `main()` reads the config files into `ApplicationSettings` once and calls
  `Application.bootstrap(settings)`. `application-<cluster>.conf`, selected by
  `NAIS_CLUSTER_NAME`, overrides `application.conf`; without a cluster only
  `application.conf` is read, and an unknown cluster stops startup.
- Place settings by kind: secrets and values NAIS injects (database, Kafka,
  Texas, Valkey, leader election) as `${?ENV}` in `application.conf`;
  non-secret values that differ per environment (URLs, scopes, intervals) and
  feature switches in `application-dev-gcp.conf` and
  `application-prod-gcp.conf`. Manifest `env:` holds only runtime settings such
  as `JDK_JAVA_OPTIONS`. `ClusterSettingsTest` builds `ApplicationSettings`
  for each cluster.
- Settings are grouped per owner and read by
  `*SettingsReader.kt` in `bootstrap`; startup fails on the first missing or
  invalid key without logging its value. Capabilities receive only their own
  settings as parameters, never through Koin or `ApplicationSettings`.
- TLS for database, Valkey and Kafka is on in `application.conf` and off only
  in `application-local.conf`. When on, every certificate setting is required.
- `runLocal` and tests use `application-local.conf`, ignore environment
  variables and pass fakes through `externalClients`. Production never
  selects fakes.
- `platformModule` installs Koin, Texas auth providers, CallId, content
  negotiation, StatusPages and operational endpoints. Capability modules never
  install Koin.
- Each capability has `Application.<capability>Module()` in
  `<Capability>Module.kt` and its Koin definitions in
  `<Capability>Dependencies.kt`, loaded with `koinModules(...)`. Routes take
  dependencies as parameters. Business classes do not depend on Koin.
- Module order matters because routes resolve eagerly: organisasjonstilgang,
  relasjon, behov, statistikk, sykmelding; then consumers and tasks.
- `DependencyInjectionTest` verifies the full graph and fails on duplicate
  definitions. `ClusterSettingsTest` checks that each cluster's settings
  resolve and that manifest `env:` holds no application settings.

## Testing

- Each flow has use-case tests for decisions and effect order,
  adapter/endpoint tests for PostgreSQL, Kafka, HTTP and Ktor, and
  architecture tests.
- Use small, explicitly seeded fakes instead of broad shared fixtures.
- A capability module can start alone with `platformModule` in
  `testApplication`. Route tests use `testApiApplication` (production plugins)
  or `testRouteApplication` (without CallId).

## Separate changes

Keep these out of structural pull requests:

- outbox or changed relation-delivery guarantees;
- removing Kafka leader gating or changing consumer topology;
- local sykmelding lookup instead of Dinesykmeldte;
- splitting requirement and Dialogporten state in the database;
- separate Gradle modules or deployable services;
- the person ownership rollout, which follows ADR-0005 slice by slice.
