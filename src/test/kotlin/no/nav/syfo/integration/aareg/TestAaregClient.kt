package no.nav.syfo.integration.aareg

import no.nav.syfo.platform.upstream.UpstreamFailure
import no.nav.syfo.platform.upstream.UpstreamResult

/**
 * Focused test infrastructure for seeding employments in automated tests.
 * Local and development environments use [FakeAaregClient] with fixtures instead.
 */
class TestAaregClient : AaregClient {
    private val employmentsByPersonIdent = mutableMapOf<String, List<Pair<String, String>>>()
    private var failure: UpstreamFailure? = null

    fun clear() {
        employmentsByPersonIdent.clear()
        failure = null
    }

    fun setFailure(failure: UpstreamFailure) {
        this.failure = failure
    }

    override suspend fun getArbeidsforholdHistorikk(personIdent: String): UpstreamResult<AaregArbeidsforholdOversikt> = getArbeidsforhold(personIdent)

    fun seedEmployment(
        personIdent: String,
        orgNumber: String,
        mainOrgNumber: String,
    ) {
        employmentsByPersonIdent[personIdent] = listOf(orgNumber to mainOrgNumber)
    }

    override suspend fun getArbeidsforhold(
        personIdent: String,
    ): UpstreamResult<AaregArbeidsforholdOversikt> {
        failure?.let { return UpstreamResult.Failure(it) }
        val overview = AaregArbeidsforholdOversikt(
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
        return UpstreamResult.Success(overview)
    }
}
