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
        test("Seeding stops at a partial batch or the run bound and refreshes global gauges in either case") {
            val now = Instant.parse("2026-06-15T12:00:00Z")
            listOf(listOf(2, 1), List(SeedNarmestelederrelasjonEmploymentChecksUseCase.MAX_SEED_ROUNDS) { 2 }).forEach { batches ->
                val repository = RecordingEmploymentReconciliationRepository().apply {
                    seedResults = batches
                    stats = EmploymentCheckStats(1, 2, 3, 4)
                }
                val metrics = RecordingEmploymentCheckMetrics()

                withProductionLogs(SeedNarmestelederrelasjonEmploymentChecksUseCase::class.java) { capture ->
                    SeedNarmestelederrelasjonEmploymentChecksUseCase(
                        repository,
                        EmploymentCheckSettings(seedLimit = 2),
                        Clock.fixed(now, ZoneOffset.UTC),
                        metrics,
                    ).execute()

                    RuntimeLogContract.forEvents(employmentCheckSeedCompleted).assertValid(capture.records, expectedCount = 1)
                    val summary = jacksonObjectMapper().readTree(capture.records.single())
                    summary["level"].asText() shouldBe "INFO"
                    summary["seeded_count"].asLong() shouldBe batches.sum().toLong()
                    summary["rounds"].asInt() shouldBe batches.size
                }

                repository.seedCalls shouldBe batches.size
                repository.statsCalls shouldBe 1
                metrics.stats shouldBe listOf(repository.stats)
            }
        }
    })
