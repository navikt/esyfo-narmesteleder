package no.nav.syfo.sykmelding.infrastructure.kafka

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import no.nav.syfo.platform.observability.METRICS_NS
import no.nav.syfo.platform.observability.METRICS_REGISTRY
import no.nav.syfo.sykmelding.application.SykmeldingsregisterRejection

class SykmeldingsregisterMetrics(
    meterRegistry: MeterRegistry = METRICS_REGISTRY,
) {
    private val tombstones: Counter = Counter
        .builder(TOMBSTONES)
        .description("Counts processed sykmeldingsregister tombstones")
        .register(meterRegistry)

    private val deadLetters: Map<SykmeldingsregisterRejection, Counter> =
        SykmeldingsregisterRejection.entries.associateWith { rejection ->
            Counter
                .builder(DEAD_LETTERS)
                .description("Counts dead-lettered sykmeldingsregister records by rejection")
                .tag("rejection", rejection.name)
                .register(meterRegistry)
        }

    fun countTombstones(count: Int) {
        if (count > 0) tombstones.increment(count.toDouble())
    }

    fun countDeadLetter(rejection: SykmeldingsregisterRejection) {
        deadLetters.getValue(rejection).increment()
    }

    companion object {
        const val TOMBSTONES = "${METRICS_NS}_sykmeldingsregister_tombstones"
        const val DEAD_LETTERS = "${METRICS_NS}_sykmeldingsregister_dead_letters"
    }
}
