package no.nav.syfo.application.api

import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import no.nav.syfo.aareg.AaregService
import no.nav.syfo.application.ApplicationState
import no.nav.syfo.application.database.DatabaseInterface
import no.nav.syfo.application.metric.registerMetricApi
import no.nav.syfo.narmesteleder.service.NarmestelederKafkaService
import no.nav.syfo.registerApiV1
import no.nav.syfo.texas.client.TexasHttpClient
import org.koin.ktor.ext.inject

fun Application.configureRouting() {
    val applicationState by inject<ApplicationState>()
    val database by inject<DatabaseInterface>()
    val narmestelederKafkaService by inject<NarmestelederKafkaService>()
    val texasHttpClient by inject<TexasHttpClient>()
    val aaregService by inject<AaregService>()

    installCallId()
    installContentNegotiation()
    installStatusPages()

    routing {

        registerPodApi(applicationState, database)
        registerMetricApi()
        registerApiV1(narmestelederKafkaService, texasHttpClient, aaregService)
        get("/") {
            call.respondText("Hello World!")
        }
    }
}
