package no.nav.syfo.bootstrap

import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopPreparing
import io.ktor.server.application.ServerReady
import kotlinx.coroutines.runBlocking
import no.nav.syfo.narmestelederrelasjon.application.CheckNarmestelederrelasjonEmploymentUseCase
import no.nav.syfo.narmestelederrelasjon.application.EmploymentCheckSettings
import no.nav.syfo.narmestelederrelasjon.application.SeedNarmestelederrelasjonEmploymentChecksUseCase
import no.nav.syfo.platform.scheduling.BackgroundLoop
import org.koin.ktor.ext.inject

internal fun Application.configureEmploymentChecks(other: OtherEnvironmentProperties) {
    if (!other.employmentCheckEnabled) return

    val settings by inject<EmploymentCheckSettings>()
    val check by inject<CheckNarmestelederrelasjonEmploymentUseCase>()
    val seed by inject<SeedNarmestelederrelasjonEmploymentChecksUseCase>()
    val checkLoop = BackgroundLoop(name = "employment-check", interval = settings.checkInterval) { check.execute() }
    val seedLoop = BackgroundLoop(name = "employment-check-seed", interval = settings.seedInterval) { seed.execute() }

    // Runs on every pod without leader election; claims with a lease split the work (ADR-0003).
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
