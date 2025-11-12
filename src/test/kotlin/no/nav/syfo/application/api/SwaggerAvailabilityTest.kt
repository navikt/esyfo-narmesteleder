package no.nav.syfo.application.api

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.server.http.content.staticResources
import io.ktor.server.plugins.swagger.swaggerUI
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText


class SwaggerAvailabilityTest : StringSpec({

    "swagger ui is available" {
        testApplication {
            application {
                routing {
                    staticResources("/openapi", "openapi")
                    swaggerUI(path = "swagger", swaggerFile = "openapi/documentation.yaml")
                }
            }
            val response = client.get("/swagger")
            response.status.value shouldBe 200
            response.bodyAsText() shouldContain "Swagger UI"
        }
    }

    "openapi yaml is available" {
        testApplication {
            application {
                routing {
                    staticResources("/openapi", "openapi")
                }
            }
            val response = client.get("/openapi/documentation.yaml")
            response.status.value shouldBe 200
            response.bodyAsText() shouldContain "openapi: 3.0.3"
        }
    }
})
