package no.nav.syfo.openapi

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.testing.testApplication

class OpenApiYamlTest : StringSpec({
    "GET /openapi.yaml returns YAML with title" {
        testApplication {
            application {
                routing {
                    get("/openapi.yaml") {
                        val resource = this::class.java.classLoader.getResource("openapi/linemanager-v1.yaml")
                        val yaml = resource?.readText() ?: "openapi: 3.0.3"
                        call.respondText(yaml)
                    }
                }
            }
            val response = client.get("/openapi.yaml")
            response.status.value shouldBe 200
            val body = response.bodyAsText()
            body.contains("title: Line Manager API") shouldBe true
        }
    }
})
