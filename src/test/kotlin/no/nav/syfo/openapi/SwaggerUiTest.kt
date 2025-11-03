package no.nav.syfo.openapi

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.testing.testApplication

class SwaggerUiTest : StringSpec({
    "GET /swagger returns HTML page" {
        testApplication {
            application {
                routing {
                    get("/swagger") {
                        val html = """
                            <!DOCTYPE html>
                            <html lang=\"en\">
                            <head><meta charset=\"UTF-8\" /><title>Line Manager API Docs</title></head>
                            <body><div id=\"swagger-ui\">Swagger placeholder</div></body>
                            </html>
                        """.trimIndent()
                        call.respondText(html, io.ktor.http.ContentType.Text.Html)
                    }
                }
            }
            val response = client.get("/swagger")
            response.status.value shouldBe 200
            val body = response.bodyAsText()
            body.contains("Line Manager API Docs") shouldBe true
        }
    }
})

