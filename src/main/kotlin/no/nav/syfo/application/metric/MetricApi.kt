package no.nav.syfo.application.metric

import io.ktor.server.response.respondText
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get

const val POD_METRICS_PATH = "/internal/metrics"

fun Routing.registerMetricApi() {
    get(POD_METRICS_PATH) {
        call.respondText(METRICS_REGISTRY.scrape())
    }
}
