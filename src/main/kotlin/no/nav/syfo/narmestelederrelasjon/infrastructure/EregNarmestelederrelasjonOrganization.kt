package no.nav.syfo.narmestelederrelasjon.infrastructure

import no.nav.syfo.application.api.ErrorType
import no.nav.syfo.application.exception.ApiErrorException
import no.nav.syfo.application.exception.UpstreamRequestException
import no.nav.syfo.ident.OrganizationNumber
import no.nav.syfo.integration.ereg.EregClient
import no.nav.syfo.narmestelederrelasjon.application.NarmestelederrelasjonOrganization

class EregNarmestelederrelasjonOrganization(
    private val eregClient: EregClient,
) : NarmestelederrelasjonOrganization {

    override suspend fun findName(orgNumber: OrganizationNumber): String? = try {
        eregClient.getOrganisasjon(orgNumber.value)?.getForetrukketNavn()?.takeIf { it.isNotBlank() }
    } catch (e: UpstreamRequestException) {
        throw ApiErrorException.InternalServerErrorException(
            "Could not get organization",
            type = ErrorType.UPSTREAM_SERVICE_UNAVAILABLE,
            cause = e,
        )
    }
}
