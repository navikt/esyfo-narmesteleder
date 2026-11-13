package no.nav.syfo.narmestelederrelasjon.infrastructure

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import no.nav.syfo.narmestelederrelasjon.application.employmentcheck.ClaimedCheckOutcome
import no.nav.syfo.narmestelederrelasjon.application.employmentcheck.EmploymentCheckStats
import no.nav.syfo.platform.observability.METRICS_NS

class MicrometerEmploymentCheckMetricsTest :
    FunSpec({
        test("counts checks by outcome and reports the backlog gauge from the first refresh") {
            val registry = SimpleMeterRegistry()
            val metrics = MicrometerEmploymentCheckMetrics(registry)
            registry.find(EMPLOYMENT_CHECK_DUE).gauge() shouldBe null

            metrics.recordCheck(ClaimedCheckOutcome.REVOKED)
            metrics.recordCheck(ClaimedCheckOutcome.REVOKED)
            metrics.recordCheck(ClaimedCheckOutcome.TIMEOUT)
            metrics.recordCheck(ClaimedCheckOutcome.STALE)
            metrics.refresh(EmploymentCheckStats(due = 3))

            ClaimedCheckOutcome.entries.associateWith {
                registry.get(EMPLOYMENT_CHECK_TOTAL).tag("outcome", it.name.lowercase()).counter().count()
            } shouldBe mapOf(
                ClaimedCheckOutcome.VALID to 0.0,
                ClaimedCheckOutcome.REVOKED to 2.0,
                ClaimedCheckOutcome.STALE to 1.0,
                ClaimedCheckOutcome.FAILED to 0.0,
                ClaimedCheckOutcome.CLAIM_LOST to 0.0,
                ClaimedCheckOutcome.TIMEOUT to 1.0,
            )
            registry.get(EMPLOYMENT_CHECK_DUE).gauge().value() shouldBe 3.0
        }

        test("does not expose shadow or source-comparison metrics after cutover") {
            val registry = SimpleMeterRegistry()
            val metrics = MicrometerEmploymentCheckMetrics(registry)
            metrics.refresh(EmploymentCheckStats(due = 3))

            listOf(
                "${METRICS_NS}_employment_check_comparison_total",
                "${METRICS_NS}_employment_check_source_observation_total",
                "${METRICS_NS}_employment_check_only_shadow",
            ).forEach { name -> registry.find(name).meters().isEmpty() shouldBe true }
            registry.find(EMPLOYMENT_CHECK_TOTAL).tag("outcome", "would_revoke").meters().isEmpty() shouldBe true
        }
    })
