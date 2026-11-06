package no.nav.syfo.platform.auth.texas

import io.ktor.server.application.ApplicationCall
import no.nav.syfo.platform.api.ApiErrorException
import no.nav.syfo.platform.auth.texas.client.TexasHttpClient

internal data class AzureAdPrincipal(val clientId: String?)

internal suspend fun ApplicationCall.authenticateAzureAd(
    client: TexasHttpClient,
    role: AzureRole,
    bearerToken: String,
): AzureAdPrincipal {
    val introspectionResponse = introspectActiveToken(
        client,
        TokenRejectionProvider.AZURE_AD,
        TexasHttpClient.IDENTITY_PROVIDER_AZUREAD,
        bearerToken,
    )
    if (role.value !in introspectionResponse.roles.orEmpty()) {
        recordTokenRejection(TokenRejectionProvider.AZURE_AD, TokenRejectionReason.ROLE_NOT_AUTHORIZED)
        throw ApiErrorException.ForbiddenException("Application is not authorized")
    }
    return AzureAdPrincipal(introspectionResponse.azp)
}
