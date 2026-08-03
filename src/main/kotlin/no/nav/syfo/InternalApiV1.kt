package no.nav.syfo

import io.ktor.server.routing.Route
import io.ktor.server.routing.route
import no.nav.syfo.application.auth.AddTokenIssuerPlugin
import no.nav.syfo.narmesteleder.api.v1.registerLinemanagerSearchApi
import no.nav.syfo.narmesteleder.service.LinemanagerSearchService
import no.nav.syfo.texas.client.TexasHttpClient

const val INTERNAL_API_V1_PATH = "/internal/api/v1"

fun Route.registerInternalApiV1(
    texasHttpClient: TexasHttpClient,
    linemanagerSearchService: LinemanagerSearchService,
) {
    route(INTERNAL_API_V1_PATH) {
        install(AddTokenIssuerPlugin)
        registerLinemanagerSearchApi(texasHttpClient, linemanagerSearchService)
    }
}
