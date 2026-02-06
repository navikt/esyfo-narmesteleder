package no.nav.syfo.altinn.pdp.client

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.ResponseException
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import no.nav.syfo.application.exception.UpstreamRequestException
import no.nav.syfo.texas.AltinnTokenProvider
import no.nav.syfo.texas.AltinnTokenProvider.Companion.PDP_TARGET_SCOPE

interface IPdpClient {
    suspend fun authorize(
        user: User,
        orgNumberSet: Set<String>,
        resource: String
    ): PdpResponse
}

class PdpClient(
    private val baseUrl: String,
    private val httpClient: HttpClient,
    private val altinnTokenProvider: AltinnTokenProvider,
    private val subscriptionKey: String,
) : IPdpClient {
    override suspend fun authorize(
        user: User,
        orgNumberSet: Set<String>,
        resource: String
    ): PdpResponse {
        val request = createPdpRequest(user, orgNumberSet, resource)
        val response = try {
            val token = altinnTokenProvider.token(PDP_TARGET_SCOPE)
                .accessToken

            httpClient
                .post("$baseUrl/authorization/api/v1/authorize") {
                    header("Ocp-Apim-Subscription-Key", subscriptionKey)
                    header("Content-Type", "application/json")
                    header("Accept", "application/json")
                    bearerAuth(token)
                    setBody(request)
                }
                .body<PdpResponse>()
        } catch (e: ResponseException) {
            throw UpstreamRequestException("Error while calling PDP", e)
        }
        return response
    }
}
