package no.nav.syfo.integration.aareg

import no.nav.syfo.platform.upstream.UpstreamFailure
import no.nav.syfo.platform.upstream.UpstreamResult

/**
 * Focused test infrastructure for seeding employments in automated tests.
 * Local and development environments use [FakeAaregClient] with fixtures instead.
 */
class TestAaregClient : AaregClient {
    private val employmentsByPersonalIdentificationNumber = mutableMapOf<String, List<Pair<String, String>>>()
    private var failure: UpstreamFailure? = null

    fun clear() {
        employmentsByPersonalIdentificationNumber.clear()
        failure = null
    }

    override suspend fun getArbeidsforholdHistorikk(personIdent: String): UpstreamResult<AaregArbeidsforholdOversikt> = getArbeidsforhold(personIdent)

    override suspend fun getArbeidsforhold(
        personIdent: String,
    ): UpstreamResult<AaregArbeidsforholdOversikt> {
        failure?.let { return UpstreamResult.Failure(it) }
        val overview = AaregArbeidsforholdOversikt(
            arbeidsforholdoversikter = employmentsByPersonalIdentificationNumber[personIdent].orEmpty().map { (orgNumber, mainOrgNumber) ->
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
