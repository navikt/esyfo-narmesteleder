package no.nav.syfo.organisasjonstilgang.infrastructure.altinnauthorization

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import no.nav.syfo.platform.auth.texas.AltinnTokenProvider
import no.nav.syfo.platform.auth.texas.AltinnTokenProvider.Companion.PDP_TARGET_SCOPE
import no.nav.syfo.platform.upstream.UpstreamName
import no.nav.syfo.platform.upstream.UpstreamResult
import no.nav.syfo.platform.upstream.decodeUpstream
import no.nav.syfo.platform.upstream.getOrElse
import no.nav.syfo.platform.upstream.requestUpstream

internal val ALTINN_PDP = UpstreamName("altinn-pdp")

interface AltinnAuthorizationClient {
    suspend fun authorize(
        user: User,
        orgNumberSet: Set<String>,
        resource: String
    ): UpstreamResult<AuthorizationDecision>
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
    ): UpstreamResult<AuthorizationDecision> {
        val request = createAltinnAuthorizationRequest(user, orgNumberSet, resource)
        val token = altinnTokenProvider.token(PDP_TARGET_SCOPE).getOrElse { return UpstreamResult.Failure(it) }.accessToken
        val response = postAuthorize(token, request).getOrElse { return UpstreamResult.Failure(it) }
        val result = readDecisionResult(response).getOrElse { return UpstreamResult.Failure(it) }

        return UpstreamResult.Success(result.decision)
    }

    private suspend fun postAuthorize(
        token: String,
        request: AltinnAuthorizationRequest
    ): UpstreamResult<HttpResponse> = requestUpstream(ALTINN_PDP) {
        httpClient.post("$baseUrl/authorization/api/v1/authorize") {
            header("Ocp-Apim-Subscription-Key", subscriptionKey)
            header("Content-Type", "application/json")
            header("Accept", "application/json")
            bearerAuth(token)
            setBody(request)
        }
    }

    private suspend fun readDecisionResult(response: HttpResponse): UpstreamResult<DecisionResult> =
        response.decodeUpstream(ALTINN_PDP) { body<AltinnAuthorizationResponse>().response.single() }
}
