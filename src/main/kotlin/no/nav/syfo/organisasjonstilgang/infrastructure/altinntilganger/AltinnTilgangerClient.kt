package no.nav.syfo.organisasjonstilgang.infrastructure.altinntilganger

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import no.nav.syfo.platform.auth.UserPrincipal
import no.nav.syfo.platform.auth.texas.client.TexasHttpClient
import no.nav.syfo.platform.upstream.UpstreamError
import no.nav.syfo.platform.upstream.UpstreamFailure
import no.nav.syfo.platform.upstream.UpstreamFailureStage
import no.nav.syfo.platform.upstream.UpstreamName
import no.nav.syfo.platform.upstream.UpstreamResult
import no.nav.syfo.platform.upstream.decodeUpstream
import no.nav.syfo.platform.upstream.getOrElse
import no.nav.syfo.platform.upstream.requestUpstream

internal val ALTINN_TILGANGER = UpstreamName("arbeidsgiver-altinn-tilganger")

interface AltinnTilgangerClient {
    suspend fun fetchAltinnTilganger(
        bruker: UserPrincipal,
    ): UpstreamResult<AltinnTilgangerResponse>
}

class HttpAltinnTilgangerClient(
    private val texasClient: TexasHttpClient,
    private val httpClient: HttpClient,
    private val baseUrl: String,
) : AltinnTilgangerClient {
    override suspend fun fetchAltinnTilganger(
        bruker: UserPrincipal,
    ): UpstreamResult<AltinnTilgangerResponse> {
        val oboToken = texasClient.exchangeToAltinnToken(bruker.token, ALTINN_TILGANGER).getOrElse { return UpstreamResult.Failure(it) }
        val response = postTilganger(oboToken).getOrElse { return UpstreamResult.Failure(it) }

        return if (response.isError == true) isErrorFailure() else UpstreamResult.Success(response)
    }

    private suspend fun postTilganger(oboToken: String): UpstreamResult<AltinnTilgangerResponse> {
        val response = requestUpstream(ALTINN_TILGANGER) {
            httpClient.post("$baseUrl/altinn-tilganger") {
                bearerAuth(oboToken)
            }
        }.getOrElse { return UpstreamResult.Failure(it) }

        return response.decodeUpstream(ALTINN_TILGANGER) { body<AltinnTilgangerResponse>() }
    }
}

private val IS_ERROR = UpstreamError("is_error")

// Altinn Tilganger answers a failed Altinn 3 lookup with isError=true, and its route always responds 200
// (navikt/arbeidsgiver-altinn-tilganger, Application.kt: call.respond(...toResponse()) without a status).
private fun isErrorFailure() = UpstreamResult.Failure(UpstreamFailure(ALTINN_TILGANGER, UpstreamFailureStage.RESPONSE, 200, errors = listOf(IS_ERROR)))
