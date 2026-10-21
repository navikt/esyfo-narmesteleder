package no.nav.syfo.narmestelederbehov.application

import kotlinx.coroutines.CancellationException
import no.nav.syfo.platform.observability.applicationLogger
import no.nav.syfo.platform.observability.logEvent
import java.time.Clock

class CompleteFulfilledNarmestelederbehovDialogsUseCase(
    private val repository: NarmestelederbehovRepository,
    private val dialog: NarmestelederbehovDialog,
    private val clock: Clock,
) {
    suspend fun execute() {
        val batch = repository.findFulfilledDialogs(clock.instant().minusSeconds(10))
        logger.info("Found ${batch.size} fulfilled behovs to complete in dialogporten")
        for (behov in batch) {
            val details = DialogEventDetails(behov.id.value, behov.dialogId).copy(action = DialogAction.COMPLETE)
            try {
                when (val result = dialog.complete(behov.dialogId)) {
                    DialogCompletion.Completed -> {
                        when (repository.markDialogCompleted(behov.id)) {
                            MarkDialogCompletedResult.Marked -> logger.info("Successfully updated Dialogporten for dialog ${behov.dialogId}")
                            MarkDialogCompletedResult.NotFulfilled -> logger.info("Behov ${behov.id.value} is no longer fulfilled; dialog completion was not marked")
                        }
                    }
                    is DialogCompletion.Unavailable -> logger.logEvent(dialogportenDialogFailed, details, upstreamFailure = result.failure)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.logEvent(dialogportenDialogFailed, details, cause = e)
            }
        }
    }

    private companion object {
        val logger = applicationLogger(CompleteFulfilledNarmestelederbehovDialogsUseCase::class.java)
    }
}
