package no.nav.syfo.altinn.dialogporten.task

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import no.nav.syfo.altinn.dialogporten.service.DialogportenService
import no.nav.syfo.application.leaderelection.LeaderElection
import no.nav.syfo.util.logger
import kotlin.time.Duration

class UpdateDialogTask(
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
                        logger.info("Starting task for updating dialog statuses")
                        dialogportenService.setAllFulfilledBehovsAsCompletedInDialogporten()
                    } catch (ex: Exception) {
                        logger.error("Could not update dialogs in dialogporten", ex)
                    }
                }
                delay(pollingInterval)
            }
        } catch (ex: CancellationException) {
            logger.info("Cancelled SendDialogTask", ex)
        }
    }
}
