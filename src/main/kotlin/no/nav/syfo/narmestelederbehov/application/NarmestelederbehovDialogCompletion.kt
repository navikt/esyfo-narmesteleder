package no.nav.syfo.narmestelederbehov.application

import kotlinx.coroutines.CancellationException
import no.nav.syfo.logging.applicationEvent
import no.nav.syfo.logging.applicationLogger
import no.nav.syfo.logging.logEvent
import org.slf4j.event.Level

/**
 * Completes the Dialogporten dialog of a fulfilled behov and records it. Failures are logged and returned as
 * [DialogportenCompletionAttempt.Failed]; they never fail the fulfillment.
 */
class NarmestelederbehovDialogCompletion(
    private val dialog: NarmestelederbehovDialog,
    private val dialogStatus: NarmestelederbehovDialogStatus,
) {
    suspend fun complete(marked: MarkFulfilledResult.Marked): DialogportenCompletionAttempt {
        val dialogId = marked.dialogId ?: return DialogportenCompletionAttempt.NotApplicable
        val behovId = marked.id.value.toString()
        try {
            dialog.complete(dialogId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.logEvent(dialogportenCompletionFailed, behovId, cause = e)
            return DialogportenCompletionAttempt.Failed
        }
        return try {
            when (dialogStatus.markDialogCompleted(marked.id)) {
                MarkDialogCompletedResult.Marked,
                MarkDialogCompletedResult.NotFulfilled,
                -> DialogportenCompletionAttempt.Completed
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.logEvent(dialogStatusPersistenceFailed, behovId, cause = e)
            DialogportenCompletionAttempt.Failed
        }
    }

    private companion object {
        val logger = applicationLogger(NarmestelederbehovDialogCompletion::class.java)
    }
}

internal val DialogportenCompletionAttempt.logValue: String
    get() = when (this) {
        DialogportenCompletionAttempt.Completed -> "COMPLETED"
        DialogportenCompletionAttempt.Failed -> "FAILED"
        DialogportenCompletionAttempt.NotApplicable -> "NOT_APPLICABLE"
    }

private const val OPERATION = "complete_narmestelederbehov_dialog"

private val dialogStatusPersistenceFailed = applicationEvent<String>(
    name = "narmestelederbehov_dialog_status_persistence_failed",
    level = Level.WARN,
    message = "Dialogporten completed but behov status could not be persisted",
    operation = OPERATION,
    fields = mapOf("behov_id" to { it }),
)

private val dialogportenCompletionFailed = applicationEvent<String>(
    name = "narmestelederbehov_dialogporten_completion_failed",
    level = Level.WARN,
    message = "Dialogporten completion failed; pending behov remains retryable",
    operation = OPERATION,
    upstream = "dialogporten",
    fields = mapOf("behov_id" to { it }),
)
