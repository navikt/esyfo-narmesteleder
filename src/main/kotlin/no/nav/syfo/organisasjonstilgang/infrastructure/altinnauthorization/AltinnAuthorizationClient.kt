package no.nav.syfo.organisasjonstilgang.infrastructure.altinnauthorization

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.ResponseException
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import no.nav.syfo.platform.upstream.UpstreamError
import no.nav.syfo.platform.upstream.UpstreamFailure
import no.nav.syfo.platform.upstream.UpstreamFailureStage
import no.nav.syfo.platform.upstream.UpstreamName
import no.nav.syfo.platform.upstream.UpstreamResult
import no.nav.syfo.platform.upstream.getOrElse
import no.nav.syfo.texas.AltinnTokenProvider
import no.nav.syfo.texas.AltinnTokenProvider.Companion.PDP_TARGET_SCOPE
import kotlin.coroutines.cancellation.CancellationException

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

        return if (result.decision == AuthorizationDecision.Indeterminate) {
            indeterminateFailure(result, response.status.value)
        } else {
            UpstreamResult.Success(result.decision)
        }
    }

    private suspend fun postAuthorize(token: String, request: AltinnAuthorizationRequest): UpstreamResult<HttpResponse> = try {
        UpstreamResult.Success(
            httpClient.post("$baseUrl/authorization/api/v1/authorize") {
                header("Ocp-Apim-Subscription-Key", subscriptionKey)
                header("Content-Type", "application/json")
                header("Accept", "application/json")
                bearerAuth(token)
                setBody(request)
            },
        )
    } catch (e: CancellationException) {
        throw e
    } catch (e: ResponseException) {
        failure(UpstreamFailureStage.RESPONSE, e.response.status.value, e)
    } catch (e: Exception) {
        failure(UpstreamFailureStage.REQUEST, null, e)
    }

    private suspend fun readDecisionResult(response: HttpResponse): UpstreamResult<DecisionResult> = try {
        UpstreamResult.Success(response.body<AltinnAuthorizationResponse>().response.single())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        failure(UpstreamFailureStage.RESPONSE, response.status.value, e)
    }

    private fun indeterminateFailure(result: DecisionResult, status: Int): UpstreamResult.Failure {
        val detail = result.status?.statusCode?.value?.takeIf { it in allowedStatusCodes } ?: "unknown"
        return UpstreamResult.Failure(
            UpstreamFailure(ALTINN_PDP, UpstreamFailureStage.RESPONSE, status, errors = listOf(UpstreamError("indeterminate", detail = detail))),
        )
    }

    private fun failure(stage: UpstreamFailureStage, status: Int?, cause: Throwable) = UpstreamResult.Failure(UpstreamFailure(ALTINN_PDP, stage, status, cause))

    private val allowedStatusCodes = setOf(
        "urn:oasis:names:tc:xacml:1.0:status:syntax-error",
        "urn:oasis:names:tc:xacml:1.0:status:processing-error",
    )
}
