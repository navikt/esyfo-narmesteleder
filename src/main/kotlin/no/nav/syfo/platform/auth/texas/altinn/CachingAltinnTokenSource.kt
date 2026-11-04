package no.nav.syfo.platform.auth.texas.altinn

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import no.nav.syfo.platform.upstream.UpstreamResult
import java.time.Clock
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Caches valid Altinn tokens and, for a short period, failures per target, so that concurrent and
 * subsequent calls do not repeat a failing Texas/Altinn token exchange.
 */
class CachingAltinnTokenSource(
    private val delegate: AltinnTokenSource,
    private val clock: Clock = Clock.systemUTC(),
    private val failureTtl: Duration = DEFAULT_FAILURE_TTL,
) : AltinnTokenSource {
    private val targets = ConcurrentHashMap<String, TargetState>()

    override suspend fun token(target: String): UpstreamResult<AltinnToken> {
        val state = targets.computeIfAbsent(target) { TargetState() }
        return state.mutex.withLock {
            val now = clock.millis().milliseconds
            state.freshToken(now)?.let { return UpstreamResult.Success(it) }
            state.activeFailure(now)?.let { return it }
            refresh(state, target, now)
        }
    }

    private suspend fun refresh(state: TargetState, target: String, now: Duration): UpstreamResult<AltinnToken> = when (val result = delegate.token(target)) {
        is UpstreamResult.Success -> result.also { state.store(it.value) }
        is UpstreamResult.Failure -> state.usableToken(now)
            ?.let { UpstreamResult.Success(it) }
            ?: result.also { state.remember(it, now + failureTtl) }
    }

    // Fields are only read and written while holding [mutex].
    private class TargetState {
        val mutex = Mutex()
        private var token: AltinnToken? = null
        private var failure: UpstreamResult.Failure? = null
        private var failureExpiry: Duration = Duration.ZERO

        fun freshToken(now: Duration): AltinnToken? = token?.takeIf { it.altinnExpiryTime - now > REFRESH_THRESHOLD }

        fun usableToken(now: Duration): AltinnToken? = token?.takeIf { it.altinnExpiryTime > now }

        fun activeFailure(now: Duration): UpstreamResult.Failure? = failure?.takeIf { now < failureExpiry }

        fun store(newToken: AltinnToken) {
            token = newToken
            failure = null
        }

        fun remember(newFailure: UpstreamResult.Failure, expiry: Duration) {
            failure = newFailure
            failureExpiry = expiry
        }
    }

    companion object {
        val DEFAULT_FAILURE_TTL = 10.seconds
        private val REFRESH_THRESHOLD = 300.seconds
    }
}
