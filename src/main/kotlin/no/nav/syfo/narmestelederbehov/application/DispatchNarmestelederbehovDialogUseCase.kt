package no.nav.syfo.narmestelederbehov.application

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import no.nav.syfo.narmestelederbehov.domain.BehovStatus
import no.nav.syfo.platform.observability.applicationEvent
import no.nav.syfo.platform.observability.applicationLogger
import no.nav.syfo.platform.observability.logEvent
import no.nav.syfo.platform.upstream.UpstreamFailure
import no.nav.syfo.platform.upstream.UpstreamFailureStage
import org.slf4j.event.Level
import java.util.UUID
import kotlin.math.min
import kotlin.random.Random
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
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

fun interface DialogDispatchMetrics {
    fun record(outcome: String)
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
                if (settings.runBudget - run.elapsedNow() <= Duration.ZERO) return@withTimeoutOrNull
                val claim = dispatchQueue.claim(clock.now().toJavaInstant(), settings.lease) ?: return@withTimeoutOrNull
                val remaining = settings.runBudget - run.elapsedNow()
                if (remaining <= settings.acknowledgementBudget) return@withTimeoutOrNull
                val leaseRemaining = (claim.leaseUntil.toKotlinInstant() - clock.now()) - settings.safetyMargin
                val reconciliationBudget = minOf(remaining, leaseRemaining) - settings.acknowledgementBudget
                val budget = minOf(settings.attemptBudget, reconciliationBudget)
                if (budget <= Duration.ZERO) return@withTimeoutOrNull
                val result = try {
                    withTimeoutOrNull(budget) { reconcile(claim) } ?: retry(claim, DialogDispatchFailure.TIMEOUT)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // Exception messages can contain upstream payloads. Persist only a bounded technical reason.
                    logger.logEvent(dialogDispatchFailed, claim.id.value.toString())
                    retry(claim, DialogDispatchFailure.UNEXPECTED)
                }
                val acknowledgementBudget = minOf(
                    settings.acknowledgementBudget,
                    settings.runBudget - run.elapsedNow(),
                    (claim.leaseUntil.toKotlinInstant() - clock.now()) - settings.safetyMargin,
                )
                if (acknowledgementBudget <= Duration.ZERO) return@withTimeoutOrNull
                val acknowledged = withTimeoutOrNull(acknowledgementBudget) { dispatchQueue.finish(claim, result, clock.now().toJavaInstant()) }
                    ?: return@withTimeoutOrNull
                if (acknowledged) {
                    dispatchMetrics.record(
                        when (result) {
                            DialogDispatchResult.Done -> "done"
                            is DialogDispatchResult.Retry -> "retry"
                            is DialogDispatchResult.Parked -> "parked"
                        },
                    )
                }
            }
        }
    }

    private suspend fun reconcile(claim: DialogClaim): DialogDispatchResult {
        val state = dispatchWork.load(claim, clock.now().toJavaInstant()) ?: return DialogDispatchResult.Done
        val dialogId = state.dialogId
        if (dialogId == null) {
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
        val person = try {
            (employeePersonLookup.find(state.employee.personIdent) as? EmployeePersonLookupResult.Found)?.person
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        if (!dispatchWork.beginCreation(claim, clock.now().toJavaInstant())) return DialogDispatchResult.Done
        return when (val created = dialogCreation.create(claim.id, state.employee, person)) {
            is NarmestelederbehovDialogCreationResult.Created -> {
                dispatchWork.saveCreated(claim, created.dialogId, person?.name, clock.now().toJavaInstant())
                DialogDispatchResult.Done
            }
            is NarmestelederbehovDialogCreationResult.Unavailable -> failed(claim, created.failure)
        }
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
            ?: Instant.fromEpochSeconds((clock.now() + 10.minutes).epochSeconds)
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
        val capSeconds = min(900L, 5L * (1L shl min(claim.attempts, 8)))
        return DialogDispatchResult.Retry((clock.now() + Random.nextLong(capSeconds * 500, capSeconds * 1000 + 1).milliseconds).toJavaInstant(), reason)
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
    }
}

data class DialogDispatchSettings(
    val interval: Duration = 5.seconds,
    val lease: Duration = 60.seconds,
    val safetyMargin: Duration = 5.seconds,
    val acknowledgementBudget: Duration = 5.seconds,
    val attemptBudget: Duration = 20.seconds,
    val runBudget: Duration = 20.seconds,
    val maxAttemptsPerRun: Int = 10,
) {
    init {
        require(listOf(interval, lease, safetyMargin, acknowledgementBudget, attemptBudget, runBudget).all { it.isPositive() && it.isFinite() })
        require(attemptBudget + safetyMargin < lease)
        require(acknowledgementBudget + safetyMargin < lease)
        require(acknowledgementBudget < runBudget)
        require(maxAttemptsPerRun > 0)
    }
}
