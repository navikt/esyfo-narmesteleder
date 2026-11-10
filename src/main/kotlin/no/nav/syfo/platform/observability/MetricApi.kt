package no.nav.syfo.platform.observability

import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

const val POD_METRICS_PATH = "/internal/metrics"

fun Route.registerMetricApi() {
    get(POD_METRICS_PATH) {
        call.respondText(METRICS_REGISTRY.scrape())
    }
}
