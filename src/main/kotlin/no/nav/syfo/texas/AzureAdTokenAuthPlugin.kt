package no.nav.syfo.texas

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.ktor.server.application.createRouteScopedPlugin
import no.nav.syfo.application.auth.JwtIssuer
import no.nav.syfo.application.auth.TOKEN_ISSUER
import no.nav.syfo.application.environment.getEnvVar
import no.nav.syfo.application.exception.ApiErrorException
import no.nav.syfo.texas.client.TexasHttpClient

class AzureAdTokenAuthPluginConfiguration(
    var client: TexasHttpClient? = null,
    var preAuthorizedApps: Set<String> = emptySet(),
)

val AzureAdTokenAuthPlugin = createRouteScopedPlugin(
    name = "AzureAdTokenAuthPlugin",
    createConfiguration = ::AzureAdTokenAuthPluginConfiguration,
) {
    onCall { call ->
        if (call.attributes.getOrNull(TOKEN_ISSUER) != JwtIssuer.AZURE_AD) {
            throw ApiErrorException.UnauthorizedException("Invalid token issuer")
        }

        val bearerToken = call.bearerToken()
            ?: throw ApiErrorException.UnauthorizedException("No bearer token found in request")
        val introspectionResponse = pluginConfig.client
            ?.introspectToken(TexasHttpClient.IDENTITY_PROVIDER_AZUREAD, bearerToken)
            ?: error("TexasHttpClient is not configured")

        if (!introspectionResponse.active) {
            throw ApiErrorException.UnauthorizedException("Token is not active")
        }

        if (introspectionResponse.azp !in pluginConfig.preAuthorizedApps) {
            throw ApiErrorException.ForbiddenException("Application is not authorized")
        }
    }
}

fun preAuthorizedAppsFromEnvironment(): Set<String> {
    val configuredApps = getEnvVar("AZURE_APP_PRE_AUTHORIZED_APPS")
    val apps = jacksonObjectMapper().readTree(configuredApps)
    require(apps.isArray) { "AZURE_APP_PRE_AUTHORIZED_APPS must be a JSON array" }
    return apps.map { it.asText() }.filter(String::isNotBlank).toSet()
}
