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
import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.math.min
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlin.time.toJavaDuration
import kotlin.time.toKotlinDuration

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
    private val repository: DialogDispatchRepository,
    private val creation: NarmestelederbehovDialogCreation,
    private val completion: NarmestelederbehovDialog,
    private val expiry: NarmestelederbehovDialogExpiry,
    private val inspection: DialogExpiryInspection,
    private val personLookup: EmployeePersonLookup,
    private val clock: Clock,
    private val metrics: DialogDispatchMetrics,
    private val settings: DialogDispatchSettings = DialogDispatchSettings(),
) {
    suspend fun execute() {
        val run = TimeSource.Monotonic.markNow()
        repeat(settings.maxAttemptsPerRun) {
            val remaining = settings.runBudget - run.elapsedNow()
            if (remaining <= Duration.ZERO) return
            val claim = repository.claim(clock.instant(), settings.lease) ?: return
            val leaseRemaining = java.time.Duration.between(clock.instant(), claim.leaseUntil).toKotlinDuration() - settings.safetyMargin
            val budget = minOf(remaining, settings.attemptBudget, leaseRemaining)
            if (budget <= Duration.ZERO) return
            val result = try {
                withTimeoutOrNull(budget) { reconcile(claim) } ?: retry(claim, "timeout")
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Exception messages can contain upstream payloads. Persist only a bounded technical reason.
                logger.logEvent(dialogDispatchFailed, claim.id.value.toString())
                retry(claim, "unexpected")
            }
            if (repository.finish(claim, result, clock.instant())) {
                metrics.record(
                    when (result) {
                        DialogDispatchResult.Done -> "done"
                        is DialogDispatchResult.Retry -> "retry"
                        is DialogDispatchResult.Parked -> "parked"
                    },
                )
            }
        }
    }

    private suspend fun reconcile(claim: DialogClaim): DialogDispatchResult {
        val state = repository.load(claim, clock.instant()) ?: return DialogDispatchResult.Done
        val dialogId = state.dialogId
        if (dialogId == null) {
            if (state.status != BehovStatus.BEHOV_CREATED && !state.createStarted) return DialogDispatchResult.Done
            return create(claim, state)
        }
        return when (state.status) {
            BehovStatus.BEHOV_FULFILLED -> when (val completed = completion.complete(dialogId)) {
                DialogCompletion.Completed -> {
                    repository.saveCompleted(claim, clock.instant())
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
            (personLookup.find(state.employee.personIdent) as? EmployeePersonLookupResult.Found)?.person
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        if (!repository.beginCreation(claim, clock.instant())) return DialogDispatchResult.Done
        return when (val created = creation.create(claim.id, state.employee, person)) {
            is NarmestelederbehovDialogCreationResult.Created -> {
                repository.saveCreated(claim, created.dialogId, person?.name, clock.instant())
                DialogDispatchResult.Done
            }
            is NarmestelederbehovDialogCreationResult.Unavailable -> failed(claim, created.failure)
        }
    }

    private suspend fun expire(claim: DialogClaim, state: DialogDispatchState, dialogId: UUID): DialogDispatchResult {
        val observed = when (val observation = inspection.inspect(dialogId)) {
            is DialogExpiryObservation.Found -> observation.expiresAt
            is DialogExpiryObservation.Unavailable -> return failed(claim, observation.failure)
        }
        val intended = state.intendedExpiry
        if (intended != null && observed == intended) {
            repository.saveExpired(claim, intended, clock.instant())
            return DialogDispatchResult.Done
        }
        val safeThrough = clock.instant().plus((settings.attemptBudget + settings.safetyMargin).toJavaDuration())
        val target = intended?.takeIf { it.isAfter(safeThrough) }
            ?: clock.instant().plusSeconds(600).truncatedTo(ChronoUnit.SECONDS)
        if (target != intended && !repository.prepareExpiry(claim, target, clock.instant())) return DialogDispatchResult.Done
        return when (val expired = expiry.expire(dialogId, target)) {
            DialogExpiry.Expired -> {
                repository.saveExpired(claim, target, clock.instant())
                DialogDispatchResult.Done
            }
            is DialogExpiry.Unavailable -> failed(claim, expired.failure)
        }
    }

    private fun failed(claim: DialogClaim, failure: UpstreamFailure): DialogDispatchResult {
        logger.logEvent(dialogDispatchFailed, claim.id.value.toString(), upstreamFailure = failure)
        return if (failure.stage == UpstreamFailureStage.RESPONSE && failure.status in setOf(400, 422)) {
            DialogDispatchResult.Parked("invalid_request")
        } else {
            retry(claim, "upstream")
        }
    }

    private fun retry(claim: DialogClaim, reason: String): DialogDispatchResult.Retry {
        val capSeconds = min(900L, 5L * (1L shl min(claim.attempts, 8)))
        return DialogDispatchResult.Retry(clock.instant().plusMillis(Random.nextLong(capSeconds * 500, capSeconds * 1000 + 1)), reason)
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
    val attemptBudget: Duration = 20.seconds,
    val runBudget: Duration = 20.seconds,
    val maxAttemptsPerRun: Int = 10,
) {
    init {
        require(listOf(interval, lease, safetyMargin, attemptBudget, runBudget).all { it.isPositive() && it.isFinite() })
        require(attemptBudget + safetyMargin < lease)
        require(maxAttemptsPerRun > 0)
    }
}
