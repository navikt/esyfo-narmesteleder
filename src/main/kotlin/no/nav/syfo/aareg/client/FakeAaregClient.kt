package no.nav.syfo.aareg.client

import no.nav.syfo.util.JsonFixtureLoader
import java.util.concurrent.atomic.AtomicReference

/**
 * Fake implementation of [IAaregClient] for testing and local development.
 *
 * @param fixtureLoader [JsonFixtureLoader] to load arbeidsforhold from JSON files.
 *                      Expects a file mapping fnr -> AaregArbeidsforholdOversikt.
 *                      Defaults to loading from classpath:fake-clients/aareg.
 */
class FakeAaregClient(
    fixtureLoader: JsonFixtureLoader = defaultFixtureLoader
) : IAaregClient {
    /**
     * Mutable map of fnr -> List<Pair<orgnummer, juridiskOrgnummer>> for test manipulation.
     * Pre-populated from the fixture file.
     */
    val arbeidsForholdForIdent: MutableMap<String, List<Pair<String, String>>> =
        loadArbeidsforhold(fixtureLoader)
            .mapValues { (_, oversikt) ->
                oversikt.arbeidsforholdoversikter.map { arbeidsforhold ->
                    val orgnummer = arbeidsforhold.arbeidssted.getOrgnummer() ?: ""
                    val juridiskOrgnummer = arbeidsforhold.opplysningspliktig.getJuridiskOrgnummer() ?: ""
                    orgnummer to juridiskOrgnummer
                }
            }
            .toMutableMap()

    private val failureRef = AtomicReference<Throwable?>(null)

    /**
     * getArbeidsforhold will return a failure if this property is set.
     *
     * @param failure a `Throwable` to wrap in `Result.failure`
     */
    fun setFailure(failure: Throwable) {
        failureRef.set(failure)
    }

    fun clearFailure() = failureRef.set(null)

    /**
     * @param personIdent the fnr to look up
     * @returns the arbeidsforhold for the given fnr, or an empty response if not found
     * @throws the configured failure if set
     */
    override suspend fun getArbeidsforhold(
        personIdent: String
    ): AaregArbeidsforholdOversikt {
        failureRef.get()?.let { throw it }
        val pairs = arbeidsForholdForIdent[personIdent] ?: return AaregArbeidsforholdOversikt()
        return createArbeidsforholdOversikt(*pairs.toTypedArray())
    }

    private fun loadArbeidsforhold(fixtureLoader: JsonFixtureLoader): Map<String, AaregArbeidsforholdOversikt> = fixtureLoader.loadOrNull<Map<String, AaregArbeidsforholdOversikt>>(FIXTURE_FILE) ?: emptyMap()

    /**
     * Helper to create an AaregArbeidsforholdOversikt from a list of (orgnummer, juridiskOrgnummer) pairs.
     */
    fun createArbeidsforholdOversikt(vararg arbeidsforhold: Pair<String, String>): AaregArbeidsforholdOversikt = AaregArbeidsforholdOversikt(
        arbeidsforholdoversikter = arbeidsforhold.map { (orgnummer, juridiskOrgnummer) ->
            Arbeidsforholdoversikt(
                arbeidssted = Arbeidssted(
                    type = ArbeidsstedType.Underenhet,
                    identer = listOf(
                        Ident(type = IdentType.ORGANISASJONSNUMMER, ident = orgnummer, gjeldende = true)
                    )
                ),
                opplysningspliktig = Opplysningspliktig(
                    type = OpplysningspliktigType.Hovedenhet,
                    identer = listOf(
                        Ident(type = IdentType.ORGANISASJONSNUMMER, ident = juridiskOrgnummer, gjeldende = true)
                    )
                )
            )
        }
    )

    companion object {
        private const val FIXTURE_FILE = "arbeidsforhold.json"
        private val defaultFixtureLoader = JsonFixtureLoader("classpath:fake-clients/aareg")
    }
}
