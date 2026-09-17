# Narmesteleder

This glossary defines the canonical domain terms used by
`esyfo-narmesteleder`. Technical roles and actions are named in English, while
established domain terms remain Norwegian.

## Language

**Narmestelederbehov**:
A temporary need for an employer to report who is the narmesteleder for an
employee. It can be created, fulfilled, expired or end in a documented error
state.
_Avoid_: Requirement when the specific domain concept is meant.

**Narmestelederrelasjon**:
The connection between an employee, a narmesteleder and an organization. It has
an identity and an active period and can be established or revoked.
_Avoid_: Narmestelederbehov, generic Relation.

**Sykmelding**:
The sickness certificate whose state and periods may trigger a
narmestelederbehov or a revocation of a narmestelederrelasjon.
_Avoid_: Sick leave when the concrete sykmelding record or event is meant.

**Organisasjonstilgang**:
The business decision that a caller may act for a specific organization. It is
separate from authentication, which only establishes the caller's identity.
_Avoid_: Authentication, generic permission.

**PersonIdent**:
The official person identifier used across module boundaries. It is distinct
from an internal database row identifier.
_Avoid_: PersonId when it is unclear whether the value is an ident or a
database ID.

**OrganizationNumber**:
The official nine-digit identifier for an organization.
_Avoid_: OrganizationId when the value is specifically an organization number.
