package no.nav.syfo.plugins

import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopping
import kotlin.getValue
import kotlinx.coroutines.launch
import no.nav.syfo.altinn.dialogporten.task.DeleteDialogTask
import no.nav.syfo.altinn.dialogporten.task.SendDialogTask
import no.nav.syfo.altinn.dialogporten.task.UpdateDialogTask
import no.nav.syfo.application.environment.Environment
import no.nav.syfo.util.logger
import org.koin.ktor.ext.inject

fun Application.configureBackgroundTasks() {
    val environment by inject<Environment>()

    val logger = logger()
    if (!environment.otherProperties.isDialogportenBackgroundTaskEnabled) {
        logger.info("Integration with Dialogporten is not enabled. Skipping background tasks")
        return
    }
    logger.info("Integration with Dialogporten is enabled. Configuring background tasks")

    val sendDialogTask by inject<SendDialogTask>()
    val updateDialogTast by inject<UpdateDialogTask>()
    val deleteDialogTast by inject<DeleteDialogTask>()

    val sendDialogTaskJob = launch { sendDialogTask.runTask() }
    monitor.subscribe(ApplicationStopping) {
        sendDialogTaskJob.cancel()
    }

    val updateDialogTaskJob = launch { updateDialogTast.runSetCompletedTask() }
    monitor.subscribe(ApplicationStopping) {
        updateDialogTaskJob.cancel()
    }

    val deleteDialogTaskJob = launch { deleteDialogTast.runSetCompletedTask() }
    monitor.subscribe(ApplicationStopping) {
        deleteDialogTaskJob.cancel()
    }
}
