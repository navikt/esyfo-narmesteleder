package no.nav.syfo.texas

import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.authentication
import no.nav.syfo.application.auth.JwtIssuer
import no.nav.syfo.application.auth.SystemPrincipal
import no.nav.syfo.application.exception.ApiErrorException
import no.nav.syfo.texas.client.TexasHttpClient
import no.nav.syfo.texas.client.getSystemUserId
import no.nav.syfo.texas.client.getSystemUserOrganization

internal suspend fun ApplicationCall.authenticateMaskinporten(
    client: TexasHttpClient?,
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

    authentication.principal(
        SystemPrincipal(
            ident = systemUserOrganization,
            token = bearerToken,
            systemOwner = introspectionResponse.consumer.ID,
            systemUserId = systemUserId,
        ),
    )
}
