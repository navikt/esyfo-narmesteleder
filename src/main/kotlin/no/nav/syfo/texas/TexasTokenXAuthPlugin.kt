package no.nav.syfo.texas

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.createRouteScopedPlugin
import io.ktor.server.auth.authentication
import io.ktor.server.response.respondNullable
import no.nav.syfo.application.auth.BrukerPrincipal
import no.nav.syfo.util.logger

private val logger = logger("no.nav.syfo.texas.TexasTokenXAuthPlugin")

val TexasTokenXAuthPlugin = createRouteScopedPlugin(
    name = "TexasTokenXAuthPlugin",
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

            val introspectionResponse = try {
                client?.introspectToken("tokenx", bearerToken)
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
    logger.info("TexasTokenXAuthPlugin installed")
}
