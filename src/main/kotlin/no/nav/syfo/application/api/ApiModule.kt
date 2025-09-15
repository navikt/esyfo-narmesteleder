package no.nav.syfo.application.api

import io.ktor.server.application.Application
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlin.getValue
import no.nav.syfo.application.ApplicationState
import no.nav.syfo.application.database.DatabaseInterface
import no.nav.syfo.application.metric.registerMetricApi
import no.nav.syfo.narmesteleder.service.NarmestelederKafkaService
import no.nav.syfo.registerApiV1
import org.koin.ktor.ext.inject

fun Application.configureRouting() {
    val applicationState by inject<ApplicationState>()
    val database by inject<DatabaseInterface>()
    val narmestelederKafkaService by inject<NarmestelederKafkaService>()

    installCallId()
    installContentNegotiation()
    installStatusPages()

    routing {
        registerPodApi(applicationState, database)
        registerMetricApi()
        registerApiV1(narmestelederKafkaService)
        get("/") {
            call.respondText("Hello World!")
        }
    }
}
