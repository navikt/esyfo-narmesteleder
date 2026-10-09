package no.nav.syfo.narmestelederbehov.application

import no.nav.syfo.narmestelederbehov.domain.Employee
import no.nav.syfo.platform.upstream.UpstreamFailure

fun interface EmployerMainOrganizationLookup {
    suspend fun findMainOrganization(employee: Employee): MainOrganizationResult
}

sealed interface MainOrganizationResult {
    data class Found(val mainOrganizationNumber: String) : MainOrganizationResult
    data object MainOrganizationMissing : MainOrganizationResult
    data object EmploymentMissing : MainOrganizationResult
    data class Unavailable(val failure: UpstreamFailure) : MainOrganizationResult
}
