package no.nav.syfo.texas

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import createMockToken
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.mockk.coEvery
import io.mockk.mockk
import no.nav.syfo.application.api.ErrorType
import no.nav.syfo.application.api.installContentNegotiation
import no.nav.syfo.application.api.installStatusPages
import no.nav.syfo.application.auth.AddTokenIssuerPlugin
import no.nav.syfo.texas.client.TexasHttpClient
import no.nav.syfo.texas.client.TexasIntrospectionResponse

private const val CALLING_APP = "calling-app"
private const val AZURE_AD_ISSUER = "https://login.microsoftonline.com/tenant/v2.0"

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

        test("fails at install when pre-authorized apps are not configured") {
            val exception = shouldThrow<IllegalStateException> {
                startWithPlugin { client = mockk() }
            }

            exception.message shouldContain "installed without pre-authorized apps"
        }

        test("fails at install when pre-authorized apps are explicitly empty") {
            val exception = shouldThrow<IllegalStateException> {
                startWithPlugin {
                    client = mockk()
                    preAuthorizedApps = emptySet()
                }
            }

            exception.message shouldContain "installed without pre-authorized apps"
        }

        test("responds 401 when the token is not active") {
            val texasHttpClient = mockk<TexasHttpClient>()
            coEvery { texasHttpClient.introspectToken(any(), any()) } returns TexasIntrospectionResponse(
                active = false,
                azp = CALLING_APP,
            )

            val response = callProtectedRoute(texasHttpClient)

            response.status shouldBe HttpStatusCode.Unauthorized
        }

        test("responds 500 with INTERNAL_SERVER_ERROR when Texas introspection fails") {
            val texasHttpClient = mockk<TexasHttpClient>()
            coEvery { texasHttpClient.introspectToken(any(), any()) } throws RuntimeException("Texas unavailable")

            val response = callProtectedRoute(texasHttpClient)

            response.status shouldBe HttpStatusCode.InternalServerError
            response.errorType() shouldBe ErrorType.INTERNAL_SERVER_ERROR.name
        }
    })

private fun startWithPlugin(configure: AzureAdTokenAuthPluginConfiguration.() -> Unit) = testApplication {
    application {
        routing {
            route("/protected") {
                install(AzureAdTokenAuthPlugin, configure)
            }
        }
    }
    startApplication()
}

private suspend fun callProtectedRoute(texasHttpClient: TexasHttpClient): HttpResponse {
    lateinit var response: HttpResponse
    testApplication {
        application {
            installContentNegotiation()
            installStatusPages()
            routing {
                route("/protected") {
                    install(AddTokenIssuerPlugin)
                    install(AzureAdTokenAuthPlugin) {
                        client = texasHttpClient
                        preAuthorizedApps = setOf(CALLING_APP)
                    }
                    get { call.respond(HttpStatusCode.OK) }
                }
            }
        }

        response = client.get("/protected") {
            bearerAuth(createMockToken("ignored", issuer = AZURE_AD_ISSUER))
        }
        response.bodyAsText()
    }
    return response
}

private suspend fun HttpResponse.errorType(): String = jacksonObjectMapper().readTree(bodyAsText())["type"].asText()
