package no.nav.syfo.narmestelederrelasjon.infrastructure

import no.nav.syfo.application.api.ErrorType
import no.nav.syfo.application.exception.ApiErrorException
import no.nav.syfo.ereg.EregService
import no.nav.syfo.ident.OrganizationNumber
import no.nav.syfo.narmestelederrelasjon.application.NarmestelederrelasjonOrganization

class EregNarmestelederrelasjonOrganization(
    private val eregService: EregService,
) : NarmestelederrelasjonOrganization {

    override suspend fun findName(orgNumber: OrganizationNumber): String? = try {
        eregService.getOrganization(orgNumber.value).getForetrukketNavn()?.takeIf { it.isNotBlank() }
    } catch (exception: ApiErrorException.BadRequestException) {
        if (exception.type == ErrorType.ORGANIZATION_NOT_FOUND) null else throw exception
    }
}
