package no.nav.syfo.aareg

import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.serialization.json.JsonDecoder
import no.nav.syfo.aareg.client.IAaregClient
import no.nav.syfo.util.logger

class AaregService(private val arbeidsforholdOversiktClient: IAaregClient) {
    suspend fun findOrgNumbersByPersonIdent(
        personIdent: String
    ): Set<String> {
        val arbeidsforholdOversikt =
            arbeidsforholdOversiktClient.getArbeidsforhold(
                personIdent = personIdent
            )
        logger().info(ObjectMapper().writeValueAsString(arbeidsforholdOversikt))

        // Ansettelsforhold gitt ved organisasjonsnummer i over- og underenhet
        return buildSet {
            arbeidsforholdOversikt.arbeidsforholdoversikter.forEach { arbeidsforhold ->
                arbeidsforhold.opplysningspliktig.getJuridiskOrgnummer()?.let { add(it) }
                arbeidsforhold.arbeidssted.getOrgnummer()?.let { add(it) }
            }
        }
    }
}
