package no.nav.syfo.bootstrap

import io.ktor.server.application.Application
import no.nav.syfo.narmestelederbehov.narmestelederbehovModule
import no.nav.syfo.narmestelederrelasjon.narmestelederrelasjonModule
import no.nav.syfo.narmestelederstatistikk.narmestelederstatistikkModule
import no.nav.syfo.organisasjonstilgang.organisasjonstilgangModule
import no.nav.syfo.platform.observability.bindJvmAndProcessMetrics
import no.nav.syfo.sykmelding.sykmeldingModule
import org.koin.ktor.ext.get

/** Reads the environment once, assembles the Koin graph and starts every capability. */
fun Application.bootstrap(isLocal: Boolean = isLocalEnv()) {
    val environment = loadEnvironment(isLocal)
    platformModule(applicationModules(environment, isLocal), environment.texas.azurePreAuthorizedApps)
    bindJvmAndProcessMetrics()
    configureLifecycleHooks(get())
    configureLeaderMonitoring(get(), get())
    organisasjonstilgangModule()
    narmestelederrelasjonModule(environment.narmestelederrelasjonEmploymentSettings)
    narmestelederbehovModule(environment.narmestelederbehovSettings)
    narmestelederstatistikkModule()
    sykmeldingModule()
    configureKafkaConsumers(
        kafkaSettings = environment.kafka,
        narmestelederrelasjonKafkaSettings = environment.narmestelederrelasjonKafkaSettings,
        sykmeldingKafkaSettings = environment.sykmeldingKafkaSettings,
    )
    configureBackgroundTasks(
        dialogportenTaskSettings = environment.dialogportenTaskSettings,
        maintenanceTaskSettings = environment.maintenanceTaskSettings,
        personEnrichmentTaskSettings = environment.personEnrichmentTaskSettings,
    )
    configureEmploymentChecks(environment.narmestelederrelasjonEmploymentSettings)
}
