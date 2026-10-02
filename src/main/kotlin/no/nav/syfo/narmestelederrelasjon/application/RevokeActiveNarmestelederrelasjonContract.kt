package no.nav.syfo.narmestelederrelasjon.application

import no.nav.syfo.ident.OrganizationNumber
import no.nav.syfo.ident.PersonIdent
import no.nav.syfo.organisasjonstilgang.application.DenialReason
import no.nav.syfo.organisasjonstilgang.application.OrganizationAccessSubject

data class RevokeActiveNarmestelederrelasjonCommand(
    val accessSubject: OrganizationAccessSubject,
    val employeeIdent: PersonIdent,
    val organizationNumber: OrganizationNumber,
    val employeeLastName: String,
)

sealed interface RevokeActiveNarmestelederrelasjonResult {
    data class AccessDenied(val reason: DenialReason, val organizationNumber: OrganizationNumber) : RevokeActiveNarmestelederrelasjonResult
    data object EmployeeNotFound : RevokeActiveNarmestelederrelasjonResult
    data object EmployeeNameMismatch : RevokeActiveNarmestelederrelasjonResult
    data object NoActiveRelation : RevokeActiveNarmestelederrelasjonResult
    data class Revoked(val initiator: RevocationInitiator) : RevokeActiveNarmestelederrelasjonResult
}
