# Application logging

WARN and ERROR are typed, named events. INFO and DEBUG are usually ordinary
SLF4J logs; an INFO outcome worth querying may also be an event.

## Defining an event

Define the event next to the code that owns the outcome, or in a small
`*Events.kt` file when several classes share it:

```kotlin
private val dialogFailed = applicationEvent<DialogFailureDetails>(
    name = "dialogporten_dialog_failed",
    level = Level.ERROR,
    message = "Dialogporten dialog operation failed",
    upstream = "dialogporten",
    fields = mapOf("behov_id" to { it.behovId }, "action" to { it.action.name }),
)

logger.logEvent(dialogFailed, details, cause = exception)
```

- One event per operational outcome. Variants become a bounded `reason` or
  `action` enum, logged as the enum name.
- The helper in `logging/FailureEvent.kt` adds technical fields derived from the
  cause: `failure_kind`, `exception_type`, `cause_type(s)`, `upstream`,
  `upstream_status`, `failure_stage` and `sql_state`. It also logs a sanitized
  stack trace that contains class names and frames, never exception messages.
- The contract fields `exception_type`, `cause_type`, `upstream_status` and
  `sql_state` come from the esyfo-logger 0.3.0 helpers, so they always match the
  team's runtime-error contract. Nested classes keep their binary name, so
  `ApiErrorException.InternalServerErrorException` is logged as
  `ApiErrorException$InternalServerErrorException`. Classification,
  `failure_stage`, `cause_types` (simple names) and the sanitized
  TechnicalFailure stack stay in the app.
- Kafka consumers share the events in `application/kafka/KafkaEvents.kt` and
  log them through `KafkaEventLogger`, which adds a bounded `consumer` field.
- Code already using `applicationLogger` keeps using it.

## Who logs

- Each terminal outcome is logged once, by the code that handles it. Clients
  propagate failures; the service or consumer that catches them logs.
- StatusPages logs only what has not already been logged: unexpected 5xx as
  ERROR `api_request_failed` with the cause, other 4xx as WARN
  `api_request_invalid` with `error_type`, and 404 as INFO.
- A deliberate, domain-owned rejection worth following up, such as rejected
  system-user access, uses the library's shared `apiRequestRejected` with an
  `operation` and a `rejection_reason`. Do not use it for ordinary client errors.
- At a boundary catching `Exception` or `Throwable`, rethrow a direct
  `CancellationException` before logging. Logging never throws.

## Privacy

Events never contain person identifiers, names, contact details, organization
numbers, tokens, request or response bodies, or exception messages. Correlation IDs,
bounded enum values, HTTP statuses and allowlisted SQL states are fine. PDL
`errors[]` may be logged as `pdl_errors` (PDL documentation D.2–D.3), but never
response `data` or request variables.

## Testing

Test the owner with the production Logback JSON encoder and validate every
captured record with `RuntimeLogContract` before filtering by event name.
Assert level, event count, fields and sensitive-data canaries. The schema
validates shape, not privacy. `LoggingArchitectureTest` keeps migrated packages
on the local logging entry point.
