package no.nav.syfo.platform.auth.texas

import io.ktor.server.application.ApplicationCall
import no.nav.syfo.platform.api.ApiErrorException
import no.nav.syfo.platform.auth.texas.client.TexasHttpClient

internal data class AzureAdPrincipal(val clientId: String)

internal suspend fun ApplicationCall.authenticateAzureAd(
    client: TexasHttpClient,
    preAuthorizedApps: Set<String>,
    bearerToken: String,
): AzureAdPrincipal {
    val introspectionResponse = introspectActiveToken(
        client,
        TokenRejectionProvider.AZURE_AD,
        TexasHttpClient.IDENTITY_PROVIDER_AZUREAD,
        bearerToken,
    )
    val clientId = introspectionResponse.azp
    if (clientId == null || clientId !in preAuthorizedApps) {
        recordTokenRejection(TokenRejectionProvider.AZURE_AD, TokenRejectionReason.CLIENT_NOT_PRE_AUTHORIZED)
        throw ApiErrorException.ForbiddenException("Application is not authorized")
    }
    return AzureAdPrincipal(clientId)
}
