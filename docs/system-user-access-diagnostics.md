# System-user access rejections

## Decision

- Altinn PDP checks the system user against the requested organization. Only
  `Permit` grants access.
- After `Deny` or `NotApplicable`, check whether the system user's organization
  is in the requested organization's Ereg hierarchy. If it is, ask PDP for that
  organization.
- `Indeterminate` is a denial (HTTP 403), not an upstream failure.
- Empty, malformed or multiple PDP decisions fail closed.

## Upstream failures

- A Texas, Altinn token exchange, PDP or Ereg failure returns
  `OrganizationAccessResult.Unavailable(originalFailure)` at once: no fallback
  and no rejection log.
- The API answers HTTP 500 `UPSTREAM_SERVICE_UNAVAILABLE`, logs
  `api_request_failed` once at the edge and counts
  `get_narmestelederrelasjon_total{outcome="upstream_unavailable"}`.
- Ereg `Success(null)` gives HTTP 400 `ORGANIZATION_NOT_FOUND`.

## Rejection event

When both checks deny, `AltinnOrganizationAccess` logs one WARN, owned by
`SystemUserAccessRejection`. The response stays HTTP 403 and StatusPages does
not log it again.

| Field | Value |
| --- | --- |
| `event_type` | `api_request_rejected` |
| `error_code` | `MISSING_ALTINN_RESOURCE_ACCESS` |
| `operation` | `validate_system_user_access` |
| `rejection_reason` | `SYSTEM_USER_ACCESS_NOT_GRANTED` |
| `pdp_decision` | `Deny`, `NotApplicable` or `Indeterminate` |
| `pdp_fallback_decision` | Same values, or `not_checked` outside the hierarchy |

- The event does not say why access is missing.
- Never add tokens, system-user IDs, organization numbers, provider messages or
  payloads. Do not use the fields as metric labels.

## Tests

`AltinnOrganizationAccessTest` covers decisions, fallback, upstream failures,
cancellation, trace context and the log contract.
