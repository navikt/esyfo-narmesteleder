package no.nav.syfo.narmestelederbehov.infrastructure

import no.nav.syfo.narmestelederbehov.application.SendPendingNarmestelederbehovDialogsUseCase
import no.nav.syfo.platform.scheduling.ScheduledLeaderTask
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
