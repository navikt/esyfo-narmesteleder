package no.nav.syfo.plugins

import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopping
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

    var sendDialogTaskJob: Job? = null
    var updateDialogTaskJob: Job? = null
    var behovMaintenanceTaskJob: Job? = null

    monitor.subscribe(LeaderChangeEvent) { event ->
        when (event) {
            is LeaderChange.Promoted -> {
                logger.info("Promoted to leader — starting background tasks")
                sendDialogTaskJob = launch { sendDialogTask.runTask() }
                updateDialogTaskJob = launch { updateDialogTask.runTask() }

                if (environment.otherProperties.maintenanceTaskEnabled) {
                    behovMaintenanceTaskJob = launch { behovMaintenanceTask.runTask() }
                }
            }

            is LeaderChange.Demoted -> {
                logger.info("Demoted from leader — cancelling background tasks")
                sendDialogTaskJob?.cancel()
                updateDialogTaskJob?.cancel()
                behovMaintenanceTaskJob?.cancel()
            }

            is LeaderChange.Unaffected -> {}
        }
    }

    monitor.subscribe(ApplicationStopping) {
        sendDialogTaskJob?.cancel()
        updateDialogTaskJob?.cancel()
        behovMaintenanceTaskJob?.cancel()
    }
}
