package no.nav.syfo.narmesteleder.api.v1

import io.ktor.http.HttpStatusCode
import io.ktor.serialization.JsonConvertException
import io.ktor.server.auth.principal
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import no.nav.syfo.application.auth.BrukerPrincipal
import no.nav.syfo.narmesteleder.kafka.model.NlResponseSource
import no.nav.syfo.narmesteleder.service.NarmestelederKafkaService
import no.nav.syfo.util.logger

fun Route.registerIdportenNarmestelederApiV1(
    narmestelederKafkaService: NarmestelederKafkaService
) {
    route("/narmesteleder") {
        install(AddSBrukerPrincipalPlugin) {
            this.texasHttpClient = texasHttpClient
        }
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
            install(AddSBrukerPrincipalPlugin) {
                this.texasHttpClient = texasHttpClient
            }
            val bruker = call.attributes[BRUKER_PRINCIPAL]
            logger().info("Bruker ${bruker.ident}")
            val avkreft = try {
                call.receive<NarmestelederRelasjonAvkreft>()
            } catch (e: JsonConvertException) {
                throw BadRequestException("Invalid payload in request: ${e.message}", e)
            }
            try {
                narmestelederKafkaService.avbrytNarmesteLederRelation(avkreft, NlResponseSource.LPS)
            } catch (e: Exception) {
                // TODO: Introduce more specific exceptions. Differentiate between then and reply with 4xx VS 5xx based on them.
                throw BadRequestException("Error processing request: ${e.message}", e)
            }
            call.respond(HttpStatusCode.Accepted)
        }
    }
}
