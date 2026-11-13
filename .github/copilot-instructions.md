# esyfo-narmesteleder

Manages relations between sykmeldte and their narmeste ledere.

## Commands

- Build, test and lint: `./gradlew build` (CI runs `./gradlew check`)
- Tests only: `./gradlew test`

## Docs

Read a doc only when the task matches, and only the relevant section
(`grep -n '^#' <file>`).

- Unclear domain term: `CONTEXT.md`
- Running locally, Docker Compose, dev tokens: `docs/development.md`
- Adding or moving code; modules, ports, errors, transactions: `docs/architecture.md`
- Changing an endpoint, Kafka consumer/producer or scheduled task: `docs/data-flow.md`
- Adding or changing logs: `docs/logging.md`
- Changing the Leesah topic payload: `docs/kafka/syfo-narmesteleder-leesah.md` and its schema
- System-user 403 or Altinn PDP: `docs/system-user-access-diagnostics.md`
- E-mail validation: `docs/email-address-rule.md`
- `/api/v1/access/organizations` or `/internal/api/v1/employee/linemanager`: matching file in `docs/`
- Questioning a design choice: `docs/adr/` (REST revocation, modules, claim/lease,
  upstream errors as results, legal frame, person data)

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
- Pass `Connection.TRANSACTION_READ_COMMITTED` to `ioTransaction` for claims
  and queues; the pool's default `REPEATABLE_READ` raises 40001 on concurrent
  row updates.
- Make transactions and HTTP calls safe to repeat: Exposed retries
  `ioTransaction` up to 3 times on any `SQLException` (opt out with
  `maxAttempts = 1`), and `httpClientDefault` retries all methods, including
  POST, twice on non-4xx failures and throws on non-2xx.
- Match new `NlResponseSource` values to the source strings team sykmelding's
  `narmesteleder` app reads.
- Access the database only through Exposed DSL (`Table`). Never write raw JDBC,
  SQL strings or Exposed DAO code; migrate existing JDBC/DAO code you touch.
- Add a new Flyway migration; never edit an existing one.
- Read configuration through `application.conf` and `ApplicationSettings`,
  never from environment variables directly. Per-environment values go in
  `application-<cluster>.conf`, local overrides in
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
