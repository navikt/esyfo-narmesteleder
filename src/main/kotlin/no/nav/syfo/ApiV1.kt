package no.nav.syfo

import io.ktor.server.routing.Route
import io.ktor.server.routing.route
import no.nav.syfo.altinntilganger.client.AltinnTilgangerService
import no.nav.syfo.narmesteleder.api.v1.registerIdportenNarmestelederApiV1
import no.nav.syfo.application.auth.AddTokenIssuerPlugin
import no.nav.syfo.narmesteleder.api.v1.registerNarmestelederApiV1
import no.nav.syfo.narmesteleder.service.NarmestelederKafkaService
import no.nav.syfo.texas.TexasTokenXAuthPlugin
import no.nav.syfo.texas.client.TexasHttpClient

@Suppress("LongParameterList")
fun Route.registerApiV1(
    narmestelederKafkaService: NarmestelederKafkaService,
    texasHttpClient: TexasHttpClient,
    altinnTilgangerService: AltinnTilgangerService,
) {
    route("/api/v1") {
        install(AddTokenIssuerPlugin)
        registerNarmestelederApiV1(narmestelederKafkaService, texasHttpClient)
    }

    route("/idporten/api/v1") {
        install(TexasTokenXAuthPlugin) {
            client = texasHttpClient
        }
        registerIdportenNarmestelederApiV1(narmestelederKafkaService, altinnTilgangerService)
    }
}
