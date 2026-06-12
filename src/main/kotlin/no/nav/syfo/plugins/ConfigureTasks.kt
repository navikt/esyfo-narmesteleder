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
import no.nav.syfo.person.task.PersonEnrichmentTask
import no.nav.syfo.util.logger
import org.koin.ktor.ext.inject
import java.util.Collections
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
    val personEnrichmentTask by inject<PersonEnrichmentTask>()

    val taskJobs: MutableList<Job> = Collections.synchronizedList(mutableListOf())

    monitor.subscribe(LeaderChangeEvent) { event ->
        when (event) {
            is LeaderChange.Promoted -> {
                logger.info("Promoted to leader — starting background tasks")

                taskJobs.lock { jobs ->
                    jobs.cancelAndClear()
                    jobs += launch { sendDialogTask.runTask() }
                    jobs += launch { updateDialogTask.runTask() }
                    if (environment.otherProperties.maintenanceTaskEnabled) {
                        logger.info("Maintenance task is enabled. Starting behovMaintenanceTask.")
                        jobs += launch { behovMaintenanceTask.runTask() }
                    } else {
                        logger.info("Maintenance task is NOT enabled. Skipping behovMaintenanceTask.")
                    }
                    if (environment.otherProperties.personEnrichmentTaskEnabled) {
                        jobs += launch { personEnrichmentTask.runTask() }
                    }
                }
            }

            is LeaderChange.Demoted -> {
                logger.info("Demoted from leader — stopping background tasks")
                taskJobs.lock { jobs ->
                    jobs.cancelAndClear()
                }
            }

            is LeaderChange.Unaffected -> {}
        }
    }

    monitor.subscribe(ApplicationStopPreparing) {
        logger.info("Received ApplicationStopPreparing — stopping background tasks")
        taskJobs.lock { jobs ->
            jobs.cancelAndClear()
        }
    }
}

private inline fun MutableList<Job>.lock(block: (MutableList<Job>) -> Unit) = synchronized(this) {
    block(this)
}

private fun MutableList<Job>.cancelAndClear() {
    forEach(Job::cancel)
    clear()
}
