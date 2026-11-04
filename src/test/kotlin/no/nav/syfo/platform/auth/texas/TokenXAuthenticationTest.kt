package no.nav.syfo.platform.auth.texas

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeIn
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
import no.nav.syfo.platform.api.ErrorType
import no.nav.syfo.platform.api.INTERNAL_API_V1_PATH
import no.nav.syfo.platform.api.internalApiV1
import no.nav.syfo.platform.api.testRouteApplication
import no.nav.syfo.platform.auth.JwtIssuer
import no.nav.syfo.platform.auth.getMyPrincipal
import no.nav.syfo.platform.auth.texas.client.TexasHttpClient
import java.io.IOException

private const val PROTECTED_PATH = "/tokenx-protected"
private const val TOKEN_X_ISSUER = "https://tokenx.example.com"
private const val MASKINPORTEN_ISSUER = "https://test.maskinporten.no"
private const val AZURE_AD_ISSUER = "https://login.microsoftonline.com/tenant/v2.0"
private const val PID = "11223344556"

class TokenXAuthenticationTest :
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
            val before = tokenRejections(TokenRejectionProvider.TOKEN_X, TokenRejectionReason.LEVEL_INSUFFICIENT)

            val response = texas.callProtectedRoute()

            response.status shouldBe HttpStatusCode.Forbidden
            jacksonObjectMapper().readTree(response.bodyAsText()).path("type").asText() shouldBe ErrorType.AUTHENTICATION_ERROR.name
            tokenRejections(TokenRejectionProvider.TOKEN_X, TokenRejectionReason.LEVEL_INSUFFICIENT) shouldBe before + 1
        }

        test("preserves TokenX 403 when the first authentication provider does not match the issuer") {
            val texas = mockk<TexasHttpClient>()
            texas.stubTokenX(pid = PID, acr = "Level3")

            texas.callProtectedRoute(useEitherProvider = true).status shouldBe HttpStatusCode.Forbidden
        }

        test("authenticates a Maskinporten token with the first provider") {
            val texas = mockk<TexasHttpClient>()
            texas.stubMaskinportenSystemUser()

            texas.callProtectedRoute(MASKINPORTEN_ISSUER, useEitherProvider = true).status shouldBe HttpStatusCode.OK
        }

        listOf(
            TokenRejectionReason.CONSUMER_MISSING to { texas: TexasHttpClient -> texas.stubMaskinportenSystemUser(consumer = null) },
            TokenRejectionReason.SCOPE_INVALID to { texas: TexasHttpClient -> texas.stubMaskinportenSystemUser(scope = "other:scope") },
            TokenRejectionReason.SYSTEM_USER_ID_INVALID to { texas: TexasHttpClient -> texas.stubMaskinportenSystemUser(systemUserId = "not-a-uuid") },
        ).forEach { (reason, stub) ->
            test("records a Maskinporten rejection for $reason without falling back to TokenX") {
                val texas = mockk<TexasHttpClient>()
                stub(texas)
                val before = tokenRejections(TokenRejectionProvider.MASKINPORTEN, reason)

                texas.callProtectedRoute(MASKINPORTEN_ISSUER, useEitherProvider = true).status.value shouldBeIn listOf(400, 401)
                tokenRejections(TokenRejectionProvider.MASKINPORTEN, reason) shouldBe before + 1
                coVerify(exactly = 0) { texas.introspectToken(requireNotNull(JwtIssuer.TOKEN_X.value), any()) }
            }
        }

        test("authenticates a TokenX token with the second provider") {
            val texas = mockk<TexasHttpClient>()
            texas.stubTokenX(pid = PID)

            val response = texas.callProtectedRoute(useEitherProvider = true)

            response.status shouldBe HttpStatusCode.OK
            response.bodyAsText() shouldBe PID
        }

        test("responds 401 without introspection for an Azure AD token on the dual-provider route") {
            val texas = mockk<TexasHttpClient>()

            texas.callProtectedRoute(AZURE_AD_ISSUER, useEitherProvider = true).status shouldBe HttpStatusCode.Unauthorized
            coVerify(exactly = 0) { texas.introspectToken(any(), any()) }
        }

        test("responds 401 when TokenX has no pid") {
            val texas = mockk<TexasHttpClient>()
            texas.stubTokenX(pid = null)

            val response = texas.callProtectedRoute()

            response.status shouldBe HttpStatusCode.Unauthorized
            jacksonObjectMapper().readTree(response.bodyAsText()).path("type").asText() shouldBe ErrorType.AUTHORIZATION_ERROR.name
        }

        test("responds with the API error body when no bearer token is provided") {
            val texas = mockk<TexasHttpClient>()
            val response = texas.callProtectedRoute(issuer = null)

            response.status shouldBe HttpStatusCode.Unauthorized
            jacksonObjectMapper().readTree(response.bodyAsText()).path("message").asText() shouldBe "Invalid token"
        }

        listOf(MASKINPORTEN_ISSUER, AZURE_AD_ISSUER).forEach { issuer ->
            test("responds 401 for $issuer without introspection") {
                val texas = mockk<TexasHttpClient>()

                texas.callProtectedRoute(issuer).status shouldBe HttpStatusCode.Unauthorized
                coVerify(exactly = 0) { texas.introspectToken(any(), any()) }
            }
        }
    })

private fun TexasHttpClient.callProtectedRoute(
    issuer: String? = TOKEN_X_ISSUER,
    useEitherProvider: Boolean = false,
): HttpResponse {
    val texas = this
    lateinit var response: HttpResponse
    testRouteApplication(
        texasHttpClient = texas,
        routes = {
            internalApiV1 {
                route(PROTECTED_PATH) {
                    if (useEitherProvider) {
                        authenticateMaskinportenOrTokenX {
                            get { call.respondText(call.getMyPrincipal().ident) }
                        }
                    } else {
                        authenticateTokenX {
                            get { call.respondText(call.getMyPrincipal().ident) }
                        }
                    }
                }
            }
        },
    ) {
        response = client.get("$INTERNAL_API_V1_PATH$PROTECTED_PATH") {
            issuer?.let { bearerAuth(createMockToken(PID, issuer = it)) }
        }
        response.bodyAsText()
    }
    return response
}
