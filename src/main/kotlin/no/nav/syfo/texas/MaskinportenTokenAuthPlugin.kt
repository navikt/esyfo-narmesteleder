package no.nav.syfo.texas

import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.util.AttributeKey
import no.nav.syfo.application.auth.BrukerPrincipal
import no.nav.syfo.application.auth.JwtIssuer
import no.nav.syfo.application.auth.TOKEN_ISSUER
import no.nav.syfo.application.exception.ApiErrorException
import no.nav.syfo.texas.client.OrganizationId
import no.nav.syfo.util.logger

val TOKEN_CONSUMER_KEY = AttributeKey<OrganizationId>("tokenConsumer")
private val VALID_ISSUERS = listOf(JwtIssuer.MASKINPORTEN)

private val logger = logger("no.nav.syfo.texas.MaskinportenTokenAuthPlugin")

val MaskinportenTokenAuthPlugin = createRouteScopedPlugin(
    name = "MaskinportenTokenAuthPlugin",
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

            val bearerToken = call.bearerToken()
            if (bearerToken == null) {
                throw ApiErrorException.UnauthorizedException("No bearer token found in request")
            }

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

            // Eventuelt, for generalisering
//            if (issuer == JwtIssuer.TOKEN_X && !introspectionResponse.acr.equals("Level4", ignoreCase = true)) {
//                call.application.environment.log.warn("User does not have Level4 access: ${introspectionResponse.acr}")
//                call.respondNullable(HttpStatusCode.Forbidden)
//                return@onCall
//            }
//            if (issuer == JwtIssuer.TOKEN_X && introspectionResponse.pid == null) {
//                call.application.environment.log.warn("No pid in token claims")
//                call.respondNullable(HttpStatusCode.Unauthorized)
//                return@onCall
//            }

            // Present in both idporten, tokenx & maskinporten
            if (introspectionResponse.consumer == null) {
                throw ApiErrorException.UnauthorizedException("No consumer in token claims")
            }

            call.attributes.put(TOKEN_CONSUMER_KEY, introspectionResponse.consumer)

            // TODO: Maskinporten har ikke ident (idp). Den gjelder kun idporten/tokenx. Går i beina på Bjørns kode
            call.authentication.principal(
                BrukerPrincipal(
                    ident = introspectionResponse.pid,
                    token = bearerToken,
                    consumer = introspectionResponse.consumer
                )
            )
        }
    }
    logger.info("TexasMaskinportenAuthPlugin installed")
}
