package no.nav.syfo.altinn.dialogporten.task

import no.nav.syfo.altinn.dialogporten.service.DialogportenService
import no.nav.syfo.application.task.ScheduledLeaderTask
import kotlin.time.Duration.Companion.minutes

class SendDialogTask(
    private val dialogportenService: DialogportenService,
) : ScheduledLeaderTask(
    name = SendDialogTask::class.qualifiedName!!,
    interval = 5.minutes,
) {
    override suspend fun execute() {
        dialogportenService.sendDocumentsToDialogporten()
    }
}
