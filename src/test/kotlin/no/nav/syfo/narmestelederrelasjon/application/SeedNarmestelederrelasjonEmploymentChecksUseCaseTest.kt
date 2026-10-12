package no.nav.syfo.narmestelederrelasjon.application

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class SeedNarmestelederrelasjonEmploymentChecksUseCaseTest :
    FunSpec({
        test("seeds new relations and refreshes the gauges") {
            val stats = EmploymentCheckStats(onlyShadowUnder31Days = 1, onlyShadow31DaysOrMore = 2, due = 3)
            val repository = RecordingEmploymentReconciliationRepository().apply {
                seeded = 7
                this.stats = stats
            }
            val metrics = RecordingEmploymentCheckMetrics()
            val useCase = SeedNarmestelederrelasjonEmploymentChecksUseCase(
                repository,
                EmploymentCheckSettings(),
                Clock.fixed(Instant.parse("2026-06-15T12:00:00Z"), ZoneOffset.UTC),
                metrics,
            )

            useCase.execute()

            metrics.refreshed shouldBe listOf(stats)
        }
    })
