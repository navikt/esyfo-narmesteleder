package no.nav.syfo.plugins

import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopPreparing
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


    // Leader-dependent tasks — started when elected, cancelled when leadership is lost
    val leaderTaskJobs = mutableListOf<Job>()

    monitor.subscribe(LeaderChangeEvent) { event ->
        when (event) {
            is LeaderChange.ElectedLeader -> {
                logger.info("Elected leader — starting background tasks")
                if (environment.otherProperties.maintenanceTaskEnabled) {
                    val behovMaintenanceTask by inject<BehovMaintenanceTask>()
                    leaderTaskJobs += launch { behovMaintenanceTask.runTask() }
                }

                if (environment.otherProperties.isDialogportenBackgroundTaskEnabled) {
                    val sendDialogTask by inject<SendDialogTask>()
                    val updateDialogTask by inject<UpdateDialogTask>()
                    leaderTaskJobs += launch { sendDialogTask.runTask() }
                    leaderTaskJobs += launch { updateDialogTask.runTask() }
                }
            }
            is LeaderChange.NotLeader -> {
                logger.info("No longer leader — cancelling background tasks")
                leaderTaskJobs.forEach { it.cancel() }
                leaderTaskJobs.clear()
            }
        }
    }

    monitor.subscribe(ApplicationStopPreparing) {
        leaderTaskJobs.forEach { it.cancel() }
        leaderTaskJobs.clear()
    }
}
