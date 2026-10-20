package no.nav.syfo.application.api

import io.ktor.server.application.Application
import io.ktor.server.routing.Route
import io.ktor.server.routing.routing
import no.nav.syfo.organisasjonstilgang.api.registerAccessibleOrganizationsApi
import no.nav.syfo.platform.api.apiV1
import org.koin.ktor.ext.get

fun Application.configureRouting() {
    routing {
        apiV1 {
            registerApiV1Routes()
        }
    }
}

private fun Route.registerApiV1Routes() {
    registerAccessibleOrganizationsApi(listAccessibleOrganizations = get(), texasHttpClient = get())
}
