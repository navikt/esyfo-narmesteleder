package no.nav.syfo

import io.ktor.server.routing.Route
import io.ktor.server.routing.route
import no.nav.syfo.application.auth.AddTokenIssuerPlugin
import no.nav.syfo.narmesteleder.api.v1.NlBehovRESTHandler
import no.nav.syfo.narmesteleder.api.v1.registerNarmestelederApiV1
import no.nav.syfo.narmesteleder.service.NarmestelederKafkaService
import no.nav.syfo.narmesteleder.service.ValidationService
import no.nav.syfo.texas.client.TexasHttpClient

@Suppress("LongParameterList")
fun Route.registerApiV1(
    narmestelederKafkaService: NarmestelederKafkaService,
    texasHttpClient: TexasHttpClient,
    validationService: ValidationService,
    nlBehovRESTHandler: NlBehovRESTHandler
) {
    route("/api/v1") {
        install(AddTokenIssuerPlugin)
        registerNarmestelederApiV1(narmestelederKafkaService, validationService, texasHttpClient, nlBehovRESTHandler)
    }

}
