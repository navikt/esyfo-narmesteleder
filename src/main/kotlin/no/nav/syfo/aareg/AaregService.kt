package no.nav.syfo.aareg

import no.nav.syfo.aareg.client.IAaregClient

class AaregService(private val arbeidsforholdOversiktClient: IAaregClient) {

    /**
     * @param personIdent persons identificator used for looking up active employments in aareg
     * @param requestToken token with grants to looking up `personIdent` in aareg
     *
     * @return list of organization numbers from any hoved- and underenhet where the employee is currently employed
     * */
    suspend fun findEmployeesOrgNumbers(
        personIdent: String
    ): List<String> {
        val arbeidsforholdOversikt =
            arbeidsforholdOversiktClient.getArbeidsforhold(
                personIdent = personIdent
            )

        // Arbeidstakers ansettelsforhold gitt ved organisasjonsnummer i over- og underenhet
        return arbeidsforholdOversikt.arbeidsforholdoversikter.flatMap { arbeidsforhold ->
            buildList {
                arbeidsforhold.opplysningspliktig.getJuridiskOrgnummer()?.let { add(it) }
                arbeidsforhold.arbeidssted.getOrgnummer()?.let { add(it) }
            }
        }
    }
}