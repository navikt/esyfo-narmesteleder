package no.nav.syfo.texas

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.authentication
import io.ktor.server.response.respondNullable
import no.nav.syfo.application.auth.JwtIssuer
import no.nav.syfo.application.auth.UserPrincipal
import no.nav.syfo.application.exception.ApiErrorException
import no.nav.syfo.texas.client.TexasHttpClient

internal suspend fun ApplicationCall.authenticateTokenX(
    client: TexasHttpClient?,
    bearerToken: String,
) {
    val issuer = JwtIssuer.TOKEN_X.value
        ?: throw ApiErrorException.UnauthorizedException("Missing TokenX issuer value")
    val introspectionResponse = introspectActiveToken(client, issuer, bearerToken)

    if (!introspectionResponse.acr.equals("Level4", ignoreCase = true)) {
        application.environment.log.warn("User does not have Level4 access: ${introspectionResponse.acr}")
        respondNullable(HttpStatusCode.Forbidden)
        return
    }

    if (introspectionResponse.pid == null) {
        application.environment.log.warn("No pid in token claims")
        respondNullable(HttpStatusCode.Unauthorized)
        return
    }

    authentication.principal(UserPrincipal(introspectionResponse.pid, bearerToken))
}
