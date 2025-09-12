package no.nav.syfo.narmesteleder.api.v1

import io.ktor.http.HttpStatusCode
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import no.nav.syfo.narmesteleder.kafka.model.NlResponseSource
import no.nav.syfo.narmesteleder.service.NarmestelederKafkaService

fun Route.registerNarmestelederApiV1(
    narmestelederKafkaService: NarmestelederKafkaService
) {
    route("/narmesteleder") {
        post() {
            val nlRelasjon = try {
                call.receive<NarmesteLederRelasjonerWrite>()
            } catch (e: Exception) {
                throw BadRequestException("Invalid payload in request: ${e.message}", e)
            }
            narmestelederKafkaService.sendNarmesteLederRelation(nlRelasjon, NlResponseSource.LPS)

            call.respond(HttpStatusCode.Accepted)
        }
    }

    route("/narmesteleder/avkreft") {
        post() {
            val avkreft = try {
                call.receive<NarmestelederRelasjonAvkreft>()
            } catch (e: Exception) {
                throw BadRequestException("Invalid payload in request: ${e.message}", e)
            }
            narmestelederKafkaService.avbrytNarmesteLederRelation(avkreft, NlResponseSource.LPS)
            call.respond(HttpStatusCode.Accepted)
        }
    }
}
