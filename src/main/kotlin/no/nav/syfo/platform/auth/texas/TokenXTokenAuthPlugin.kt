package no.nav.syfo.platform.auth.texas

import io.ktor.server.application.createRouteScopedPlugin
import no.nav.syfo.platform.api.ApiErrorException
import no.nav.syfo.platform.auth.JwtIssuer
import no.nav.syfo.platform.observability.logger

private val VALID_ISSUERS = listOf(JwtIssuer.TOKEN_X)
private val logger = logger("no.nav.syfo.platform.auth.texas.TokenXTokenAuthPlugin")

val TokenXTokenAuthPlugin = createRouteScopedPlugin(
    name = "TokenXTokenAuthPlugin",
    createConfiguration = ::TexasAuthPluginConfiguration,
) {
    val client = pluginConfig.client.requireConfigured("TokenXTokenAuthPlugin")

    onCall { call ->
        val (issuer, bearerToken) = call.requireIssuedBearerToken(VALID_ISSUERS)

        when (issuer) {
            JwtIssuer.TOKEN_X -> call.authenticateTokenX(client, bearerToken)

            else -> throw ApiErrorException.UnauthorizedException("Unsupported token issuer")
        }
    }
    logger.info("TexasTokenXAuthPlugin installed")
}
