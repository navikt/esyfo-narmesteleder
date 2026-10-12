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
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private val now = Instant.parse("2026-01-31T12:00:00Z")
private val claim = ClaimedEmploymentCheck(
    narmesteLederId = UUID.randomUUID(),
    organizationNumber = OrganizationNumber("123456789"),
    employeeIdent = PersonIdent("12345678901"),
    claimToken = UUID.randomUUID(),
    claimedAt = now,
)
private val aaregFailure = UpstreamFailure(
    UpstreamName("aareg"),
    UpstreamFailureStage.REQUEST,
    503,
    IllegalStateException("private-response-canary ${claim.employeeIdent.value} ${claim.organizationNumber.value}"),
)

class CheckNarmestelederrelasjonEmploymentUseCaseTest :
    FunSpec({
        lateinit var repository: RecordingEmploymentReconciliationRepository
        lateinit var metrics: RecordingEmploymentCheckMetrics

        fun useCase(
            settings: EmploymentCheckSettings = EmploymentCheckSettings(),
            clock: Clock = Clock.fixed(now, ZoneOffset.UTC),
            lookup: EmploymentHistoryLookup,
        ) = CheckNarmestelederrelasjonEmploymentUseCase(repository, lookup, settings, clock, metrics)

        beforeTest {
            repository = RecordingEmploymentReconciliationRepository().apply { claims = listOf(claim) }
            metrics = RecordingEmploymentCheckMetrics()
        }

        test("keeps a relation with employment in the organization and checks it again one calendar month later") {
            val employment = Employment(claim.organizationNumber, null)

            useCase { EmploymentHistoryResult.Found(listOf(employment)) }.execute()

            // January 31 plus one month is the last day of February.
            repository.completed.single() shouldBe
                RecordedCompletion(claim, EmploymentCheckOutcome.VALID, Instant.parse("2026-02-28T12:00:00Z"), now)
            metrics.checks shouldBe listOf(ClaimedCheckOutcome.VALID)
        }

        test("records that a relation without employment would be revoked") {
            useCase { EmploymentHistoryResult.Found(emptyList()) }.execute()

            repository.completed.single().outcome shouldBe EmploymentCheckOutcome.WOULD_REVOKE
            metrics.checks shouldBe listOf(ClaimedCheckOutcome.WOULD_REVOKE)
        }

        // Just after Norwegian midnight the UTC date is still the day before, which would keep these relations.
        listOf(
            Triple("winter", Instant.parse("2026-01-31T23:30:00Z"), LocalDate.parse("2025-09-30")),
            Triple("summer", Instant.parse("2026-06-30T22:30:00Z"), LocalDate.parse("2026-02-28")),
        ).forEach { (season, checkedAt, endDate) ->
            test("judges the rule on the Norwegian date just after midnight in $season") {
                repository.claims = listOf(claim.copy(claimedAt = checkedAt))
                val employment = Employment(claim.organizationNumber, endDate)

                useCase(clock = Clock.fixed(checkedAt, ZoneOffset.UTC)) { EmploymentHistoryResult.Found(listOf(employment)) }.execute()

                metrics.checks shouldBe listOf(ClaimedCheckOutcome.WOULD_REVOKE)
            }
        }

        test("a failed lookup is retried the next day and logged once without personal data") {
            withProductionLogs(CheckNarmestelederrelasjonEmploymentUseCase::class.java) { capture ->
                useCase { EmploymentHistoryResult.Unavailable(aaregFailure) }.execute()

                repository.completed.single().outcome shouldBe EmploymentCheckOutcome.FAILED
                repository.completed.single().nextCheck shouldBe now.plusSeconds(86_400)
                RuntimeLogContract.forEvents(
                    employmentCheckFailed,
                    exceptionTypes = setOf("IllegalStateException"),
                ).assertValid(capture.records, expectedCount = 1)
                val failed = jacksonObjectMapper().readTree(capture.records.single())
                failed["narmesteleder_id"].asText() shouldBe claim.narmesteLederId.toString()
                failed["upstream_status"].asInt() shouldBe 503
                capture.records.single() shouldNotContain claim.employeeIdent.value
                capture.records.single() shouldNotContain claim.organizationNumber.value
                capture.records.single() shouldNotContain "private-response-canary"
            }
            metrics.checks shouldBe listOf(ClaimedCheckOutcome.FAILED)
        }

        test("a claim taken over by another pod is counted as lost, without a comparison or a failure log") {
            repository.claims = listOf(claim.copy(sourceRevokedAt = now))
            repository.completeSucceeds = false

            withProductionLogs(CheckNarmestelederrelasjonEmploymentUseCase::class.java) { capture ->
                useCase { EmploymentHistoryResult.Unavailable(aaregFailure) }.execute()

                capture.records shouldBe emptyList()
            }
            metrics.checks shouldBe listOf(ClaimedCheckOutcome.CLAIM_LOST)
            metrics.comparisons shouldBe emptyList()
        }

        test("compares with the source's revocation and warns when we would keep the relation") {
            val agrees = claim.copy(narmesteLederId = UUID.randomUUID(), sourceRevokedAt = now)
            val disagrees = claim.copy(narmesteLederId = UUID.randomUUID(), sourceRevokedAt = now)
            val fails = claim.copy(narmesteLederId = UUID.randomUUID(), sourceRevokedAt = now)
            repository.claims = listOf(agrees, disagrees, fails)
            val histories = listOf(
                EmploymentHistoryResult.Found(emptyList()),
                EmploymentHistoryResult.Found(listOf(Employment(claim.organizationNumber, null))),
                EmploymentHistoryResult.Unavailable(aaregFailure),
            ).iterator()

            withProductionLogs(CheckNarmestelederrelasjonEmploymentUseCase::class.java) { capture ->
                useCase { histories.next() }.execute()

                RuntimeLogContract.forEvents(
                    employmentCheckComparisonMismatch,
                    employmentCheckFailed,
                    exceptionTypes = setOf("IllegalStateException"),
                ).assertValid(capture.records, expectedCount = 2)
                val mismatch = capture.records.map { jacksonObjectMapper().readTree(it) }
                    .single { it["event_type"].asText() == "employment_check_comparison_mismatch" }
                mismatch["narmesteleder_id"].asText() shouldBe disagrees.narmesteLederId.toString()
            }
            // The failed check is compared when it is retried.
            metrics.comparisons shouldBe listOf(EmploymentComparisonResult.AGREE, EmploymentComparisonResult.DISAGREE)
        }

        // The rule is judged on the Norwegian date the source revoked the relation, not when we ingest or check it.
        listOf(
            ComparisonDateCase(
                name = "a revocation ingested the next day",
                revokedAt = Instant.parse("2026-10-11T12:00:00Z"),
                checkedAt = Instant.parse("2026-10-12T12:00:00Z"),
                endDate = LocalDate.parse("2026-06-11"),
                expected = EmploymentComparisonResult.DISAGREE,
            ),
            ComparisonDateCase(
                name = "a revocation just after Norwegian midnight",
                revokedAt = Instant.parse("2026-06-30T22:30:00Z"),
                checkedAt = Instant.parse("2026-06-30T22:30:00Z"),
                endDate = LocalDate.parse("2026-02-28"),
                expected = EmploymentComparisonResult.AGREE,
            ),
        ).forEach { case ->
            test("judges ${case.name} on the Norwegian date of the source's revocation") {
                repository.claims = listOf(claim.copy(claimedAt = case.checkedAt, sourceRevokedAt = case.revokedAt))
                val employment = Employment(claim.organizationNumber, case.endDate)

                useCase(clock = Clock.fixed(case.checkedAt, ZoneOffset.UTC)) { EmploymentHistoryResult.Found(listOf(employment)) }.execute()

                metrics.comparisons shouldBe listOf(case.expected)
            }
        }

        test("gives up a lookup that would outlast the claim's lease") {
            runTest {
                val halfSecondLookup = EmploymentHistoryLookup {
                    delay(500.milliseconds)
                    EmploymentHistoryResult.Found(emptyList())
                }
                // One second is left for a fresh claim, and none for a claim taken two seconds ago.
                repository.claims = listOf(claim, claim.copy(claimedAt = now.minusSeconds(2)))

                useCase(EmploymentCheckSettings(lease = CLAIM_SAFETY_MARGIN + 1.seconds), lookup = halfSecondLookup).execute()

                metrics.checks shouldBe listOf(ClaimedCheckOutcome.WOULD_REVOKE, ClaimedCheckOutcome.TIMEOUT)
            }
        }

        test("cancellation during the lookup is not swallowed") {
            shouldThrow<CancellationException> { useCase { throw CancellationException("Stopping") }.execute() }
        }
    })

private data class ComparisonDateCase(
    val name: String,
    val revokedAt: Instant,
    val checkedAt: Instant,
    val endDate: LocalDate,
    val expected: EmploymentComparisonResult,
)
