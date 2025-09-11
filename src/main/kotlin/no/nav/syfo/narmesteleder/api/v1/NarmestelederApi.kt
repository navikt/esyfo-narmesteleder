package no.nav.syfo.narmesteleder.api.v1

import io.ktor.http.HttpStatusCode
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import no.nav.syfo.narmesteleder.service.NarmestelederKafkaService
import no.nav.syfo.util.logger

fun Route.registerNarmestelederApiV1(
    narmestelederKafkaService: NarmestelederKafkaService
) {
    val logger = logger()
    route("/narmesteleder") {
        post() {
            val nlRelasjon = try {
                call.receive<NarmesteLederRelasjonerWrite>()
            } catch (e: Exception) {
                throw BadRequestException("Invalid payload in request: ${e.message}", e)
            }
            narmestelederKafkaService.sendNarmesteLederRelation(nlRelasjon)
            logger.info("Mottok NL relasjon: $nlRelasjon")
            call.respond(HttpStatusCode.OK)
        }
    }
}
