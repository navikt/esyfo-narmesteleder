package no.nav.syfo.altinntilganger.client

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.plugins.RedirectResponseException
import io.ktor.client.plugins.ResponseException
import io.ktor.client.plugins.ServerResponseException
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.statement.HttpResponse
import kotlinx.coroutines.CancellationException
import no.nav.syfo.altinntilganger.AltinnTilgangerService.Companion.OPPGI_NARMESTELEDER_RESOURCE
import no.nav.syfo.application.auth.UserPrincipal
import no.nav.syfo.application.exception.UpstreamExceptionType
import no.nav.syfo.application.exception.UpstreamFailureStage
import no.nav.syfo.application.exception.UpstreamRequestException
import no.nav.syfo.texas.client.TexasHttpClient
import no.nav.syfo.util.JsonFixtureLoader

interface IAltinnTilgangerClient {
    suspend fun fetchAltinnTilganger(
        bruker: UserPrincipal,
    ): AltinnTilgangerResponse?
}

class FakeAltinnTilgangerClient(private val fixtureLoader: JsonFixtureLoader = defaultFixtureLoader) : IAltinnTilgangerClient {
    val accessPolicy by lazy { loadTilganger(fixtureLoader).toMutableList() }
    private var failure: Throwable? = null

    fun setFailure(failure: Throwable) {
        this.failure = failure
    }

    fun clearFailure() {
        this.failure = null
    }

    override suspend fun fetchAltinnTilganger(
        bruker: UserPrincipal,
    ): AltinnTilgangerResponse {
        failure?.let { throw it }

        val userIdent = runCatching { bruker.ident }.getOrNull()
            ?: return emptyResponse()

        val orgsUserHasAccessTo = accessPolicy
            .firstOrNull { userIdent in it.hasAccess }
            ?.altinnTilgangerResponse

        return orgsUserHasAccessTo ?: emptyResponse()
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
                                altinn2Tilganger = emptySet(),
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

    private fun emptyResponse() = AltinnTilgangerResponse(
        isError = false,
        hierarki = emptyList(),
        orgNrTilTilganger = emptyMap(),
        tilgangTilOrgNr = emptyMap(),
    )

    private fun loadTilganger(fixtureLoader: JsonFixtureLoader): List<FakeArbeidsforholdOversikt> = fixtureLoader.loadOrNull(FIXTURE_FILE) ?: emptyList()

    data class FakeArbeidsforholdOversikt(
        val hasAccess: MutableList<String>,
        val altinnTilgangerResponse: AltinnTilgangerResponse,
    )

    companion object {
        private const val FIXTURE_FILE = "tilganger.json"
        private val defaultFixtureLoader = JsonFixtureLoader("classpath:fake-clients/altinn-tilganger")
    }
}

class AltinnTilgangerClient(
    private val texasClient: TexasHttpClient,
    private val httpClient: HttpClient,
    private val baseUrl: String,
) : IAltinnTilgangerClient {
    override suspend fun fetchAltinnTilganger(
        bruker: UserPrincipal,
    ): AltinnTilgangerResponse? {
        val oboToken = exchangeToken(bruker.token)
        val response = requestAltinnTilganger(oboToken)
        return decodeAltinnTilgangerResponse(response)
    }

    private suspend fun exchangeToken(token: String): String = try {
        texasClient.exchangeTokenForIsAltinnTilganger(token).accessToken
    } catch (e: CancellationException) {
        throw e
    } catch (e: ResponseException) {
        throw UpstreamRequestException(
            message = "Token exchange for AltinnTilganger failed",
            upstreamStatus = e.response.status.value,
            upstreamExceptionType = e.toUpstreamExceptionType(),
            failureStage = UpstreamFailureStage.TOKEN_EXCHANGE,
        )
    } catch (e: Exception) {
        throw UpstreamRequestException(
            message = "Token exchange for AltinnTilganger failed",
            upstreamExceptionType = UpstreamExceptionType.UNEXPECTED_EXCEPTION,
            failureStage = UpstreamFailureStage.TOKEN_EXCHANGE,
        )
    }

    private suspend fun requestAltinnTilganger(oboToken: String): HttpResponse = try {
        httpClient.post("$baseUrl/altinn-tilganger") {
            bearerAuth(oboToken)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: ResponseException) {
        throw UpstreamRequestException(
            message = "AltinnTilganger returned an unsuccessful response",
            upstreamStatus = e.response.status.value,
            upstreamExceptionType = e.toUpstreamExceptionType(),
            failureStage = UpstreamFailureStage.RESPONSE,
        )
    } catch (e: Exception) {
        throw UpstreamRequestException(
            message = "AltinnTilganger request failed",
            upstreamExceptionType = UpstreamExceptionType.TRANSPORT_EXCEPTION,
            failureStage = UpstreamFailureStage.REQUEST,
        )
    }

    private suspend fun decodeAltinnTilgangerResponse(response: HttpResponse): AltinnTilgangerResponse = try {
        response.body()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        throw UpstreamRequestException(
            message = "AltinnTilganger response could not be decoded",
            upstreamStatus = response.status.value,
            upstreamExceptionType = UpstreamExceptionType.RESPONSE_DECODING_EXCEPTION,
            failureStage = UpstreamFailureStage.RESPONSE,
        )
    }
}

private fun ResponseException.toUpstreamExceptionType(): UpstreamExceptionType = when (this) {
    is ClientRequestException -> UpstreamExceptionType.CLIENT_REQUEST_EXCEPTION
    is ServerResponseException -> UpstreamExceptionType.SERVER_RESPONSE_EXCEPTION
    is RedirectResponseException -> UpstreamExceptionType.REDIRECT_RESPONSE_EXCEPTION
    else -> UpstreamExceptionType.RESPONSE_EXCEPTION
}
