package no.nav.syfo.application.api

import io.ktor.server.application.Application
import io.ktor.server.routing.routing
import no.nav.syfo.application.ApplicationState
import no.nav.syfo.application.database.DatabaseInterface

fun Application.apiModule(
    applicationState: ApplicationState,
    database: DatabaseInterface,
) {
    installCallId()
    installContentNegotiation()
    installStatusPages()

    routing {
        registerPodApi(
            applicationState = applicationState,
            database = database,
        )
    }
}
