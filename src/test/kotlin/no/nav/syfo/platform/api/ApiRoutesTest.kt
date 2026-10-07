package no.nav.syfo.platform.api

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import no.nav.syfo.application.api.API_V1_PATH
import no.nav.syfo.application.api.INTERNAL_API_V1_PATH
import no.nav.syfo.application.api.installApiPlugins

class ApiRoutesTest :
    FunSpec({
        test("several modules can add routes to the same API prefix and each route requires a token issuer") {
            testApplication {
                application {
                    installApiPlugins()
                    routing {
                        internalApiV1 { get("/first") { call.respondText("first") } }
                        apiV1 { get("/first") { call.respondText("first") } }
                    }
                    routing {
                        internalApiV1 { get("/second") { call.respondText("second") } }
                        apiV1 { get("/second") { call.respondText("second") } }
                    }
                }

                listOf(INTERNAL_API_V1_PATH, API_V1_PATH).forEach { prefix ->
                    client.get("$prefix/first").status shouldBe HttpStatusCode.Unauthorized
                    client.get("$prefix/second").status shouldBe HttpStatusCode.Unauthorized
                }
            }
        }
    })
