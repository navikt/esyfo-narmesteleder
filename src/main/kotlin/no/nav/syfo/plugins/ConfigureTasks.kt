package no.nav.syfo.plugins

import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStarted
import io.ktor.server.application.ApplicationStopping
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import no.nav.syfo.altinn.dialogporten.task.DeleteDialogTask
import no.nav.syfo.altinn.dialogporten.task.ResendDialogTask
import no.nav.syfo.altinn.dialogporten.task.SendDialogTask
import no.nav.syfo.altinn.dialogporten.task.UpdateDialogTask
import no.nav.syfo.application.environment.Environment
import no.nav.syfo.narmesteleder.task.BehovMaintenanceTask
import no.nav.syfo.util.logger
import org.koin.ktor.ext.inject
import kotlin.getValue

fun Application.configureBackgroundTasks() {
    val logger = logger()
    val environment by inject<Environment>()
    if (environment.otherProperties.deleteDialogportenDialogsTaskProperties.deleteDialogerTaskEnabled) {
        logger.info("Activating delete dialoger task")
        val deleteDialogTask by inject<DeleteDialogTask>()
        val deleteDialogTaskJob = launch { deleteDialogTask.runSetCompletedTask() }
        monitor.subscribe(ApplicationStopping) {
            deleteDialogTaskJob.cancel()
        }
    }
    if (environment.otherProperties.deleteDialogportenDialogsTaskProperties.resendDialogTaskEnabled) {
        logger.info("Activating delete dialoger task")
        val resendDialogTask by inject<ResendDialogTask>()
        val resendDialogTaskJob = launch { resendDialogTask.runTask() }
        monitor.subscribe(ApplicationStopping) {
            resendDialogTaskJob.cancel()
        }
    }
    if (!environment.otherProperties.isDialogportenBackgroundTaskEnabled) {
        logger.info("Integration with Dialogporten is not enabled. Skipping background tasks")
        return
    }
    logger.info("Integration with Dialogporten is enabled. Configuring background tasks")

    val sendDialogTask by inject<SendDialogTask>()
    val updateDialogTast by inject<UpdateDialogTask>()
    val behovMaintenanceTask by inject<BehovMaintenanceTask>()

    val behovMaintenanceTaskJob = launch(start = CoroutineStart.LAZY) { behovMaintenanceTask.runTask() }
    val sendDialogTaskJob = launch(start = CoroutineStart.LAZY) { sendDialogTask.runTask() }
    val updateDialogTaskJob = launch(start = CoroutineStart.LAZY) { updateDialogTast.runTask() }

    monitor.subscribe(ApplicationStarted) {
        behovMaintenanceTaskJob.start()

        if (!environment.otherProperties.isDialogportenBackgroundTaskEnabled) {
            logger.info("Integration with Dialogporten is not enabled. Skipping background tasks")
            return@subscribe
        }
        logger.info("Integration with Dialogporten is enabled. Configuring background tasks")
        sendDialogTaskJob.start()
        updateDialogTaskJob.start()
    }
    monitor.subscribe(ApplicationStopping) {
        behovMaintenanceTaskJob.cancel()

        if (!environment.otherProperties.isDialogportenBackgroundTaskEnabled) {
            return@subscribe
        }
        sendDialogTaskJob.cancel()
        updateDialogTaskJob.cancel()
    }
}
