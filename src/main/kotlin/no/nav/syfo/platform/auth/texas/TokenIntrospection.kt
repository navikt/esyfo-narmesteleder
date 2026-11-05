package no.nav.syfo.platform.auth.texas

import io.ktor.server.application.ApplicationCall
import kotlinx.coroutines.CancellationException
import no.nav.syfo.platform.api.ApiErrorException
import no.nav.syfo.platform.auth.texas.client.TexasHttpClient
import no.nav.syfo.platform.auth.texas.client.TexasIntrospectionResponse

internal suspend fun ApplicationCall.introspectActiveToken(
    client: TexasHttpClient,
    provider: TokenRejectionProvider,
    issuer: String,
    bearerToken: String,
): TexasIntrospectionResponse {
    val introspectionResponse = try {
        client.introspectToken(issuer, bearerToken)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        throw ApiErrorException.InternalServerErrorException(cause = e)
    }

    if (!introspectionResponse.active) {
        recordTokenRejection(provider, TokenRejectionReason.TOKEN_INACTIVE)
        throw ApiErrorException.UnauthorizedException("Token is not active")
    }

    return introspectionResponse
}
