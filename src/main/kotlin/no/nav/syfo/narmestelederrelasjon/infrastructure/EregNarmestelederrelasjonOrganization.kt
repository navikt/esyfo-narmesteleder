package no.nav.syfo.narmestelederrelasjon.infrastructure

import no.nav.syfo.ident.OrganizationNumber
import no.nav.syfo.integration.ereg.EregClient
import no.nav.syfo.narmestelederrelasjon.application.NarmestelederrelasjonOrganization
import no.nav.syfo.narmestelederrelasjon.application.OrganizationNameResult
import no.nav.syfo.platform.upstream.UpstreamResult

class EregNarmestelederrelasjonOrganization(
    private val eregClient: EregClient,
) : NarmestelederrelasjonOrganization {

    override suspend fun findName(orgNumber: OrganizationNumber): OrganizationNameResult = when (val result = eregClient.getOrganisasjon(orgNumber.value)) {
        is UpstreamResult.Failure -> OrganizationNameResult.Unavailable(result.failure)

        is UpstreamResult.Success -> result.value?.getForetrukketNavn()?.takeIf { it.isNotBlank() }
            ?.let { OrganizationNameResult.Found(it) } ?: OrganizationNameResult.Missing
    }
}
