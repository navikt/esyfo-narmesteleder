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
import no.nav.syfo.narmestelederbehov.infrastructure.ExpireNarmestelederbehovTask
import no.nav.syfo.narmestelederrelasjon.infrastructure.PersonEnrichmentTask
import no.nav.syfo.sykmelding.retention.infrastructure.DeleteOldSykmeldingerTask
import no.nav.syfo.util.logger
import org.koin.ktor.ext.inject
import java.util.Collections
import kotlin.getValue

fun Application.configureBackgroundTasks() {
    val logger = logger()
    val environment by inject<Environment>()
    val sendDialogTask by inject<SendDialogTask>()
    val updateDialogTask by inject<UpdateDialogTask>()
    val expireNarmestelederbehovTask by inject<ExpireNarmestelederbehovTask>()
    val deleteOldSykmeldingerTask by inject<DeleteOldSykmeldingerTask>()
    val personEnrichmentTask by inject<PersonEnrichmentTask>()

    val taskJobs: MutableList<Job> = Collections.synchronizedList(mutableListOf())

    monitor.subscribe(LeaderChangeEvent) { event ->
        when (event) {
            is LeaderChange.Promoted -> {
                logger.info("Promoted to leader — starting background tasks")
                taskJobs.lock { jobs ->
                    jobs.cancelAndClear()
                    if (environment.otherProperties.isDialogportenBackgroundTaskEnabled) {
                        jobs += launch { sendDialogTask.runTask() }
                        jobs += launch { updateDialogTask.runTask() }
                        if (environment.otherProperties.personEnrichmentTaskEnabled) {
                            jobs += launch { personEnrichmentTask.runTask() }
                        }
                    } else {
                        logger.info(
                            "Integration with Dialogporten is not enabled. " +
                                "Skipping Dialogporten background tasks",
                        )
                    }
                    if (environment.otherProperties.maintenanceTaskEnabled) {
                        logger.info("Maintenance tasks are enabled. Starting behov expiry and sykmelding retention tasks.")
                        jobs += launch { expireNarmestelederbehovTask.runTask() }
                        jobs += launch { deleteOldSykmeldingerTask.runTask() }
                    } else {
                        logger.info("Maintenance tasks are NOT enabled. Skipping behov expiry and sykmelding retention tasks.")
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
