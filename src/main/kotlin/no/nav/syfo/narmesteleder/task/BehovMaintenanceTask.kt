package no.nav.syfo.narmesteleder.task

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import no.nav.syfo.application.environment.OtherEnvironmentProperties
import no.nav.syfo.application.leaderelection.LeaderElection
import no.nav.syfo.narmesteleder.service.NarmestelederService
import no.nav.syfo.util.logger
import kotlin.time.Duration

class BehovMaintenanceTask(
    private val narmestelederService: NarmestelederService,
    private val leaderElection: LeaderElection,
    private val env: OtherEnvironmentProperties
) {
    private val logger = logger()

    suspend fun runTask() = coroutineScope {
        try {
            while (isActive) {
                if (leaderElection.isLeader()) {
                    try {
                        logger.info("Starting task for behov maintenance")
                        narmestelederService.updateStatusOnExpiredBehovs(
                            env.daysAfterTomToExpireBehovs
                        )
                    } catch (ex: Exception) {
                        logger.error("Something went wrong", ex)
                    }
                }
                delay(Duration.parse(env.maintenanceTaskDelay))
            }
        } catch (ex: CancellationException) {
            logger.info("Cancelled BehovMaintenanceTask", ex)
        }
    }
}
