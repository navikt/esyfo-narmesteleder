package no.nav.syfo.platform.auth.texas.client

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.ResponseException
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import no.nav.syfo.platform.auth.texas.TexasSettings
import no.nav.syfo.platform.upstream.UpstreamError
import no.nav.syfo.platform.upstream.UpstreamFailure
import no.nav.syfo.platform.upstream.UpstreamFailureStage
import no.nav.syfo.platform.upstream.UpstreamName
import no.nav.syfo.platform.upstream.UpstreamResult
import kotlin.coroutines.cancellation.CancellationException

internal val TEXAS = UpstreamName("texas")

class TexasHttpClient(
    val client: HttpClient,
    val settings: TexasSettings
) {

    suspend fun introspectToken(identityProvider: String, token: String): TexasIntrospectionResponse = client.post(settings.tokenIntrospectionEndpoint) {
        contentType(ContentType.Application.Json)
        setBody(
            TexasIntrospectionRequest(
                identityProvider = identityProvider,
                token = token
            )
        )
    }.body<TexasIntrospectionResponse>()

    suspend fun azureAdSystemToken(scope: String, tokenTarget: UpstreamName): UpstreamResult<String> = requestToken(
        endpoint = settings.tokenEndpoint,
        tokenTarget = tokenTarget,
        body = TexasTokenRequest(identityProvider = IDENTITY_PROVIDER_AZUREAD, target = getTarget(scope)),
    )

    suspend fun maskinportenSystemToken(target: String, tokenTarget: UpstreamName): UpstreamResult<String> = requestToken(
        endpoint = settings.tokenEndpoint,
        tokenTarget = tokenTarget,
        body = TexasTokenRequest(identityProvider = IDENTITY_PROVIDER_MASKINPORTEN, target = target),
    )

    suspend fun exchangeToAltinnToken(token: String, tokenTarget: UpstreamName): UpstreamResult<String> = requestToken(
        endpoint = settings.tokenExchangeEndpoint,
        tokenTarget = tokenTarget,
        body = TexasExchangeRequest(
            identityProvider = IDENTITY_PROVIDER_TOKENX,
            target = settings.exchangeTargetIsAltinnTilganger,
            userToken = token,
        ),
    )

    private suspend fun requestToken(endpoint: String, tokenTarget: UpstreamName, body: Any): UpstreamResult<String> {
        val response = try {
            client.post(endpoint) {
                contentType(ContentType.Application.Json)
                setBody(body)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: ResponseException) {
            return failure(tokenTarget, e.response.status.value, e, listOf(UpstreamError(e.oauthFailureCode().name.lowercase())))
        } catch (e: Exception) {
            return failure(tokenTarget, null, e)
        }
        return try {
            UpstreamResult.Success(response.body<TexasResponse>().accessToken.also { require(it.isNotBlank()) })
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failure(tokenTarget, response.status.value, e)
        }
    }

    companion object {
        fun getTarget(scope: String) = "api://$scope/.default"
        const val IDENTITY_PROVIDER_TOKENX = "tokenx"
        const val IDENTITY_PROVIDER_AZUREAD = "azuread"
        const val IDENTITY_PROVIDER_MASKINPORTEN = "maskinporten"
    }
}

private fun failure(tokenTarget: UpstreamName, status: Int?, cause: Throwable, errors: List<UpstreamError> = emptyList()) = UpstreamResult.Failure(UpstreamFailure(TEXAS, UpstreamFailureStage.TOKEN_EXCHANGE, status, cause, errors, tokenTarget))

private val oauthMapper = jacksonObjectMapper()

private suspend fun ResponseException.oauthFailureCode(): OAuthFailureCode = try {
    val error = oauthMapper.readTree(response.bodyAsText()).path("error").asText()
    OAuthFailureCode.entries.firstOrNull { it.name.lowercase() == error } ?: OAuthFailureCode.UNKNOWN
} catch (e: CancellationException) {
    throw e
} catch (_: Exception) {
    OAuthFailureCode.UNKNOWN
}
