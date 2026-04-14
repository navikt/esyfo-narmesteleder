package no.nav.syfo.altinn.dialogporten.task

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import no.nav.syfo.altinn.dialogporten.service.DialogportenService
import no.nav.syfo.util.logger
import kotlin.time.Duration.Companion.minutes

class SendDialogTask(
    private val dialogportenService: DialogportenService
) {
    private val logger = logger()

    suspend fun runTask() = coroutineScope {
        try {
            while (isActive) {
                try {
                    logger.info("Starting task for sending documents to dialogporten")
                    dialogportenService.sendDocumentsToDialogporten()
                } catch (ex: Exception) {
                    logger.error("Could not send dialogs to dialogporten", ex)
                }
                delay(5.minutes)
            }
        } catch (ex: CancellationException) {
            logger.info("Cancelled SendDialogTask", ex)
        }
    }
}
