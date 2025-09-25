package no.nav.syfo.altinntilganger.client

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.ResponseException
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import java.util.*
import net.datafaker.Faker
import no.nav.syfo.altinntilganger.AltinnTilgangerService.Companion.OPPRETT_NL_REALASJON_RESOURCE
import no.nav.syfo.application.auth.BrukerPrincipal
import no.nav.syfo.application.exception.UpstreamRequestException
import no.nav.syfo.texas.client.TexasHttpClient
import no.nav.syfo.util.logger

interface IAltinnTilgangerClient {
    suspend fun hentTilganger(
        bruker: BrukerPrincipal,
    ): AltinnTilgangerResponse?
}

class FakeAltinnTilgangerClient : IAltinnTilgangerClient {
    val usersWithAccess = hasAccess.toMutableList()
    override suspend fun hentTilganger(
        bruker: BrukerPrincipal,
    ): AltinnTilgangerResponse {
        val faker = Faker(Random(bruker.ident.toLong()))
        val accessPair = usersWithAccess.find { it.first == bruker.ident }
        val organisasjonsnummer = accessPair?.second ?: faker.numerify("#########")
        return AltinnTilgangerResponse(
            false,
            listOf(
                AltinnTilgang(
                    organisasjonsnummer,
                    setOf(),
                    setOf(),
                    emptyList(),
                    faker.ghostbusters().character(),
                    "BEDR"
                )
            ),
            if (accessPair != null) mapOf(organisasjonsnummer to setOf(OPPRETT_NL_REALASJON_RESOURCE)) else emptyMap(),
            if (accessPair != null) mapOf(OPPRETT_NL_REALASJON_RESOURCE to setOf(organisasjonsnummer)) else emptyMap(),
        )
    }

    companion object {
        val hasAccess = listOf("72022183071" to "879880760")
    }
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
        } catch (e: ResponseException) {
            logger.error("Feil ved henting av altinn-tilganger, status: ${e.response.status}", e)
            throw UpstreamRequestException("Feil ved henting av altinn-tilganger", e)
        }
    }

    companion object {
        private val logger = logger()
    }
}
