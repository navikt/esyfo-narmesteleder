package no.nav.syfo.application.api

import io.ktor.server.application.*
import io.ktor.server.http.content.staticFiles
import io.ktor.server.routing.*
import io.ktor.server.plugins.openapi.*
import io.ktor.server.plugins.swagger.*
import java.io.File

fun Application.configureOpenApi() {
    routing {
        openAPI(path = "openapi") {
            staticFiles("/", File("src/main/resources/openapi"))
        }
        swaggerUI(
            path = "docs",
            swaggerFile = "openapi/documentation.yaml"
        )
    }
}
