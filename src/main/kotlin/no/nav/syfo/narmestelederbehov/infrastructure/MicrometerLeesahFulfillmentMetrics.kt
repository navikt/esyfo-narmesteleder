package no.nav.syfo.narmestelederbehov.infrastructure

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import no.nav.syfo.application.metric.METRICS_NS
import no.nav.syfo.application.metric.METRICS_REGISTRY
import no.nav.syfo.narmestelederbehov.application.LeesahFulfillmentMetrics

const val FULFILL_LINEMANAGER_BY_LEGACY_SYSTEM = "${METRICS_NS}_fulfill_linemanager_requirement_by_legacy_system"

class MicrometerLeesahFulfillmentMetrics(registry: MeterRegistry = METRICS_REGISTRY) : LeesahFulfillmentMetrics {
    private val fulfilledByLegacySystem: Counter = Counter.builder(FULFILL_LINEMANAGER_BY_LEGACY_SYSTEM)
        .description("Counts the number of fulfilled requirements performed by legacy system")
        .register(registry)

    override fun recordFulfilled() = fulfilledByLegacySystem.increment()
}
