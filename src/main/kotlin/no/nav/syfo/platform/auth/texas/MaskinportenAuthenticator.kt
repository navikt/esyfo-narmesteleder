package no.nav.syfo.platform.auth.texas

import io.ktor.server.application.ApplicationCall
import no.nav.syfo.platform.api.ApiErrorException
import no.nav.syfo.platform.auth.JwtIssuer
import no.nav.syfo.platform.auth.SystemPrincipal
import no.nav.syfo.platform.auth.texas.client.TexasHttpClient
import no.nav.syfo.platform.auth.texas.client.getSystemUserId
import no.nav.syfo.platform.auth.texas.client.getSystemUserOrganization
import java.util.UUID

const val MASKINPORTEN_NL_SCOPE = "nav:syfo/narmesteleder/lps"

internal suspend fun ApplicationCall.authenticateMaskinporten(
    client: TexasHttpClient,
    bearerToken: String,
): SystemPrincipal {
    val issuer = JwtIssuer.MASKINPORTEN.value
        ?: throw ApiErrorException.UnauthorizedException("Missing Maskinporten issuer value")
    val introspectionResponse = introspectActiveToken(client, TokenRejectionProvider.MASKINPORTEN, issuer, bearerToken)

    if (introspectionResponse.consumer == null) {
        rejectMaskinporten(TokenRejectionReason.CONSUMER_MISSING, ApiErrorException.UnauthorizedException("No consumer in token claims"))
    }
    if (introspectionResponse.scope != MASKINPORTEN_NL_SCOPE) {
        rejectMaskinporten(TokenRejectionReason.SCOPE_INVALID, ApiErrorException.UnauthorizedException("Invalid scope from maskinporten"))
    }
    val systemUserOrganization = introspectionResponse.getSystemUserOrganization()
        ?: rejectMaskinporten(
            TokenRejectionReason.SYSTEM_USER_ORGANIZATION_MISSING,
            ApiErrorException.UnauthorizedException("No system user organization number in token claims"),
        )
    val systemUserId = introspectionResponse.getSystemUserId()
        ?: rejectMaskinporten(
            TokenRejectionReason.SYSTEM_USER_ID_MISSING,
            ApiErrorException.UnauthorizedException("No system user id in token claims"),
        )
    // The Altinn PDP rejects malformed system user UUIDs with an opaque syntax-error, so reject them here.
    if (!systemUserId.isUuid()) {
        rejectMaskinporten(
            TokenRejectionReason.SYSTEM_USER_ID_INVALID,
            ApiErrorException.BadRequestException("Invalid system user id in token claims"),
        )
    }

    return SystemPrincipal(
        ident = systemUserOrganization,
        token = bearerToken,
        systemOwner = introspectionResponse.consumer.ID,
        systemUserId = systemUserId,
    )
}

private fun ApplicationCall.rejectMaskinporten(reason: TokenRejectionReason, exception: ApiErrorException): Nothing {
    recordTokenRejection(TokenRejectionProvider.MASKINPORTEN, reason)
    throw exception
}

private fun String.isUuid(): Boolean = runCatching { UUID.fromString(this) }.isSuccess
