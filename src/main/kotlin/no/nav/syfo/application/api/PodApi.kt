package no.nav.syfo.application.api

import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respondText
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get
import no.nav.syfo.application.HealthState
import no.nav.syfo.application.database.DatabaseInterface

fun Routing.registerPodApi(
    healthState: HealthState,
    database: DatabaseInterface
) {
    get("/internal/is_alive") {
        if (healthState.alive) {
            call.respondText("I'm alive! :)")
        } else {
            call.respondText("I'm dead x_x", status = HttpStatusCode.InternalServerError)
        }
    }
    get("/internal/is_ready") {
        if (isReady(healthState, database)) {
            call.respondText("I'm ready! :)")
        } else {
            call.respondText("Please wait! I'm not ready :(", status = HttpStatusCode.InternalServerError)
        }
    }
}

private fun isReady(healthState: HealthState, database: DatabaseInterface): Boolean = healthState.ready && database.isOk()

private fun DatabaseInterface.isOk(): Boolean = try {
    connection.use {
        it.isValid(1)
    }
} catch (ex: Exception) {
    false
}
