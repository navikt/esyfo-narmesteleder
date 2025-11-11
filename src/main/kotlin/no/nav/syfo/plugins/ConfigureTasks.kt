package no.nav.syfo.plugins

import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopping
import kotlin.getValue
import kotlinx.coroutines.launch
import no.nav.syfo.altinn.dialogporten.task.SendDialogTask
import no.nav.syfo.altinn.dialogporten.task.UpdateDialogTask
import org.koin.ktor.ext.inject

fun Application.configureBackgroundTasks() {
    val sendDialogTask by inject<SendDialogTask>()
    val updateDialogTast by inject<UpdateDialogTask>()

    val sendDialogTaskJob = launch { sendDialogTask.runTask() }
    monitor.subscribe(ApplicationStopping) {
        sendDialogTaskJob.cancel()
    }

    val updateDialogTaskJob = launch { updateDialogTast.runSetCompletedTask() }
    monitor.subscribe(ApplicationStopping) {
        updateDialogTaskJob.cancel()
    }
}
