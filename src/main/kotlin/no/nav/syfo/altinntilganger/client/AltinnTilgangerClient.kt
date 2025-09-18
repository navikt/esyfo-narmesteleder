package no.nav.syfo.altinntilganger.client

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.plugins.ServerResponseException
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import no.nav.syfo.application.auth.BrukerPrincipal
import no.nav.syfo.texas.client.TexasHttpClient
import no.nav.syfo.util.logger

interface IAltinnTilgangerClient {
    suspend fun hentTilganger(
        bruker: BrukerPrincipal,
    ): AltinnTilgangerResponse?
}

class FakeAltinnTilgangerClient : IAltinnTilgangerClient {
    override suspend fun hentTilganger(
        bruker: BrukerPrincipal,
    ): AltinnTilgangerResponse = AltinnTilgangerResponse(
        false,
        listOf(AltinnTilgang("123456789", setOf(), setOf(), emptyList(), "Whatever kommune", "KOMM")),
        mapOf("123456789" to setOf("nav_sosialtjenester_digisos-avtale")),
        mapOf("nav_sosialtjenester_digisos-avtale" to setOf("12345789")),
    )
}

class AltinnTilgangerClient(
    private val texasClient: TexasHttpClient,
    private val httpClient: HttpClient,
    private val baseUrl: String,
) : IAltinnTilgangerClient {
    override suspend fun hentTilganger(
        bruker: BrukerPrincipal,
    ): AltinnTilgangerResponse? {
        val oboToken = texasClient.exchangeTokenForIsAltinnTilganger(bruker.token).accessToken
        try {
            val response = httpClient.post("$baseUrl/altinn-tilganger") {
                bearerAuth(oboToken)
            }.body<AltinnTilgangerResponse>()
            return response
        } catch (e: ClientRequestException) {
            logger.error("Feil ved henting av altinn-tilganger, status: ${e.response.status}", e)
            throw RuntimeException("Feil ved henting av altinn-tilganger", e)
        } catch (e: ServerResponseException) {
            logger.error("Feil ved henting av altinn-tilganger, status: ${e.response.status}", e)
            throw RuntimeException("Feil ved henting av altinn-tilganger", e)
        }
    }

    companion object {
        private val logger = logger()
    }
}
