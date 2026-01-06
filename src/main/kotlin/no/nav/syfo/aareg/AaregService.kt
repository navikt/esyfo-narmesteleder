package no.nav.syfo.aareg

import no.nav.syfo.aareg.client.AaregClientException
import no.nav.syfo.aareg.client.IAaregClient
import no.nav.syfo.application.exception.ApiErrorException
import no.nav.syfo.util.logger

class AaregService(private val arbeidsforholdOversiktClient: IAaregClient) {
    suspend fun findOrgNumbersByPersonIdent(
        personIdent: String
    ): Map<String, String> {
        val arbeidsforholdOversikt = try {
            arbeidsforholdOversiktClient.getArbeidsforhold(
                personIdent = personIdent
            )
        } catch (e: AaregClientException) {
            throw ApiErrorException.InternalServerErrorException(
                "Could not fetch employment status fra Aareg",
                e
            )
        }

        // Ansettelsforhold gitt ved organisasjonsnummer i over- og underenhet
        return arbeidsforholdOversikt.arbeidsforholdoversikter.mapNotNull { arbeidsforhold ->
            val orgnummer = arbeidsforhold.arbeidssted.getOrgnummer()
            val juridiskOrgnummer = arbeidsforhold.opplysningspliktig.getJuridiskOrgnummer()
            if (orgnummer != null && juridiskOrgnummer != null) {
                orgnummer to juridiskOrgnummer
            } else {
                logger.warn(
                    "Could not find arbeidsforhold: orgnummer: $orgnummer, type: ${arbeidsforhold.arbeidssted.type}, juridiskOrgnummer: $juridiskOrgnummer, type: ${arbeidsforhold.opplysningspliktig.type}"
                )
                null
            }
        }
            .toMap()
    }
    companion object {
        private val logger = logger()
    }
}
