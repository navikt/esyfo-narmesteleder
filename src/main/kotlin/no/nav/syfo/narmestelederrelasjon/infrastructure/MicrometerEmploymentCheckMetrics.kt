package no.nav.syfo.narmestelederrelasjon.infrastructure

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import no.nav.syfo.application.metric.METRICS_NS
import no.nav.syfo.application.metric.METRICS_REGISTRY
import no.nav.syfo.narmestelederrelasjon.application.ClaimedCheckOutcome
import no.nav.syfo.narmestelederrelasjon.application.EmploymentCheckMetrics
import no.nav.syfo.narmestelederrelasjon.application.EmploymentCheckStats
import java.util.concurrent.atomic.AtomicReference

const val EMPLOYMENT_CHECK_TOTAL = "${METRICS_NS}_employment_check_total"
const val EMPLOYMENT_CHECK_ONLY_SHADOW = "${METRICS_NS}_employment_check_only_shadow"
const val EMPLOYMENT_CHECK_DUE = "${METRICS_NS}_employment_check_due"

class MicrometerEmploymentCheckMetrics(private val registry: MeterRegistry = METRICS_REGISTRY) : EmploymentCheckMetrics {
    private val checks = ClaimedCheckOutcome.entries.associateWith { outcome ->
        Counter.builder(EMPLOYMENT_CHECK_TOTAL)
            .description("Counts employment checks by outcome")
            .tag("outcome", outcome.name.lowercase())
            .register(registry)
    }
    private val latestStats = AtomicReference<EmploymentCheckStats>()

    override fun recordCheck(outcome: ClaimedCheckOutcome) = checks.getValue(outcome).increment()

    override fun refresh(stats: EmploymentCheckStats) {
        // Registered on the first refresh, so the gauges never report a count the database has not given.
        if (latestStats.getAndSet(stats) == null) registerGauges()
    }

    private fun registerGauges() {
        val onlyShadow = "Active relations the shadow check would revoke, by age of the first decision"
        gauge(EMPLOYMENT_CHECK_ONLY_SHADOW, onlyShadow, "age", "lt_31d") { it.onlyShadowUnder31Days }
        gauge(EMPLOYMENT_CHECK_ONLY_SHADOW, onlyShadow, "age", "gte_31d") { it.onlyShadow31DaysOrMore }
        gauge(EMPLOYMENT_CHECK_DUE, "Active relations whose employment check is due") { it.due }
    }

    private fun gauge(name: String, description: String, vararg tags: String, value: (EmploymentCheckStats) -> Long) {
        Gauge.builder(name, latestStats) { value(it.get()).toDouble() }
            .description(description)
            .tags(*tags)
            .register(registry)
    }
}
