package no.nav.syfo.altinntilganger.client

import com.fasterxml.jackson.databind.ObjectMapper
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.ResponseException
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import java.util.*
import net.datafaker.Faker
import no.nav.syfo.altinntilganger.AltinnTilgangerService.Companion.OPPGI_NARMESTELEDER_RESOURCE
import no.nav.syfo.application.auth.UserPrincipal
import no.nav.syfo.application.exception.UpstreamRequestException
import no.nav.syfo.texas.client.TexasHttpClient
import no.nav.syfo.util.logger

interface IAltinnTilgangerClient {
    suspend fun fetchAltinnTilganger(
        bruker: UserPrincipal,
    ): AltinnTilgangerResponse?
}

class FakeAltinnTilgangerClient : IAltinnTilgangerClient {
    val usersWithAccess = hasAccess.toMutableList()
    override suspend fun fetchAltinnTilganger(
        bruker: UserPrincipal,
    ): AltinnTilgangerResponse {
        val faker = Faker(Random(bruker.ident.toLong()))
        val accessPair = usersWithAccess.find { it.first == bruker.ident }
        val organisasjonsnummer = accessPair?.second ?: faker.numerify("#########")
        return AltinnTilgangerResponse(
            false,
            listOf(
                AltinnTilgang(
                    organisasjonsnummer,
                    if (accessPair != null) setOf(OPPGI_NARMESTELEDER_RESOURCE) else emptySet(),
                    setOf(),
                    emptyList(),
                    faker.ghostbusters().character(),
                    "BEDR",

                )
            ),
            if (accessPair != null) mapOf(organisasjonsnummer to setOf(OPPGI_NARMESTELEDER_RESOURCE)) else emptyMap(),
            if (accessPair != null) mapOf(OPPGI_NARMESTELEDER_RESOURCE to setOf(organisasjonsnummer)) else emptyMap(),
        )
    }

    fun reset() {
        usersWithAccess.clear()
        usersWithAccess.addAll(hasAccess)
    }

    companion object {
        val hasAccess = listOf("72022183071" to "215649202", "215649202" to "310667633", "15436803416" to "215649202", "15823349332" to "215649202")
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
        val oboToken = texasClient.exchangeTokenForIsAltinnTilganger(bruker.token).accessToken
        try {
            val response = httpClient.post("$baseUrl/altinn-tilganger") {
                bearerAuth(oboToken)
            }.body<AltinnTilgangerResponse>()
            return response
        } catch (e: ResponseException) {
            logger.error("Feil ved henting av altinn-tilganger, status: ${e.response.status}", e)
            throw UpstreamRequestException("Feil ved henting av altinn-tilganger", e)
        } catch (e: Exception) {
            logger.error("Uventet feil ved henting av altinn-tilganger", e)
            throw UpstreamRequestException("Uventet feil ved henting av altinn-tilganger")
        }
    }

    companion object {
        private val logger = logger()
    }
}
