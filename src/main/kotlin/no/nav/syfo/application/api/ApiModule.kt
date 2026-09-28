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
import no.nav.syfo.application.environment.isProdEnv
import no.nav.syfo.application.metric.registerMetricApi
import no.nav.syfo.narmesteleder.api.internal.v1.registerEmployeeLinemanagerApi
import no.nav.syfo.narmesteleder.api.internal.v1.registerLineManagerLookupApi
import no.nav.syfo.narmesteleder.api.internal.v1.registerLinemanagerRevokeApi
import no.nav.syfo.narmesteleder.api.v1.registerLinemanagerApiV1
import no.nav.syfo.narmesteleder.api.v1.registerLinemanagerSearchApi
import no.nav.syfo.narmesteleder.api.v1.registerLinemanagerStatisticsApi
import no.nav.syfo.narmestelederrelasjon.api.registerNarmestelederrelasjonApi
import no.nav.syfo.texas.preAuthorizedAppsFromEnvironment
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
    registerLinemanagerApiV1(
        narmestelederKafkaService = get(),
        validationService = get(),
        texasHttpClient = get(),
        linemanagerRequirementRestHandler = get(),
        narmestelederLookupService = get(),
        fulfillNarmestelederbehov = get(),
    )
    registerAccessOrganizationsApi(altinnTilgangerService = get(), texasHttpClient = get())
}

private fun Route.registerInternalApiV1Routes() {
    registerLineManagerLookupApi(
        narmestelederLookupService = get(),
        texasHttpClient = get(),
        preAuthorizedApps = preAuthorizedAppsFromEnvironment(),
    )
    registerLinemanagerSearchApi(texasHttpClient = get(), linemanagerSearchService = get())
    registerLinemanagerStatisticsApi(texasHttpClient = get(), linemanagerStatisticsService = get())
    registerEmployeeLinemanagerApi(texasHttpClient = get(), employeeLinemanagerService = get())
    registerLinemanagerRevokeApi(texasHttpClient = get(), linemanagerRevokeService = get())
    registerNarmestelederrelasjonApi(getNarmestelederrelasjon = get(), texasHttpClient = get())
}
