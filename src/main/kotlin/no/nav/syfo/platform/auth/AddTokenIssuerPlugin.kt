package no.nav.syfo.platform.auth

import com.auth0.jwt.JWT
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.createRouteScopedPlugin
import io.ktor.util.AttributeKey
import no.nav.syfo.platform.api.ApiErrorException
import no.nav.syfo.platform.auth.texas.bearerToken

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

/**
 *
 * @return A supported `JwtIssuer` from an _**unverified**_ `JWT`.
 * Returns `UNSUPPORTED` if no issuer is applicable.
 * Throws `IllegalStateException` if unable to find a token or issuer.
 *
 * @see JwtIssuer
 * */
private fun ApplicationCall.jwtIssuer(): JwtIssuer {
    val token = bearerToken() ?: throw ApiErrorException.UnauthorizedException("Invalid token")
    val decodedToken = JWT.decode(token)
    val issuer = decodedToken.issuer ?: throw ApiErrorException.UnauthorizedException("Invalid token")

    val hasIdpClaim = !decodedToken.getClaim("idp").asString().isNullOrEmpty()
    if (hasIdpClaim) return JwtIssuer.TOKEN_X

    return JwtIssuer.fromIssuerString(issuer)
}
