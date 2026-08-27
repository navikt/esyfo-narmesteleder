package no.nav.syfo.texas

import kotlinx.coroutines.CancellationException
import no.nav.syfo.application.exception.ApiErrorException
import no.nav.syfo.texas.client.TexasHttpClient
import no.nav.syfo.texas.client.TexasIntrospectionResponse

internal suspend fun introspectActiveToken(
    client: TexasHttpClient?,
    issuer: String,
    bearerToken: String,
): TexasIntrospectionResponse {
    val introspectionResponse = try {
        client?.introspectToken(issuer, bearerToken)
            ?: error("TexasHttpClient is not configured")
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        throw ApiErrorException.InternalServerErrorException(cause = e)
    }

    if (!introspectionResponse.active) {
        throw ApiErrorException.UnauthorizedException("Token is not active")
    }

    return introspectionResponse
}
