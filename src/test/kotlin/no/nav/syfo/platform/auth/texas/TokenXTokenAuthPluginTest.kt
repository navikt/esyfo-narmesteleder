package no.nav.syfo.platform.auth.texas

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import io.mockk.coVerify
import io.mockk.mockk
import no.nav.syfo.createMockToken
import no.nav.syfo.platform.api.INTERNAL_API_V1_PATH
import no.nav.syfo.platform.api.internalApiV1
import no.nav.syfo.platform.api.testRouteApplication
import no.nav.syfo.platform.auth.getMyPrincipal
import no.nav.syfo.platform.auth.texas.client.TexasHttpClient
import java.io.IOException

private const val PROTECTED_PATH = "/tokenx-protected"
private const val TOKEN_X_ISSUER = "https://tokenx.example.com"
private const val PID = "11223344556"

class TokenXTokenAuthPluginTest :
    FunSpec({
        test("authenticates a Level4 TokenX token with pid as the user principal") {
            val texas = mockk<TexasHttpClient>()
            texas.stubTokenX(pid = PID)

            val response = texas.callProtectedRoute()

            response.status shouldBe HttpStatusCode.OK
            response.bodyAsText() shouldBe PID
        }

        test("responds 401 for an inactive TokenX token") {
            val texas = mockk<TexasHttpClient>()
            texas.stubInactive(provider = "tokenx")

            texas.callProtectedRoute().status shouldBe HttpStatusCode.Unauthorized
        }

        test("responds generic 500 without leaking the cause when TokenX introspection fails") {
            val failureMessage = "Texas is unavailable"
            val texas = mockk<TexasHttpClient>()
            texas.stubIntrospectionFailure(IOException(failureMessage), provider = "tokenx")

            val response = texas.callProtectedRoute()

            response.status shouldBe HttpStatusCode.InternalServerError
            val body = response.bodyAsText()
            jacksonObjectMapper().readTree(body).path("message").asText() shouldBe "Internal Server Error"
            body shouldNotContain failureMessage
        }

        test("responds 403 below Level4") {
            val texas = mockk<TexasHttpClient>()
            texas.stubTokenX(pid = PID, acr = "Level3")

            texas.callProtectedRoute().status shouldBe HttpStatusCode.Forbidden
        }

        test("responds 401 when TokenX has no pid") {
            val texas = mockk<TexasHttpClient>()
            texas.stubTokenX(pid = null)

            texas.callProtectedRoute().status shouldBe HttpStatusCode.Unauthorized
        }

        listOf("https://test.maskinporten.no", "https://login.microsoftonline.com/tenant/v2.0").forEach { issuer ->
            test("responds 401 for $issuer without introspection") {
                val texas = mockk<TexasHttpClient>()

                texas.callProtectedRoute(issuer).status shouldBe HttpStatusCode.Unauthorized
                coVerify(exactly = 0) { texas.introspectToken(any(), any()) }
            }
        }
    })

private fun TexasHttpClient.callProtectedRoute(issuer: String = TOKEN_X_ISSUER): HttpResponse {
    val texas = this
    lateinit var response: HttpResponse
    testRouteApplication(
        routes = {
            internalApiV1 {
                route(PROTECTED_PATH) {
                    install(TokenXTokenAuthPlugin) { client = texas }
                    get { call.respondText(call.getMyPrincipal().ident) }
                }
            }
        },
    ) {
        response = client.get("$INTERNAL_API_V1_PATH$PROTECTED_PATH") {
            bearerAuth(createMockToken(PID, issuer = issuer))
        }
        response.bodyAsText()
    }
    return response
}
