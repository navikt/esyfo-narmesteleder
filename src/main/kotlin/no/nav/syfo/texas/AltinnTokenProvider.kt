package no.nav.syfo.texas

import com.auth0.jwt.JWT
import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import no.nav.syfo.texas.client.TexasHttpClient
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class AltinnTokenProvider(
    private val texasHttpClient: TexasHttpClient,
    private val httpClient: HttpClient,
    private val altinnBaseUrl: String,
) {
    private val tokens: MutableMap<String, AltinnToken> = mutableMapOf()
    private val mutex = Mutex()

    data class AltinnToken(
        val accessToken: String,
        val altinnExpiryTime: Duration,
        val scope: String,
    )

    suspend fun token(target: String): AltinnToken {
        mutex.withLock {
            val token = tokens[target]
            if (token != null) {
                val now = System.currentTimeMillis().milliseconds
                val timeLeft = token.altinnExpiryTime - now

                if (timeLeft > 300.seconds) {
                    return token
                }

                if (timeLeft < 120.seconds && timeLeft > 1.seconds) {
                    tokens[target] = token.refresh()
                    return requireNotNull(tokens[target]) { "Access token is null" }
                }
            }
            val maskinportenToken = texasHttpClient.systemToken("maskinporten", target)
            val newToken = altinnExchange(maskinportenToken.accessToken).toAltinnToken()

            tokens[target] = newToken
            return newToken
        }
    }

    private fun String.toAltinnToken(): AltinnToken {
        val decodedAltinnToken = JWT.decode(this)
        val scope = decodedAltinnToken.claims["scope"]?.asString()
            ?: throw IllegalStateException("Altinn token is missing scope claim")

        return AltinnToken(
            accessToken = this,
            altinnExpiryTime = decodedAltinnToken.expiresAt.time.milliseconds,
            scope = scope,
        )
    }

    suspend fun AltinnToken.refresh(): AltinnToken {
        val res = httpClient
            .get("$altinnBaseUrl/authentication/api/v1/refresh") {
                bearerAuth(accessToken)
            }

        val token = if (!res.status.isSuccess()) {
            val maskinportenToken = texasHttpClient.systemToken("maskinporten", scope)
            altinnExchange(maskinportenToken.accessToken).toAltinnToken()
        } else {
            res.bodyAsText()
                .replace("\"", "")
                .toAltinnToken()
        }

        tokens[scope] = token
        return token
    }

    private suspend fun altinnExchange(token: String): String = httpClient
        .get("$altinnBaseUrl/authentication/api/v1/exchange/maskinporten") {
            bearerAuth(token)
        }.bodyAsText()
        .replace("\"", "")

    companion object {
        const val DIALOGPORTEN_TARGET_SCOPE = "digdir:dialogporten.serviceprovider"
        const val PDP_TARGET_SCOPE = "altinn:authorization/authorize"
    }
}
