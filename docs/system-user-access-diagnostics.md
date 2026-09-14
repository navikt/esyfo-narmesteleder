# System-user access rejections

System-user requests are checked against the requested organization in Altinn PDP.
If that check does not return `Permit`, the existing fallback checks whether the
system user's organization belongs to the requested organization's Ereg hierarchy
and, if so, asks PDP for that organization. Only `Permit` grants access.

## One event for the final rejection

When neither check grants access, the validator emits one `WARN` through the
shared `esyfo-logger` adapter. The local `SystemUserAccessRejection` definition
owns the operation, reason, message and typed PDP context:

| Field | Value |
| --- | --- |
| `event_type` | `api_request_rejected` |
| `error_code` | `MISSING_ALTINN_RESOURCE_ACCESS` |
| `operation` | `validate_system_user_access` |
| `rejection_reason` | `SYSTEM_USER_ACCESS_NOT_GRANTED` |
| `pdp_decision` | The requested organization's PDP decision |
| `pdp_fallback_decision` | The fallback PDP decision, or `not_checked` when the organization does not match the hierarchy |

PDP decisions retain their exact closed enum values: `Permit`, `Deny`,
`NotApplicable`, and `Indeterminate`. A rejected request cannot have `Permit` in
either decision field. These are diagnostic fields, not metric labels or separate
error groups. Any existing `trace_id` in logging context is preserved.

An initial non-`Permit` decision followed by fallback `Permit` is successful and
does not emit a rejection. A PDP or Ereg exception is not a completed access
decision and follows existing exception handling instead. The public response
remains HTTP 403 with the existing API error type and message. StatusPages does
not log the already recorded rejection a second time.

The event does not establish why access is missing or that the caller lacks a
delegation. Investigate the decision pattern before involving the integration
owner; use an approved environment if caller identity or delegation is needed.
Do not add tokens, system-user IDs, organization numbers, or raw PDP payloads to
this event. Changing access policy merely to reduce the count is not a fix.

## Verification

Run `./gradlew test --tests '*SystemAccessLoggingContractTest'`. The test exercises
the validator, PDP service and Ktor error handler. `esyfo-logger-testkit` captures
JSON with the `stdout_json` encoder loaded from `src/main/resources/logback.xml`
using its NAIS profile in an isolated logging context. It validates the packaged
v1 contract and a catalog derived from the actual local event definition.

Schema validation runs inside the existing Gradle test and therefore also in
normal CI. The testkit and its packaged schema remain test-only dependencies;
the runtime adapter uses the application's existing SLF4J logger and does not
install an encoder, tracing, error handling or scrubbing. Tests prove that missing
fields and wrong JSON types are rejected without keeping a second schema copy
in the application.

The public libraries are resolved through
[Nav's GitHub Packages mirror](https://github.com/navikt/github-package-registry-mirror).
Local builds and CI do not need registry credentials to download them.

The tests cover all direct/fallback decision combinations, skipped fallback,
unchanged HTTP responses, exception/cancellation paths, trace context, duplicate
prevention and identifier canaries. This diagnostic improvement does not by
itself resolve the functional investigation in
[issue #516](https://github.com/navikt/esyfo-narmesteleder/issues/516).
