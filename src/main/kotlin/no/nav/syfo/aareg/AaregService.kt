package no.nav.syfo.aareg

import com.fasterxml.jackson.databind.ObjectMapper
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
                "Klarte ikke hente arbeidsforhold fra Aareg",
                e
            )
        }
        logger().info(ObjectMapper().writeValueAsString(arbeidsforholdOversikt))

        // Ansettelsforhold gitt ved organisasjonsnummer i over- og underenhet
        val map = arbeidsforholdOversikt.arbeidsforholdoversikter.mapNotNull { arbeidsforhold ->
            val orgnummer = arbeidsforhold.arbeidssted.getOrgnummer()
            val juridiskOrgnummer = arbeidsforhold.opplysningspliktig.getJuridiskOrgnummer()
            logger().info("Arbeidsforhold for person: $orgnummer, juridisk: $juridiskOrgnummer")
            if (orgnummer != null && juridiskOrgnummer != null) {
                orgnummer to juridiskOrgnummer
            } else null
        }
            .toMap()
        logger().info(ObjectMapper().writeValueAsString(map))
        return map
    }
}
