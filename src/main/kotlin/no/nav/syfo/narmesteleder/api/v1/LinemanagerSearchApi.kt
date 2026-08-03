package no.nav.syfo.narmesteleder.api.v1

import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import no.nav.syfo.narmesteleder.domain.LinemanagerSearchRequest
import no.nav.syfo.narmesteleder.service.LinemanagerSearchService
import no.nav.syfo.texas.MaskinportenAndTokenXTokenAuthPlugin
import no.nav.syfo.texas.client.TexasHttpClient

const val LINEMANAGER_SEARCH_API_PATH = "/linemanager/search"

fun Route.registerLinemanagerSearchApi(
    texasHttpClient: TexasHttpClient,
    linemanagerSearchService: LinemanagerSearchService,
) {
    route(LINEMANAGER_SEARCH_API_PATH) {
        install(MaskinportenAndTokenXTokenAuthPlugin) {
            client = texasHttpClient
        }

        post {
            val collection = linemanagerSearchService.search(
                request = call.tryReceive<LinemanagerSearchRequest>(),
                principal = call.getMyPrincipal(),
            )
            call.respond(HttpStatusCode.OK, collection)
        }
    }
}
