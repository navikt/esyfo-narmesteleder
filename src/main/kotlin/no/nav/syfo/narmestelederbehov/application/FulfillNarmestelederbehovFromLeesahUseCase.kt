package no.nav.syfo.narmestelederbehov.application

import kotlinx.coroutines.CancellationException
import no.nav.esyfo.observability.Event
import no.nav.syfo.logging.applicationLogger
import no.nav.syfo.logging.logEvent
import no.nav.syfo.narmestelederbehov.domain.Employee
import no.nav.syfo.narmestelederbehov.domain.NarmestelederbehovId
import org.slf4j.event.Level

/**
 * Fulfills the employee's open behov when Leesah reports an active narmesteleder registered outside this service.
 * The status is saved before the Dialogporten dialog is completed, so a dialog failure leaves the behov fulfilled.
 */
class FulfillNarmestelederbehovFromLeesahUseCase(
    private val behovRepository: NarmestelederbehovRepository,
    private val metrics: LeesahFulfillmentMetrics,
    private val dialog: NarmestelederbehovDialog,
) {
    suspend fun execute(employee: Employee) {
        behovRepository.findOpenFor(employee).forEach { fulfill(it) }
    }

    private suspend fun fulfill(id: NarmestelederbehovId) {
        when (val marked = behovRepository.markFulfilled(id)) {
            is MarkFulfilledResult.Marked -> {
                metrics.recordFulfilled()
                val completion = completeDialog(marked)
                logger.event(fulfilledFromLeesah, FulfilledFromLeesah(id, completion))
            }
            MarkFulfilledResult.Missing -> Unit
        }
    }

    private suspend fun completeDialog(marked: MarkFulfilledResult.Marked): DialogportenCompletionAttempt {
        val dialogId = marked.dialogId ?: return DialogportenCompletionAttempt.NotApplicable
        try {
            dialog.complete(dialogId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.logEvent(dialogportenCompletionFailed, marked.id.value.toString(), cause = e)
            return DialogportenCompletionAttempt.Failed
        }
        return try {
            when (behovRepository.markDialogCompleted(marked.id)) {
                MarkDialogCompletedResult.Marked,
                MarkDialogCompletedResult.NotFulfilled,
                -> DialogportenCompletionAttempt.Completed
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.logEvent(dialogStatusPersistenceFailed, marked.id.value.toString(), cause = e)
            DialogportenCompletionAttempt.Failed
        }
    }

    private companion object {
        val logger = applicationLogger(FulfillNarmestelederbehovFromLeesahUseCase::class.java)
    }
}

private data class FulfilledFromLeesah(val id: NarmestelederbehovId, val dialogCompletion: DialogportenCompletionAttempt)

private val fulfilledFromLeesah = Event<FulfilledFromLeesah>(
    name = "narmestelederbehov_fulfilled_from_leesah",
    level = Level.INFO,
    message = "Narmestelederbehov fulfilled after a new narmesteleder was registered on Leesah",
    operation = "fulfill_narmestelederbehov_from_leesah",
    fields = mapOf(
        "behov_id" to { it.id.value.toString() },
        "dialogporten_completion" to {
            when (it.dialogCompletion) {
                DialogportenCompletionAttempt.Completed -> "COMPLETED"
                DialogportenCompletionAttempt.Failed -> "FAILED"
                DialogportenCompletionAttempt.NotApplicable -> "NOT_APPLICABLE"
            }
        },
    ),
)
