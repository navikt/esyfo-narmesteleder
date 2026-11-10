package no.nav.syfo.organisasjonstilgang.infrastructure.altinntilganger

import no.nav.syfo.organisasjonstilgang.application.OPPGI_NARMESTELEDER_RESOURCE
import no.nav.syfo.platform.auth.UserPrincipal
import no.nav.syfo.platform.local.JsonFixtureLoader
import no.nav.syfo.platform.upstream.UpstreamFailure
import no.nav.syfo.platform.upstream.UpstreamResult

class FakeAltinnTilgangerClient(private val fixtureLoader: JsonFixtureLoader = defaultFixtureLoader) : AltinnTilgangerClient {
    val accessPolicy by lazy { loadTilganger(fixtureLoader).toMutableList() }
    private var failure: UpstreamFailure? = null

    fun setFailure(failure: UpstreamFailure) {
        this.failure = failure
    }

    fun clearFailure() {
        this.failure = null
    }

    override suspend fun fetchAltinnTilganger(
        bruker: UserPrincipal,
    ): UpstreamResult<AltinnTilgangerResponse> {
        failure?.let { return UpstreamResult.Failure(it) }

        val userIdent = runCatching { bruker.ident }.getOrNull()
            ?: return UpstreamResult.Success(emptyResponse())

        val orgsUserHasAccessTo = accessPolicy
            .firstOrNull { userIdent in it.hasAccess }
            ?.altinnTilgangerResponse

        return UpstreamResult.Success(orgsUserHasAccessTo ?: emptyResponse())
    }

    fun addAccess(fnr: String, orgNrTilgang: String, altinn3tilgang: String = OPPGI_NARMESTELEDER_RESOURCE) {
        val existingPolicy = accessPolicy.find {
            orgNrTilgang in it.altinnTilgangerResponse.orgNrTilTilganger.keys
        }
        if (existingPolicy != null) {
            if (fnr !in existingPolicy.hasAccess) {
                existingPolicy.hasAccess += fnr
            }
            val updatedOrgNrTilTilganger = existingPolicy.altinnTilgangerResponse.orgNrTilTilganger.toMutableMap()
            val existingTilganger = updatedOrgNrTilTilganger[orgNrTilgang] ?: emptySet()
            updatedOrgNrTilTilganger[orgNrTilgang] = existingTilganger + altinn3tilgang

            val updatedTilgangTilOrgNr = existingPolicy.altinnTilgangerResponse.tilgangTilOrgNr.toMutableMap()
            val existingOrgs = updatedTilgangTilOrgNr[altinn3tilgang] ?: emptySet()
            updatedTilgangTilOrgNr[altinn3tilgang] = existingOrgs + orgNrTilgang

            val index = accessPolicy.indexOf(existingPolicy)
            accessPolicy[index] = existingPolicy.copy(
                altinnTilgangerResponse = existingPolicy.altinnTilgangerResponse.copy(
                    orgNrTilTilganger = updatedOrgNrTilTilganger,
                    tilgangTilOrgNr = updatedTilgangTilOrgNr
                )
            )
        } else {
            accessPolicy.add(
                FakeArbeidsforholdOversikt(
                    hasAccess = mutableListOf(fnr),
                    altinnTilgangerResponse = AltinnTilgangerResponse(
                        isError = false,
                        hierarki = listOf(
                            AltinnTilgang(
                                orgnr = orgNrTilgang,
                                altinn3Tilganger = setOf(altinn3tilgang),
                                underenheter = emptyList(),
                                navn = "Test Org",
                                organisasjonsform = "BEDR",
                            )
                        ),
                        orgNrTilTilganger = mapOf(orgNrTilgang to setOf(altinn3tilgang)),
                        tilgangTilOrgNr = mapOf(altinn3tilgang to setOf(orgNrTilgang)),
                    )
                )
            )
        }
    }

    fun reset() {
        accessPolicy.clear()
        accessPolicy.addAll(loadTilganger(fixtureLoader))
        failure = null
    }

    data class FakeArbeidsforholdOversikt(
        val hasAccess: MutableList<String>,
        val altinnTilgangerResponse: AltinnTilgangerResponse,
    )

    companion object {
        private const val FIXTURE_FILE = "tilganger.json"
        private val defaultFixtureLoader = JsonFixtureLoader("classpath:fake-clients/altinn-tilganger")
    }

    private fun emptyResponse() = AltinnTilgangerResponse(
        isError = false,
        hierarki = emptyList(),
        orgNrTilTilganger = emptyMap(),
        tilgangTilOrgNr = emptyMap(),
    )

    private fun loadTilganger(fixtureLoader: JsonFixtureLoader): List<FakeArbeidsforholdOversikt> = fixtureLoader.loadOrNull(FIXTURE_FILE) ?: emptyList()
}
