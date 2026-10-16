package no.nav.syfo.texas.client

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.ResponseException
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import no.nav.syfo.application.texas.TexasEnvironment
import no.nav.syfo.platform.upstream.UpstreamError
import no.nav.syfo.platform.upstream.UpstreamFailure
import no.nav.syfo.platform.upstream.UpstreamFailureStage
import no.nav.syfo.platform.upstream.UpstreamName
import no.nav.syfo.platform.upstream.UpstreamResult
import kotlin.coroutines.cancellation.CancellationException

internal val TEXAS = UpstreamName("texas")

class TexasHttpClient(
    val client: HttpClient,
    val environment: TexasEnvironment
) {

    suspend fun introspectToken(identityProvider: String, token: String): TexasIntrospectionResponse = client.post(environment.tokenIntrospectionEndpoint) {
        contentType(ContentType.Application.Json)
        setBody(
            TexasIntrospectionRequest(
                identityProvider = identityProvider,
                token = token
            )
        )
    }.body<TexasIntrospectionResponse>()

    suspend fun systemToken(identityProvider: String, target: String): TexasResponse = client.post(environment.tokenEndpoint) {
        contentType(ContentType.Application.Json)
        setBody(
            TexasTokenRequest(
                identityProvider = identityProvider,
                target = target,
            )
        )
    }.body<TexasResponse>()

    suspend fun azureAdSystemToken(scope: String): UpstreamResult<String> = try {
        UpstreamResult.Success(systemToken(IDENTITY_PROVIDER_AZUREAD, getTarget(scope)).accessToken)
    } catch (e: CancellationException) {
        throw e
    } catch (e: ResponseException) {
        failure(e.response.status.value, e)
    } catch (e: Exception) {
        failure(null, e)
    }

    suspend fun exchangeTokenForIsAltinnTilganger(token: String): UpstreamResult<String> = exchangeToken(IDENTITY_PROVIDER_TOKENX, environment.exchangeTargetIsAltinnTilganger, token)

    suspend fun maskinportenSystemToken(target: String): UpstreamResult<String> {
        val response = try {
            client.post(environment.tokenEndpoint) {
                contentType(ContentType.Application.Json)
                setBody(TexasTokenRequest(identityProvider = "maskinporten", target = target))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: ResponseException) {
            return failure(e.response.status.value, e, listOf(UpstreamError(e.oauthFailureCode().name.lowercase())))
        } catch (e: Exception) {
            return failure(null, e)
        }
        return try {
            UpstreamResult.Success(response.body<TexasResponse>().accessToken.also { require(it.isNotBlank()) })
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failure(response.status.value, e)
        }
    }

    private suspend fun exchangeToken(identityProvider: String, target: String, token: String): UpstreamResult<String> {
        val response = try {
            client.post(environment.tokenExchangeEndpoint) {
                contentType(ContentType.Application.Json)
                setBody(
                    TexasExchangeRequest(
                        identityProvider = identityProvider,
                        target = target,
                        userToken = token
                    )
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: ResponseException) {
            return failure(e.response.status.value, e, listOf(UpstreamError(e.oauthFailureCode().name.lowercase())))
        } catch (e: Exception) {
            return failure(null, e)
        }

        return try {
            UpstreamResult.Success(response.body<TexasResponse>().accessToken)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failure(response.status.value, e)
        }
    }

    companion object {
        fun getTarget(scope: String) = "api://$scope/.default"
        const val IDENTITY_PROVIDER_TOKENX = "tokenx"
        const val IDENTITY_PROVIDER_AZUREAD = "azuread"
    }
}

private fun failure(status: Int?, cause: Throwable, errors: List<UpstreamError> = emptyList()) = UpstreamResult.Failure(UpstreamFailure(TEXAS, UpstreamFailureStage.TOKEN_EXCHANGE, status, cause, errors))

private val oauthMapper = jacksonObjectMapper()

private suspend fun ResponseException.oauthFailureCode(): OAuthFailureCode = try {
    val error = oauthMapper.readTree(response.bodyAsText()).path("error").asText()
    OAuthFailureCode.entries.firstOrNull { it.name.lowercase() == error } ?: OAuthFailureCode.UNKNOWN
} catch (e: CancellationException) {
    throw e
} catch (_: Exception) {
    OAuthFailureCode.UNKNOWN
}
