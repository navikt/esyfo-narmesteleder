package no.nav.syfo.narmesteleder.api.internal

import io.ktor.server.routing.Route
import io.ktor.server.routing.route
import no.nav.syfo.application.auth.AddTokenIssuerPlugin
import no.nav.syfo.narmesteleder.api.internal.v1.registerLineManagerLookupApi
import no.nav.syfo.narmesteleder.service.NarmestelederLookupService
import no.nav.syfo.texas.AzureAdTokenAuthPlugin
import no.nav.syfo.texas.client.TexasHttpClient

const val INTERNAL_API_V1_PATH = "/internal/api/v1"

fun Route.registerInternalApi(
    narmestelederLookupService: NarmestelederLookupService,
    texasHttpClient: TexasHttpClient,
    preAuthorizedApps: Set<String>
) {
    route(INTERNAL_API_V1_PATH) {
        install(AddTokenIssuerPlugin)
        install(AzureAdTokenAuthPlugin) {
            client = texasHttpClient
            this.preAuthorizedApps = preAuthorizedApps
        }
        registerLineManagerLookupApi(
            narmestelederLookupService,
        )
    }
}
