package no.nav.syfo.altinn.pdp.client

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.ResponseException
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import no.nav.syfo.application.exception.UpstreamRequestException
import no.nav.syfo.texas.client.TexasHttpClient

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
    private val texasHttpClient: TexasHttpClient,
    private val subscriptionKey: String,
): IPdpClient {
    override suspend fun authorize(
        user: User,
        orgNumberSet: Set<String>,
        resource: String
    ): PdpResponse {
        val request = createPdpRequest(user, orgNumberSet, resource)
        val response = try {
            val texasResponse = texasHttpClient.systemToken("maskinporten", "altinn:authorization/authorize")
            val token = altinnExchange(texasResponse.accessToken)

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

    private suspend fun altinnExchange(token: String): String =
        httpClient
            .get("$baseUrl/authentication/api/v1/exchange/maskinporten") {
                bearerAuth(token)
            }.bodyAsText()
            .replace("\"", "")
}
