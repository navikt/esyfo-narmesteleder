package no.nav.syfo.application.auth

import io.ktor.server.application.createRouteScopedPlugin
import io.ktor.util.AttributeKey
import no.nav.syfo.application.exception.ApiErrorException

val TOKEN_ISSUER = AttributeKey<JwtIssuer>("tokenIssuer")

val AddTokenIssuerPlugin = createRouteScopedPlugin(
    name = "AddTokenIssuerPlugin"
) {
    onCall { call ->
        val issuer = try {
            call.jwtIssuer()
        } catch (e: Exception) {
            throw ApiErrorException.UnauthorizedException("Could not find token issuer: ${e.message}", e)
        }

        if (issuer == JwtIssuer.UNSUPPORTED) {
            throw ApiErrorException.UnauthorizedException("Unsupported token issuer")
        }

        call.attributes[TOKEN_ISSUER] = issuer
    }
}
