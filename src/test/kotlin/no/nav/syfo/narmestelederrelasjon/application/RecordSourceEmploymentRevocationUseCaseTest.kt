package no.nav.syfo.narmestelederrelasjon.application

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import no.nav.esyfo.observability.testkit.RuntimeLogContract
import no.nav.syfo.logging.withProductionLogs
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException

class RecordSourceEmploymentRevocationUseCaseTest :
    FunSpec({
        val now = Instant.parse("2026-06-15T12:00:00Z")

        test("Recent observations are recorded once and redeliveries are counted as already recorded") {
            val repository = RecordingEmploymentReconciliationRepository()
            val metrics = RecordingEmploymentCheckMetrics()
            val useCase = RecordSourceEmploymentRevocationUseCase(repository, Clock.fixed(now, ZoneOffset.UTC), metrics)
            val id = UUID.randomUUID()
            val observedAt = now.minusSeconds(60)

            repeat(2) { useCase.execute(id, observedAt) }

            repository.observations shouldBe List(2) { Triple(id, observedAt, now) }
            metrics.observations shouldBe listOf(SourceObservationOutcome.RECORDED, SourceObservationOutcome.ALREADY_RECORDED)
            metrics.comparisons shouldBe emptyList()
        }

        test("Observations older than seven days are ignored without accessing the repository") {
            val repository = RecordingEmploymentReconciliationRepository()
            val metrics = RecordingEmploymentCheckMetrics()
            val useCase = RecordSourceEmploymentRevocationUseCase(repository, Clock.fixed(now, ZoneOffset.UTC), metrics)

            useCase.execute(UUID.randomUUID(), now.minus(SOURCE_OBSERVATION_WINDOW).minusNanos(1))

            repository.observations shouldBe emptyList()
            metrics.observations shouldBe listOf(SourceObservationOutcome.IGNORED_STALE)
            useCase.execute(UUID.randomUUID(), now.minus(SOURCE_OBSERVATION_WINDOW))
            repository.observations.size shouldBe 1
            metrics.observations.last() shouldBe SourceObservationOutcome.RECORDED
        }

        test("Repository failure is counted and logged once without leaking the cause message or failing observation") {
            val id = UUID.randomUUID()
            val privacyCanary = "private-observation-canary"
            var failure: Exception = IllegalStateException("$privacyCanary 12345678901 123456789")
            val recording = RecordingEmploymentReconciliationRepository()
            val repository = object : EmploymentReconciliationRepository by recording {
                override fun recordSourceRevocation(
                    narmesteLederId: UUID,
                    observedAt: Instant,
                    now: Instant,
                ): Boolean = throw failure
            }
            val metrics = RecordingEmploymentCheckMetrics()
            val useCase = RecordSourceEmploymentRevocationUseCase(repository, Clock.fixed(now, ZoneOffset.UTC), metrics)

            withProductionLogs(RecordSourceEmploymentRevocationUseCase::class.java) { capture ->
                useCase.execute(id, now)

                metrics.observations shouldBe listOf(SourceObservationOutcome.FAILED)
                metrics.comparisons shouldBe emptyList()
                RuntimeLogContract.forEvents(
                    employmentCheckSourceObservationFailed,
                    exceptionTypes = setOf("IllegalStateException"),
                ).assertValid(capture.records, expectedCount = 1)
                val event = jacksonObjectMapper().readTree(capture.records.single())
                event["level"].asText() shouldBe "WARN"
                event["event_type"].asText() shouldBe "employment_check_source_observation_failed"
                event["narmesteleder_id"].asText() shouldBe id.toString()
                capture.records.single() shouldNotContain privacyCanary
                capture.records.single() shouldNotContain "12345678901"
                capture.records.single() shouldNotContain "123456789"
                failure = CancellationException("Stopping")
                shouldThrow<CancellationException> { useCase.execute(id, now) }
                metrics.observations shouldBe listOf(SourceObservationOutcome.FAILED)
                capture.records.size shouldBe 1
            }
        }
    })
