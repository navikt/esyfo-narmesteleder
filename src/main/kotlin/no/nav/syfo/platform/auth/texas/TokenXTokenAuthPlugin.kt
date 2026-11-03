package no.nav.syfo.platform.auth.texas

import io.ktor.server.application.createRouteScopedPlugin
import no.nav.syfo.platform.api.ApiErrorException
import no.nav.syfo.platform.auth.JwtIssuer
import no.nav.syfo.platform.observability.logger

private val VALID_ISSUERS = listOf(JwtIssuer.TOKEN_X)
private val logger = logger("no.nav.syfo.platform.auth.texas.TokenXTokenAuthPlugin")

private const val PLUGIN_NAME = "TokenXTokenAuthPlugin"

val TokenXTokenAuthPlugin = createRouteScopedPlugin(
    name = PLUGIN_NAME,
    createConfiguration = ::TexasAuthPluginConfiguration,
) {
    val client = application.resolveTexasHttpClient(pluginConfig.client, PLUGIN_NAME)

    onCall { call ->
        val (issuer, bearerToken) = call.requireIssuedBearerToken(VALID_ISSUERS)

        when (issuer) {
            JwtIssuer.TOKEN_X -> call.authenticateTokenX(client, bearerToken)

            else -> throw ApiErrorException.UnauthorizedException("Unsupported token issuer")
        }
    }
    logger.info("$PLUGIN_NAME installed")
}
