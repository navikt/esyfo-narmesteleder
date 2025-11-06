package no.nav.syfo.application.api

import io.ktor.server.application.Application
import io.ktor.server.http.content.staticResources
import io.ktor.server.plugins.swagger.swaggerUI
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import no.nav.syfo.application.ApplicationState
import no.nav.syfo.application.database.DatabaseInterface
import no.nav.syfo.application.isProdEnv
import no.nav.syfo.application.metric.registerMetricApi
import no.nav.syfo.dialogporten.client.IDialogportenClient
import no.nav.syfo.dialogporten.registerDialogportenTokenApi
import no.nav.syfo.narmesteleder.api.v1.LinemanagerRequirementRESTHandler
import no.nav.syfo.narmesteleder.service.NarmestelederKafkaService
import no.nav.syfo.narmesteleder.service.ValidationService
import no.nav.syfo.registerApiV1
import no.nav.syfo.texas.client.TexasHttpClient
import org.koin.ktor.ext.inject

fun Application.configureRouting() {
    val applicationState by inject<ApplicationState>()
    val database by inject<DatabaseInterface>()
    val narmestelederKafkaService by inject<NarmestelederKafkaService>()
    val texasHttpClient by inject<TexasHttpClient>()
    val validationService by inject<ValidationService>()
    val linemanagerRequirementRESTHandler by inject<LinemanagerRequirementRESTHandler>()
    val dialogportenClient by inject<IDialogportenClient>()

    installCallId()
    installContentNegotiation()
    installStatusPages()

    routing {
        registerPodApi(applicationState, database)
        registerMetricApi()
        registerApiV1(narmestelederKafkaService, texasHttpClient, validationService, linemanagerRequirementRESTHandler)
        // Static openAPI spec + swagger
        staticResources("/openapi", "openapi")
        swaggerUI(path = "swagger", swaggerFile = "openapi/documentation.yaml")
        if (!isProdEnv()) {
            // TODO: Remove this endpoint later
            registerDialogportenTokenApi(texasHttpClient, dialogportenClient)
        }
        get("/") {
            call.respondText("Hello World!")
        }
    }
}
