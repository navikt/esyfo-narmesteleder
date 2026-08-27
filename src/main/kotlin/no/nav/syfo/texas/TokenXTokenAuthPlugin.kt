package no.nav.syfo.texas

import io.ktor.server.application.createRouteScopedPlugin
import no.nav.syfo.application.auth.JwtIssuer
import no.nav.syfo.application.auth.TOKEN_ISSUER
import no.nav.syfo.application.exception.ApiErrorException
import no.nav.syfo.util.logger

private val VALID_ISSUERS = listOf(JwtIssuer.TOKEN_X)
private val logger = logger("no.nav.syfo.texas.TokenXTokenAuthPlugin")

val TokenXTokenAuthPlugin = createRouteScopedPlugin(
    name = "TokenXTokenAuthPlugin",
    createConfiguration = ::TexasAuthPluginConfiguration,
) {

    pluginConfig.apply {
        onCall { call ->
            val issuer = try {
                call.attributes.getOrNull(TOKEN_ISSUER)
                    ?.takeIf { it in VALID_ISSUERS }
                    ?: error("Missing or invalid token issuer")
            } catch (e: Exception) {
                throw ApiErrorException.UnauthorizedException("Failed to find issuer in token: ${e.message}", e)
            }

            val bearerToken =
                call.bearerToken() ?: throw ApiErrorException.UnauthorizedException("No bearer token found in request")

            when (issuer) {
                JwtIssuer.TOKEN_X -> call.authenticateTokenX(client, bearerToken)

                else -> throw ApiErrorException.UnauthorizedException("Unsupported token issuer")
            }
        }
    }
    logger.info("TexasTokenXAuthPlugin installed")
}
