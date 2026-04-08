package no.nav.syfo.plugins

import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStarted
import io.ktor.server.application.ApplicationStopped
import no.nav.syfo.application.HealthState
import no.nav.syfo.util.logger

fun Application.configureLifecycleHooks(healthState: HealthState = HealthState()) {
    val logger = logger()

    monitor.subscribe(ApplicationStarted) {
        healthState.ready = true
        logger.info("Application is ready, running Java VM ${Runtime.version()}")
    }
    monitor.subscribe(ApplicationStopped) {
        healthState.ready = false
        logger.info("Application is stopped")
    }
}
