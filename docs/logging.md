# Application logging

## Levels

- WARN and ERROR: typed, named events via `logger.logEvent(event, details, cause = e)`.
- INFO and DEBUG: plain SLF4J. Use an event only for an INFO outcome worth querying.
- Code already using `applicationLogger` keeps it.

## Events

```kotlin
internal val dialogportenDialogFailed = applicationEvent<DialogEventDetails>(
    name = "dialogporten_dialog_failed",
    level = Level.ERROR,
    message = "Dialogporten dialog operation failed",
    upstream = "dialogporten",
    fields = mapOf("behov_id" to { it.behovId?.toString() }, "action" to { it.action?.name }),
)
```

- Define the event next to its owner, or in a `*Events.kt` file when shared.
- Use one event per outcome. Express variants as a bounded `reason` or `action`
  enum name.
- `logEvent` (`platform/observability/FailureEvent.kt`) adds `failure_kind`,
  `exception_type`, `cause_type(s)`, `upstream`, `upstream_status`,
  `failure_stage`, `sql_state` and a stack with class names and frames only.
  The contract fields come from esyfo-logger; nested classes use binary names
  (`Outer$Inner`).
- Pass `upstreamFailure` to override `upstream`, `failure_stage` and
  `upstream_status`. Without a cause, it describes the error response:
  `failure_kind = error_response`, no stack.
- Kafka consumers use the events in `platform/kafka/KafkaEvents.kt` through
  `KafkaEventLogger`, which adds `consumer`.

## Who logs

- Log each terminal outcome once, where it is handled. Clients propagate; the
  service, consumer or HTTP edge logs.
- StatusPages logs only what is not yet logged: 5xx as ERROR
  `api_request_failed` (with `upstreamFailure` when present), other 4xx as WARN
  `api_request_invalid` with `error_type`, 404 as INFO.
- Log deliberate domain rejections, such as denied system-user access, with
  `apiRequestRejected` plus `operation` and `rejection_reason`. Not for ordinary
  client errors.
- Rethrow a direct `CancellationException` before logging in a catch of
  `Exception` or `Throwable`. Logging never throws.

## Upstream failures

These clients do not log; the failure reaches the HTTP edge as an
`UpstreamFailure` and is logged once as `api_request_failed`.

| Upstream | `upstream` | `failure_stage` | Notes |
|---|---|---|---|
| Texas token (Azure AD, Maskinporten, TokenX) | `texas` | `token_exchange` | `token_target` names the target system. `upstream_errors` holds the lowercase `OAuthFailureCode`, else `unknown` |
| Altinn Tilganger | `arbeidsgiver-altinn-tilganger` | `response` | `isError = true` gives status 200 and `upstream_errors = [{code: "is_error"}]`, no cause, even with data |
| Altinn token exchange | `altinn-authentication` | `token_exchange` | Status when Altinn answered, including invalid tokens |
| Altinn PDP | `altinn-pdp` | `response` with status; `request` without status for timeout and IO | Invalid, empty or ambiguous answers count as `response` |
| PDL single lookup | `pdl` | `response` for GraphQL errors | `upstream_errors` entries have `code`, `detail` (`extensions.id`), dot-joined `path`, and `message` (max 200 chars, only with `extensions.code`). `ugyldig_ident` is not found, not a failure, so no `pdl_lookup_degraded` |

- System-user check: the first failure from Texas, Altinn exchange, PDP or Ereg
  stops the check as `Unavailable`, with no fallback and no
  `api_request_rejected`. The edge answers 500 `UPSTREAM_SERVICE_UNAVAILABLE`.
  The rejection log covers `Deny`, `NotApplicable` and `Indeterminate`.
- PDL bulk lookup keeps its existing logging, with `pdl_errors` in the same
  shape as `upstream_errors`.

## Privacy

Never log person identifiers, names, contact details, organization numbers,
system-user IDs, tokens, request or response bodies, exception messages,
`error_description`, `StatusMessage` or `StatusDetails`. Correlation IDs,
bounded enums, HTTP statuses, allowlisted SQL states and PDL `message` (rules
above) are allowed.

## Testing

- Capture logs with `ProductionLogs` (production Logback JSON encoder).
- Validate every record with `RuntimeLogContract` before filtering by event.
  `ProductionLogs.logs` checks shape only; to lock event names, validate
  `records` with your own catalog.
- Assert level, count, fields and sensitive-data canaries; the schema does not
  check privacy.
- `LoggingArchitectureTest` keeps migrated packages on the local logging entry
  point.
