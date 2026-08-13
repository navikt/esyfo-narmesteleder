package no.nav.syfo.narmesteleder.api.v1

import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import no.nav.syfo.narmesteleder.service.LinemanagerStatisticsService
import no.nav.syfo.texas.MaskinportenAndTokenXTokenAuthPlugin
import no.nav.syfo.texas.client.TexasHttpClient

const val LINEMANAGER_STATISTICS_API_PATH = "/linemanager/statistics"

fun Route.registerLinemanagerStatisticsApi(
    texasHttpClient: TexasHttpClient,
    linemanagerStatisticsService: LinemanagerStatisticsService,
) {
    route(LINEMANAGER_STATISTICS_API_PATH) {
        install(MaskinportenAndTokenXTokenAuthPlugin) {
            client = texasHttpClient
        }

        get {
            val orgNumber = call.getRequiredOrganizationNumberQueryParameter("orgNumber")
            val principal = call.getMyPrincipal()
            val statistics = linemanagerStatisticsService.getStatistics(
                orgNumber = orgNumber,
                principal = principal,
            )
            countLinemanagerStatistics(principal)
            call.respond(HttpStatusCode.OK, statistics)
        }
    }
}
