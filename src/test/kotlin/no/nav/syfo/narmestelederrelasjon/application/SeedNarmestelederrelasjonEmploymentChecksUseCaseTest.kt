package no.nav.syfo.narmestelederrelasjon.application

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import no.nav.esyfo.observability.testkit.RuntimeLogContract
import no.nav.syfo.logging.withProductionLogs
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class SeedNarmestelederrelasjonEmploymentChecksUseCaseTest :
    FunSpec({
        test("seeds new relations, refreshes the gauges and logs how many were seeded") {
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

            withProductionLogs(SeedNarmestelederrelasjonEmploymentChecksUseCase::class.java) { capture ->
                useCase.execute()

                RuntimeLogContract.forEvents(employmentCheckSeedCompleted).assertValid(capture.records, expectedCount = 1)
                jacksonObjectMapper().readTree(capture.records.single())["seeded_count"].asInt() shouldBe 7
            }
            metrics.refreshed shouldBe listOf(stats)
        }
    })
