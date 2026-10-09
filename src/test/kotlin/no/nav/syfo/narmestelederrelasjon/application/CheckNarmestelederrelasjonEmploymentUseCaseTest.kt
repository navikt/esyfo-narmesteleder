package no.nav.syfo.narmestelederrelasjon.application

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import no.nav.esyfo.observability.testkit.RuntimeLogContract
import no.nav.syfo.ident.OrganizationNumber
import no.nav.syfo.ident.PersonIdent
import no.nav.syfo.logging.withProductionLogs
import no.nav.syfo.narmestelederrelasjon.domain.Employment
import no.nav.syfo.platform.upstream.UpstreamFailure
import no.nav.syfo.platform.upstream.UpstreamFailureStage
import no.nav.syfo.platform.upstream.UpstreamName
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

private val checkNow = Instant.parse("2026-01-31T12:00:00Z")
private val checkClaim = ClaimedEmploymentCheck(
    UUID.randomUUID(),
    OrganizationNumber("123456789"),
    PersonIdent("12345678901"),
    UUID.randomUUID(),
    checkNow,
)

class CheckNarmestelederrelasjonEmploymentUseCaseTest :
    FunSpec({
        test("KEEP completes VALID one UTC calendar month later, clamping month end") {
            val repository = checkRepository()
            val metrics = RecordingEmploymentCheckMetrics()
            val useCase = CheckNarmestelederrelasjonEmploymentUseCase(
                repository,
                { EmploymentHistoryResult.Found(listOf(Employment(checkClaim.organizationNumber, null))) },
                EmploymentCheckSettings(),
                Clock.fixed(checkNow, ZoneOffset.UTC),
                metrics,
            )

            useCase.execute() shouldBe CheckBatchResult(valid = 1)
            repository.completed.single() shouldBe RecordedCompletion(
                checkClaim,
                EmploymentCheckOutcome.VALID,
                Instant.parse("2026-02-28T12:00:00Z"),
                checkNow,
            )
            repository.claimArguments shouldBe Triple(50, 15.minutes, checkNow)
            metrics.checks shouldBe listOf(CheckOutcome.VALID)
        }

        test("REVOKE records WOULD_REVOKE and schedules a month later without publishing") {
            val repository = checkRepository()
            val useCase = CheckNarmestelederrelasjonEmploymentUseCase(
                repository,
                { EmploymentHistoryResult.Found(emptyList()) },
                EmploymentCheckSettings(),
                Clock.fixed(checkNow, ZoneOffset.UTC),
                RecordingEmploymentCheckMetrics(),
            )

            useCase.execute() shouldBe CheckBatchResult(wouldRevoke = 1)
            repository.completed.single().outcome shouldBe EmploymentCheckOutcome.WOULD_REVOKE
            repository.completed.single().nextCheck shouldBe Instant.parse("2026-02-28T12:00:00Z")
        }

        test("Unavailable records FAILED with one-day backoff and exactly one sanitized upstream event") {
            val repository = checkRepository()
            val useCase = CheckNarmestelederrelasjonEmploymentUseCase(
                repository,
                { EmploymentHistoryResult.Unavailable(checkFailure) },
                EmploymentCheckSettings(),
                Clock.fixed(checkNow, ZoneOffset.UTC),
                RecordingEmploymentCheckMetrics(),
            )
            withProductionLogs(CheckNarmestelederrelasjonEmploymentUseCase::class.java) { capture ->
                useCase.execute() shouldBe CheckBatchResult(failed = 1)
                repository.completed.single().outcome shouldBe EmploymentCheckOutcome.FAILED
                repository.completed.single().nextCheck shouldBe checkNow.plusSeconds(86_400)
                RuntimeLogContract.forEvents(
                    employmentCheckFailed,
                    employmentCheckBatchCompleted,
                    exceptionTypes = setOf("IllegalStateException"),
                ).assertValid(capture.records, expectedCount = 2)
                val event = capture.records.map { jacksonObjectMapper().readTree(it) }
                    .single { it["event_type"].asText() == "employment_check_failed" }
                event["event_type"].asText() shouldBe "employment_check_failed"
                event["level"].asText() shouldBe "WARN"
                event["narmesteleder_id"].asText() shouldBe checkClaim.narmesteLederId.toString()
                event["upstream"].asText() shouldBe "aareg"
                event["upstream_status"].asInt() shouldBe 503
                event["failure_stage"].asText() shouldBe "request"
                capture.records.forEach {
                    it shouldNotContain checkClaim.employeeIdent.value
                    it shouldNotContain checkClaim.organizationNumber.value
                    it shouldNotContain "private-response-canary"
                }
            }
        }

        test("Lost CAS counts only claim_lost without comparison or failure logging") {
            val repository = checkRepository().apply {
                completeSucceeds = false
                claims = listOf(checkClaim.copy(sourceRevocationObservedAt = checkNow.minusSeconds(60)))
            }
            val metrics = RecordingEmploymentCheckMetrics()
            val useCase = CheckNarmestelederrelasjonEmploymentUseCase(
                repository,
                { EmploymentHistoryResult.Unavailable(checkFailure) },
                EmploymentCheckSettings(),
                Clock.fixed(checkNow, ZoneOffset.UTC),
                metrics,
            )
            withProductionLogs(CheckNarmestelederrelasjonEmploymentUseCase::class.java) { capture ->
                useCase.execute() shouldBe CheckBatchResult(claimLost = 1)
                repository.completed.size shouldBe 1
                RuntimeLogContract.forEvents(employmentCheckBatchCompleted).assertValid(capture.records, expectedCount = 1)
                metrics.checks shouldBe listOf(CheckOutcome.CLAIM_LOST)
                metrics.comparisons shouldBe emptyList()
            }
        }

        test("Expired and slow claims time out without completion; cancellation and unexpected failures propagate") {
            runTest {
                val repository = checkRepository().apply {
                    claims = listOf(checkClaim.copy(claimedAt = checkNow.minusSeconds(2)), checkClaim)
                }
                var lookups = 0
                fun useCase(lookup: EmploymentHistoryLookup) = CheckNarmestelederrelasjonEmploymentUseCase(
                    repository,
                    lookup,
                    EmploymentCheckSettings(lease = 31.seconds),
                    Clock.fixed(checkNow, ZoneOffset.UTC),
                    RecordingEmploymentCheckMetrics(),
                )
                useCase {
                    lookups++
                    delay(2.seconds)
                    EmploymentHistoryResult.Found(emptyList())
                }.execute() shouldBe CheckBatchResult(timeout = 2)
                lookups shouldBe 1
                repository.completed shouldBe emptyList()
                repository.claims = listOf(checkClaim)
                shouldThrow<CancellationException> { useCase { throw CancellationException("Stopping") }.execute() }
                shouldThrow<IllegalStateException> { useCase { error("Unexpected") }.execute() }
                repository.completed shouldBe emptyList()
            }
        }

        test("Successful observed checks agree or disagree while FAILED checks remain unclassified") {
            val repository = checkRepository().apply {
                claims = List(3) {
                    checkClaim.copy(
                        narmesteLederId = UUID.randomUUID(),
                        sourceRevocationObservedAt = checkNow.minusSeconds(60),
                    )
                }
            }
            val histories = listOf(
                EmploymentHistoryResult.Found(emptyList()),
                EmploymentHistoryResult.Found(listOf(Employment(checkClaim.organizationNumber, null))),
                EmploymentHistoryResult.Unavailable(checkFailure),
            ).iterator()
            val metrics = RecordingEmploymentCheckMetrics()
            val useCase = CheckNarmestelederrelasjonEmploymentUseCase(
                repository,
                { histories.next() },
                EmploymentCheckSettings(),
                Clock.fixed(checkNow, ZoneOffset.UTC),
                metrics,
            )
            withProductionLogs(CheckNarmestelederrelasjonEmploymentUseCase::class.java) { capture ->
                useCase.execute() shouldBe CheckBatchResult(valid = 1, wouldRevoke = 1, failed = 1)
                metrics.comparisons shouldBe listOf(EmploymentComparisonResult.AGREE, EmploymentComparisonResult.DISAGREE)
                RuntimeLogContract.forEvents(
                    employmentCheckComparisonMismatch,
                    employmentCheckFailed,
                    employmentCheckBatchCompleted,
                    exceptionTypes = setOf("IllegalStateException"),
                ).assertValid(capture.records, expectedCount = 3)
                val events = capture.records.map { jacksonObjectMapper().readTree(it) }
                events.single { it["event_type"].asText() == "employment_check_comparison_mismatch" }.also {
                    it["level"].asText() shouldBe "WARN"
                    it["narmesteleder_id"].asText() shouldBe repository.claims[1].narmesteLederId.toString()
                }
                events.single { it["event_type"].asText() == "employment_check_batch_completed" }.also {
                    it["level"].asText() shouldBe "INFO"
                    it["valid"].asInt() shouldBe 1
                    it["would_revoke"].asInt() shouldBe 1
                    it["failed"].asInt() shouldBe 1
                }
                capture.records.forEach {
                    it shouldNotContain checkClaim.employeeIdent.value
                    it shouldNotContain checkClaim.organizationNumber.value
                    it shouldNotContain "private-response-canary"
                }
                repository.claims = emptyList()
                useCase.execute() shouldBe CheckBatchResult()
                capture.records.size shouldBe 3
            }
        }
    })

private val checkFailure = UpstreamFailure(
    UpstreamName("aareg"),
    UpstreamFailureStage.REQUEST,
    503,
    IllegalStateException("private-response-canary ${checkClaim.employeeIdent.value} ${checkClaim.organizationNumber.value}"),
)

private fun checkRepository() = RecordingEmploymentReconciliationRepository().apply { claims = listOf(checkClaim) }
