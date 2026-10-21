package no.nav.syfo.bootstrap

import io.ktor.server.application.Application
import io.ktor.server.http.content.staticResources
import io.ktor.server.plugins.swagger.swaggerUI
import io.ktor.server.response.respondRedirect
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import no.nav.syfo.platform.api.installApiPlugins
import no.nav.syfo.platform.observability.registerMetricApi
import no.nav.syfo.platform.observability.registerPodApi
import org.koin.core.module.Module
import org.koin.ktor.ext.get

fun Application.platformModule(koinModules: List<Module>) {
    configureDependencies(koinModules)
    installApiPlugins()

    routing {
        registerPodApi(applicationState = get(), database = get())
        registerMetricApi()
        staticResources("/openapi", "openapi")
        swaggerUI(path = "swagger", swaggerFile = "openapi/documentation.yaml")
        swaggerUI(path = "internal/swagger", swaggerFile = "openapi/internal-documentation.yaml")
        swaggerUI(
            path = "internal/linemanager-search/swagger",
            swaggerFile = "openapi/internal-linemanager-search.yaml",
        )
        get("/") {
            call.respondRedirect("/swagger")
        }
    }
}
