package no.nav.syfo.platform.auth.texas

import no.nav.syfo.platform.upstream.UpstreamResult
import kotlin.time.Duration

data class AltinnToken(
    val accessToken: String,
    val altinnExpiryTime: Duration,
)

interface AltinnTokenSource {
    suspend fun token(target: String): UpstreamResult<AltinnToken>
}
