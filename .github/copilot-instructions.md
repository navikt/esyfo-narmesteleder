# esyfo-narmesteleder

narmesteleder-api (team-esyfo, navikt) manages the narmesteleder relation
between employees on sick leave and their narmeste ledere. Kotlin, Ktor,
Exposed on PostgreSQL, Flyway, Kafka, Valkey, Kotest and MockK. Runs on Nais
(GCP). This file is maintained by hand in this repository; do not assume
removed `.github/` agents, skills or workflows still exist.

## Commands

```bash
./gradlew build   # Build, test and ktlint. CI runs ./gradlew check
./gradlew test    # Tests only
```

## Where to look

Read only the section you need, not whole documents.

| Topic | Source |
|---|---|
| Modules and their responsibilities | `docs/architecture.md` → Module responsibilities |
| Allowed dependencies between modules | `docs/architecture.md` → Dependency rules |
| Use cases, ports, adapters | `docs/architecture.md` → Application design, Ports and adapters |
| Errors and HTTP mapping | `docs/architecture.md` → Errors and HTTP mapping, ADR-0004 |
| Transactions | `docs/architecture.md` → Persistence and transactions, ADR-0003 |
| Request, Kafka and background flows | `docs/data-flow.md` (one section per flow) |
| Logging | `docs/logging.md` |
| Leesah topic contract | `docs/kafka/syfo-narmesteleder-leesah.md` |
| Decisions | `docs/adr/` |

Use `grep -n '^#' <file>` to find line ranges before reading.

## Conventions

- English code and comments; Norwegian for user-facing text and domain terms
  (sykmelding, narmestelederbehov, oppfolgingsplan).
- Follow the nearest existing pattern. Check `gradle/libs.versions.toml`
  before suggesting libraries.
- Modules: only `*.infrastructure` and `bootstrap` import `integration`. A
  module never imports another module's infrastructure;
  `CapabilityArchitectureTest` enforces this. Map cross-module foreign keys as
  plain columns.
- Place a port used by one use case in that use case's file; keep a separate
  file only for ports shared by several use cases.
- Repositories own transactions through `Database.ioTransaction`; do not wrap it
  in `withContext(Dispatchers.IO)`. Use cases never open transactions, and no
  transaction spans Kafka, Dialogporten or other remote calls.
- SQL is always parameterized. Schema changes are new Flyway migrations; never
  edit an existing one.
- Configuration lives in `src/main/resources/application.conf` and maps to
  `ApplicationSettings`; never read environment variables directly.
  Per-environment and secret values come from `${?VAR}`. Local overrides are in
  `src/test/resources/application-local.conf`.
- Tests: new Kotest classes use `FunSpec` with flat `test("...")` blocks. Prefer
  small hand-written fakes over heavy MockK graphs. HTTP client tests use the
  helpers in `src/test/kotlin/no/nav/syfo/integration/UpstreamTestHttpClient.kt`.
- When a change makes `docs/data-flow.md` or this file inaccurate, update it in
  the same change. Put drafts and plans in `.local-notes/` (not checked in).

## Authentication

Texas-backed Ktor `Authentication` providers are installed in `platformModule`.
Protected routes use `authenticateTokenX`, `authenticateMaskinportenOrTokenX` or
`authenticateAzureAd(role)`, never Ktor's `authenticate` directly, and keep
their use-case authorization checks. Azure AD routes require a custom role
granted through the matching Nais inbound permission. Routes are denied by
default: startup fails unless every endpoint requires authentication or is
declared in `publicRoutes { }`.

## Boundaries

- ✅ Always: run `./gradlew build` after changes; add Prometheus metrics for
  business operations.
- ⚠️ Ask first: database schema or Kafka event schema changes, authentication
  configuration, new GCP resources.
- 🚫 Never: hardcode secrets or configuration, use `!!` without a null check,
  bypass authentication checks.
