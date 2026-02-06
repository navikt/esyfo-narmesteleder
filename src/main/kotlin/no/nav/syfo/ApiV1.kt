package no.nav.syfo

import io.ktor.server.routing.Route
import io.ktor.server.routing.route
import no.nav.syfo.application.auth.AddTokenIssuerPlugin
import no.nav.syfo.narmesteleder.api.v1.LinemanagerRequirementRESTHandler
import no.nav.syfo.narmesteleder.api.v1.registerLinemanagerApiV1
import no.nav.syfo.narmesteleder.service.NarmestelederKafkaService
import no.nav.syfo.narmesteleder.service.ValidationService
import no.nav.syfo.texas.client.TexasHttpClient

const val API_V1_PATH = "/api/v1"

@Suppress("LongParameterList")
fun Route.registerApiV1(
    narmestelederKafkaService: NarmestelederKafkaService,
    texasHttpClient: TexasHttpClient,
    validationService: ValidationService,
    linemanagerRequirementRESTHandler: LinemanagerRequirementRESTHandler
) {
    route(API_V1_PATH) {
        install(AddTokenIssuerPlugin)
        registerLinemanagerApiV1(narmestelederKafkaService, validationService, texasHttpClient, linemanagerRequirementRESTHandler)
    }
}
