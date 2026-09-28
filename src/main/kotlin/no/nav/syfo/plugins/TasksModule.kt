package no.nav.syfo.plugins

import no.nav.syfo.altinn.dialogporten.task.SendDialogTask
import no.nav.syfo.altinn.dialogporten.task.UpdateDialogTask
import no.nav.syfo.maintenance.MaintenanceTask
import no.nav.syfo.person.task.PersonEnrichmentTask
import org.koin.dsl.module
import kotlin.time.Duration

internal fun tasksModule() = module {
    single {
        MaintenanceTask(
            narmestelederService = get(),
            deleteOldSykmeldinger = get(),
            env = env().otherProperties,
        )
    }
    single { SendDialogTask(dialogportenService = get()) }
    single {
        UpdateDialogTask(
            dialogportenService = get(),
            pollingInterval = Duration.parse(env().otherProperties.updateDialogportenTaskProperties.pollingDelay),
        )
    }
    single {
        PersonEnrichmentTask(
            personEnrichmentService = get(),
            pollingInterval = Duration.parse(env().otherProperties.personEnrichmentTaskDelay),
        )
    }
}
