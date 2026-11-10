package no.nav.syfo.narmestelederbehov.application

import ch.qos.logback.classic.Level
import io.kotest.assertions.assertSoftly
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import no.nav.syfo.ident.OrganizationNumber
import no.nav.syfo.ident.PersonalIdentificationNumber
import no.nav.syfo.integration.pdl.PDL
import no.nav.syfo.narmestelederbehov.domain.BehovStatus
import no.nav.syfo.narmestelederbehov.domain.Employee
import no.nav.syfo.narmestelederbehov.domain.NarmestelederbehovId
import no.nav.syfo.platform.observability.withProductionLogs
import no.nav.syfo.platform.upstream.UpstreamError
import no.nav.syfo.platform.upstream.UpstreamFailure
import no.nav.syfo.platform.upstream.UpstreamFailureStage
import java.time.Instant
import java.util.UUID
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toKotlinInstant

class DispatchNarmestelederbehovDialogUseCaseTest :
    FunSpec({
        val now = Instant.parse("2030-01-01T10:00:00Z")
        val employee = Employee(PersonalIdentificationNumber("12345678901"), OrganizationNumber("123456789"))
        val claim = DialogClaim(
            id = NarmestelederbehovId(UUID.randomUUID()),
            token = UUID.randomUUID(),
            revision = 1,
            leaseUntil = now.plusSeconds(60),
            attempts = 0,
        )

        test("a claim that consumes the run budget does not start external delivery") {
            val queue = FakeQueue(claimBlock = {
                withContext(NonCancellable) { delay(80.milliseconds) }
                claim
            })
            var creationStarted = false
            val outcomes = mutableListOf<DialogDispatchOutcome>()
            val useCase = useCase(
                dispatchQueue = queue,
                dispatchWork = FakeWork(
                    DialogDispatchState(
                        employee = employee,
                        status = BehovStatus.BEHOV_CREATED,
                        dialogId = null,
                        createStarted = false,
                        intendedExpiry = null,
                        expiredAt = null,
                    ),
                ),
                dialogCreation = { _, _, _ ->
                    creationStarted = true
                    NarmestelederbehovDialogCreationResult.Created(UUID.randomUUID())
                },
                dispatchMetrics = { outcomes += it },
                settings = settings(runBudget = 40.milliseconds),
            )

            useCase.execute()

            assertSoftly {
                creationStarted shouldBe false
                outcomes shouldBe listOf(DialogDispatchOutcome.CLAIM_LOST)
            }
        }

        test("a run whose remaining budget only covers an acknowledgement does not take another claim") {
            val queue = FakeQueue()
            val useCase = useCase(
                dispatchQueue = queue,
                dispatchWork = FakeWork(null, loadBlock = { awaitCancellation() }),
                settings = DialogDispatchSettings(
                    safetyMargin = 50.milliseconds,
                    acknowledgementBudget = 100.milliseconds,
                    attemptBudget = 500.milliseconds,
                    runBudget = 300.milliseconds,
                    lease = 10.seconds,
                    maxAttemptsPerRun = 2,
                ),
            )

            useCase.execute()

            queue.claims shouldBe 1
        }

        test("a rejected acknowledgement is recorded as a lost claim and the run continues") {
            val acknowledgements = ArrayDeque(listOf(false, true))
            val outcomes = mutableListOf<DialogDispatchOutcome>()
            val useCase = useCase(
                dispatchQueue = FakeQueue(finishBlock = { acknowledgements.removeFirst() }),
                dispatchWork = FakeWork(null),
                dispatchMetrics = { outcomes += it },
                settings = settings(runBudget = 5.seconds).copy(maxAttemptsPerRun = 2),
            )

            useCase.execute()

            outcomes shouldBe listOf(DialogDispatchOutcome.CLAIM_LOST, DialogDispatchOutcome.DONE)
        }

        test("a suspended acknowledgement is cancelled within its budget without being acknowledged") {
            var finishStarted = false
            var finishCancelled = false
            val queue = FakeQueue(finishBlock = {
                finishStarted = true
                try {
                    awaitCancellation()
                } finally {
                    finishCancelled = true
                }
            })
            val outcomes = mutableListOf<DialogDispatchOutcome>()
            val useCase = useCase(
                dispatchQueue = queue,
                dispatchWork = FakeWork(null),
                dispatchMetrics = { outcomes += it },
                settings = settings(runBudget = 500.milliseconds),
            )

            val completedWithinGuard = withTimeoutOrNull(1.seconds) {
                useCase.execute()
                true
            }

            assertSoftly {
                completedWithinGuard shouldBe true
                finishStarted shouldBe true
                finishCancelled shouldBe true
                queue.acknowledged shouldBe false
                outcomes shouldBe listOf(DialogDispatchOutcome.CLAIM_LOST)
            }
        }

        test("a timed out attempt reserves run time to persist its retry") {
            val queue = FakeQueue(finishBlock = {
                delay(20.milliseconds)
                true
            })
            val useCase = useCase(
                dispatchQueue = queue,
                dispatchWork = FakeWork(null, loadBlock = { awaitCancellation() }),
                settings = DialogDispatchSettings(
                    acknowledgementBudget = 500.milliseconds,
                    attemptBudget = 1.seconds,
                    runBudget = 1.seconds,
                    lease = 10.seconds,
                    maxAttemptsPerRun = 1,
                ),
            )

            useCase.execute()

            assertSoftly {
                queue.acknowledged shouldBe true
                queue.finishedResult.shouldBeInstanceOf<DialogDispatchResult.Retry>().reason shouldBe DialogDispatchFailure.TIMEOUT
            }
        }

        listOf(
            "an unavailable" to EmployeePersonLookup { EmployeePersonLookupResult.Unavailable(UpstreamFailure(PDL, UpstreamFailureStage.RESPONSE, 503, errors = listOf(UpstreamError("server_error")))) },
            "a failing" to EmployeePersonLookup { throw IllegalStateException("lookup failed") },
        ).forEach { (description, lookup) ->
            test("$description person lookup is logged and the dialog is created without person details") {
                val persons = mutableListOf<EmployeePerson?>()
                val useCase = useCase(
                    dispatchQueue = FakeQueue(),
                    dispatchWork = FakeWork(
                        DialogDispatchState(
                            employee = employee,
                            status = BehovStatus.BEHOV_CREATED,
                            dialogId = null,
                            createStarted = false,
                            intendedExpiry = null,
                            expiredAt = null,
                        ),
                    ),
                    dialogCreation = { _, _, person ->
                        persons += person
                        NarmestelederbehovDialogCreationResult.Created(UUID.randomUUID())
                    },
                    employeePersonLookup = lookup,
                    settings = settings(runBudget = 5.seconds),
                )

                val logs = withProductionLogs(DispatchNarmestelederbehovDialogUseCase::class.java, Level.WARN) { capture ->
                    useCase.execute()
                    capture.logs
                }

                assertSoftly {
                    persons shouldBe listOf(null)
                    logs.map { it.fields["event_type"] } shouldBe listOf("dialog_person_enrichment_failed")
                }
            }
        }

        test("caller cancellation is propagated while claiming") {
            val useCase = useCase(
                dispatchQueue = FakeQueue(claimBlock = { awaitCancellation() }),
                dispatchWork = FakeWork(null),
                settings = settings(runBudget = 5.seconds),
            )

            shouldThrow<TimeoutCancellationException> {
                withTimeout(20.milliseconds) { useCase.execute() }
            }
        }
    })

private fun useCase(
    dispatchQueue: DialogDispatchQueue,
    dispatchWork: DialogDispatchWork,
    dialogCreation: NarmestelederbehovDialogCreation = { _, _, _ -> error("must not create") },
    employeePersonLookup: EmployeePersonLookup = EmployeePersonLookup { error("must not look up a person") },
    dispatchMetrics: DialogDispatchMetrics = DialogDispatchMetrics {},
    settings: DialogDispatchSettings,
) = DispatchNarmestelederbehovDialogUseCase(
    dispatchQueue = dispatchQueue,
    dispatchWork = dispatchWork,
    dialogCreation = dialogCreation,
    dialogCompletion = { error("must not complete") },
    dialogExpiry = { _, _ -> error("must not expire") },
    dialogExpiryInspection = { error("must not inspect") },
    employeePersonLookup = employeePersonLookup,
    clock = object : Clock {
        override fun now() = Instant.parse("2030-01-01T10:00:00Z").toKotlinInstant()
    },
    dispatchMetrics = dispatchMetrics,
    settings = settings,
)

private fun settings(runBudget: Duration) = DialogDispatchSettings(
    safetyMargin = 50.milliseconds,
    acknowledgementBudget = 10.milliseconds,
    attemptBudget = 500.milliseconds,
    runBudget = runBudget,
    lease = 10.seconds,
    maxAttemptsPerRun = 1,
)

private class FakeQueue(
    private val claimBlock: suspend () -> DialogClaim? = { CLAIM },
    private val finishBlock: suspend () -> Boolean = { true },
) : DialogDispatchQueue {
    var acknowledged = false
    var finishedResult: DialogDispatchResult? = null
    var claims = 0

    override suspend fun claim(now: Instant, lease: Duration): DialogClaim? {
        claims++
        return claimBlock()
    }

    override suspend fun finish(claim: DialogClaim, result: DialogDispatchResult, now: Instant): Boolean {
        finishedResult = result
        return finishBlock().also { acknowledged = it }
    }

    companion object {
        private val CLAIM = DialogClaim(
            id = NarmestelederbehovId(UUID.randomUUID()),
            token = UUID.randomUUID(),
            revision = 1,
            leaseUntil = Instant.parse("2030-01-01T10:01:00Z"),
            attempts = 0,
        )
    }
}

private class FakeWork(
    private val state: DialogDispatchState?,
    private val loadBlock: suspend () -> DialogDispatchState? = { state },
) : DialogDispatchWork {
    override suspend fun load(claim: DialogClaim, now: Instant): DialogDispatchState? = loadBlock()
    override suspend fun beginCreation(claim: DialogClaim, now: Instant) = true
    override suspend fun saveCreated(claim: DialogClaim, dialogId: UUID, name: BehovPersonName?, now: Instant) = true
    override suspend fun prepareExpiry(claim: DialogClaim, expiry: Instant, now: Instant) = true
    override suspend fun saveCompleted(claim: DialogClaim, now: Instant) = true
    override suspend fun saveExpired(claim: DialogClaim, expiry: Instant, now: Instant) = true
}
