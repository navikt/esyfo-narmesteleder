# System-user access rejections

System-user requests are checked against the requested organization in Altinn PDP.
Only `Permit` grants access. After a normal `Deny` or `NotApplicable`, the
fallback checks whether the system user's organization belongs to the requested
organization's Ereg hierarchy and, if so, asks PDP for that organization.

## Stop on upstream failures

Failures from Texas, the Altinn token exchange, PDP or Ereg return
`OrganizationAccessResult.Unavailable(originalFailure)` immediately. No further
fallback or rejection log is produced. The API returns HTTP 500
`UPSTREAM_SERVICE_UNAVAILABLE`, logs `api_request_failed` once at the edge and
counts `get_narmestelederrelasjon_total{outcome="upstream_unavailable"}`.
Ereg `Success(null)` still gives HTTP 400 `ORGANIZATION_NOT_FOUND`.

PDP `Indeterminate` is an upstream failure, not a denial: `altinn-pdp`,
`RESPONSE`, status 200, error code `indeterminate`, no cause. Only the known
XACML syntax-error and processing-error status URIs are retained as detail;
everything else becomes `unknown`. `StatusMessage` is never retained or logged.
Token exchange failures retain `texas` or `altinn-authentication` as their source
and use `TOKEN_EXCHANGE`. See ADR-0004 and [logging.md](logging.md).
Empty, malformed or multiple PDP decisions fail closed rather than granting
based on the first decision.

## One event for the final rejection

When both completed checks deny access, `AltinnOrganizationAccess` emits one
`WARN` through `esyfo-logger`. `SystemUserAccessRejection` owns the typed event:

| Field | Value |
| --- | --- |
| `event_type` | `api_request_rejected` |
| `error_code` | `MISSING_ALTINN_RESOURCE_ACCESS` |
| `operation` | `validate_system_user_access` |
| `rejection_reason` | `SYSTEM_USER_ACCESS_NOT_GRANTED` |
| `pdp_decision` | `Deny` or `NotApplicable` |
| `pdp_fallback_decision` | `Deny`, `NotApplicable`, or `not_checked` outside the hierarchy |

Neither `Permit` nor `Indeterminate` can occur in a rejection. The diagnostic
fields are not metric labels. Existing `trace_id` is preserved. A fallback
`Permit` succeeds without a rejection. Completed denials retain HTTP 403 and
the existing API error type and message; StatusPages does not log them again.

The event does not establish why access is missing or prove a missing delegation.
Use an approved environment for investigation requiring caller identity.
Never add tokens, system-user IDs, organization numbers, provider messages or
raw payloads to diagnostics. Do not change policy merely to reduce counts.

## Verification and pre-merge gate

Run `./gradlew test --tests '*AltinnOrganizationAccessLoggingContractTest'`.
The test covers direct/fallback decisions, original failures with no rejection,
cancellation, trace context and identifier canaries using fakes. It validates
the packaged v1 contract and local event catalog with `esyfo-logger-testkit`,
capturing JSON from the NAIS `stdout_json` encoder in `src/main/resources/logback.xml`.
Schema validation is part of the normal Gradle test; the testkit remains test-only.

Before merge, a human must inspect approved Loki data for existing rejections
with `operation = validate_system_user_access` and `pdp_decision` or
`pdp_fallback_decision` equal to `Indeterminate`. This establishes how often the
change from 403 to 500 affects callers. Automated tests do not prove production
frequency or resolve [#516](https://github.com/navikt/esyfo-narmesteleder/issues/516).
