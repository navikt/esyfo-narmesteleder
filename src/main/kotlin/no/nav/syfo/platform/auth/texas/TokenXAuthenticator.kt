package no.nav.syfo.platform.auth.texas

import io.ktor.server.application.ApplicationCall
import no.nav.syfo.platform.api.ApiErrorException
import no.nav.syfo.platform.auth.JwtIssuer
import no.nav.syfo.platform.auth.UserPrincipal
import no.nav.syfo.platform.auth.texas.client.TexasHttpClient

private val knownAcr = setOf("Level3", "Level4", "idporten-loa-substantial", "idporten-loa-high")

internal fun String?.logAcr(): String = this?.takeIf { it in knownAcr } ?: "unknown"

internal suspend fun ApplicationCall.authenticateTokenX(
    client: TexasHttpClient,
    bearerToken: String,
): UserPrincipal {
    val issuer = JwtIssuer.TOKEN_X.value
        ?: throw ApiErrorException.UnauthorizedException("Missing TokenX issuer value")
    val introspectionResponse = introspectActiveToken(client, TokenRejectionProvider.TOKEN_X, issuer, bearerToken)

    if (!introspectionResponse.acr.equals("Level4", ignoreCase = true)) {
        recordTokenRejection(TokenRejectionProvider.TOKEN_X, TokenRejectionReason.LEVEL_INSUFFICIENT, introspectionResponse.acr.logAcr())
        throw ApiErrorException.ForbiddenException("Insufficient authentication level")
    }

    val pid = introspectionResponse.pid
    if (pid == null) {
        recordTokenRejection(TokenRejectionProvider.TOKEN_X, TokenRejectionReason.PERSON_CLAIM_MISSING)
        throw ApiErrorException.UnauthorizedException("No pid in token claims")
    }

    return UserPrincipal(pid, bearerToken)
}
