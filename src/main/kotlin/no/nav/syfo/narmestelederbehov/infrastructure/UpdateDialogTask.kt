package no.nav.syfo.narmestelederbehov.infrastructure

import no.nav.syfo.application.task.ScheduledLeaderTask
import no.nav.syfo.narmestelederbehov.application.CompleteFulfilledNarmestelederbehovDialogsUseCase
import no.nav.syfo.narmestelederbehov.application.ExpireNarmestelederbehovDialogsUseCase
import kotlin.time.Duration

class UpdateDialogTask(
    private val completeFulfilledDialogs: CompleteFulfilledNarmestelederbehovDialogsUseCase,
    private val expireDialogs: ExpireNarmestelederbehovDialogsUseCase,
    pollingInterval: Duration,
) : ScheduledLeaderTask(
    name = UpdateDialogTask::class.java.name,
    interval = pollingInterval,
) {
    override suspend fun execute() {
        completeFulfilledDialogs.execute()
        expireDialogs.execute()
    }
}
