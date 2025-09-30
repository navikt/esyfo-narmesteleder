package no.nav.syfo.narmesteleder.api.v1

import io.ktor.http.HttpStatusCode
import io.ktor.serialization.JsonConvertException
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import no.nav.syfo.altinntilganger.AltinnTilgangerService
import no.nav.syfo.narmesteleder.kafka.model.NlResponseSource
import no.nav.syfo.narmesteleder.service.NarmestelederKafkaService

fun Route.registerIdportenNarmestelederApiV1(
    narmestelederKafkaService: NarmestelederKafkaService,
    altinnTilgangerService: AltinnTilgangerService,
) {
    route("/narmesteleder") {
        install(AddBrukerPrincipalPlugin) {
            this.texasHttpClient = texasHttpClient
        }
        post() {
            val nlRelasjon = try {
                call.receive<NarmesteLederRelasjonerWrite>()
            } catch (e: JsonConvertException) {
                throw BadRequestException("Invalid payload in request: ${e.message}", e)
            }
            val bruker = call.attributes[BRUKER_PRINCIPAL]
            altinnTilgangerService.validateTilgangToOrganisasjon(bruker, nlRelasjon.organisasjonsnummer)
            narmestelederKafkaService.sendNarmesteLederRelation(nlRelasjon, NlResponseSource.LPS, nlRelasjon.organisasjonsnummer)
            call.respond(HttpStatusCode.Accepted)
        }
    }

    route("/narmesteleder/avkreft") {
        install(AddBrukerPrincipalPlugin) {
            this.texasHttpClient = texasHttpClient
        }
        post() {
            val bruker = call.attributes[BRUKER_PRINCIPAL]
            val avkreft = try {
                call.receive<NarmestelederRelasjonAvkreft>()
            } catch (e: JsonConvertException) {
                throw BadRequestException("Invalid payload in request: ${e.message}", e)
            }
            altinnTilgangerService.validateTilgangToOrganisasjon(bruker, avkreft.organisasjonsnummer)
            narmestelederKafkaService.avbrytNarmesteLederRelation(avkreft, NlResponseSource.LPS)
            call.respond(HttpStatusCode.Accepted)
        }
    }
}
