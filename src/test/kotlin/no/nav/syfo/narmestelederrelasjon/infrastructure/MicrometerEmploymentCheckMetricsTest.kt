package no.nav.syfo.narmestelederrelasjon.infrastructure

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import no.nav.syfo.application.metric.METRICS_NS
import no.nav.syfo.application.metric.METRICS_REGISTRY
import no.nav.syfo.narmestelederrelasjon.application.CheckOutcome
import no.nav.syfo.narmestelederrelasjon.application.EmploymentCheckStats
import no.nav.syfo.narmestelederrelasjon.application.EmploymentComparisonResult
import no.nav.syfo.narmestelederrelasjon.application.SourceObservationOutcome

class MicrometerEmploymentCheckMetricsTest :
    FunSpec({
        test("The adapter describes bounded counters and registers global gauges only when refreshed") {
            val metrics = MicrometerEmploymentCheckMetrics()
            val gaugeNames = listOf("only_shadow", "due", "first_sweep_remaining")
            gaugeNames.forEach {
                METRICS_REGISTRY.find("${METRICS_NS}_employment_check_$it").gauge() shouldBe null
            }
            CheckOutcome.entries.forEach { outcome ->
                val counter = METRICS_REGISTRY.get("${METRICS_NS}_employment_check_total")
                    .tag("outcome", outcome.name.lowercase()).counter()
                val before = counter.count()
                metrics.countCheck(outcome)
                counter.count() shouldBe before + 1
            }
            EmploymentComparisonResult.entries.forEach { result ->
                val counter = METRICS_REGISTRY.get("${METRICS_NS}_employment_check_comparison_total")
                    .tag("result", result.name.lowercase()).counter()
                val before = counter.count()
                metrics.countComparison(result)
                counter.count() shouldBe before + 1
            }
            SourceObservationOutcome.entries.forEach { outcome ->
                val counter = METRICS_REGISTRY.get("${METRICS_NS}_employment_check_source_observation_total")
                    .tag("outcome", outcome.name.lowercase()).counter()
                val before = counter.count()
                metrics.countObservation(outcome)
                counter.count() shouldBe before + 1
            }
            metrics.refresh(EmploymentCheckStats(1, 2, 3, 4))
            METRICS_REGISTRY.get("${METRICS_NS}_employment_check_only_shadow")
                .tag("age", "lt_31d").gauge().value() shouldBe 1.0
            METRICS_REGISTRY.get("${METRICS_NS}_employment_check_only_shadow")
                .tag("age", "gte_31d").gauge().value() shouldBe 2.0
            METRICS_REGISTRY.get("${METRICS_NS}_employment_check_due").gauge().value() shouldBe 3.0
            METRICS_REGISTRY.get("${METRICS_NS}_employment_check_first_sweep_remaining").gauge().value() shouldBe 4.0
            METRICS_REGISTRY.meters.filter { it.id.name.startsWith("${METRICS_NS}_employment_check_") }.forEach {
                it.id.description.isNullOrBlank() shouldBe false
            }
            metrics.refresh(EmploymentCheckStats(0, 0, 0, 0))
        }
    })
