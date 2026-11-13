package no.nav.syfo.narmestelederrelasjon.application.employmentcheck

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
import no.nav.syfo.ident.PersonalIdentificationNumber
import no.nav.syfo.narmestelederrelasjon.application.PublishNarmestelederrelasjonRevocation
import no.nav.syfo.narmestelederrelasjon.application.PublishNarmestelederrelasjonRevocationCommand
import no.nav.syfo.narmestelederrelasjon.application.RecordingRevocationPublisher
import no.nav.syfo.narmestelederrelasjon.application.leesah.validated
import no.nav.syfo.narmestelederrelasjon.application.revoke.RevocationInitiator
import no.nav.syfo.narmestelederrelasjon.domain.Employment
import no.nav.syfo.platform.observability.withProductionLogs
import no.nav.syfo.platform.upstream.UpstreamFailure
import no.nav.syfo.platform.upstream.UpstreamFailureStage
import no.nav.syfo.platform.upstream.UpstreamName
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private val now = Instant.parse("2026-01-31T12:00:00Z")
private val claim = ClaimedEmploymentCheck(
    narmesteLederId = UUID.randomUUID(),
    organizationNumber = OrganizationNumber("123456789"),
    employeeIdent = PersonalIdentificationNumber("12345678901"),
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
        lateinit var published: MutableList<PublishNarmestelederrelasjonRevocationCommand>

        fun useCase(
            settings: EmploymentCheckSettings = EmploymentCheckSettings(),
            clock: Clock = Clock.fixed(now, ZoneOffset.UTC),
            publisher: PublishNarmestelederrelasjonRevocation = RecordingRevocationPublisher {
                repository.validated shouldBe repository.claims.take(published.size + 1)
                repository.completed.size shouldBe published.size
                published += it
            },
            lookup: EmploymentHistoryLookup,
        ) = CheckNarmestelederrelasjonEmploymentUseCase(
            repository,
            lookup,
            settings,
            clock,
            metrics,
            publisher,
        )

        beforeTest {
            repository = RecordingEmploymentReconciliationRepository().apply { claims = listOf(claim) }
            metrics = RecordingEmploymentCheckMetrics()
            published = mutableListOf()
        }

        test("never publishes or completes a stale claim") {
            repository.claimStillValid = false

            useCase { EmploymentHistoryResult.Found(emptyList()) }.execute()

            repository.validated shouldBe listOf(claim)
            published shouldBe emptyList()
            repository.completed shouldBe emptyList()
            metrics.checks shouldBe listOf(ClaimedCheckOutcome.STALE)
        }

        test("publishes only when the publisher's max publish duration fits before the safety margin") {
            repository.claims = listOf(689L, 690L, 691L).map { claim.copy(claimedAt = now.minusSeconds(it)) }

            useCase { EmploymentHistoryResult.Found(emptyList()) }.execute()

            published.size shouldBe 1
            repository.validated shouldBe listOf(repository.claims.first())
            repository.completed.map { it.claim } shouldBe listOf(repository.claims.first())
            metrics.checks shouldBe listOf(ClaimedCheckOutcome.REVOKED, ClaimedCheckOutcome.TIMEOUT, ClaimedCheckOutcome.TIMEOUT)
        }

        test("a publish failure is retried the next day and logged without personal data") {
            withProductionLogs(CheckNarmestelederrelasjonEmploymentUseCase::class.java) { capture ->
                useCase(
                    publisher = RecordingRevocationPublisher { throw IllegalStateException("private-publish-canary ${claim.employeeIdent.value} ${claim.organizationNumber.value}") },
                ) { EmploymentHistoryResult.Found(emptyList()) }.execute()

                repository.completed.single() shouldBe RecordedCompletion(claim, EmploymentCheckOutcome.FAILED, now.plusSeconds(86_400), now)
                RuntimeLogContract.forEvents(
                    employmentCheckRevocationFailed,
                    exceptionTypes = setOf("IllegalStateException"),
                ).assertValid(capture.records, expectedCount = 1)
                val failed = jacksonObjectMapper().readTree(capture.records.single())
                failed["narmesteleder_id"].asText() shouldBe claim.narmesteLederId.toString()
                capture.records.single() shouldNotContain claim.employeeIdent.value
                capture.records.single() shouldNotContain claim.organizationNumber.value
                capture.records.single() shouldNotContain "private-publish-canary"
            }
            metrics.checks shouldBe listOf(ClaimedCheckOutcome.FAILED)
        }

        test("a successful publish with a lost completion is counted as claim lost and warns without personal data") {
            repository.completeSucceeds = false

            withProductionLogs(CheckNarmestelederrelasjonEmploymentUseCase::class.java) { capture ->
                useCase { EmploymentHistoryResult.Found(emptyList()) }.execute()

                published.size shouldBe 1
                repository.completed.single().outcome shouldBe EmploymentCheckOutcome.REVOKED
                RuntimeLogContract.forEvents(employmentCheckRevocationClaimLost).assertValid(capture.records, expectedCount = 1)
                val lost = jacksonObjectMapper().readTree(capture.records.single())
                lost["narmesteleder_id"].asText() shouldBe claim.narmesteLederId.toString()
                lost["level"].asText() shouldBe "WARN"
                capture.records.single() shouldNotContain claim.employeeIdent.value
                capture.records.single() shouldNotContain claim.organizationNumber.value
            }
            metrics.checks shouldBe listOf(ClaimedCheckOutcome.CLAIM_LOST)
        }

        test("keeps qualifying employment without publishing") {
            useCase { EmploymentHistoryResult.Found(listOf(Employment(claim.organizationNumber, null))) }.execute()

            published shouldBe emptyList()
            repository.validated shouldBe emptyList()
            repository.completed.single().outcome shouldBe EmploymentCheckOutcome.VALID
            metrics.checks shouldBe listOf(ClaimedCheckOutcome.VALID)
        }

        test("a failed lookup never publishes or validates a claim and retries the next day") {
            useCase { EmploymentHistoryResult.Unavailable(aaregFailure) }.execute()

            published shouldBe emptyList()
            repository.validated shouldBe emptyList()
            repository.completed.single() shouldBe RecordedCompletion(claim, EmploymentCheckOutcome.FAILED, now.plusSeconds(86_400), now)
            metrics.checks shouldBe listOf(ClaimedCheckOutcome.FAILED)
        }

        test("recalculates the publish budget after the lookup") {
            var checkedAt = now
            val clock = object : Clock() {
                override fun instant(): Instant = checkedAt
                override fun getZone(): ZoneId = ZoneOffset.UTC
                override fun withZone(zone: ZoneId): Clock = fixed(checkedAt, zone)
            }

            useCase(clock = clock) {
                checkedAt = now.plusSeconds(690)
                EmploymentHistoryResult.Found(emptyList())
            }.execute()

            published shouldBe emptyList()
            repository.validated shouldBe emptyList()
            repository.completed shouldBe emptyList()
            metrics.checks shouldBe listOf(ClaimedCheckOutcome.TIMEOUT)
        }

        test("cancellation during publishing is not swallowed or completed") {
            shouldThrow<CancellationException> {
                useCase(publisher = RecordingRevocationPublisher { throw CancellationException("Stopping") }) {
                    EmploymentHistoryResult.Found(emptyList())
                }.execute()
            }

            repository.completed shouldBe emptyList()
            metrics.checks shouldBe emptyList()
        }

        test("database completion failures after publishing remain exceptions") {
            val failingRepository = object : EmploymentReconciliationRepository by repository {
                override suspend fun complete(
                    claim: ClaimedEmploymentCheck,
                    outcome: EmploymentCheckOutcome,
                    nextCheck: Instant,
                    now: Instant,
                ): Boolean = throw IllegalStateException("Database unavailable")
            }
            val useCase = CheckNarmestelederrelasjonEmploymentUseCase(
                failingRepository,
                { EmploymentHistoryResult.Found(emptyList()) },
                EmploymentCheckSettings(),
                Clock.fixed(now, ZoneOffset.UTC),
                metrics,
                RecordingRevocationPublisher { published += it },
            )

            shouldThrow<IllegalStateException> { useCase.execute() }

            published.size shouldBe 1
            metrics.checks shouldBe emptyList()
        }

        test("keeps a relation with employment in the organization and checks it again one calendar month later") {
            val employment = Employment(claim.organizationNumber, null)

            useCase { EmploymentHistoryResult.Found(listOf(employment)) }.execute()

            // January 31 plus one month is the last day of February.
            repository.completed.single() shouldBe
                RecordedCompletion(claim, EmploymentCheckOutcome.VALID, Instant.parse("2026-02-28T12:00:00Z"), now)
            metrics.checks shouldBe listOf(ClaimedCheckOutcome.VALID)
        }

        test("a check publishes an employment-ended revocation without a separate revocation switch") {
            useCase { EmploymentHistoryResult.Found(emptyList()) }.execute()

            published shouldBe listOf(
                PublishNarmestelederrelasjonRevocationCommand(claim.employeeIdent, claim.organizationNumber, RevocationInitiator.EMPLOYMENT_ENDED),
            )
            repository.completed.single() shouldBe
                RecordedCompletion(claim, EmploymentCheckOutcome.REVOKED, Instant.parse("2026-02-28T12:00:00Z"), now)
            metrics.checks shouldBe listOf(ClaimedCheckOutcome.REVOKED)
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

                published.size shouldBe 1
                metrics.checks shouldBe listOf(ClaimedCheckOutcome.REVOKED)
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

        test("a claim taken over by another pod is counted as lost without a failure log") {
            repository.completeSucceeds = false

            withProductionLogs(CheckNarmestelederrelasjonEmploymentUseCase::class.java) { capture ->
                useCase { EmploymentHistoryResult.Unavailable(aaregFailure) }.execute()

                capture.records shouldBe emptyList()
            }
            metrics.checks shouldBe listOf(ClaimedCheckOutcome.CLAIM_LOST)
        }

        test("gives up a lookup that would outlast the claim's lease") {
            runTest {
                val halfSecondLookup = EmploymentHistoryLookup {
                    delay(500.milliseconds)
                    EmploymentHistoryResult.Found(emptyList())
                }
                // One second is left for a fresh claim, and none for a claim taken two seconds ago.
                repository.claims = listOf(claim, claim.copy(claimedAt = now.minusSeconds(2)))

                useCase(
                    settings = EmploymentCheckSettings(lease = CLAIM_SAFETY_MARGIN + 1.seconds),
                    publisher = RecordingRevocationPublisher(maxPublishDuration = 0.seconds) { published += it },
                    lookup = halfSecondLookup,
                ).execute()

                metrics.checks shouldBe listOf(ClaimedCheckOutcome.REVOKED, ClaimedCheckOutcome.TIMEOUT)
            }
        }

        test("cancellation during the lookup is not swallowed") {
            shouldThrow<CancellationException> { useCase { throw CancellationException("Stopping") }.execute() }
        }
    })
