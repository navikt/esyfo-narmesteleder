# Application logging

WARN and ERROR are typed, named events. INFO and DEBUG are usually ordinary
SLF4J logs; an INFO outcome worth querying may also be an event.

## Defining an event

Define the event next to the code that owns the outcome, or in a small
`*Events.kt` file when several classes share it:

```kotlin
internal val dialogportenDialogFailed = applicationEvent<DialogEventDetails>(
    name = "dialogporten_dialog_failed",
    level = Level.ERROR,
    message = "Dialogporten dialog operation failed",
    upstream = "dialogporten",
    fields = mapOf("behov_id" to { it.behovId?.toString() }, "action" to { it.action?.name }),
)

logger.logEvent(dialogportenDialogFailed, details, cause = exception)
```

- One event per operational outcome. Variants become a bounded `reason` or
  `action` enum, logged as the enum name.
- The helper in `platform/observability/FailureEvent.kt` adds technical fields derived from the
  cause: `failure_kind`, `exception_type`, `cause_type(s)`, `upstream`,
  `upstream_status`, `failure_stage` and `sql_state`. It also logs a sanitized
  stack trace that contains class names and frames, never exception messages.
- `logEvent` also accepts `upstreamFailure`. Its `upstream`, `failure_stage` and
  validated `upstream_status` override throwable-derived metadata.
  Når `upstreamFailure.cause` finnes, brukes en eksplisitt cause, eller
  `upstreamFailure.cause`, til klassifisering og renset stack.
  Uten `upstreamFailure.cause` beskriver diagnostikken selve feilsvaret.
- The contract fields `exception_type`, `cause_type`, `upstream_status` and
  `sql_state` come from the esyfo-logger 0.3.0 helpers, so they always match the
  team's runtime-error contract. Nested classes keep their binary name, so
  `ApiErrorException.InternalServerErrorException` is logged as
  `ApiErrorException$InternalServerErrorException`. Classification,
  `failure_stage`, `cause_types` (simple names) and the sanitized
  TechnicalFailure stack stay in the app.
- Kafka consumers share the events in `platform/kafka/KafkaEvents.kt` and
  log them through `KafkaEventLogger`, which adds a bounded `consumer` field.
- Code already using `applicationLogger` keeps using it.

## Who logs

- Each terminal outcome is logged once, by the code that handles it. Clients
  propagate failures; the service or consumer that catches them logs.
- StatusPages logs only what has not already been logged: unexpected 5xx as
  ERROR `api_request_failed` with the cause, other 4xx as WARN
  `api_request_invalid` with `error_type`, and 404 as INFO.
- `ApiErrorException.upstreamFailure` is passed to `api_request_failed` by
  StatusPages, which logs the upstream failure once at the HTTP edge.
- Altinn Tilganger-feil følger kanten som `UpstreamFailure`. Klienten og
  `AltinnTilgangerService` logger ikke. `StatusPages` logger
  `api_request_failed` én gang med `upstream`, `failure_stage`,
  `upstream_status` og eventuelle `upstream_errors`.
  `isError = true` gir `upstream = arbeidsgiver-altinn-tilganger`,
  `failure_stage = response`, `upstream_status = 200` og
  `upstream_errors = [{code: "is_error"}]`, uten `cause` eller stack.
  Det gjelder også når hierarkiet inneholder data.
  TokenX-vekslingsfeil har `upstream = texas` og
  `failure_stage = token_exchange`. HTTP-feilsvar fra Texas gir OAuth-koden
  i `upstream_errors`, begrenset til små bokstaver av `OAuthFailureCode`.
  Ukjente, manglende eller uleselige koder blir `unknown`.
  `error_description`, tokens og svartekst fra Texas eller Altinn Tilganger
  logges aldri.
- Systembrukerløpet sender feil fra Texas, Altinn-vekslingen, PDP og Ereg videre
  uendret som `Unavailable`. Første feil stopper sjekken uten fallback eller
  `api_request_rejected`; HTTP-kanten logger én `api_request_failed` og svarer
  500 `UPSTREAM_SERVICE_UNAVAILABLE`.
  Maskinporten-tokenfeil har `upstream = texas`, `failure_stage = token_exchange`
  og samme begrensede OAuth-feilkode som TokenX-vekslingen ovenfor.
  Altinn-vekslingsfeil, også ugyldige tokens, har
  `upstream = altinn-authentication`, `failure_stage = token_exchange` og
  status når Altinn svarte.
  PDP-feil har `upstream = altinn-pdp`: HTTP-feil, ugyldige/tomme/flertydige svar
  har `failure_stage = response` og status; timeout og IO har
  `failure_stage = request` uten status.
  `Indeterminate` er en feil med `upstream_status = 200`,
  `upstream_errors = [{code: "indeterminate", detail: ...}]`, uten cause eller
  stack. `detail` tillater bare
  `urn:oasis:names:tc:xacml:1.0:status:syntax-error` og
  `urn:oasis:names:tc:xacml:1.0:status:processing-error`, ellers `unknown`.
  `StatusMessage`, `StatusDetails`, rå provider-svar, tokens, systembruker-ID
  og organisasjonsnummer logges aldri. Avvisningsloggen inneholder
  `Deny`-, `NotApplicable`- og `Indeterminate`-beslutninger.
- For enkeltoppslag i PDL følger feilene kanten som verdier. Feltet
  `upstream_errors` er en liste med `code`, `detail`, `path` og `message` fra
  `UpstreamFailure.errors`. `message` settes bare når upstream dokumenterer at
  feilmeldingene ikke inneholder personopplysninger (i dag PDL-spesifikke
  feil). `cause` er valgfri; minst ett av `cause` og
  `errors` må ha innhold. Uten `cause` blir `failure_kind = error_response`,
  og verken exception-meldinger eller stack logges, selv om HTTP-kanten
  bruker en `ApiErrorException`.
  PDLs `message` tas med (maks 200 tegn) bare når feilen har
  `extensions.code`. `detail` er `extensions.id`, og `path` har
  punktum mellom leddene. Ugyldig ident (`bad_request` med
  `id = ugyldig_ident`) behandles som ikke funnet, ikke som en oppstrømsfeil.
  `getPerson` logger derfor ikke `pdl_lookup_degraded`; bolkoppslaget
  beholder dagens logging.
- A deliberate, domain-owned rejection worth following up, such as rejected
  system-user access, uses the library's shared `apiRequestRejected` with an
  `operation` and a `rejection_reason`. Do not use it for ordinary client errors.
- At a boundary catching `Exception` or `Throwable`, rethrow a direct
  `CancellationException` before logging. Logging never throws.

## Privacy

Events never contain person identifiers, names, contact details, organization
numbers, tokens, request or response bodies, or exception messages. Correlation IDs,
bounded enum values, HTTP statuses and allowlisted SQL states are fine. PDL
`errors[]` may be logged as `pdl_errors` with `code`, `detail`, dot-joined `path` and
`message`, the same shape as `upstream_errors`. `message` is included (max 200
characters) only for PDL-specific errors with `extensions.code`; PDL documents that
these contain no personal data. It never contains response `data` or request variables.

## Testing

Test the owner with the production Logback JSON encoder and validate every
captured record with `RuntimeLogContract` before filtering by event name. Use
the shared test helper `ProductionLogs` for the encoder. Its `logs` check the
schema shape and reject `logging_context_invalid`, but they build the catalog
from the captured records, so they do not check event names against a closed
catalog. A test that locks event names validates `records` with its own
`RuntimeLogContract` catalog.
Assert level, event count, fields and sensitive-data canaries. The schema
validates shape, not privacy. `LoggingArchitectureTest` keeps migrated packages
on the local logging entry point.
