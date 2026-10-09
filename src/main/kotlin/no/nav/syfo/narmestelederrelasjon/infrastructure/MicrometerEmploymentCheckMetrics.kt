package no.nav.syfo.narmestelederrelasjon.infrastructure

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.Gauge
import no.nav.syfo.application.metric.METRICS_NS
import no.nav.syfo.application.metric.METRICS_REGISTRY
import no.nav.syfo.narmestelederrelasjon.application.CheckOutcome
import no.nav.syfo.narmestelederrelasjon.application.EmploymentCheckMetrics
import no.nav.syfo.narmestelederrelasjon.application.EmploymentCheckStats
import no.nav.syfo.narmestelederrelasjon.application.EmploymentComparisonResult
import no.nav.syfo.narmestelederrelasjon.application.SourceObservationOutcome
import java.util.concurrent.atomic.AtomicLong

class MicrometerEmploymentCheckMetrics : EmploymentCheckMetrics {
    override fun countCheck(outcome: CheckOutcome) = checks.getValue(outcome).increment()

    override fun countComparison(result: EmploymentComparisonResult) = comparisons.getValue(result).increment()

    override fun countObservation(outcome: SourceObservationOutcome) = observations.getValue(outcome).increment()

    override fun refresh(stats: EmploymentCheckStats) = Gauges.refresh(stats)

    private companion object {
        val checks = CheckOutcome.entries.associateWith {
            counter(
                name = "employment_check_total",
                description = "Counts outcomes of narmestelederrelasjon employment checks",
                tag = "outcome",
                value = it.name.lowercase(),
            )
        }
        val comparisons = EmploymentComparisonResult.entries.associateWith {
            counter(
                name = "employment_check_comparison_total",
                description = "Counts completed employment check comparisons with source revocations",
                tag = "result",
                value = it.name.lowercase(),
            )
        }
        val observations = SourceObservationOutcome.entries.associateWith {
            counter(
                name = "employment_check_source_observation_total",
                description = "Counts recorded, duplicate, stale and failed source revocation observations",
                tag = "outcome",
                value = it.name.lowercase(),
            )
        }

        fun counter(
            name: String,
            description: String,
            tag: String,
            value: String,
        ): Counter = Counter.builder("${METRICS_NS}_$name")
            .description(description)
            .tag(tag, value)
            .register(METRICS_REGISTRY)
    }

    private object Gauges {
        private val onlyShadowLt31d = AtomicLong()
        private val onlyShadowGte31d = AtomicLong()
        private val due = AtomicLong()
        private val firstSweepRemaining = AtomicLong()

        init {
            Gauge.builder("${METRICS_NS}_employment_check_only_shadow", onlyShadowLt31d) { it.get().toDouble() }
                .description("Global count of active shadow revocation candidates without a source observation")
                .tag("age", "lt_31d")
                .register(METRICS_REGISTRY)
            Gauge.builder("${METRICS_NS}_employment_check_only_shadow", onlyShadowGte31d) { it.get().toDouble() }
                .description("Global count of active shadow revocation candidates without a source observation")
                .tag("age", "gte_31d")
                .register(METRICS_REGISTRY)
            Gauge.builder("${METRICS_NS}_employment_check_due", due) { it.get().toDouble() }
                .description("Global count of active relations with READY employment checks due now")
                .register(METRICS_REGISTRY)
            Gauge.builder("${METRICS_NS}_employment_check_first_sweep_remaining", firstSweepRemaining) { it.get().toDouble() }
                .description("Global count of active relations awaiting a first successful employment check")
                .register(METRICS_REGISTRY)
        }

        fun refresh(stats: EmploymentCheckStats) {
            onlyShadowLt31d.set(stats.onlyShadowLt31d)
            onlyShadowGte31d.set(stats.onlyShadowGte31d)
            due.set(stats.due)
            firstSweepRemaining.set(stats.firstSweepRemaining)
        }
    }
}
