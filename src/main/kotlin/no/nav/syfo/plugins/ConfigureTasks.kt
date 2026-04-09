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
import java.util.Collections
import kotlin.getValue

fun Application.configureBackgroundTasks() {
    val logger = logger()
    val environment by inject<Environment>()


    val leaderTaskJobs: MutableList<Job> = Collections.synchronizedList(mutableListOf())

    monitor.subscribe(LeaderChangeEvent) { event ->
        when (event) {
            is LeaderChange.Unaffected -> {
                logger.debug("Unaffected by leader election. Skipping background tasks")
            }

            is LeaderChange.Promoted -> {
                logger.info("Elected leader — starting background tasks")

                // Cancel any still-running tasks from a previous election
                synchronized(leaderTaskJobs) {
                    leaderTaskJobs.forEach { it.cancel() }
                    leaderTaskJobs.clear()
                }

                if (environment.otherProperties.isDialogportenBackgroundTaskEnabled) {
                    val sendDialogTask by inject<SendDialogTask>()
                    val updateDialogTask by inject<UpdateDialogTask>()
                    leaderTaskJobs += launch { sendDialogTask.runTask() }
                    leaderTaskJobs += launch { updateDialogTask.runTask() }
                }

                if (environment.otherProperties.maintenanceTaskEnabled) {
                    val behovMaintenanceTask by inject<BehovMaintenanceTask>()
                    leaderTaskJobs += launch { behovMaintenanceTask.runTask() }
                }
            }

            is LeaderChange.Demoted -> {
                logger.info("No longer leader — cancelling background tasks")
                synchronized(leaderTaskJobs) {
                    leaderTaskJobs.forEach { it.cancel() }
                    leaderTaskJobs.clear()
                }
            }
        }
    }

    monitor.subscribe(ApplicationStopPreparing) {
        synchronized(leaderTaskJobs) {
            leaderTaskJobs.forEach { it.cancel() }
            leaderTaskJobs.clear()
        }
    }
}
