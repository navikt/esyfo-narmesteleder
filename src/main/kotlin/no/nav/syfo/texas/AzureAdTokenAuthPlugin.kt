package no.nav.syfo.texas

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import io.ktor.server.application.createRouteScopedPlugin
import no.nav.syfo.application.auth.JwtIssuer
import no.nav.syfo.application.auth.TOKEN_ISSUER
import no.nav.syfo.application.exception.ApiErrorException
import no.nav.syfo.texas.client.TexasHttpClient

class AzureAdTokenAuthPluginConfiguration(
    var client: TexasHttpClient? = null,
    var preAuthorizedApps: Set<String> = emptySet(),
)

data class AzureAdPreAuthorizedApp(
    val name: String,
    val clientId: String,
)

val AzureAdTokenAuthPlugin = createRouteScopedPlugin(
    name = "AzureAdTokenAuthPlugin",
    createConfiguration = ::AzureAdTokenAuthPluginConfiguration,
) {
    val client = pluginConfig.client.requireConfigured("AzureAdTokenAuthPlugin")
    val preAuthorizedApps = pluginConfig.preAuthorizedApps
        .ifEmpty { error("AzureAdTokenAuthPlugin installed without pre-authorized apps") }

    onCall { call ->
        if (call.attributes.getOrNull(TOKEN_ISSUER) != JwtIssuer.AZURE_AD) {
            throw ApiErrorException.UnauthorizedException("Invalid token issuer")
        }

        val bearerToken = call.bearerToken()
            ?: throw ApiErrorException.UnauthorizedException("No bearer token found in request")
        val introspectionResponse =
            introspectActiveToken(client, TexasHttpClient.IDENTITY_PROVIDER_AZUREAD, bearerToken)

        if (introspectionResponse.azp !in preAuthorizedApps) {
            throw ApiErrorException.ForbiddenException("Application is not authorized")
        }
    }
}

fun preAuthorizedAppsFromJson(configuredApps: String): Set<String> = jacksonObjectMapper()
    .readValue<List<AzureAdPreAuthorizedApp>>(configuredApps)
    .map(AzureAdPreAuthorizedApp::clientId)
    .toSet()
