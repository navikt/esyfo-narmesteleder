package no.nav.syfo.narmestelederrelasjon.application

import no.nav.syfo.ident.OrganizationNumber
import no.nav.syfo.platform.upstream.UpstreamFailure

interface NarmestelederrelasjonOrganization {
    suspend fun findName(orgNumber: OrganizationNumber): OrganizationNameResult
}

sealed interface OrganizationNameResult {
    data class Found(val name: String) : OrganizationNameResult
    data object Missing : OrganizationNameResult
    data class Unavailable(val failure: UpstreamFailure) : OrganizationNameResult
}
