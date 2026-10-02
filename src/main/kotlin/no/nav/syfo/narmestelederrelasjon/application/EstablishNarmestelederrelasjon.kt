package no.nav.syfo.narmestelederrelasjon.application

import no.nav.syfo.ident.OrganizationNumber
import no.nav.syfo.ident.PersonIdent
import no.nav.syfo.narmestelederrelasjon.domain.LastNameMatch
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
    /** Submitted last name of the employee; `null` skips the employee name check. */
    val employeeLastName: String? = null,
)

sealed interface EstablishNarmestelederrelasjonResult {
    data class Published(val managerNameMatch: LastNameMatch) : EstablishNarmestelederrelasjonResult
    data class NoActiveSykmelding(val organizationNumber: OrganizationNumber) : EstablishNarmestelederrelasjonResult
    data class NoEmployment(val reason: EmploymentResult) : EstablishNarmestelederrelasjonResult
    data object PersonNotFound : EstablishNarmestelederrelasjonResult
    data class ManagerNameMismatch(val managerNameMatch: LastNameMatch.NoMatch) : EstablishNarmestelederrelasjonResult
    data class EmployeeNameMismatch(val employeeNameMatch: LastNameMatch.NoMatch) : EstablishNarmestelederrelasjonResult
}
