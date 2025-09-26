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

    val hasIdpClaim = !decodedToken.getClaim("idp").asString().isNullOrEmpty()
    if (hasIdpClaim) return JwtIssuer.TOKEN_X

    val issuer = decodedToken.issuer ?: throw ApiErrorException.UnauthorizedException("Invalid token")
    return JwtIssuer.fromIssuerString(issuer)
}

private fun JwtIssuer.Companion.fromIssuerString(iss: String): JwtIssuer = when {
    // https://maskinporten.no/.well-known/oauth-authorization-server
    // https://test.maskinporten.no/.well-known/oauth-authorization-server
    iss.matches(Regex("https://(test\\.)?maskinporten\\.no/?")) -> JwtIssuer.MASKINPORTEN
    // https://idporten.no/.well-known/openid-configuration
    // https://test.idporten.no/.well-known/openid-configuration
    iss.matches(Regex("https://(test\\.)?idporten\\.no/?")) -> JwtIssuer.IDPORTEN
    iss == "https://fakedings.intern.dev.nav.no/fake" -> JwtIssuer.FAKEDINGS
    // tokenx is found at well-known doc found in TOKEN_X_WELL_KNOWN_URL env. var
    else -> JwtIssuer.UNSUPPORTED
}

fun maskinportenIdToOrgnumber(id: String): String {
    return id.split(":")[1].trim()
}