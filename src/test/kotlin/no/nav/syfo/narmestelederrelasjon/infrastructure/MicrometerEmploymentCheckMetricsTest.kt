package no.nav.syfo.narmestelederrelasjon.infrastructure

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import no.nav.syfo.narmestelederrelasjon.application.ClaimedCheckOutcome
import no.nav.syfo.narmestelederrelasjon.application.EmploymentCheckStats
import no.nav.syfo.narmestelederrelasjon.application.EmploymentComparisonResult
import no.nav.syfo.narmestelederrelasjon.application.SourceObservationOutcome

class MicrometerEmploymentCheckMetricsTest :
    FunSpec({
        test("counts checks by outcome and reports the gauges from the first refresh") {
            val registry = SimpleMeterRegistry()
            val metrics = MicrometerEmploymentCheckMetrics(registry)
            registry.find(EMPLOYMENT_CHECK_DUE).gauge() shouldBe null

            metrics.recordCheck(ClaimedCheckOutcome.WOULD_REVOKE)
            metrics.recordCheck(ClaimedCheckOutcome.WOULD_REVOKE)
            metrics.recordCheck(ClaimedCheckOutcome.TIMEOUT)
            metrics.refresh(EmploymentCheckStats(onlyShadowUnder31Days = 1, onlyShadow31DaysOrMore = 2, due = 3))

            ClaimedCheckOutcome.entries.associateWith {
                registry.get(EMPLOYMENT_CHECK_TOTAL).tag("outcome", it.name.lowercase()).counter().count()
            } shouldBe mapOf(
                ClaimedCheckOutcome.VALID to 0.0,
                ClaimedCheckOutcome.WOULD_REVOKE to 2.0,
                ClaimedCheckOutcome.FAILED to 0.0,
                ClaimedCheckOutcome.CLAIM_LOST to 0.0,
                ClaimedCheckOutcome.TIMEOUT to 1.0,
            )
            registry.get(EMPLOYMENT_CHECK_ONLY_SHADOW).tag("age", "lt_31d").gauge().value() shouldBe 1.0
            registry.get(EMPLOYMENT_CHECK_ONLY_SHADOW).tag("age", "gte_31d").gauge().value() shouldBe 2.0
            registry.get(EMPLOYMENT_CHECK_DUE).gauge().value() shouldBe 3.0
        }

        test("counts comparisons by result and source observations by outcome") {
            val registry = SimpleMeterRegistry()
            val metrics = MicrometerEmploymentCheckMetrics(registry)

            metrics.recordComparison(EmploymentComparisonResult.DISAGREE)
            metrics.recordObservation(SourceObservationOutcome.FAILED)

            registry.get(EMPLOYMENT_CHECK_COMPARISON_TOTAL).tag("result", "disagree").counter().count() shouldBe 1.0
            registry.get(EMPLOYMENT_CHECK_COMPARISON_TOTAL).tag("result", "agree").counter().count() shouldBe 0.0
            registry.get(EMPLOYMENT_CHECK_SOURCE_OBSERVATION_TOTAL).tag("outcome", "failed").counter().count() shouldBe 1.0
            registry.get(EMPLOYMENT_CHECK_SOURCE_OBSERVATION_TOTAL).tag("outcome", "recorded").counter().count() shouldBe 0.0
        }
    })
