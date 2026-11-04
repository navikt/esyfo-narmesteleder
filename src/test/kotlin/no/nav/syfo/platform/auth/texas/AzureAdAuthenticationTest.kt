package no.nav.syfo.platform.auth.texas

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import io.mockk.coVerify
import io.mockk.mockk
import no.nav.syfo.createMockToken
import no.nav.syfo.platform.api.ErrorType
import no.nav.syfo.platform.api.testRouteApplication
import no.nav.syfo.platform.auth.texas.client.TexasHttpClient

private const val CALLING_APP = "calling-app"
private const val AZURE_AD_ISSUER = "https://login.microsoftonline.com/tenant/v2.0"
private const val TOKEN_X_ISSUER = "https://tokenx.example.com"
private const val MASKINPORTEN_ISSUER = "https://test.maskinporten.no"

class AzureAdAuthenticationTest :
    FunSpec({
        test("parses client IDs from the NAIS Azure AD pre-authorized applications payload") {
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

        test("responds 401 when the token is not active") {
            val texasHttpClient = mockk<TexasHttpClient>()
            texasHttpClient.stubInactive(azp = CALLING_APP)
            val before = tokenRejections(TokenRejectionProvider.AZURE_AD, TokenRejectionReason.TOKEN_INACTIVE)

            val response = callProtectedRoute(texasHttpClient)

            response.status shouldBe HttpStatusCode.Unauthorized
            tokenRejections(TokenRejectionProvider.AZURE_AD, TokenRejectionReason.TOKEN_INACTIVE) shouldBe before + 1
        }

        test("responds 500 with INTERNAL_SERVER_ERROR when Texas introspection fails") {
            val texasHttpClient = mockk<TexasHttpClient>()
            texasHttpClient.stubIntrospectionFailure(RuntimeException("Texas unavailable"))

            val response = callProtectedRoute(texasHttpClient)

            response.status shouldBe HttpStatusCode.InternalServerError
            response.errorType() shouldBe ErrorType.INTERNAL_SERVER_ERROR.name
        }

        test("responds 403 when the Azure AD application is not pre-authorized") {
            val texasHttpClient = mockk<TexasHttpClient>()
            texasHttpClient.stubAzureAd(azp = "unapproved-caller")
            val before = tokenRejections(TokenRejectionProvider.AZURE_AD, TokenRejectionReason.CLIENT_NOT_PRE_AUTHORIZED)

            callProtectedRoute(texasHttpClient).status shouldBe HttpStatusCode.Forbidden
            tokenRejections(TokenRejectionProvider.AZURE_AD, TokenRejectionReason.CLIENT_NOT_PRE_AUTHORIZED) shouldBe before + 1
        }

        test("responds 403 when the Azure AD token has no azp") {
            val texasHttpClient = mockk<TexasHttpClient>()
            texasHttpClient.stubAzureAd(azp = null)

            callProtectedRoute(texasHttpClient).status shouldBe HttpStatusCode.Forbidden
        }

        mapOf(
            "a TokenX token" to tokenIssuedBy(TOKEN_X_ISSUER),
            "a Maskinporten token" to tokenIssuedBy(MASKINPORTEN_ISSUER),
            "an unrecognised issuer" to tokenIssuedBy("https://unknown-issuer.example.com"),
            "a bearer token that is not a JWT" to "not-a-jwt",
            "a missing bearer token" to null,
        ).forEach { (case, token) ->
            test("responds 401 without introspection for $case") {
                val texasHttpClient = mockk<TexasHttpClient>()

                callProtectedRoute(texasHttpClient, token).status shouldBe HttpStatusCode.Unauthorized
                coVerify(exactly = 0) { texasHttpClient.introspectToken(any(), any()) }
            }
        }
    })

private fun callProtectedRoute(
    texasHttpClient: TexasHttpClient,
    token: String? = tokenIssuedBy(AZURE_AD_ISSUER),
): HttpResponse {
    lateinit var response: HttpResponse
    testRouteApplication(
        texasHttpClient = texasHttpClient,
        routes = {
            route("/protected") {
                authenticateAzureAd {
                    get { call.respond(HttpStatusCode.OK) }
                }
            }
        },
        block = {
            response = client.get("/protected") {
                token?.let { bearerAuth(it) }
            }
            response.bodyAsText()
        },
        azurePreAuthorizedApps = setOf(CALLING_APP),
    )
    return response
}

private fun tokenIssuedBy(issuer: String): String = createMockToken("ignored", issuer = issuer)

private suspend fun HttpResponse.errorType(): String = jacksonObjectMapper().readTree(bodyAsText())["type"].asText()
