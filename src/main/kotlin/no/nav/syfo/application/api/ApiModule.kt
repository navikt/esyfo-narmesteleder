package no.nav.syfo.application.api

import io.ktor.server.application.Application
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import no.nav.syfo.application.ApplicationState
import no.nav.syfo.application.database.DatabaseInterface
import no.nav.syfo.application.metric.registerMetricApi
import no.nav.syfo.narmesteleder.api.v1.LinemanagerRequirementRESTHandler
import no.nav.syfo.narmesteleder.service.NarmestelederKafkaService
import no.nav.syfo.narmesteleder.service.ValidationService
import no.nav.syfo.registerApiV1
import no.nav.syfo.texas.client.TexasHttpClient
import no.nav.syfo.util.logger
import org.koin.ktor.ext.inject

fun Application.configureRouting() {
    val applicationState by inject<ApplicationState>()
    val database by inject<DatabaseInterface>()
    val narmestelederKafkaService by inject<NarmestelederKafkaService>()
    val texasHttpClient by inject<TexasHttpClient>()
    val validationService by inject<ValidationService>()
    val linemanagerRequirementRESTHandler by inject<LinemanagerRequirementRESTHandler>()

    installCallId()
    installContentNegotiation()
    installStatusPages()

    // Optional: log presence of the OpenAPI resource
    val openApiRes = environment.classLoader.getResource("openapi/documentation.yaml")
    val logger = logger()
    logger.info("OpenAPI spec found: ${openApiRes != null} url=$openApiRes")

    routing {
        registerPodApi(applicationState, database)
        registerMetricApi()
        registerApiV1(narmestelederKafkaService, texasHttpClient, validationService, linemanagerRequirementRESTHandler)
        get("/") {
            call.respondText("Hello World!")
        }
    }
    configureOpenApi()
}
