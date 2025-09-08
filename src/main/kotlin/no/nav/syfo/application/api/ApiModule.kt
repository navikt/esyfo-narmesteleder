package no.nav.syfo.application.api

import io.ktor.server.application.Application
import io.ktor.server.routing.routing
import no.nav.syfo.application.ApplicationState

fun Application.apiModule(
    applicationState: ApplicationState,
) {
    installCallId()
    installContentNegotiation()
    installStatusPages()

    routing {
        registerPodApi(
            applicationState = applicationState,
        )
    }
}
