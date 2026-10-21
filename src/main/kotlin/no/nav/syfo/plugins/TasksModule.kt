package no.nav.syfo.plugins

import no.nav.syfo.narmestelederbehov.application.NarmestelederbehovExpirySettings
import no.nav.syfo.narmestelederbehov.infrastructure.ExpireNarmestelederbehovTask
import no.nav.syfo.narmestelederrelasjon.infrastructure.PersonEnrichmentTask
import no.nav.syfo.sykmelding.retention.infrastructure.DeleteOldSykmeldingerTask
import org.koin.dsl.module
import kotlin.time.Duration

internal fun tasksModule() = module {
    single { NarmestelederbehovExpirySettings(daysAfterTom = env().otherProperties.daysAfterTomToExpireBehovs) }
    single {
        ExpireNarmestelederbehovTask(
            expireNarmestelederbehov = get(),
            interval = Duration.parse(env().otherProperties.maintenanceTaskDelay),
        )
    }
    single {
        DeleteOldSykmeldingerTask(
            deleteOldSykmeldinger = get(),
            interval = Duration.parse(env().otherProperties.maintenanceTaskDelay),
        )
    }
    single {
        PersonEnrichmentTask(
            enrichPendingPersons = get(),
            pollingInterval = Duration.parse(env().otherProperties.personEnrichmentTaskDelay),
        )
    }
}
