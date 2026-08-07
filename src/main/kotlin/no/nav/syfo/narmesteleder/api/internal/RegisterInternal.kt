package no.nav.syfo.narmesteleder.api.internal

import io.ktor.server.routing.Route
import io.ktor.server.routing.route
import no.nav.syfo.narmesteleder.api.internal.v1.registerNarmestelederLookupApi
import no.nav.syfo.narmesteleder.service.NarmestelederLookupService
import no.nav.syfo.texas.AzureAdTokenAuthPlugin
import no.nav.syfo.texas.client.TexasHttpClient

const val INTERNAL_API_PATH = "/internal"
fun Route.registerInternalAPI(
    narmestelederLookupService: NarmestelederLookupService,
    texasHttpClient: TexasHttpClient,
    preAuthorizedApps: Set<String>
) {
    route(INTERNAL_API_PATH) {
        install(AzureAdTokenAuthPlugin) {
            client = texasHttpClient
            this.preAuthorizedApps = preAuthorizedApps
        }
        registerNarmestelederLookupApi(
            narmestelederLookupService,
        )
    }
}
