package no.nav.syfo

import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import no.nav.syfo.application.ApplicationState
import no.nav.syfo.application.api.installCallId
import no.nav.syfo.application.api.installContentNegotiation
import no.nav.syfo.application.api.installStatusPages
import no.nav.syfo.application.api.registerPodApi
import no.nav.syfo.no.nav.syfo.application.metric.registerMetricApi


fun Application.configureRouting(applicationState: ApplicationState) {
    installCallId()
    installContentNegotiation()
    installStatusPages()

    routing {
        registerPodApi(applicationState)
        registerMetricApi()
        get("/") {
            call.respondText("Hello World!")
        }
    }
}
