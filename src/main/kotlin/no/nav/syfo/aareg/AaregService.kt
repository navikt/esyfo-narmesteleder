package no.nav.syfo.aareg

import no.nav.syfo.aareg.client.AaregClientException
import no.nav.syfo.aareg.client.Arbeidsforholdoversikt
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

    suspend fun findArbeidsforholdByPersonIdent(
        personIdent: String
    ): List<Arbeidsforhold> {
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
