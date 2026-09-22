package no.nav.syfo.narmestelederrelasjon.infrastructure

import no.nav.syfo.application.exception.ApiErrorException
import no.nav.syfo.ereg.EregService
import no.nav.syfo.narmestelederrelasjon.application.NarmestelederrelasjonOrganization

class EregNarmestelederrelasjonOrganization(
    private val eregService: EregService,
) : NarmestelederrelasjonOrganization {

    override suspend fun findName(orgNumber: String): String? = try {
        eregService.getOrganization(orgNumber).getForetrukketNavn()?.takeIf { it.isNotBlank() }
    } catch (_: ApiErrorException.BadRequestException) {
        null
    }
}
