package no.nav.syfo.platform.auth.texas

import io.ktor.server.application.createRouteScopedPlugin
import no.nav.syfo.platform.api.ApiErrorException
import no.nav.syfo.platform.auth.JwtIssuer
import no.nav.syfo.platform.observability.logger

private val VALID_ISSUERS = listOf(JwtIssuer.MASKINPORTEN, JwtIssuer.TOKEN_X)
const val MASKINPORTEN_NL_SCOPE = "nav:syfo/narmesteleder/lps"
private val logger = logger("no.nav.syfo.platform.auth.texas.MaskinportenAndTokenXTokenAuthPlugin")

private const val PLUGIN_NAME = "MaskinportenAndTokenXTokenAuthPlugin"

val MaskinportenAndTokenXTokenAuthPlugin = createRouteScopedPlugin(
    name = PLUGIN_NAME,
    createConfiguration = ::TexasAuthPluginConfiguration,
) {
    val client = application.resolveTexasHttpClient(pluginConfig.client, PLUGIN_NAME)

    onCall { call ->
        val (issuer, bearerToken) = call.requireIssuedBearerToken(VALID_ISSUERS)

        when (issuer) {
            JwtIssuer.MASKINPORTEN -> call.authenticateMaskinporten(client, bearerToken)

            JwtIssuer.TOKEN_X -> call.authenticateTokenX(client, bearerToken)

            else -> throw ApiErrorException.UnauthorizedException("Unsupported token issuer")
        }
    }
    logger.info("$PLUGIN_NAME installed")
}
