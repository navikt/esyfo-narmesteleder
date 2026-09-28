package no.nav.syfo.narmestelederrelasjon.application

import no.nav.syfo.ident.OrganizationNumber
import no.nav.syfo.ident.PersonIdent
import no.nav.syfo.narmestelederrelasjon.domain.ManagerLastNameMatch
import no.nav.syfo.narmestelederrelasjon.domain.NormalizedManagerContact
import no.nav.syfo.narmestelederrelasjon.domain.RelationSource

fun interface EstablishNarmestelederrelasjon {
    suspend fun execute(command: EstablishNarmestelederrelasjonCommand): EstablishNarmestelederrelasjonResult
}

data class EstablishNarmestelederrelasjonCommand(
    val employeeIdent: PersonIdent,
    val organizationNumber: OrganizationNumber,
    val manager: NormalizedManagerContact,
    val source: RelationSource,
)

sealed interface EstablishNarmestelederrelasjonResult {
    data class Published(val managerNameMatch: ManagerLastNameMatch) : EstablishNarmestelederrelasjonResult
    data class NoActiveSykmelding(val organizationNumber: OrganizationNumber) : EstablishNarmestelederrelasjonResult
    data class NoEmployment(val reason: EmploymentResult) : EstablishNarmestelederrelasjonResult
    data object PersonNotFound : EstablishNarmestelederrelasjonResult
    data class ManagerNameMismatch(val managerNameMatch: ManagerLastNameMatch.NoMatch) : EstablishNarmestelederrelasjonResult
}
