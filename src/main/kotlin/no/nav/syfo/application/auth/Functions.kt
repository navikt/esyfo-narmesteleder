package no.nav.syfo.application.auth

import com.auth0.jwt.JWT
import io.ktor.server.application.ApplicationCall
import no.nav.syfo.application.exception.ApiErrorException
import no.nav.syfo.texas.bearerToken

/**
 *
 * @return A supported `JwtIssuer` from an _**unverified**_ `JWT`.
 * Returns `UNSUPPORTED` if no issuer is applicable.
 * Throws `IllegalStateException` if unable to find a token or issuer.
 *
 * @see JwtIssuer
 * */
fun ApplicationCall.jwtIssuer(): JwtIssuer {
    val token = bearerToken() ?: throw ApiErrorException.UnauthorizedException("Invalid token")
    val decodedToken = JWT.decode(token)
    val issuer = decodedToken.issuer ?: throw ApiErrorException.UnauthorizedException("Invalid token")

    val hasIdpClaim = !decodedToken.getClaim("idp").asString().isNullOrEmpty()
    if (hasIdpClaim) return JwtIssuer.TOKEN_X

    return JwtIssuer.fromIssuerString(issuer)
}

fun maskinportenIdToOrgnumber(id: String): String = id.split(":")[1].trim()
