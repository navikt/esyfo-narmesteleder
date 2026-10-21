package no.nav.syfo.platform.auth.texas

import io.ktor.server.application.createRouteScopedPlugin
import no.nav.syfo.platform.api.ApiErrorException
import no.nav.syfo.platform.auth.JwtIssuer
import no.nav.syfo.platform.auth.TOKEN_ISSUER
import no.nav.syfo.platform.observability.logger

private val VALID_ISSUERS = listOf(JwtIssuer.MASKINPORTEN, JwtIssuer.TOKEN_X)
const val MASKINPORTEN_NL_SCOPE = "nav:syfo/narmesteleder/lps"
private val logger = logger("no.nav.syfo.platform.auth.texas.MaskinportenAndTokenXTokenAuthPlugin")

val MaskinportenAndTokenXTokenAuthPlugin = createRouteScopedPlugin(
    name = "MaskinportenAndTokenXTokenAuthPlugin",
    createConfiguration = ::TexasAuthPluginConfiguration,
) {
    val client = pluginConfig.client.requireConfigured("MaskinportenAndTokenXTokenAuthPlugin")

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
            JwtIssuer.MASKINPORTEN -> call.authenticateMaskinporten(client, bearerToken)

            JwtIssuer.TOKEN_X -> call.authenticateTokenX(client, bearerToken)

            else -> throw ApiErrorException.UnauthorizedException("Unsupported token issuer")
        }
    }
    logger.info("TexasMaskinportenAuthPlugin installed")
}
