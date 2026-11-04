package no.nav.syfo.platform.auth.texas.altinn

import com.auth0.jwt.JWT
import io.ktor.client.HttpClient
import io.ktor.client.plugins.ResponseException
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import no.nav.syfo.platform.auth.texas.client.TexasHttpClient
import no.nav.syfo.platform.upstream.UpstreamFailure
import no.nav.syfo.platform.upstream.UpstreamFailureStage
import no.nav.syfo.platform.upstream.UpstreamName
import no.nav.syfo.platform.upstream.UpstreamResult
import no.nav.syfo.platform.upstream.getOrElse
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.milliseconds

internal val ALTINN_AUTHENTICATION = UpstreamName("altinn-authentication")

class TexasAltinnTokenFetcher(
    private val texasHttpClient: TexasHttpClient,
    private val httpClient: HttpClient,
    private val altinnBaseUrl: String,
) : AltinnTokenSource {
    override suspend fun token(target: String): UpstreamResult<AltinnToken> {
        val maskinportenToken = texasHttpClient.maskinportenSystemToken(target, ALTINN_AUTHENTICATION).getOrElse { return UpstreamResult.Failure(it) }
        val response = try {
            httpClient.get("$altinnBaseUrl/authentication/api/v1/exchange/maskinporten") {
                bearerAuth(maskinportenToken)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: ResponseException) {
            return failure(e.response.status.value, e)
        } catch (e: Exception) {
            return failure(null, e)
        }
        try {
            val accessToken = response.bodyAsText().removeSurrounding("\"")
            val expiry = requireNotNull(JWT.decode(accessToken).expiresAt) { "Altinn token is missing expiry" }
            val token = AltinnToken(accessToken, expiry.time.milliseconds)
            return UpstreamResult.Success(token)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return failure(response.status.value, e)
        }
    }

    private fun failure(status: Int?, cause: Throwable) = UpstreamResult.Failure(
        UpstreamFailure(ALTINN_AUTHENTICATION, UpstreamFailureStage.TOKEN_EXCHANGE, status, cause),
    )

    companion object {
        const val DIALOGPORTEN_TARGET_SCOPE = "digdir:dialogporten.serviceprovider"
        const val PDP_TARGET_SCOPE = "altinn:authorization/authorize"
    }
}
