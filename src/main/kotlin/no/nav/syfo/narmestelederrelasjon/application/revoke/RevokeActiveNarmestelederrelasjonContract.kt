package no.nav.syfo.narmestelederrelasjon.application.revoke

import no.nav.syfo.ident.OrganizationNumber
import no.nav.syfo.ident.PersonalIdentificationNumber
import no.nav.syfo.organisasjonstilgang.application.DenialReason
import no.nav.syfo.organisasjonstilgang.application.OrganizationAccessSubject
import no.nav.syfo.platform.upstream.UpstreamFailure

data class RevokeActiveNarmestelederrelasjonCommand(
    val accessSubject: OrganizationAccessSubject,
    val employeeIdent: PersonalIdentificationNumber,
    val organizationNumber: OrganizationNumber,
    val employeeLastName: String,
)

sealed interface RevokeActiveNarmestelederrelasjonResult {
    data class UpstreamUnavailable(val failure: UpstreamFailure) : RevokeActiveNarmestelederrelasjonResult
    data class AccessDenied(val reason: DenialReason, val organizationNumber: OrganizationNumber) : RevokeActiveNarmestelederrelasjonResult
    data object EmployeeNotFound : RevokeActiveNarmestelederrelasjonResult
    data object EmployeeNameMismatch : RevokeActiveNarmestelederrelasjonResult
    data object NoActiveRelation : RevokeActiveNarmestelederrelasjonResult
    data class Revoked(val initiator: RevocationInitiator) : RevokeActiveNarmestelederrelasjonResult
}
