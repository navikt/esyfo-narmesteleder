package no.nav.syfo.texas

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.createRouteScopedPlugin
import io.ktor.server.auth.authentication
import io.ktor.server.response.respondNullable
import io.ktor.util.AttributeKey
import no.nav.syfo.application.auth.BrukerPrincipal
import no.nav.syfo.application.auth.JwtIssuer
import no.nav.syfo.application.auth.OrganisasjonPrincipal
import no.nav.syfo.application.auth.TOKEN_ISSUER
import no.nav.syfo.application.exception.ApiErrorException
import no.nav.syfo.texas.client.OrganizationId
import no.nav.syfo.util.logger

val TOKEN_CONSUMER_KEY = AttributeKey<OrganizationId>("tokenConsumer")
private val VALID_ISSUERS = listOf(JwtIssuer.MASKINPORTEN, JwtIssuer.TOKEN_X)
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

            when (issuer) {
                JwtIssuer.MASKINPORTEN -> {
                    if (introspectionResponse.consumer == null) {
                        throw ApiErrorException.UnauthorizedException("No consumer in token claims")
                    }
                    call.authentication.principal(
                        OrganisasjonPrincipal(
                            ident = introspectionResponse.consumer.ID,
                            token = bearerToken,
                        )
                    )
                    call.attributes.put(TOKEN_CONSUMER_KEY, introspectionResponse.consumer)
                }

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
                    call.authentication.principal(BrukerPrincipal(introspectionResponse.pid, bearerToken))
                }

                else -> throw ApiErrorException.UnauthorizedException("Unsupported token issuer")
            }
        }
    }
    logger.info("TexasMaskinportenAuthPlugin installed")
}
