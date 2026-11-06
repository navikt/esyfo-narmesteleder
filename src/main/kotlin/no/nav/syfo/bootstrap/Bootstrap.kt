package no.nav.syfo.bootstrap

import io.ktor.server.application.Application
import no.nav.syfo.narmestelederbehov.narmestelederbehovModule
import no.nav.syfo.narmestelederrelasjon.narmestelederrelasjonModule
import no.nav.syfo.narmestelederstatistikk.narmestelederstatistikkModule
import no.nav.syfo.organisasjonstilgang.organisasjonstilgangModule
import no.nav.syfo.platform.observability.bindJvmAndProcessMetrics
import no.nav.syfo.sykmelding.sykmeldingModule
import org.koin.core.module.Module
import org.koin.ktor.ext.get

/**
 * Assembles the Koin graph from [koinModules] and starts every capability with settings from [settings].
 */
fun Application.bootstrap(settings: ApplicationSettings, koinModules: List<Module> = applicationModules(settings)) {
    platformModule(koinModules, settings.texas.azurePreAuthorizedApps)
    bindJvmAndProcessMetrics()
    configureLifecycleHooks(get())
    configureLeaderMonitoring(get(), get())
    organisasjonstilgangModule()
    narmestelederrelasjonModule(settings.narmestelederrelasjonEmploymentSettings)
    narmestelederbehovModule(settings.narmestelederbehovSettings)
    narmestelederstatistikkModule()
    sykmeldingModule()
    configureKafkaConsumers(
        kafkaSettings = settings.kafka,
        narmestelederrelasjonKafkaSettings = settings.narmestelederrelasjonKafkaSettings,
        sykmeldingKafkaSettings = settings.sykmeldingKafkaSettings,
    )
    configureBackgroundTasks(
        dialogportenTaskSettings = settings.dialogportenTaskSettings,
        maintenanceTaskSettings = settings.maintenanceTaskSettings,
        personEnrichmentTaskSettings = settings.personEnrichmentTaskSettings,
    )
    configureEmploymentChecks(settings.narmestelederrelasjonEmploymentSettings)
}
