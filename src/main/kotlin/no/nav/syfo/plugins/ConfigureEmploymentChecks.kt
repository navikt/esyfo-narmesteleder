package no.nav.syfo.plugins

import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopPreparing
import io.ktor.server.application.ServerReady
import kotlinx.coroutines.runBlocking
import no.nav.syfo.narmestelederrelasjon.application.CheckNarmestelederrelasjonEmploymentUseCase
import no.nav.syfo.narmestelederrelasjon.application.EmploymentCheckSettings
import no.nav.syfo.narmestelederrelasjon.application.SeedNarmestelederrelasjonEmploymentChecksUseCase
import no.nav.syfo.platform.scheduling.BackgroundLoop
import org.koin.ktor.ext.inject

fun Application.configureEmploymentChecks() {
    val settings by inject<EmploymentCheckSettings>()
    if (!settings.enabled) return

    val check by inject<CheckNarmestelederrelasjonEmploymentUseCase>()
    val seed by inject<SeedNarmestelederrelasjonEmploymentChecksUseCase>()
    val checkLoop = BackgroundLoop(name = "employment-check", interval = settings.interval) { check.execute() }
    val seedLoop = BackgroundLoop(name = "employment-check-seed", interval = settings.seedInterval) { seed.execute() }

    // Deliberately independent of leader election: the repository distributes claims across all pods.
    monitor.subscribe(ServerReady) {
        seedLoop.start(this)
        checkLoop.start(this)
    }
    monitor.subscribe(ApplicationStopPreparing) {
        runBlocking {
            checkLoop.stop()
            seedLoop.stop()
        }
    }
}
