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
    platformModule(applicationModules(environment, isLocal))
    bindJvmAndProcessMetrics()
    configureLifecycleHooks(get())
    configureLeaderMonitoring(get(), get())
    organisasjonstilgangModule()
    narmestelederrelasjonModule()
    narmestelederbehovModule()
    narmestelederstatistikkModule()
    sykmeldingModule()
    configureKafkaConsumers(environment)
    configureBackgroundTasks(environment.otherProperties)
    configureEmploymentChecks(environment.otherProperties)
}
