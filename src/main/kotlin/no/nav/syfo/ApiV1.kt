package no.nav.syfo

import io.ktor.server.routing.Route
import io.ktor.server.routing.route
import no.nav.syfo.narmesteleder.api.v1.registerNarmestelederApiV1
import no.nav.syfo.narmesteleder.service.NarmestelederKafkaService

@Suppress("LongParameterList")
fun Route.registerApiV1(
    narmestelederKafkaService: NarmestelederKafkaService,
) {
    route("/api/v1") {
        registerNarmestelederApiV1(narmestelederKafkaService)
    }
}
