package no.nav.syfo.platform.observability

import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * Self-reported liveness signal for a single background loop, such as a Kafka consumer.
 * The loop calls [record] every round, including idle ones, so a quiet loop
 * is not mistaken for a dead one. [isAlive] reports stale once no round has run within [staleThreshold]
 * — e.g. the loop has hung or exited. Never couple this to broker availability, downstream health,
 * processing success or lag; those belong in metrics and alerts.
 *
 * State is a lock-free [AtomicReference]: the loop coroutine writes, and the signal can be read independently.
 * [Clock] is injectable so the stale threshold can be unit-tested with a fake clock.
 */
class Heartbeat(
    private val clock: Clock = Clock.System,
    private val staleThreshold: Duration = DEFAULT_STALE_THRESHOLD,
) {
    private val lastBeat = AtomicReference(clock.now())

    fun record() {
        lastBeat.set(clock.now())
    }

    fun isAlive(): Boolean = (clock.now() - lastBeat.get()) <= staleThreshold

    companion object {
        // Larger than loop frequency plus max processing time so a slow-but-healthy round does not
        // appear stale; safe default for low-volume loops.
        val DEFAULT_STALE_THRESHOLD: Duration = 5.minutes
    }
}
