package no.nav.syfo.narmesteleder.task

import no.nav.syfo.application.environment.OtherEnvironmentProperties
import no.nav.syfo.application.task.ScheduledLeaderTask
import no.nav.syfo.narmesteleder.service.NarmestelederService
import kotlin.time.Duration

class BehovMaintenanceTask(
    private val narmestelederService: NarmestelederService,
    private val env: OtherEnvironmentProperties
) : ScheduledLeaderTask(
    name = "BehovMaintenanceTask",
    interval = Duration.parse(env.maintenanceTaskDelay),
) {
    override suspend fun execute() {
        narmestelederService.updateStatusOnExpiredBehovs(env.daysAfterTomToExpireBehovs)
    }
}
