package no.nav.syfo.narmestelederrelasjon.application

import no.nav.syfo.ident.OrganizationNumber
import no.nav.syfo.ident.PersonIdent
import no.nav.syfo.platform.upstream.UpstreamFailure

fun interface EmploymentLookup {
    suspend fun findEmployment(
        personIdent: PersonIdent,
        organizationNumber: OrganizationNumber,
    ): EmploymentResult
}

sealed interface EmploymentResult {
    data object InOrganization : EmploymentResult
    sealed interface Missing : EmploymentResult
    data object None : Missing
    data object NotInOrganization : Missing
    data class Unavailable(val failure: UpstreamFailure) : EmploymentResult
}
