package no.nav.syfo.aareg

import no.nav.syfo.aareg.client.IAaregClient

class AaregService(private val arbeidsforholdOversiktClient: IAaregClient) {
    suspend fun findOrgNumbersByPersonIdent(
        personIdent: String
    ): Set<String> {
        val arbeidsforholdOversikt =
            arbeidsforholdOversiktClient.getArbeidsforhold(
                personIdent = personIdent
            )

        // Ansettelsforhold gitt ved organisasjonsnummer i over- og underenhet
        return buildSet {
            arbeidsforholdOversikt.arbeidsforholdoversikter.forEach { arbeidsforhold ->
                arbeidsforhold.opplysningspliktig.getJuridiskOrgnummer()?.let { add(it) }
                arbeidsforhold.arbeidssted.getOrgnummer()?.let { add(it) }
            }
        }
    }
}
