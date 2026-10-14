package no.nav.syfo.organisasjonstilgang.infrastructure.altinntilganger

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.ResponseException
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import kotlinx.coroutines.CancellationException
import no.nav.syfo.application.auth.UserPrincipal
import no.nav.syfo.organisasjonstilgang.application.OPPGI_NARMESTELEDER_RESOURCE
import no.nav.syfo.platform.upstream.UpstreamError
import no.nav.syfo.platform.upstream.UpstreamFailure
import no.nav.syfo.platform.upstream.UpstreamFailureStage
import no.nav.syfo.platform.upstream.UpstreamName
import no.nav.syfo.platform.upstream.UpstreamResult
import no.nav.syfo.texas.client.TexasHttpClient
import no.nav.syfo.util.JsonFixtureLoader

internal val ALTINN_TILGANGER = UpstreamName("arbeidsgiver-altinn-tilganger")

interface AltinnTilgangerClient {
    suspend fun fetchAltinnTilganger(
        bruker: UserPrincipal,
    ): UpstreamResult<AltinnTilgangerResponse>
}

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

    fun removeAccess(fnr: String, orgNrTilgang: String? = null) {
        if (orgNrTilgang != null) {
            accessPolicy.find { orgNrTilgang in it.altinnTilgangerResponse.orgNrTilTilganger.keys }
                ?.hasAccess?.remove(fnr)
        } else {
            accessPolicy.forEach { it.hasAccess.remove(fnr) }
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

class HttpAltinnTilgangerClient(
    private val texasClient: TexasHttpClient,
    private val httpClient: HttpClient,
    private val baseUrl: String,
) : AltinnTilgangerClient {
    override suspend fun fetchAltinnTilganger(
        bruker: UserPrincipal,
    ): UpstreamResult<AltinnTilgangerResponse> {
        val oboToken = when (val token = texasClient.exchangeTokenForIsAltinnTilganger(bruker.token)) {
            is UpstreamResult.Success -> token.value
            is UpstreamResult.Failure -> return token
        }
        val response = try {
            httpClient.post("$baseUrl/altinn-tilganger") {
                bearerAuth(oboToken)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: ResponseException) {
            return UpstreamResult.Failure(UpstreamFailure(ALTINN_TILGANGER, UpstreamFailureStage.RESPONSE, e.response.status.value, e))
        } catch (e: Exception) {
            return UpstreamResult.Failure(UpstreamFailure(ALTINN_TILGANGER, UpstreamFailureStage.REQUEST, null, e))
        }
        return try {
            val decoded = response.body<AltinnTilgangerResponse>()
            if (decoded.isError == true) {
                UpstreamResult.Failure(
                    UpstreamFailure(ALTINN_TILGANGER, UpstreamFailureStage.RESPONSE, response.status.value, errors = listOf(UpstreamError("is_error")))
                )
            } else {
                UpstreamResult.Success(decoded)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            UpstreamResult.Failure(UpstreamFailure(ALTINN_TILGANGER, UpstreamFailureStage.RESPONSE, response.status.value, e))
        }
    }
}
