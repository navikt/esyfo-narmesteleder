package no.nav.syfo.texas

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.createRouteScopedPlugin
import io.ktor.server.auth.authentication
import io.ktor.server.response.respondNullable
import no.nav.syfo.application.auth.JwtIssuer
import no.nav.syfo.application.auth.TOKEN_ISSUER
import no.nav.syfo.application.auth.UserPrincipal
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

            val introspectionResponse = try {
                client?.introspectToken(issuer.value!!, bearerToken)
                    ?: error("TexasHttpClient is not configured")
            } catch (e: Exception) {
                throw ApiErrorException.UnauthorizedException("Failed to introspect token: ${e.message}", e)
            }

            if (!introspectionResponse.active) {
                throw ApiErrorException.UnauthorizedException(
                    "Token is not active: ${introspectionResponse.error ?: "No error message"}"
                )
            }

            when (issuer) {
                JwtIssuer.TOKEN_X -> {
                    if (!introspectionResponse.acr.equals("Level4", ignoreCase = true)) {
                        call.application.environment.log.warn("User does not have Level4 access: ${introspectionResponse.acr}")
                        call.respondNullable(HttpStatusCode.Forbidden)
                        return@onCall
                    }

                    if (introspectionResponse.pid == null) {
                        call.application.environment.log.warn("No pid in token claims")
                        call.respondNullable(HttpStatusCode.Unauthorized)
                        return@onCall
                    }
                    call.authentication.principal(UserPrincipal(introspectionResponse.pid, bearerToken))
                }

                else -> throw ApiErrorException.UnauthorizedException("Unsupported token issuer")
            }
        }
    }
    logger.info("TexasTokenXAuthPlugin installed")
}
