package no.nav.syfo.narmesteleder.api.v1

import io.ktor.http.*
import io.ktor.serialization.*
import io.ktor.server.plugins.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import no.nav.syfo.narmesteleder.kafka.model.NlResponseSource
import no.nav.syfo.narmesteleder.service.NarmestelederKafkaService
import no.nav.syfo.texas.client.TexasHttpClient

fun Route.registerNarmestelederApiV1(
    narmestelederKafkaService: NarmestelederKafkaService,
    texasHttpClient: TexasHttpClient
) {
    route("/narmesteleder") {
        post() {
            val nlRelasjon = try {
                call.receive<NarmesteLederRelasjonerWrite>()
            } catch (e: JsonConvertException) {
                throw BadRequestException("Invalid payload in request: ${e.message}", e)
            }
            try {
                narmestelederKafkaService.sendNarmesteLederRelation(nlRelasjon, NlResponseSource.LPS)
            } catch (e: Exception) {
                throw BadRequestException("Error processing request: ${e.message}", e)
            }
            call.respond(HttpStatusCode.Accepted)
        }
    }

    route("/narmesteleder/avkreft") {
        post() {
            val avkreft = try {
                call.receive<NarmestelederRelasjonAvkreft>()
            } catch (e: JsonConvertException) {
                throw BadRequestException("Invalid payload in request: ${e.message}", e)
            }
            try {
                narmestelederKafkaService.avbrytNarmesteLederRelation(avkreft, NlResponseSource.LPS)
            } catch (e: Exception) {
                throw BadRequestException("Error processing request: ${e.message}", e)
            }
            call.respond(HttpStatusCode.Accepted)
        }
    }
}
