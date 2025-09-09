package no.nav.syfo

import io.ktor.server.application.Application
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import no.nav.syfo.application.ApplicationState
import no.nav.syfo.application.api.installCallId
import no.nav.syfo.application.api.installContentNegotiation
import no.nav.syfo.application.api.installStatusPages
import no.nav.syfo.application.api.registerPodApi
import no.nav.syfo.application.database.DatabaseInterface
import no.nav.syfo.application.metric.registerMetricApi

fun Application.configureRouting(
    applicationState: ApplicationState,
    database: DatabaseInterface
) {
    installCallId()
    installContentNegotiation()
    installStatusPages()

    routing {
        registerPodApi(applicationState, database)
        registerMetricApi()
        get("/") {
            call.respondText("Hello World!")
        }
    }
}
