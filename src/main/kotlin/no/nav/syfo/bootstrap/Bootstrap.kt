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
 * Assembles the Koin graph from [environment] and starts every capability.
 * Production passes [NaisEnvironment] and uses the real external clients and leader election;
 * only local runs pass [externalClients] and [leaderElection] with fakes.
 */
fun Application.bootstrap(environment: Environment, externalClients: Module? = null, leaderElection: Module? = null) {
    platformModule(applicationModules(environment, externalClients, leaderElection), environment.texas.azurePreAuthorizedApps)
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
