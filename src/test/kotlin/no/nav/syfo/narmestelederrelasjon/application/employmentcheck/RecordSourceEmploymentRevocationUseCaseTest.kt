package no.nav.syfo.narmestelederrelasjon.application.employmentcheck

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import no.nav.esyfo.observability.testkit.RuntimeLogContract
import no.nav.syfo.platform.observability.withProductionLogs
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

private val now = Instant.parse("2026-06-15T12:00:00Z")

class RecordSourceEmploymentRevocationUseCaseTest :
    FunSpec({
        lateinit var repository: RecordingEmploymentReconciliationRepository
        lateinit var metrics: RecordingEmploymentCheckMetrics

        val clock = Clock.fixed(now, ZoneOffset.UTC)
        fun useCase(target: EmploymentReconciliationRepository = repository) = RecordSourceEmploymentRevocationUseCase(target, clock, metrics)

        beforeTest {
            repository = RecordingEmploymentReconciliationRepository()
            metrics = RecordingEmploymentCheckMetrics()
        }

        test("notes a recent revocation once, with the source's revocation time") {
            val id = UUID.randomUUID()
            val revokedAt = now.minusSeconds(60)

            useCase().execute(id, revokedAt)
            useCase().execute(id, revokedAt)

            repository.observed shouldBe mapOf(id to revokedAt)
            metrics.observations shouldBe listOf(SourceObservationOutcome.RECORDED, SourceObservationOutcome.ALREADY_RECORDED)
        }

        test("ignores revocations older than seven days, so a replay does not count") {
            useCase().execute(UUID.randomUUID(), revokedAt = now.minus(SOURCE_OBSERVATION_WINDOW).minusSeconds(1))

            repository.observed shouldBe emptyMap()
            metrics.observations shouldBe listOf(SourceObservationOutcome.IGNORED_STALE)
        }

        test("a database failure is counted and logged without the cause message, and does not reach the consumer") {
            val id = UUID.randomUUID()
            val failing = object : EmploymentReconciliationRepository by repository {
                override fun recordSourceRevocation(narmesteLederId: UUID, revokedAt: Instant): Boolean {
                    error("private-database-canary")
                }
            }

            withProductionLogs(RecordSourceEmploymentRevocationUseCase::class.java) { capture ->
                useCase(failing).execute(id, revokedAt = now)

                RuntimeLogContract.forEvents(
                    employmentCheckSourceObservationFailed,
                    exceptionTypes = setOf("IllegalStateException"),
                ).assertValid(capture.records, expectedCount = 1)
                jacksonObjectMapper().readTree(capture.records.single())["narmesteleder_id"].asText() shouldBe id.toString()
                capture.records.single() shouldNotContain "private-database-canary"
            }
            metrics.observations shouldBe listOf(SourceObservationOutcome.FAILED)
        }
    })
