package no.nav.syfo.texas

import com.auth0.jwt.JWT
import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import no.nav.syfo.texas.client.TexasHttpClient


class TokenProvider(
    private val texasHttpClient: TexasHttpClient,
    private val httpClient: HttpClient,
) {
    private val tokens = mutableMapOf<String, Token>()
    private val mutex = Mutex()

    private data class Token(
        val altinnToken: String? = null,
        val maskinportenToken: String? = null,
        val altinnExpiryTime: Duration? = null,
        val texasExpiryTime: Duration? = null,
        val target: String? = null
    )

    suspend fun maskinportenSystemToken(target: String): String {
        mutex.withLock {
            val token = tokens[target] ?: return systemToken("maskinoprten", target)

            val now = TimeSource.Monotonic.markNow()
            val expiryTime = token.texasExpiryTime ?: Duration.ZERO
            val timeLeft = expiryTime - now.elapsedNow()

            return if (timeLeft <= 5.seconds) {
                systemToken("maskinporten", target)
            } else {
                requireNotNull(token.maskinportenToken) { "Maskinporten token is null" }
            }
        }
    }

    suspend fun altinnToken(baseUrl: String, target: String): String {
        mutex.withLock {
            tokens[target] ?: systemToken("maskinporten", target)
            val token = requireNotNull(tokens[target])
            val maskinportenToken = requireNotNull(token.maskinportenToken)

            if (token.altinnToken != null && token.altinnExpiryTime != null) {
                val now = TimeSource.Monotonic.markNow()
                val timeLeft = token.altinnExpiryTime - now.elapsedNow()

                if (timeLeft > 5.seconds) {
                    return token.altinnToken
                }
            }
            val altinnToken = altinnExchange(baseUrl, maskinportenToken)

            val decodedAltinnToken = JWT.decode(altinnToken)
            val altinnExpiryTime = decodedAltinnToken.expiresAt.time.milliseconds
            tokens[target] = token.copy(
                altinnToken = altinnToken,
                altinnExpiryTime = altinnExpiryTime
            )
            return altinnToken
        }
    }

    private suspend fun altinnExchange(baseUrl: String, token: String): String =
        httpClient
            .get("$baseUrl/authentication/api/v1/exchange/maskinporten") {
                bearerAuth(token)
            }.bodyAsText()
            .replace("\"", "")

    private suspend fun systemToken(identityProvider: String, target: String): String =
        texasHttpClient.systemToken(identityProvider, target).let {
            val decodedToken = JWT.decode(it.accessToken)
            tokens[target] = Token(
                maskinportenToken = it.accessToken,
                texasExpiryTime = decodedToken.expiresAt.time.milliseconds,
                target = target
            )
            return it.accessToken
        }
}
