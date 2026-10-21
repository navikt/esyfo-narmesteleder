package no.nav.syfo.narmestelederbehov.infrastructure

import no.nav.syfo.narmestelederbehov.application.ExpireNarmestelederbehovUseCase
import no.nav.syfo.platform.scheduling.ScheduledLeaderTask
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
