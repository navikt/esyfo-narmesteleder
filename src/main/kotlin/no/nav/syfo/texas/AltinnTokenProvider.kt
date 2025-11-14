package no.nav.syfo.texas

import com.auth0.jwt.JWT
import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import no.nav.syfo.texas.client.TexasHttpClient

private const val DIGDIR_TARGET_SCOPE = "digdir:dialogporten.serviceprovider"

class AltinnTokenProvider(
    private val texasHttpClient: TexasHttpClient,
    private val httpClient: HttpClient,
    private val altinnBaseUrl: String,
) {
    private var altinnToken: AltinnToken? = null
    private val mutex = Mutex()

    private data class AltinnToken(
        val accessToken: String,
        val altinnExpiryTime: Duration,
    )

    suspend fun token(): String {
        mutex.withLock {
            val token = altinnToken
            if (token != null) {
                val now = TimeSource.Monotonic.markNow()
                val timeLeft = token.altinnExpiryTime - now.elapsedNow()

                if (timeLeft > 300.seconds) {
                    return requireNotNull(token.accessToken) { "Access token is null" }
                }

                // There's also a fallback to exchange in the refresh method
                if (timeLeft < 120.seconds && timeLeft > 0.seconds) {
                    altinnToken = token.refresh()
                    return requireNotNull(altinnToken?.accessToken) { "Access token is null" }
                }
            }
            val maskinportenToken = texasHttpClient.systemToken("maskinporten", DIGDIR_TARGET_SCOPE)
            val newToken = altinnExchange(maskinportenToken.accessToken).toAltinnToken()

            altinnToken = newToken
            return newToken.accessToken
        }
    }

    private fun String.toAltinnToken(): AltinnToken {
        val decodedAltinnToken = JWT.decode(this)

        return AltinnToken(
            accessToken = this,
            altinnExpiryTime = decodedAltinnToken.expiresAt.time.milliseconds,
        )
    }

    private suspend fun AltinnToken.refresh(): AltinnToken {
        val res = httpClient
            .get("$altinnBaseUrl/authentication/api/v1/refresh") {
                bearerAuth(accessToken)
            }

        return if (!res.status.isSuccess()) {
            val maskinportenToken = texasHttpClient.systemToken("maskinporten", DIGDIR_TARGET_SCOPE)
            altinnExchange(maskinportenToken.accessToken).toAltinnToken()
        } else {
            res.bodyAsText()
                .replace("\"", "")
                .toAltinnToken()
        }
    }

    private suspend fun altinnExchange(token: String): String =
        httpClient
            .get("$altinnBaseUrl/authentication/api/v1/exchange/maskinporten") {
                bearerAuth(token)
            }.bodyAsText()
            .replace("\"", "")
}
