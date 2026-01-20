package no.nav.syfo.altinn.dialogporten.task

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import no.nav.syfo.altinn.dialogporten.service.DialogportenService
import no.nav.syfo.application.leaderelection.LeaderElection
import no.nav.syfo.util.logger
import kotlin.time.Duration

class DeleteDialogTask(
    private val leaderElection: LeaderElection,
    private val dialogportenService: DialogportenService,
    private val pollingInterval: Duration
) {
    private val logger = logger()

    suspend fun runSetCompletedTask() = coroutineScope {
        try {
            while (isActive) {
                if (leaderElection.isLeader()) {
                    try {
                        logger.info("Starting task for deleting dialogs")
                        dialogportenService.deleteDialogsInDialogporten()
                    } catch (ex: Exception) {
                        logger.error("Could not delete dialogs in dialogporten", ex)
                    }
                }
                delay(pollingInterval)
            }
        } catch (ex: CancellationException) {
            logger.info("Cancelled DeleteDialogTask", ex)
        }
    }
}
