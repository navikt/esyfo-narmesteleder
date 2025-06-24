package no.nav.syfo

import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import no.nav.syfo.application.ApplicationState

fun Application.configureRouting(applicationState: ApplicationState) {
    routing {
        get("/") {
            call.respondText("Hello World!")
        }
    }
}
