package no.nav.syfo.platform.auth.texas

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respondNullable
import no.nav.syfo.platform.api.ApiErrorException
import no.nav.syfo.platform.auth.JwtIssuer
import no.nav.syfo.platform.auth.UserPrincipal
import no.nav.syfo.platform.auth.texas.client.TexasHttpClient

private val knownAcr = setOf("Level3", "Level4", "idporten-loa-substantial", "idporten-loa-high")

internal fun String?.logAcr(): String = this?.takeIf { it in knownAcr } ?: "unknown"

internal suspend fun ApplicationCall.authenticateTokenX(
    client: TexasHttpClient,
    bearerToken: String,
): UserPrincipal? {
    val issuer = JwtIssuer.TOKEN_X.value
        ?: throw ApiErrorException.UnauthorizedException("Missing TokenX issuer value")
    val introspectionResponse = introspectActiveToken(client, TokenRejectionProvider.TOKEN_X, issuer, bearerToken)

    if (!introspectionResponse.acr.equals("Level4", ignoreCase = true)) {
        recordTokenRejection(TokenRejectionProvider.TOKEN_X, TokenRejectionReason.LEVEL_INSUFFICIENT, introspectionResponse.acr.logAcr())
        respondNullable(HttpStatusCode.Forbidden)
        return null
    }

    if (introspectionResponse.pid == null) {
        recordTokenRejection(TokenRejectionProvider.TOKEN_X, TokenRejectionReason.PERSON_CLAIM_MISSING)
        respondNullable(HttpStatusCode.Unauthorized)
        return null
    }

    return UserPrincipal(introspectionResponse.pid, bearerToken)
}
