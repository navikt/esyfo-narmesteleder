package no.nav.syfo.bootstrap

import no.nav.syfo.narmestelederbehov.application.NarmestelederbehovExpirySettings
import no.nav.syfo.narmestelederbehov.infrastructure.ExpireNarmestelederbehovTask
import no.nav.syfo.narmestelederrelasjon.infrastructure.PersonEnrichmentTask
import no.nav.syfo.sykmelding.retention.infrastructure.DeleteOldSykmeldingerTask
import org.koin.dsl.module
import kotlin.time.Duration

internal fun tasksModule(other: OtherEnvironmentProperties) = module {
    single { NarmestelederbehovExpirySettings(daysAfterTom = other.daysAfterTomToExpireBehovs) }
    single {
        ExpireNarmestelederbehovTask(
            expireNarmestelederbehov = get(),
            interval = Duration.parse(other.maintenanceTaskDelay),
        )
    }
    single {
        DeleteOldSykmeldingerTask(
            deleteOldSykmeldinger = get(),
            interval = Duration.parse(other.maintenanceTaskDelay),
        )
    }
    single {
        PersonEnrichmentTask(
            enrichPendingPersons = get(),
            pollingInterval = Duration.parse(other.personEnrichmentTaskDelay),
        )
    }
}
