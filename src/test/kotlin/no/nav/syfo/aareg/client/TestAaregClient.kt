package no.nav.syfo.aareg.client

/**
 * Focused test infrastructure for seeding employments in automated tests.
 * Local and development environments use [FakeAaregClient] with fixtures instead.
 */
class TestAaregClient : AaregClient {
    private val employmentsByPersonIdent = mutableMapOf<String, List<Pair<String, String>>>()

    fun clear() = employmentsByPersonIdent.clear()

    fun seedEmployment(
        personIdent: String,
        orgNumber: String,
        mainOrgNumber: String,
    ) {
        employmentsByPersonIdent[personIdent] = listOf(orgNumber to mainOrgNumber)
    }

    override suspend fun getArbeidsforhold(
        personIdent: String,
    ) = AaregArbeidsforholdOversikt(
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
