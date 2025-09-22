package no.nav.syfo.altinntilganger.client

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.ResponseException
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import java.util.Random
import net.datafaker.Faker
import no.nav.syfo.altinntilganger.AltinnTilgangerService.Companion.OPPRETT_NL_REALASJON_RESOURCE
import no.nav.syfo.application.auth.BrukerPrincipal
import no.nav.syfo.texas.client.TexasHttpClient
import no.nav.syfo.util.logger

interface IAltinnTilgangerClient {
    suspend fun hentTilganger(
        bruker: BrukerPrincipal,
    ): AltinnTilgangerResponse?
}

class FakeAltinnTilgangerClient : IAltinnTilgangerClient {
    val usersWithAccess: MutableList<String> = hasAccess.toMutableList()
    override suspend fun hentTilganger(
        bruker: BrukerPrincipal,
    ): AltinnTilgangerResponse {
        val faker = Faker(Random(bruker.ident.toLong()))
        val orgnummer = faker.number().digits(9).toString()
        val hasAccess = usersWithAccess.contains(bruker.ident)
        return AltinnTilgangerResponse(
            false,
            listOf(AltinnTilgang(orgnummer, setOf(), setOf(), emptyList(), faker.ghostbusters().character(), "BEDR")),
            if (hasAccess) mapOf(orgnummer to setOf(OPPRETT_NL_REALASJON_RESOURCE)) else emptyMap(),
            if (hasAccess) mapOf(OPPRETT_NL_REALASJON_RESOURCE to setOf(orgnummer)) else emptyMap(),
        )
    }

    companion object {
        val hasAccess = listOf("72022183070")
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
            throw e
        }
    }

    companion object {
        private val logger = logger()
    }
}
