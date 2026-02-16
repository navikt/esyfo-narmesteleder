package no.nav.syfo.ereg.client

import no.nav.syfo.util.JsonFixtureLoader
import java.util.concurrent.atomic.*

/**
 * Fake implementation of [IEregClient] for testing and local development.
 *
 * @param fixtureLoader [JsonFixtureLoader] to load organisasjoner from JSON files.
 *                      Defaults to loading from classpath:fake-clients/ereg.
 */
class FakeEregClient(
    fixtureLoader: JsonFixtureLoader = defaultFixtureLoader
) : IEregClient {
    /**
     * Mutable map of orgnummer -> Organisasjon for test manipulation.
     * Pre-populated from the fixture file.
     */
    val organisasjoner: MutableMap<String, Organisasjon> = loadOrganisasjoner(fixtureLoader).toMutableMap()

    private val failureRef = AtomicReference<Throwable?>(null)

    fun setFailure(failure: Throwable) {
        failureRef.set(failure)
    }

    fun clearFailure() = failureRef.set(null)

    override suspend fun getOrganisasjon(
        orgnummer: String
    ): Organisasjon? {
        failureRef.get()?.let { throw it }
        return organisasjoner[orgnummer]
    }

    companion object {
        private const val FIXTURE_FILE = "organisasjoner.json"
        private val defaultFixtureLoader = JsonFixtureLoader("classpath:fake-clients/ereg")

        private fun loadOrganisasjoner(fixtureLoader: JsonFixtureLoader): Map<String, Organisasjon> = fixtureLoader.loadOrNull<List<Organisasjon>>(FIXTURE_FILE)
            ?.associateBy { it.organisasjonsnummer } ?: emptyMap()
    }
}
