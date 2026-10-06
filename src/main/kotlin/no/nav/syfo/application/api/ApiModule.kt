package no.nav.syfo.application.api

import io.ktor.server.application.Application
import io.ktor.server.http.content.staticResources
import io.ktor.server.plugins.swagger.swaggerUI
import io.ktor.server.response.respondRedirect
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import no.nav.syfo.altinn.dialogporten.registerDialogportenTokenApi
import no.nav.syfo.altinntilganger.registerAccessOrganizationsApi
import no.nav.syfo.application.auth.AddTokenIssuerPlugin
import no.nav.syfo.application.environment.Environment
import no.nav.syfo.application.environment.isProdEnv
import no.nav.syfo.application.metric.registerMetricApi
import no.nav.syfo.narmestelederbehov.api.registerFulfillNarmestelederbehovApi
import no.nav.syfo.narmestelederbehov.api.registerGetNarmestelederbehovApi
import no.nav.syfo.narmestelederbehov.api.registerListNarmestelederbehovApi
import no.nav.syfo.narmestelederrelasjon.api.registerEmployeeLinemanagerApi
import no.nav.syfo.narmestelederrelasjon.api.registerLineManagerLookupApi
import no.nav.syfo.narmestelederrelasjon.api.registerLinemanagerSearchApi
import no.nav.syfo.narmestelederrelasjon.api.registerNarmestelederrelasjonApi
import no.nav.syfo.narmestelederrelasjon.api.registerRevokeActiveNarmestelederrelasjonApi
import no.nav.syfo.narmestelederrelasjon.api.registerSubmitNarmestelederrelasjonApi
import no.nav.syfo.narmestelederstatistikk.api.registerLinemanagerStatisticsApi
import org.koin.ktor.ext.get

fun Application.configureRouting() {
    installCallId()
    installContentNegotiation()
    installStatusPages()

    routing {
        registerPodApi(applicationState = get(), database = get())
        registerMetricApi()
        route(API_V1_PATH) {
            install(AddTokenIssuerPlugin)
            registerApiV1Routes()
        }
        route(INTERNAL_API_V1_PATH) {
            install(AddTokenIssuerPlugin)
            registerInternalApiV1Routes()
        }
        // Static openAPI spec + swagger
        staticResources("/openapi", "openapi")
        swaggerUI(path = "swagger", swaggerFile = "openapi/documentation.yaml")
        swaggerUI(path = "internal/swagger", swaggerFile = "openapi/internal-documentation.yaml")
        swaggerUI(
            path = "internal/linemanager-search/swagger",
            swaggerFile = "openapi/internal-linemanager-search.yaml",
        )
        if (!isProdEnv()) {
            // TODO: Remove this endpoint later
            registerDialogportenTokenApi(texasHttpClient = get(), altinnTokenProvider = get())
        }
        get("/") {
            call.respondRedirect("/swagger")
        }
    }
}

private fun Route.registerApiV1Routes() {
    registerGetNarmestelederbehovApi(getNarmestelederbehov = get(), texasHttpClient = get())
    registerFulfillNarmestelederbehovApi(fulfillNarmestelederbehov = get(), texasHttpClient = get())
    registerListNarmestelederbehovApi(listNarmestelederbehov = get(), texasHttpClient = get())
    registerSubmitNarmestelederrelasjonApi(submit = get(), texasHttpClient = get())
    registerRevokeActiveNarmestelederrelasjonApi(revoke = get(), texasHttpClient = get())
    registerAccessOrganizationsApi(altinnTilgangerService = get(), texasHttpClient = get())
}

private fun Route.registerInternalApiV1Routes() {
    registerLineManagerLookupApi(
        lookupActiveNarmesteleder = get(),
        texasHttpClient = get(),
        preAuthorizedApps = get<Environment>().texas.azurePreAuthorizedApps,
    )
    registerLinemanagerSearchApi(searchActiveNarmestelederrelasjoner = get(), texasHttpClient = get())
    registerLinemanagerStatisticsApi(getNarmestelederstatistikk = get(), texasHttpClient = get())
    registerEmployeeLinemanagerApi(listActiveNarmesteledereForEmployee = get(), texasHttpClient = get())
    registerNarmestelederrelasjonApi(
        getNarmestelederrelasjon = get(),
        revokeNarmestelederrelasjon = get(),
        texasHttpClient = get(),
    )
}
