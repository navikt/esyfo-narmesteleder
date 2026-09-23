package no.nav.syfo.texas

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.authentication
import io.ktor.server.response.respondNullable
import no.nav.syfo.application.auth.JwtIssuer
import no.nav.syfo.application.auth.UserPrincipal
import no.nav.syfo.application.exception.ApiErrorException
import no.nav.syfo.logging.applicationEvent
import no.nav.syfo.logging.logEvent
import no.nav.syfo.texas.client.TexasHttpClient
import org.slf4j.event.Level

private val knownAcr = setOf("Level3", "Level4", "idporten-loa-substantial", "idporten-loa-high")

internal fun String?.logAcr(): String = this?.takeIf { it in knownAcr } ?: "unknown"

private enum class TokenRejectionReason {
    LEVEL_INSUFFICIENT,
    PERSON_CLAIM_MISSING
}
private data class TokenRejectionDetails(val reason: TokenRejectionReason, val acr: String? = null)

private val tokenRejected = applicationEvent<TokenRejectionDetails>(
    name = "token_rejected",
    level = Level.WARN,
    message = "User token did not satisfy authentication requirements",
    upstream = "tokenx",
    fields = mapOf(
        "acr" to { it.acr },
        "reason" to { it.reason.name },
    ),
)

private fun ApplicationCall.logRejection(reason: TokenRejectionReason, acr: String? = null) {
    application.environment.log.logEvent(tokenRejected, TokenRejectionDetails(reason, acr))
}

internal suspend fun ApplicationCall.authenticateTokenX(
    client: TexasHttpClient?,
    bearerToken: String,
) {
    val issuer = JwtIssuer.TOKEN_X.value
        ?: throw ApiErrorException.UnauthorizedException("Missing TokenX issuer value")
    val introspectionResponse = introspectActiveToken(client, issuer, bearerToken)

    if (!introspectionResponse.acr.equals("Level4", ignoreCase = true)) {
        logRejection(TokenRejectionReason.LEVEL_INSUFFICIENT, introspectionResponse.acr.logAcr())
        respondNullable(HttpStatusCode.Forbidden)
        return
    }

    if (introspectionResponse.pid == null) {
        logRejection(TokenRejectionReason.PERSON_CLAIM_MISSING)
        respondNullable(HttpStatusCode.Unauthorized)
        return
    }

    authentication.principal(UserPrincipal(introspectionResponse.pid, bearerToken))
}
