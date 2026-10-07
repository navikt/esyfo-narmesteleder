package no.nav.syfo.narmestelederbehov.infrastructure

import no.nav.syfo.application.task.ScheduledLeaderTask
import no.nav.syfo.narmestelederbehov.application.ExpireNarmestelederbehovUseCase
import kotlin.time.Duration

class ExpireNarmestelederbehovTask(
    private val expireNarmestelederbehov: ExpireNarmestelederbehovUseCase,
    interval: Duration,
) : ScheduledLeaderTask(
    name = ExpireNarmestelederbehovTask::class.java.name,
    interval = interval,
) {
    override suspend fun execute() {
        expireNarmestelederbehov.execute()
    }
}
