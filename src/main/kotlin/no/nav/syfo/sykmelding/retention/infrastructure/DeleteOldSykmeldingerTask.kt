package no.nav.syfo.sykmelding.retention.infrastructure

import no.nav.syfo.application.task.ScheduledLeaderTask
import no.nav.syfo.sykmelding.retention.application.DeleteOldSykmeldinger
import kotlin.time.Duration

class DeleteOldSykmeldingerTask(
    private val deleteOldSykmeldinger: DeleteOldSykmeldinger,
    interval: Duration,
) : ScheduledLeaderTask(
    name = DeleteOldSykmeldingerTask::class.java.name,
    interval = interval,
) {
    override suspend fun execute() {
        deleteOldSykmeldinger.execute()
    }
}
