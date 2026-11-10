package no.nav.syfo.bootstrap

import no.nav.syfo.narmestelederbehov.application.NarmestelederbehovExpirySettings
import no.nav.syfo.narmestelederbehov.infrastructure.ExpireNarmestelederbehovTask
import no.nav.syfo.narmestelederrelasjon.infrastructure.PersonEnrichmentTask
import no.nav.syfo.narmestelederrelasjon.infrastructure.PersonEnrichmentTaskSettings
import no.nav.syfo.sykmelding.retention.infrastructure.DeleteOldSykmeldingerTask
import org.koin.dsl.module

internal fun tasksModule(
    narmestelederbehovExpirySettings: NarmestelederbehovExpirySettings,
    maintenanceTaskSettings: MaintenanceTaskSettings,
    personEnrichmentTaskSettings: PersonEnrichmentTaskSettings,
) = module {
    single { narmestelederbehovExpirySettings }
    single {
        ExpireNarmestelederbehovTask(
            expireNarmestelederbehov = get(),
            interval = maintenanceTaskSettings.interval,
        )
    }
    single {
        DeleteOldSykmeldingerTask(
            deleteOldSykmeldinger = get(),
            interval = maintenanceTaskSettings.interval,
        )
    }
    single {
        PersonEnrichmentTask(
            enrichPendingPersons = get(),
            pollingInterval = personEnrichmentTaskSettings.pollingInterval,
        )
    }
}
