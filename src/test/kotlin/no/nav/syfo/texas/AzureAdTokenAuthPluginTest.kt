package no.nav.syfo.texas

import createMockToken
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.mockk.coEvery
import io.mockk.mockk
import no.nav.syfo.application.api.installContentNegotiation
import no.nav.syfo.application.api.installStatusPages
import no.nav.syfo.application.auth.AddTokenIssuerPlugin
import no.nav.syfo.texas.client.TexasHttpClient

class AzureAdTokenAuthPluginTest :
    FunSpec({
        test("preAuthorizedAppsFromJson extracts client IDs from the NAIS pre-authorized applications payload") {
            val configuredApps = """
                [
                  {
                    "name": "dev-gcp:team-esyfo:syfo-budstikka",
                    "clientId": "0b26d3d5-8e1e-47a7-8cab-719921fceddf"
                  }
                ]
            """.trimIndent()

            preAuthorizedAppsFromJson(configuredApps) shouldBe setOf("0b26d3d5-8e1e-47a7-8cab-719921fceddf")
        }

        test("fails at install when pre-authorized apps are missing") {
            val exception = shouldThrow<IllegalStateException> {
                testApplication {
                    application {
                        routing {
                            route("/protected") {
                                install(AzureAdTokenAuthPlugin) { client = mockk() }
                            }
                        }
                    }
                    startApplication()
                }
            }

            exception.message shouldContain "installed without pre-authorized apps"
        }

        test("responds 500 when Texas introspection fails") {
            val texasHttpClient = mockk<TexasHttpClient>()
            coEvery { texasHttpClient.introspectToken(any(), any()) } throws RuntimeException("Texas unavailable")

            testApplication {
                application {
                    installContentNegotiation()
                    installStatusPages()
                    routing {
                        route("/protected") {
                            install(AddTokenIssuerPlugin)
                            install(AzureAdTokenAuthPlugin) {
                                client = texasHttpClient
                                preAuthorizedApps = setOf("calling-app")
                            }
                            get { call.respond(HttpStatusCode.OK) }
                        }
                    }
                }

                val response = client.get("/protected") {
                    bearerAuth(createMockToken("ignored", issuer = "https://login.microsoftonline.com/tenant/v2.0"))
                }

                response.status shouldBe HttpStatusCode.InternalServerError
            }
        }
    })
