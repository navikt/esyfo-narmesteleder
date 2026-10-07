package no.nav.syfo.aareg

import no.nav.syfo.application.api.ErrorType
import no.nav.syfo.application.exception.ApiErrorException
import no.nav.syfo.integration.aareg.AaregClient
import no.nav.syfo.integration.aareg.AaregClientException
import no.nav.syfo.integration.aareg.Arbeidsforholdoversikt
import no.nav.syfo.util.logger

class AaregService(private val arbeidsforholdOversiktClient: AaregClient) {
    suspend fun findArbeidsforholdByPersonIdent(
        personIdent: String
    ): List<Arbeidsforhold> {
        val arbeidsforholdOversikt = try {
            arbeidsforholdOversiktClient.getArbeidsforhold(
                personIdent = personIdent
            )
        } catch (e: AaregClientException) {
            throw ApiErrorException.InternalServerErrorException(
                errorMessage = "Could not fetch employment status fra Aareg",
                cause = e,
                type = ErrorType.UPSTREAM_SERVICE_UNAVAILABLE
            )
        }
        return arbeidsforholdOversikt.arbeidsforholdoversikter.toArbeidsforholdList()
    }

    private fun List<Arbeidsforholdoversikt>.toArbeidsforholdList(): List<Arbeidsforhold> = this.mapNotNull {
        it.arbeidssted.getOrgnummer()?.let { orgnummer ->
            Arbeidsforhold(
                orgnummer = orgnummer,
                arbeidsstedType = it.arbeidssted.type,
                opplysningspliktigOrgnummer = it.opplysningspliktig.getJuridiskOrgnummer(),
                opplysningspliktigType = it.opplysningspliktig.type,
            )
        }
    }

    companion object {
        private val logger = logger()
    }
}

fun List<Arbeidsforhold>.toOrgNumberList(): List<String> = this.map {
    listOfNotNull(it.orgnummer, it.opplysningspliktigOrgnummer)
}.flatten().distinct()
