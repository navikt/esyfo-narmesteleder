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

    routing {
        registerPodApi(applicationState, database)
        registerMetricApi()
        registerApiV1(narmestelederKafkaService, texasHttpClient, validationService, linemanagerRequirementRESTHandler)
        get("/") { call.respondText("Hello World!") }
        // Serve static OpenAPI YAML
        get("/openapi.yaml") {
            val resource = this::class.java.classLoader.getResource("openapi/linemanager-v1.yaml")
            val yaml = resource?.readText() ?: "openapi: 3.0.3" // fallback minimal
            call.respondText(yaml)
        }
    }
}
