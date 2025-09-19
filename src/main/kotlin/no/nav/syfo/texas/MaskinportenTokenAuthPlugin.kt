package no.nav.syfo.texas

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.response.*
import no.nav.syfo.application.auth.BrukerPrincipal
import no.nav.syfo.application.auth.JwtIssuer
import no.nav.syfo.util.logger

private val logger = logger("no.nav.syfo.texas.MaskinportenTokenAuthPlugin")

val MaskinportenTokenAuthPlugin = createRouteScopedPlugin(
    name = "MaskinportenTokenAuthPlugin",
    createConfiguration = ::TexasAuthPluginConfiguration,
) {
    pluginConfig.apply {
        onCall { call ->
            val bearerToken = call.bearerToken()
            if (bearerToken == null) {
                call.application.environment.log.warn("No bearer token found in request")
                call.respondNullable(HttpStatusCode.Unauthorized)
                return@onCall
            }

            val issuer = try {
                call.jwtIssuer()
            } catch (e: Exception) {
                call.application.environment.log.error("Could not find token issuer: ${e.message}", e)
                call.respondNullable(HttpStatusCode.Unauthorized)
                return@onCall
            }

            if (issuer == JwtIssuer.UNSUPPORTED) {
                call.application.environment.log.warn("Unsupported token issuer")
                call.respondNullable(HttpStatusCode.Unauthorized)
                return@onCall
            }

            // TODO: Dette skaper i praksis avhengighet til andre plugins. Innafor?
            if (issuer != JwtIssuer.MASKINPORTEN) {
                call.application.environment.log.debug("Issuer not handled by plugin")
                return@onCall
            }

            val introspectionResponse = try {
                client?.introspectToken("maskinporten", bearerToken)
                    ?: error("TexasHttpClient is not configured")
            } catch (e: Exception) {
                call.application.environment.log.error("Failed to introspect token: ${e.message}", e)
                call.respondNullable(HttpStatusCode.Unauthorized)
                return@onCall
            }

            if (!introspectionResponse.active) {
                call.application.environment.log.warn(
                    "" +
                            "Token is not active: ${introspectionResponse.error ?: "No error message"}"
                )
                call.respondNullable(HttpStatusCode.Unauthorized)
                return@onCall
            }

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
            call.authentication.principal(BrukerPrincipal(introspectionResponse.pid, bearerToken))
        }
    }
    logger.info("TexasMaskinportenAuthPlugin installed")
}
