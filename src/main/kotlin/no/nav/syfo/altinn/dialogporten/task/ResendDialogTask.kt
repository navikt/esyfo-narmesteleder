package no.nav.syfo.altinn.dialogporten.task

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import no.nav.syfo.altinn.dialogporten.service.DialogportenService
import no.nav.syfo.application.leaderelection.LeaderElection
import no.nav.syfo.util.logger
import kotlin.time.Duration
/**
 * This class can be removed after we have fixed requirements and dialogs due to incorrect url in
 * dialog attachment
 */
class ResendDialogTask(
    private val leaderElection: LeaderElection,
    private val dialogportenService: DialogportenService,
    private val pollingInterval: Duration,
) {
    private val logger = logger()

    suspend fun runTask() = coroutineScope {
        try {
            while (isActive) {
                if (leaderElection.isLeader()) {
                    try {
                        logger.info("Starting task for resending dialogs to dialogporten")
                        dialogportenService.resendDocumentsToDialogporten()
                    } catch (ex: Exception) {
                        logger.error("Could not resend dialogs to dialogporten", ex)
                    }
                }
                // delay for  5 minutes before checking again
                delay(pollingInterval)
            }
        } catch (ex: CancellationException) {
            logger.info("Cancelled ResendDialogTask", ex)
        }
    }
}
