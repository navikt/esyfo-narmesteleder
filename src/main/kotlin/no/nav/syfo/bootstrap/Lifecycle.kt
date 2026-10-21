package no.nav.syfo.bootstrap

import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStarted
import io.ktor.server.application.ApplicationStopped
import no.nav.syfo.platform.observability.ApplicationState
import no.nav.syfo.platform.observability.logger

fun Application.configureLifecycleHooks(applicationState: ApplicationState = ApplicationState()) {
    val logger = logger()

    monitor.subscribe(ApplicationStarted) {
        applicationState.ready = true
        logger.info("Application is ready, running Java VM ${Runtime.version()}")
    }
    monitor.subscribe(ApplicationStopped) {
        applicationState.ready = false
        logger.info("Application is stopped")
    }
}
