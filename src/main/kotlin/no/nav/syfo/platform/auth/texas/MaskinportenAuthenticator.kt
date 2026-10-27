package no.nav.syfo.platform.auth.texas

import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.authentication
import no.nav.syfo.platform.api.ApiErrorException
import no.nav.syfo.platform.auth.JwtIssuer
import no.nav.syfo.platform.auth.SystemPrincipal
import no.nav.syfo.platform.auth.texas.client.TexasHttpClient
import no.nav.syfo.platform.auth.texas.client.getSystemUserId
import no.nav.syfo.platform.auth.texas.client.getSystemUserOrganization
import java.util.UUID

internal suspend fun ApplicationCall.authenticateMaskinporten(
    client: TexasHttpClient,
    bearerToken: String,
) {
    val issuer = JwtIssuer.MASKINPORTEN.value
        ?: throw ApiErrorException.UnauthorizedException("Missing Maskinporten issuer value")
    val introspectionResponse = introspectActiveToken(client, issuer, bearerToken)

    if (introspectionResponse.consumer == null) {
        throw ApiErrorException.UnauthorizedException("No consumer in token claims")
    }
    if (introspectionResponse.scope != MASKINPORTEN_NL_SCOPE) {
        throw ApiErrorException.UnauthorizedException("Invalid scope from maskinporten")
    }
    val systemUserOrganization = introspectionResponse.getSystemUserOrganization()
        ?: throw ApiErrorException.UnauthorizedException("No system user organization number in token claims")
    val systemUserId = introspectionResponse.getSystemUserId()
        ?: throw ApiErrorException.UnauthorizedException("No system user id in token claims")
    // The Altinn PDP rejects malformed system user UUIDs with an opaque syntax-error, so reject them here.
    if (!systemUserId.isUuid()) {
        throw ApiErrorException.BadRequestException("Invalid system user id in token claims")
    }

    authentication.principal(
        SystemPrincipal(
            ident = systemUserOrganization,
            token = bearerToken,
            systemOwner = introspectionResponse.consumer.ID,
            systemUserId = systemUserId,
        ),
    )
}

private fun String.isUuid(): Boolean = runCatching { UUID.fromString(this) }.isSuccess
