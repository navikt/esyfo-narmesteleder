package no.nav.syfo.aareg.client

class TestAaregClient : IAaregClient {
    private val employmentsByPersonIdent = mutableMapOf<String, List<Pair<String, String>>>()

    fun seedEmployment(
        personIdent: String,
        orgNumber: String,
        mainOrgNumber: String,
    ) {
        employmentsByPersonIdent[personIdent] = listOf(orgNumber to mainOrgNumber)
    }

    override suspend fun getArbeidsforhold(personIdent: String): AaregArbeidsforholdOversikt =
        AaregArbeidsforholdOversikt(
            arbeidsforholdoversikter = employmentsByPersonIdent[personIdent].orEmpty().map { (orgNumber, mainOrgNumber) ->
                Arbeidsforholdoversikt(
                    arbeidssted = Arbeidssted(
                        type = ArbeidsstedType.Underenhet,
                        identer = listOf(
                            Ident(
                                type = IdentType.ORGANISASJONSNUMMER,
                                ident = orgNumber,
                                gjeldende = true,
                            ),
                        ),
                    ),
                    opplysningspliktig = Opplysningspliktig(
                        type = OpplysningspliktigType.Hovedenhet,
                        identer = listOf(
                            Ident(
                                type = IdentType.ORGANISASJONSNUMMER,
                                ident = mainOrgNumber,
                                gjeldende = true,
                            ),
                        ),
                    ),
                )
            },
        )
}
