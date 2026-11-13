package no.nav.syfo.narmestelederrelasjon.infrastructure

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import no.nav.syfo.narmestelederrelasjon.application.employmentcheck.ClaimedCheckOutcome
import no.nav.syfo.narmestelederrelasjon.application.employmentcheck.EmploymentCheckMetrics
import no.nav.syfo.narmestelederrelasjon.application.employmentcheck.EmploymentCheckStats
import no.nav.syfo.platform.observability.METRICS_NS
import no.nav.syfo.platform.observability.METRICS_REGISTRY
import java.util.concurrent.atomic.AtomicReference

const val EMPLOYMENT_CHECK_TOTAL = "${METRICS_NS}_employment_check_total"
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
        // Registered on the first refresh, so the gauge never reports a count the database has not given.
        if (latestStats.getAndSet(stats) == null) {
            Gauge.builder(EMPLOYMENT_CHECK_DUE, latestStats) { it.get().due.toDouble() }
                .description("Active relations whose employment check is due")
                .register(registry)
        }
    }
}
