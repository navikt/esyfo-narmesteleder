package no.nav.syfo.narmestelederbehov.application

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import no.nav.esyfo.observability.Event
import no.nav.syfo.narmestelederbehov.domain.BehovStatus
import no.nav.syfo.narmestelederbehov.domain.NarmestelederbehovId
import no.nav.syfo.platform.observability.applicationEvent
import no.nav.syfo.platform.observability.applicationLogger
import no.nav.syfo.platform.observability.logEvent
import no.nav.syfo.platform.upstream.UpstreamFailure
import no.nav.syfo.platform.upstream.UpstreamFailureStage
import org.slf4j.event.Level
import java.util.UUID
import kotlin.math.min
import kotlin.math.pow
import kotlin.random.Random
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.time.TimeMark
import kotlin.time.TimeSource
import kotlin.time.toJavaInstant
import kotlin.time.toKotlinInstant

fun interface DialogExpiryInspection {
    suspend fun inspect(dialogId: UUID): DialogExpiryObservation
}

sealed interface DialogExpiryObservation {
    data class Found(val expiresAt: Instant?) : DialogExpiryObservation
    data class Unavailable(val failure: UpstreamFailure) : DialogExpiryObservation
}

enum class DialogDispatchOutcome {
    DONE,
    RETRY,
    PARKED,
    CLAIM_LOST,
}

fun interface DialogDispatchMetrics {
    fun record(outcome: DialogDispatchOutcome)
}

/** Reconciles one revision per claim; a newer revision is left for the next owner. */
class DispatchNarmestelederbehovDialogUseCase(
    private val dispatchQueue: DialogDispatchQueue,
    private val dispatchWork: DialogDispatchWork,
    private val dialogCreation: NarmestelederbehovDialogCreation,
    private val dialogCompletion: NarmestelederbehovDialog,
    private val dialogExpiry: NarmestelederbehovDialogExpiry,
    private val dialogExpiryInspection: DialogExpiryInspection,
    private val employeePersonLookup: EmployeePersonLookup,
    private val clock: Clock,
    private val dispatchMetrics: DialogDispatchMetrics,
    private val settings: DialogDispatchSettings = DialogDispatchSettings(),
) {
    suspend fun execute() {
        val run = TimeSource.Monotonic.markNow()
        withTimeoutOrNull(settings.runBudget) {
            repeat(settings.maxAttemptsPerRun) {
                // A claim that cannot be attempted and acknowledged would only wait out its lease.
                if (remainingRunBudget(run) <= settings.acknowledgementBudget) return@withTimeoutOrNull
                val claim = dispatchQueue.claim(clock.now().toJavaInstant(), settings.lease) ?: return@withTimeoutOrNull
                val loss = dispatch(claim, run) ?: return@repeat
                logger.event(dialogDispatchClaimLost, DialogClaimLost(claim.id, loss))
                dispatchMetrics.record(DialogDispatchOutcome.CLAIM_LOST)
                if (loss.stopsRun) return@withTimeoutOrNull
            }
        }
    }

    /** Returns why the claim ended without an acknowledged result, or null when [DialogDispatchQueue.finish] accepted it. */
    private suspend fun dispatch(claim: DialogClaim, run: TimeMark): ClaimLoss? {
        val reconciliationBudget = minOf(remainingRunBudget(run), remainingLease(claim)) - settings.acknowledgementBudget
        val budget = minOf(settings.attemptBudget, reconciliationBudget)
        if (budget <= Duration.ZERO) return ClaimLoss.ATTEMPT_BUDGET_SPENT
        val result = try {
            withTimeoutOrNull(budget) { reconcile(claim) } ?: retry(claim, DialogDispatchFailure.TIMEOUT)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Exception messages can contain upstream payloads. Persist only a bounded technical reason.
            logger.logEvent(dialogDispatchFailed, claim.id.value.toString())
            retry(claim, DialogDispatchFailure.UNEXPECTED)
        }
        val acknowledgementBudget = minOf(settings.acknowledgementBudget, remainingRunBudget(run), remainingLease(claim))
        if (acknowledgementBudget <= Duration.ZERO) return ClaimLoss.ACKNOWLEDGEMENT_BUDGET_SPENT
        val acknowledged = withTimeoutOrNull(acknowledgementBudget) { dispatchQueue.finish(claim, result, clock.now().toJavaInstant()) }
            ?: return ClaimLoss.ACKNOWLEDGEMENT_TIMED_OUT
        if (!acknowledged) return ClaimLoss.ACKNOWLEDGEMENT_REJECTED
        dispatchMetrics.record(
            when (result) {
                DialogDispatchResult.Done -> DialogDispatchOutcome.DONE
                is DialogDispatchResult.Retry -> DialogDispatchOutcome.RETRY
                is DialogDispatchResult.Parked -> DialogDispatchOutcome.PARKED
            },
        )
        return null
    }

    private fun remainingRunBudget(run: TimeMark): Duration = settings.runBudget - run.elapsedNow()

    private fun remainingLease(claim: DialogClaim): Duration = (claim.leaseUntil.toKotlinInstant() - clock.now()) - settings.safetyMargin

    private suspend fun reconcile(claim: DialogClaim): DialogDispatchResult {
        val state = dispatchWork.load(claim, clock.now().toJavaInstant()) ?: return DialogDispatchResult.Done
        val dialogId = state.dialogId
        if (dialogId == null) {
            // A closed behov is created only to recover an uncertain CREATE. The idempotent key returns an existing
            // dialog; otherwise subscribers see the dialog created and then closed, which is rare and accepted.
            if (state.status != BehovStatus.BEHOV_CREATED && !state.createStarted) return DialogDispatchResult.Done
            return create(claim, state)
        }
        return when (state.status) {
            BehovStatus.BEHOV_FULFILLED -> when (val completed = dialogCompletion.complete(dialogId)) {
                DialogCompletion.Completed -> {
                    dispatchWork.saveCompleted(claim, clock.now().toJavaInstant())
                    DialogDispatchResult.Done
                }
                is DialogCompletion.Unavailable -> failed(claim, completed.failure)
            }
            BehovStatus.BEHOV_EXPIRED, BehovStatus.ERROR ->
                if (state.expiredAt != null) DialogDispatchResult.Done else expire(claim, state, dialogId)
            else -> DialogDispatchResult.Done
        }
    }

    private suspend fun create(claim: DialogClaim, state: DialogDispatchState): DialogDispatchResult {
        val person = findPerson(claim, state)
        if (!dispatchWork.beginCreation(claim, clock.now().toJavaInstant())) return DialogDispatchResult.Done
        return when (val created = dialogCreation.create(claim.id, state.employee, person)) {
            is NarmestelederbehovDialogCreationResult.Created -> {
                dispatchWork.saveCreated(claim, created.dialogId, person?.name, clock.now().toJavaInstant())
                DialogDispatchResult.Done
            }
            is NarmestelederbehovDialogCreationResult.Unavailable -> failed(claim, created.failure)
        }
    }

    /** Person details only enrich the dialog, so any lookup failure falls back to a dialog without them. */
    private suspend fun findPerson(claim: DialogClaim, state: DialogDispatchState): EmployeePerson? = try {
        when (val lookup = employeePersonLookup.find(state.employee.personIdent)) {
            is EmployeePersonLookupResult.Found -> lookup.person
            EmployeePersonLookupResult.NotFound -> null
            is EmployeePersonLookupResult.Unavailable -> {
                logger.logEvent(dialogPersonEnrichmentFailed, DialogEventDetails(claim.id.value), upstreamFailure = lookup.failure)
                null
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        logger.logEvent(dialogPersonEnrichmentFailed, DialogEventDetails(claim.id.value), cause = e)
        null
    }

    private suspend fun expire(claim: DialogClaim, state: DialogDispatchState, dialogId: UUID): DialogDispatchResult {
        val observed = when (val observation = dialogExpiryInspection.inspect(dialogId)) {
            is DialogExpiryObservation.Found -> observation.expiresAt
            is DialogExpiryObservation.Unavailable -> return failed(claim, observation.failure)
        }
        val intended = state.intendedExpiry?.toKotlinInstant()
        if (intended != null && observed == intended) {
            dispatchWork.saveExpired(claim, intended.toJavaInstant(), clock.now().toJavaInstant())
            return DialogDispatchResult.Done
        }
        val safeThrough = clock.now() + (settings.attemptBudget + settings.safetyMargin)
        val target = intended?.takeIf { it > safeThrough }
            ?: Instant.fromEpochSeconds((clock.now() + settings.expiryDelay).epochSeconds)
        if (target != intended && !dispatchWork.prepareExpiry(claim, target.toJavaInstant(), clock.now().toJavaInstant())) return DialogDispatchResult.Done
        return when (val expired = dialogExpiry.expire(dialogId, target.toJavaInstant())) {
            DialogExpiry.Expired -> {
                dispatchWork.saveExpired(claim, target.toJavaInstant(), clock.now().toJavaInstant())
                DialogDispatchResult.Done
            }
            is DialogExpiry.Unavailable -> failed(claim, expired.failure)
        }
    }

    private fun failed(claim: DialogClaim, failure: UpstreamFailure): DialogDispatchResult {
        logger.logEvent(dialogDispatchFailed, claim.id.value.toString(), upstreamFailure = failure)
        return if (failure.stage == UpstreamFailureStage.RESPONSE && failure.status in setOf(400, 422)) {
            DialogDispatchResult.Parked(DialogDispatchFailure.INVALID_REQUEST)
        } else {
            retry(claim, DialogDispatchFailure.UPSTREAM)
        }
    }

    private fun retry(claim: DialogClaim, reason: DialogDispatchFailure): DialogDispatchResult.Retry {
        val ceiling = minOf(settings.retryBackoff * 2.0.pow(min(claim.attempts, 30)), settings.maxRetryBackoff).inWholeMilliseconds
        return DialogDispatchResult.Retry((clock.now() + Random.nextLong(ceiling / 2, ceiling + 1).milliseconds).toJavaInstant(), reason)
    }

    companion object {
        private val logger = applicationLogger(DispatchNarmestelederbehovDialogUseCase::class.java)
        private val dialogDispatchFailed = applicationEvent<String>(
            name = "dialog_dispatch_failed",
            level = Level.WARN,
            message = "Dialog reconciliation did not converge; inspect queue state before replay",
            operation = "reconcile_narmestelederbehov_dialog",
            fields = mapOf("behov_id" to { it }),
        )
        private val dialogDispatchClaimLost = Event<DialogClaimLost>(
            name = "dialog_dispatch_claim_lost",
            level = Level.WARN,
            message = "Dialog reconciliation ended without an acknowledged result; the lease or its current owner decides the next attempt",
            operation = "reconcile_narmestelederbehov_dialog",
            fields = mapOf(
                "behov_id" to { it.behovId.value.toString() },
                "reason" to { it.loss.name },
            ),
        )
    }
}

/** Why a claim ended without an acknowledged result; a spent budget stops the run, a rejected acknowledgement does not. */
private enum class ClaimLoss(val stopsRun: Boolean) {
    ATTEMPT_BUDGET_SPENT(stopsRun = true),
    ACKNOWLEDGEMENT_BUDGET_SPENT(stopsRun = true),
    ACKNOWLEDGEMENT_TIMED_OUT(stopsRun = true),
    ACKNOWLEDGEMENT_REJECTED(stopsRun = false),
}

private data class DialogClaimLost(val behovId: NarmestelederbehovId, val loss: ClaimLoss)

data class DialogDispatchSettings(
    val lease: Duration = 60.seconds,
    val safetyMargin: Duration = 5.seconds,
    val acknowledgementBudget: Duration = 5.seconds,
    val attemptBudget: Duration = 20.seconds,
    val runBudget: Duration = 20.seconds,
    val maxAttemptsPerRun: Int = 10,
    /** How long an expired behov stays visible in Dialogporten before its dialog expires. */
    val expiryDelay: Duration = 10.minutes,
    /** A retry waits a random half to whole of retryBackoff · 2^attempts, at most maxRetryBackoff. */
    val retryBackoff: Duration = 5.seconds,
    val maxRetryBackoff: Duration = 15.minutes,
) {
    init {
        require(
            listOf(lease, safetyMargin, acknowledgementBudget, attemptBudget, runBudget, expiryDelay, retryBackoff, maxRetryBackoff)
                .all { it.isPositive() && it.isFinite() },
        )
        require(attemptBudget + safetyMargin < lease)
        require(acknowledgementBudget + safetyMargin < lease)
        require(acknowledgementBudget < runBudget)
        require(maxAttemptsPerRun > 0)
        require(expiryDelay > attemptBudget + safetyMargin) { "An expiry target must outlive the attempt that applies it" }
        require(retryBackoff <= maxRetryBackoff)
    }
}
