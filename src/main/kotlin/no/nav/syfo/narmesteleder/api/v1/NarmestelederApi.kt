package no.nav.syfo.narmesteleder.api.v1

import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import no.nav.syfo.application.auth.maskinportenIdToOrgnumber
import no.nav.syfo.narmesteleder.kafka.model.NlResponseSource
import no.nav.syfo.narmesteleder.service.NarmestelederKafkaService
import no.nav.syfo.texas.MaskinportenTokenAuthPlugin
import no.nav.syfo.texas.client.TexasHttpClient

fun Route.registerNarmestelederApiV1(
    narmestelederKafkaService: NarmestelederKafkaService,
    texasHttpClient: TexasHttpClient,
) {
    route("/narmesteleder") {
        install(MaskinportenTokenAuthPlugin) {
            client = texasHttpClient
        }
        post() {
            val nlRelasjon = call.tryReceive<NarmesteLederRelasjonerWrite>()
            val innsenderOrgNumber = maskinportenIdToOrgnumber(call.consumerIdFromPrincipal())

            narmestelederKafkaService.sendNarmesteLederRelation(
                nlRelasjon,
                NlResponseSource.LPS,
                innsenderOrgNumber
            )

            call.respond(HttpStatusCode.Accepted)
        }
    }

    route("/narmesteleder/avkreft") {
        post() {
            val avkreft = call.tryReceive<NarmestelederRelasjonAvkreft>()

            narmestelederKafkaService.avbrytNarmesteLederRelation(avkreft, NlResponseSource.LPS)

            call.respond(HttpStatusCode.Accepted)
        }
    }
}
