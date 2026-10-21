package no.nav.syfo.platform.auth.texas

import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.authorization
import no.nav.syfo.platform.api.ApiErrorException
import no.nav.syfo.platform.auth.JwtIssuer
import no.nav.syfo.platform.auth.TOKEN_ISSUER

fun ApplicationCall.bearerToken(): String? = request
    .authorization()
    ?.takeIf { it.startsWith("Bearer ", ignoreCase = true) }
    ?.removePrefix("Bearer ")
    ?.removePrefix("bearer ")

internal data class IssuedBearerToken(val issuer: JwtIssuer, val token: String)

internal fun ApplicationCall.requireIssuedBearerToken(validIssuers: Collection<JwtIssuer>): IssuedBearerToken {
    val issuer = attributes.getOrNull(TOKEN_ISSUER)?.takeIf { it in validIssuers }
        ?: throw ApiErrorException.UnauthorizedException("Failed to find issuer in token: Missing or invalid token issuer")
    val token = bearerToken() ?: throw ApiErrorException.UnauthorizedException("No bearer token found in request")
    return IssuedBearerToken(issuer, token)
}
