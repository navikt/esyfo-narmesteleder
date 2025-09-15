package no.nav.syfo.texas.client

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import no.nav.syfo.texas.TexasEnvironment

class TexasHttpClient(
    val client: HttpClient,
    val environment: TexasEnvironment
) {

    suspend fun introspectToken(identityProvider: String, token: String): TexasIntrospectionResponse {
        return client.post(environment.tokenIntrospectionEndpoint) {
            contentType(ContentType.Application.Json)
            setBody(
                TexasIntrospectionRequest(
                    identityProvider = identityProvider,
                    token = token
                )
            )
        }.body<TexasIntrospectionResponse>()
    }

    suspend fun exchangeTokenForDineSykmeldte(token: String): TexasResponse {
        return exchangeToken(IDENTITY_PROVIDER_TOKENX, environment.exchangeTargetDineSykmeldte, token)
    }

    suspend fun exchangeTokenForIsDialogmelding(token: String): TexasResponse {
        return exchangeToken(IDENTITY_PROVIDER_TOKENX, environment.exchangeTargetIsDialogmelding, token)
    }

    suspend fun exchangeTokenForIsTilgangskontroll(token: String): TexasResponse {
        return exchangeToken(
            IDENTITY_PROVIDER_AZUREAD,
            TexasHttpClient.getTarget(environment.exchangeTargetIsTilgangskontroll),
            token
        )
    }

    suspend fun systemToken(identityProvider: String, target: String): TexasResponse {
        return client.post(environment.tokenEndpoint) {
            contentType(ContentType.Application.Json)
            setBody(
                TexasTokenRequest(
                    identityProvider = identityProvider,
                    target = target,
                )
            )
        }.body<TexasResponse>()
    }

    private suspend fun exchangeToken(identityProvider: String, target: String, token: String): TexasResponse {
        return client.post(environment.tokenExchangeEndpoint) {
            contentType(ContentType.Application.Json)
            setBody(
                TexasExchangeRequest(
                    identityProvider = identityProvider,
                    target = target,
                    userToken = token
                )
            )
        }.body<TexasResponse>()
    }

    companion object {
        fun getTarget(scope: String) = "api://$scope/.default"
        const val IDENTITY_PROVIDER_TOKENX = "tokenx"
        const val IDENTITY_PROVIDER_AZUREAD = "azuread"
    }
}
