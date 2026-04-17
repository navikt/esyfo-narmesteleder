package no.nav.syfo.plugins

import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopPreparing
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import no.nav.syfo.altinn.dialogporten.task.SendDialogTask
import no.nav.syfo.altinn.dialogporten.task.UpdateDialogTask
import no.nav.syfo.application.environment.Environment
import no.nav.syfo.application.events.LeaderChange
import no.nav.syfo.application.events.LeaderChangeEvent
import no.nav.syfo.narmesteleder.task.BehovMaintenanceTask
import no.nav.syfo.util.logger
import org.koin.ktor.ext.inject
import kotlin.getValue

fun Application.configureBackgroundTasks() {
    val logger = logger()
    val environment by inject<Environment>()
    if (!environment.otherProperties.isDialogportenBackgroundTaskEnabled) {
        logger.info("Integration with Dialogporten is not enabled. Skipping background tasks")
        return
    }
    logger.info("Integration with Dialogporten is enabled. Configuring background tasks")

    val sendDialogTask by inject<SendDialogTask>()
    val updateDialogTask by inject<UpdateDialogTask>()
    val behovMaintenanceTask by inject<BehovMaintenanceTask>()

    val taskLock = Any()
    var taskJob: Job? = null

    fun replaceTaskJob(newJob: Job?) {
        synchronized(taskLock) {
            taskJob?.cancel()
            taskJob = newJob
        }
    }

    monitor.subscribe(LeaderChangeEvent) { event ->
        when (event) {
            is LeaderChange.Promoted -> {
                logger.info("Promoted to leader — starting background tasks")
                synchronized(taskLock) {
                    taskJob?.cancel()
                    taskJob = launch {
                        launch { sendDialogTask.runTask() }
                        launch { updateDialogTask.runTask() }
                        if (environment.otherProperties.maintenanceTaskEnabled) {
                            launch { behovMaintenanceTask.runTask() }
                        }
                    }
                }
            }

            is LeaderChange.Demoted -> {
                replaceTaskJob(null)
            }

            is LeaderChange.Unaffected -> {}
        }
    }

    monitor.subscribe(ApplicationStopPreparing) {
        replaceTaskJob(null)
    }
}
