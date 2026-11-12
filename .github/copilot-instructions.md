# esyfo-narmesteleder

narmesteleder-api (team-esyfo): manages relations between sykmeldte and their
narmeste ledere. Kotlin, Ktor, Exposed/PostgreSQL, Flyway, Kafka, Valkey,
Kotest, MockK, Nais/GCP. Maintained by hand; removed `.github/` agents and
skills no longer exist.

## Commands

- Build, test and lint: `./gradlew build` (CI runs `./gradlew check`)
- Tests only: `./gradlew test`

## Docs

Read only the section you need. Find headings with `grep -n '^#' <file>`.

- Modules, dependencies, ports, errors, transactions: `docs/architecture.md`
- Request, Kafka and background flows: `docs/data-flow.md`
- Logging: `docs/logging.md`
- Decisions: `docs/adr/`

## Rules

- Write code and comments in English; use Norwegian for user-facing text and
  domain terms.
- Follow the nearest existing pattern. Check `gradle/libs.versions.toml` before
  adding libraries.
- Import `integration` only from `*.infrastructure` and `bootstrap`. Never
  import another module's infrastructure; map cross-module foreign keys as
  plain columns.
- Put a single-use port in its use case's file.
- Open transactions only in repositories, with `Database.ioTransaction`. Never
  wrap it in `withContext(Dispatchers.IO)`. Never span a transaction over Kafka
  or HTTP calls.
- Parameterize all SQL. Add a new Flyway migration; never edit an existing one.
- Read configuration through `application.conf` and `ApplicationSettings`,
  never from environment variables directly. Local overrides go in
  `src/test/resources/application-local.conf`.
- Write new tests as Kotest `FunSpec` with flat `test("...")` blocks. Prefer
  hand-written fakes over MockK graphs. Use `UpstreamTestHttpClient.kt` for
  HTTP client tests.
- Protect routes with `authenticateTokenX`, `authenticateMaskinportenOrTokenX`
  or `authenticateAzureAd(role)` (role granted via Nais inbound access), never
  Ktor's `authenticate`. Keep use-case
  authorization checks. Declare unauthenticated endpoints in `publicRoutes { }`.
- Add Prometheus metrics for business operations.
- Update `docs/data-flow.md` and this file when a change makes them inaccurate.
  Put drafts in `.local-notes/`.
- Run `./gradlew build` after changes.
- Ask first before changing a database or Kafka schema, authentication
  configuration or GCP resources.
- Never hardcode secrets or configuration. Never use `!!` without a null check.
