package no.nav.syfo.narmestelederrelasjon.application.establish

import no.nav.syfo.ident.OrganizationNumber
import no.nav.syfo.ident.PersonalIdentificationNumber
import no.nav.syfo.platform.upstream.UpstreamFailure

fun interface EmploymentLookup {
    suspend fun findEmployment(
        personIdent: PersonalIdentificationNumber,
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
