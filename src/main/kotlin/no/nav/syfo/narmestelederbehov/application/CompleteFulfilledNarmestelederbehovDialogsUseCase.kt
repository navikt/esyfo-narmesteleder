package no.nav.syfo.narmestelederbehov.application

import kotlinx.coroutines.CancellationException
import no.nav.syfo.narmestelederbehov.domain.NarmestelederbehovId
import no.nav.syfo.platform.observability.applicationLogger
import no.nav.syfo.platform.observability.logEvent
import java.time.Clock
import java.time.Instant

interface CompleteFulfilledNarmestelederbehovDialogsRepository {
    /** Up to 100 fulfilled behov with dialogs, strictly older than [createdBefore], oldest first. */
    suspend fun findFulfilledDialogs(createdBefore: Instant): List<NarmestelederbehovDialogReference>

    suspend fun markDialogCompleted(id: NarmestelederbehovId): MarkDialogCompletedResult
}

class CompleteFulfilledNarmestelederbehovDialogsUseCase(
    private val repository: CompleteFulfilledNarmestelederbehovDialogsRepository,
    private val dialog: NarmestelederbehovDialog,
    private val clock: Clock,
) {
    suspend fun execute() {
        val batch = repository.findFulfilledDialogs(clock.instant().minusSeconds(10))
        logger.info("Found ${batch.size} fulfilled behovs to complete in dialogporten")
        for ((id, dialogId) in batch) {
            val details = DialogEventDetails(id.value, dialogId).copy(action = DialogAction.COMPLETE)
            try {
                when (val result = dialog.complete(dialogId)) {
                    DialogCompletion.Completed -> {
                        when (repository.markDialogCompleted(id)) {
                            MarkDialogCompletedResult.Marked -> logger.info("Successfully updated Dialogporten for dialog $dialogId")
                            MarkDialogCompletedResult.NotFulfilled -> logger.info("Behov ${id.value} is no longer fulfilled; dialog completion was not marked")
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
