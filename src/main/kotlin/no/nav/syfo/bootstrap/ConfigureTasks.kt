package no.nav.syfo.bootstrap

import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopPreparing
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import no.nav.syfo.narmestelederbehov.infrastructure.ExpireNarmestelederbehovTask
import no.nav.syfo.narmestelederbehov.infrastructure.SendDialogTask
import no.nav.syfo.narmestelederbehov.infrastructure.UpdateDialogTask
import no.nav.syfo.narmestelederrelasjon.infrastructure.PersonEnrichmentTask
import no.nav.syfo.platform.observability.logger
import no.nav.syfo.platform.scheduling.LeaderChange
import no.nav.syfo.platform.scheduling.LeaderChangeEvent
import no.nav.syfo.sykmelding.retention.infrastructure.DeleteOldSykmeldingerTask
import org.koin.ktor.ext.inject
import java.util.Collections
import kotlin.getValue

internal fun Application.configureBackgroundTasks(other: OtherEnvironmentProperties) {
    val logger = logger()
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
                    if (other.isDialogportenBackgroundTaskEnabled) {
                        jobs += launch { sendDialogTask.runTask() }
                        jobs += launch { updateDialogTask.runTask() }
                        if (other.personEnrichmentTaskEnabled) {
                            jobs += launch { personEnrichmentTask.runTask() }
                        }
                    } else {
                        logger.info(
                            "Integration with Dialogporten is not enabled. " +
                                "Skipping Dialogporten background tasks",
                        )
                    }
                    if (other.maintenanceTaskEnabled) {
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
