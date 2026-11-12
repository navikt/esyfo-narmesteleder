# Application logging

## Levels

- WARN and ERROR: typed events, `logger.logEvent(event, details, cause = e)`.
- INFO and DEBUG: plain SLF4J. Use an event only for an INFO outcome worth querying.

## Events

- Define events with `applicationEvent` next to their owner, or in a shared
  `*Events.kt`. See `platform/observability/FailureEvent.kt` for the fields it adds.
- Use one event per outcome; express variants as a bounded `reason` or `action` enum.
- Kafka consumers log through `KafkaEventLogger` in `platform/kafka/KafkaEvents.kt`.

## Who logs

- Log each terminal outcome once, where it is handled. Clients never log; they
  return or propagate failures.
- Upstream failures reach the HTTP edge as `UpstreamFailure`, where StatusPages
  logs them once as `api_request_failed`. StatusPages logs other 4xx as
  `api_request_invalid` and 404 as INFO.
- Log deliberate domain rejections with `apiRequestRejected` (`operation`,
  `rejection_reason`), not ordinary client errors.
- In a catch of `Exception` or `Throwable`, rethrow `CancellationException`
  before logging. Logging never throws.

## Privacy

Never log person identifiers, names, contact details, organization numbers,
system-user IDs, tokens, request or response bodies, or exception messages.
Correlation IDs, bounded enums, HTTP statuses and allowlisted SQL states are fine.

## Testing

- Capture logs with `ProductionLogs` and validate every record with
  `RuntimeLogContract`. To lock event names, validate `records` with your own catalog.
- Assert level, count, fields and sensitive-data canaries; the schema does not
  check privacy.
- Per-upstream fields are specified by `AltinnFailureLoggingContract` and
  `RuntimeDiagnosticsTest`; system-user logging by
  `docs/system-user-access-diagnostics.md`.
