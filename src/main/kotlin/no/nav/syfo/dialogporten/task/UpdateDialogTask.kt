package no.nav.syfo.dialogporten.task

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import no.nav.syfo.application.isProdEnv
import no.nav.syfo.application.leaderelection.LeaderElection
import no.nav.syfo.dialogporten.service.DialogportenService
import no.nav.syfo.util.logger

class UpdateDialogTask(
    private val leaderElection: LeaderElection,
    private val dialogportenService: DialogportenService
) {
    private val logger = logger()

    suspend fun runSetCompletedTask() = coroutineScope {
        try {
            while (isActive) {
                if (leaderElection.isLeader() && !isProdEnv()) {
                    try {
                        logger.info("Starting task for updating dialog statuses")
                        dialogportenService.setAllFulfilledBehovsAsCompletedInDialogporten()
                    } catch (ex: Exception) {
                        logger.error("Could not update dialogs in dialogporten", ex)
                    }
                }
                // delay for  5 minutes before checking again
                delay(5 * 60 * 1000)
            }
        } catch (ex: CancellationException) {
            logger.info("Cancelled SendDialogTask", ex)
        }
    }
}
