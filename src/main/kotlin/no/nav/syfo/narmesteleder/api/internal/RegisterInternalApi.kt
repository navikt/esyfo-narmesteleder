package no.nav.syfo.narmesteleder.api.internal

import io.ktor.server.routing.Route
import io.ktor.server.routing.route
import no.nav.syfo.application.auth.AddTokenIssuerPlugin
import no.nav.syfo.narmesteleder.api.internal.v1.registerEmployeeLinemanagerApi
import no.nav.syfo.narmesteleder.api.internal.v1.registerLineManagerLookupApi
import no.nav.syfo.narmesteleder.api.v1.registerLinemanagerSearchApi
import no.nav.syfo.narmesteleder.api.v1.registerLinemanagerStatisticsApi
import no.nav.syfo.narmesteleder.service.EmployeeLinemanagerService
import no.nav.syfo.narmesteleder.service.LinemanagerSearchService
import no.nav.syfo.narmesteleder.service.LinemanagerStatisticsService
import no.nav.syfo.narmesteleder.service.NarmestelederLookupService
import no.nav.syfo.texas.client.TexasHttpClient

const val INTERNAL_API_V1_PATH = "/internal/api/v1"

@Suppress("LongParameterList")
fun Route.registerInternalApi(
    narmestelederLookupService: NarmestelederLookupService,
    texasHttpClient: TexasHttpClient,
    preAuthorizedApps: Set<String>,
    linemanagerSearchService: LinemanagerSearchService,
    linemanagerStatisticsService: LinemanagerStatisticsService,
    employeeLinemanagerService: EmployeeLinemanagerService,
) {
    route(INTERNAL_API_V1_PATH) {
        install(AddTokenIssuerPlugin)
        registerLineManagerLookupApi(
            narmestelederLookupService = narmestelederLookupService,
            texasHttpClient = texasHttpClient,
            preAuthorizedApps = preAuthorizedApps,
        )
        registerLinemanagerSearchApi(texasHttpClient, linemanagerSearchService)
        registerLinemanagerStatisticsApi(texasHttpClient, linemanagerStatisticsService)
        registerEmployeeLinemanagerApi(texasHttpClient, employeeLinemanagerService)
    }
}
