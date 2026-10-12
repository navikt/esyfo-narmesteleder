package no.nav.syfo.integration.ereg

import no.nav.syfo.platform.upstream.UpstreamFailure
import no.nav.syfo.platform.upstream.UpstreamResult
import no.nav.syfo.util.JsonFixtureLoader
import java.util.concurrent.atomic.AtomicReference

/**
 * Fake implementation of [EregClient] for testing and local development.
 *
 * @param fixtureLoader [JsonFixtureLoader] to load organisasjoner from JSON files.
 *                      Defaults to loading from classpath:fake-clients/ereg.
 */
class FakeEregClient(
    fixtureLoader: JsonFixtureLoader = defaultFixtureLoader
) : EregClient {
    /**
     * Mutable map of orgnummer -> Organisasjon for test manipulation.
     * Pre-populated from the fixture file.
     */
    val organisasjoner: MutableMap<String, Organisasjon> = loadOrganisasjoner(fixtureLoader).toMutableMap()
    private val failureRef = AtomicReference<UpstreamFailure?>(null)

    fun setFailure(failure: UpstreamFailure) {
        failureRef.set(failure)
    }

    fun clearFailure() = failureRef.set(null)

    override suspend fun getOrganisasjon(
        orgnummer: String
    ): UpstreamResult<Organisasjon?> {
        failureRef.get()?.let { return UpstreamResult.Failure(it) }
        val organization = when (orgnummer) {
            "314602374", "987926279" -> defaultFixtureLoader.loadOrNull<Organisasjon>("$orgnummer.json")
            else -> organisasjoner[orgnummer]
        }
        return UpstreamResult.Success(organization)
    }

    companion object {
        private const val FIXTURE_FILE = "organisasjoner.json"
        private val defaultFixtureLoader = JsonFixtureLoader("classpath:fake-clients/ereg")

        private fun loadOrganisasjoner(fixtureLoader: JsonFixtureLoader): Map<String, Organisasjon> = fixtureLoader.loadOrNull<List<Organisasjon>>(FIXTURE_FILE)
            ?.associateBy { it.organisasjonsnummer } ?: emptyMap()
    }
}
