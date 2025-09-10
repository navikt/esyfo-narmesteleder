package no.nav.syfo.no.nav.syfo

import io.ktor.server.routing.Route
import io.ktor.server.routing.route
import no.nav.syfo.no.nav.syfo.narmesteleder.api.v1.registerNarmestelederApiV1

@Suppress("LongParameterList")
fun Route.registerApiV1(
) {
    route("/api/v1") {
        registerNarmestelederApiV1()
    }
}
