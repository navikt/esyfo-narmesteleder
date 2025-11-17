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
    private val tokens: MutableMap<String, AltinnToken> = mutableMapOf()
    private val mutex = Mutex()

    private data class AltinnToken(
        val accessToken: String,
        val altinnExpiryTime: Duration,
    )

    suspend fun token(target: String): String {
        mutex.withLock {
            val token = tokens[target]
            if (token != null) {
                val now = TimeSource.Monotonic.markNow()
                val timeLeft = token.altinnExpiryTime - now.elapsedNow()

                if (timeLeft > 300.seconds) {
                    return requireNotNull(token.accessToken) { "Access token is null" }
                }

                if (timeLeft < 120.seconds && timeLeft > 1.seconds) {
                    tokens[target] = token.refresh()
                    return requireNotNull(tokens[target]?.accessToken) { "Access token is null" }
                }
            }
            val maskinportenToken = texasHttpClient.systemToken("maskinporten", target)
            val newToken = altinnExchange(maskinportenToken.accessToken).toAltinnToken()

            tokens[target] = newToken
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
            val maskinportenToken = texasHttpClient.systemToken("maskinporten", DIALOGPORTEN_TARGET_SCOPE)
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

    companion object {
        const val DIALOGPORTEN_TARGET_SCOPE = "digdir:dialogporten.serviceprovider"
    }
}
