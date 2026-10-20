package no.nav.syfo.narmestelederbehov.infrastructure

import no.nav.syfo.application.task.ScheduledLeaderTask
import no.nav.syfo.narmestelederbehov.application.SendPendingNarmestelederbehovDialogsUseCase
import kotlin.time.Duration.Companion.minutes

class SendDialogTask(
    private val sendPendingDialogs: SendPendingNarmestelederbehovDialogsUseCase,
) : ScheduledLeaderTask(
    name = SendDialogTask::class.java.name,
    interval = 5.minutes,
) {
    override suspend fun execute() {
        sendPendingDialogs.execute()
    }
}
