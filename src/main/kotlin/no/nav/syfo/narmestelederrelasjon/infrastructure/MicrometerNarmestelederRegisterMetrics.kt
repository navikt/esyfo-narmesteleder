package no.nav.syfo.narmestelederrelasjon.infrastructure

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import no.nav.syfo.application.metric.METRICS_NS
import no.nav.syfo.application.metric.METRICS_REGISTRY
import no.nav.syfo.narmestelederrelasjon.application.NarmestelederRegisterMetrics

const val NARMESTELEDER_REGISTER_UPSERTED = "${METRICS_NS}_narmesteleder_register_upserted"
const val NARMESTELEDER_REGISTER_INVALID_MESSAGE = "${METRICS_NS}_narmesteleder_register_invalid_message"

class MicrometerNarmestelederRegisterMetrics(registry: MeterRegistry = METRICS_REGISTRY) : NarmestelederRegisterMetrics {
    private val upserted: Counter = Counter.builder(NARMESTELEDER_REGISTER_UPSERTED)
        .description("Counts the number of leesah records written to the narmesteleder register")
        .register(registry)

    private val invalidMessage: Counter = Counter.builder(NARMESTELEDER_REGISTER_INVALID_MESSAGE)
        .description("Counts the number of invalid leesah records skipped during register population")
        .register(registry)

    override fun recordUpserted(count: Int) = upserted.increment(count.toDouble())

    override fun recordInvalid() = invalidMessage.increment()
}
