package no.nav.syfo.narmestelederbehov.application

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import no.nav.syfo.ident.OrganizationNumber
import no.nav.syfo.ident.PersonalIdentificationNumber
import no.nav.syfo.narmestelederbehov.domain.BehovStatus
import no.nav.syfo.narmestelederbehov.domain.Employee
import no.nav.syfo.narmestelederbehov.domain.NarmestelederbehovId
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

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
            val useCase = useCase(
                queue = queue,
                work = FakeWork(
                    DialogDispatchState(
                        employee = employee,
                        status = BehovStatus.BEHOV_CREATED,
                        dialogId = null,
                        createStarted = false,
                        intendedExpiry = null,
                        expiredAt = null,
                    ),
                ),
                creation = { _, _, _ ->
                    creationStarted = true
                    NarmestelederbehovDialogCreationResult.Created(UUID.randomUUID())
                },
                settings = settings(runBudget = 40.milliseconds),
            )

            useCase.execute()

            creationStarted shouldBe false
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
            val useCase = useCase(
                queue = queue,
                work = FakeWork(null),
                settings = settings(runBudget = 500.milliseconds),
            )

            val completedWithinGuard = withTimeoutOrNull(1.seconds) {
                useCase.execute()
                true
            }

            completedWithinGuard shouldBe true
            finishStarted shouldBe true
            finishCancelled shouldBe true
            queue.acknowledged shouldBe false
        }

        test("a timed out attempt reserves run time to persist its retry") {
            val queue = FakeQueue(finishBlock = {
                delay(20.milliseconds)
                true
            })
            val useCase = useCase(
                queue = queue,
                work = FakeWork(null, loadBlock = { awaitCancellation() }),
                settings = DialogDispatchSettings(
                    acknowledgementBudget = 500.milliseconds,
                    attemptBudget = 1.seconds,
                    runBudget = 1.seconds,
                    lease = 10.seconds,
                    maxAttemptsPerRun = 1,
                ),
            )

            useCase.execute()

            queue.acknowledged shouldBe true
            queue.finishedResult.shouldBeInstanceOf<DialogDispatchResult.Retry>().reason shouldBe DialogDispatchFailure.TIMEOUT
        }

        test("caller cancellation is propagated while claiming") {
            val useCase = useCase(
                queue = FakeQueue(claimBlock = { awaitCancellation() }),
                work = FakeWork(null),
                settings = settings(runBudget = 5.seconds),
            )

            var cancelled = false
            try {
                withTimeout(20.milliseconds) { useCase.execute() }
            } catch (_: CancellationException) {
                cancelled = true
            }

            cancelled shouldBe true
        }
    })

private fun useCase(
    queue: DialogDispatchQueue,
    work: DialogDispatchWork,
    creation: NarmestelederbehovDialogCreation = { _, _, _ -> error("must not create") },
    settings: DialogDispatchSettings,
) = DispatchNarmestelederbehovDialogUseCase(
    queue = queue,
    work = work,
    creation = creation,
    completion = { error("must not complete") },
    expiry = { _, _ -> error("must not expire") },
    inspection = { error("must not inspect") },
    personLookup = { error("must not look up a person") },
    clock = Clock.fixed(Instant.parse("2030-01-01T10:00:00Z"), ZoneOffset.UTC),
    metrics = {},
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

    override suspend fun claim(now: Instant, lease: Duration): DialogClaim? = claimBlock()

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
