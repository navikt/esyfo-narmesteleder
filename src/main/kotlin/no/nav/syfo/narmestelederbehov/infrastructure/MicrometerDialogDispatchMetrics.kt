package no.nav.syfo.narmestelederbehov.infrastructure

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import no.nav.syfo.narmestelederbehov.application.DialogDispatchMetrics
import no.nav.syfo.narmestelederbehov.application.DialogDispatchRepository
import no.nav.syfo.platform.observability.METRICS_NS
import no.nav.syfo.platform.observability.METRICS_REGISTRY
import java.time.Clock
import java.util.concurrent.atomic.AtomicReference

class MicrometerDialogDispatchMetrics(
    private val repository: DialogDispatchRepository,
    registry: MeterRegistry = METRICS_REGISTRY,
    private val clock: Clock = Clock.systemUTC(),
) : DialogDispatchMetrics {
    private val values = listOf("pending", "parked", "oldest_pending_timestamp_seconds", "observed_timestamp_seconds", "enabled")
        .associateWith { AtomicReference(0.0) }
    private val outcomes = listOf("done", "retry", "parked").associateWith {
        Counter.builder("${METRICS_NS}_dialog_dispatch_attempts_total").tag("outcome", it).register(registry)
    }

    init {
        values.forEach { (name, value) ->
            Gauge.builder("${METRICS_NS}_dialog_dispatch_$name", value) { it.get() }.register(registry)
        }
    }

    fun enabled(enabled: Boolean) {
        values.getValue("enabled").set(if (enabled) 1.0 else 0.0)
    }

    override fun record(outcome: String) = outcomes.getValue(outcome).increment()

    suspend fun refresh() {
        val now = clock.instant()
        val stats = repository.stats()
        values.getValue("pending").set(stats.pending.toDouble())
        values.getValue("parked").set(stats.parked.toDouble())
        values.getValue("oldest_pending_timestamp_seconds").set(stats.oldestPending?.epochSecond?.toDouble() ?: 0.0)
        values.getValue("observed_timestamp_seconds").set(now.epochSecond.toDouble())
    }
}
