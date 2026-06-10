package no.nav.syfo.outbox

import io.micrometer.core.instrument.Counter
import no.nav.syfo.application.metric.METRICS_NS
import no.nav.syfo.application.metric.METRICS_REGISTRY

enum class OutboxDispatchResult(val metricValue: String) {
    SENT("sent"),
    RETRY("retry"),
    DEAD("dead"),
    UNKNOWN_ROUTE("unknown_route"),
    DESERIALIZATION_ERROR("deserialization_error"),
    DISPATCH_ERROR("dispatch_error"),
}

class OutboxMetrics {
    fun countDispatch(event: OutboxEvent, result: OutboxDispatchResult) {
        Counter.builder("${METRICS_NS}_outbox_dispatch_total")
            .description("Counts outbox dispatch results by destination and event type")
            .tag("destination", event.metricDestination())
            .tag("event_type", event.metricEventType())
            .tag("result", result.metricValue)
            .register(METRICS_REGISTRY)
            .increment()
    }

    fun countStaleRequeue(count: Int) {
        if (count <= 0) {
            return
        }
        Counter.builder("${METRICS_NS}_outbox_stale_requeue_total")
            .description("Counts outbox events requeued after stale processing claims")
            .register(METRICS_REGISTRY)
            .increment(count.toDouble())
    }
}
