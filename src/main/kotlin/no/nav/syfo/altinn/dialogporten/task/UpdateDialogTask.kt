package no.nav.syfo.altinn.dialogporten.task

import no.nav.syfo.altinn.dialogporten.service.DialogportenService
import no.nav.syfo.application.task.ScheduledLeaderTask
import kotlin.time.Duration

class UpdateDialogTask(
    private val dialogportenService: DialogportenService,
    pollingInterval: Duration,
) : ScheduledLeaderTask(
    name = "UpdateDialogTask",
    interval = pollingInterval,
) {
    override suspend fun execute() {
        dialogportenService.setAllFulfilledBehovsAsCompletedInDialogporten()
        dialogportenService.setAllExpiredBehovsAsExpiredAndCompletedInDialogporten()
    }
}
