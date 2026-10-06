package no.nav.syfo.narmestelederbehov.api

import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.method
import io.ktor.server.routing.route
import no.nav.syfo.narmestelederbehov.application.ListNarmestelederbehovUseCase
import no.nav.syfo.texas.MaskinportenAndTokenXTokenAuthPlugin
import no.nav.syfo.texas.client.TexasHttpClient

const val NARMESTELEDERBEHOV_PATH = "/linemanager/requirement"

fun Route.registerListNarmestelederbehovApi(
    listNarmestelederbehov: ListNarmestelederbehovUseCase,
    texasHttpClient: TexasHttpClient,
) {
    route(NARMESTELEDERBEHOV_PATH) {
        method(HttpMethod.Get) {
            install(MaskinportenAndTokenXTokenAuthPlugin) {
                client = texasHttpClient
            }
            handle {
                val query = call.toListNarmestelederbehovQuery()
                val response = listNarmestelederbehov.execute(query)
                    .toLinemanagerRequirementCollection(query.pageSize)
                call.respond(HttpStatusCode.OK, response)
            }
        }
    }
}
