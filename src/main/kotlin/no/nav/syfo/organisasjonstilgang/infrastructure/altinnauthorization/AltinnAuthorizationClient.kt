package no.nav.syfo.organisasjonstilgang.infrastructure.altinnauthorization

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.ResponseException
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import no.nav.syfo.application.exception.UpstreamRequestException
import no.nav.syfo.platform.upstream.UpstreamFailureStage
import no.nav.syfo.texas.AltinnTokenProvider
import no.nav.syfo.texas.AltinnTokenProvider.Companion.PDP_TARGET_SCOPE

interface AltinnAuthorizationClient {
    suspend fun authorize(
        user: User,
        orgNumberSet: Set<String>,
        resource: String
    ): AltinnAuthorizationResponse
}

class HttpAltinnAuthorizationClient(
    private val baseUrl: String,
    private val httpClient: HttpClient,
    private val altinnTokenProvider: AltinnTokenProvider,
    private val subscriptionKey: String,
) : AltinnAuthorizationClient {
    override suspend fun authorize(
        user: User,
        orgNumberSet: Set<String>,
        resource: String
    ): AltinnAuthorizationResponse {
        val request = createAltinnAuthorizationRequest(user, orgNumberSet, resource)
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
                .body<AltinnAuthorizationResponse>()
        } catch (e: ResponseException) {
            throw UpstreamRequestException(
                "Error while calling PDP",
                e,
                failureStage = UpstreamFailureStage.RESPONSE,
                upstream = "altinn-pdp",
            )
        }
        return response
    }
}
