package no.nav.syfo.ereg

import no.nav.syfo.application.api.ErrorType
import no.nav.syfo.application.exception.ApiErrorException
import no.nav.syfo.application.exception.UpstreamRequestException
import no.nav.syfo.application.valkey.EregCache
import no.nav.syfo.ereg.client.IEregClient
import no.nav.syfo.ereg.client.Organisasjon

class EregService(
    private val eregClient: IEregClient,
    private val eregCache: EregCache
) {
    suspend fun getOrganization(
        orgNumber: String
    ): Organisasjon {
        eregCache.getOrganisasjon(orgNumber).let { cacheOrganisasjon ->
            if (cacheOrganisasjon != null) {
                return cacheOrganisasjon
            }
        }
        return try {
            val organisasjon = eregClient.getOrganisasjon(orgnummer = orgNumber)?.also {
                eregCache.putOrganisasjon(orgNumber, it)
            }
            organisasjon
        } catch (e: UpstreamRequestException) {
            throw ApiErrorException.InternalServerErrorException(
                "Could not get organization",
                type = ErrorType.UPSTREAM_SERVICE_UNAVAILABLE,
                cause = e
            )
        }
            ?: throw ApiErrorException.BadRequestException(
                "Unable to look up the organization",
                type = ErrorType.ORGANIZATION_NOT_FOUND
            )
    }
}
