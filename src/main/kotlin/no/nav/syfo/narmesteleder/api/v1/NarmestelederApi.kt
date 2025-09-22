package no.nav.syfo.narmesteleder.api.v1

import io.ktor.http.HttpStatusCode
import io.ktor.serialization.JsonConvertException
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import no.nav.syfo.application.exception.InternalServerErrorException
import no.nav.syfo.narmesteleder.kafka.model.NlResponseSource
import no.nav.syfo.narmesteleder.service.NarmestelederKafkaService
import no.nav.syfo.pdl.exception.PdlResourceNotFoundException
import no.nav.syfo.pdl.exception.PdlRequestException

fun Route.registerNarmestelederApiV1(
    narmestelederKafkaService: NarmestelederKafkaService
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
            } catch (e: PdlResourceNotFoundException) {
                throw BadRequestException("Could not find one or both of the persons")
            } catch (e: PdlRequestException) {
                throw InternalServerErrorException("Error when validating persons")
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
            } catch (e: PdlResourceNotFoundException) {
                throw BadRequestException("Could not find sykmeldt for provided fnr")
            } catch (e: PdlRequestException) {
                throw InternalServerErrorException("Error when validating fnr for sykmeldt")
            }
            call.respond(HttpStatusCode.Accepted)
        }
    }
}
