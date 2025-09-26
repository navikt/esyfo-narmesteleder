package no.nav.syfo

import io.ktor.server.routing.*
import no.nav.syfo.aareg.AaregService
import no.nav.syfo.application.auth.AddTokenIssuerPlugin
import no.nav.syfo.narmesteleder.api.v1.registerNarmestelederApiV1
import no.nav.syfo.narmesteleder.service.NarmestelederKafkaService
import no.nav.syfo.texas.client.TexasHttpClient

@Suppress("LongParameterList")
fun Route.registerApiV1(
    narmestelederKafkaService: NarmestelederKafkaService,
    texasHttpClient: TexasHttpClient,
) {
    route("/api/v1") {
        install(AddTokenIssuerPlugin)
        registerNarmestelederApiV1(narmestelederKafkaService, texasHttpClient)
    }
}
