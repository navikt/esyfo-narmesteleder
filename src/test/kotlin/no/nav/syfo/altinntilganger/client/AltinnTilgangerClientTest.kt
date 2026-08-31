package no.nav.syfo.altinntilganger.client

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.Headers
import io.ktor.http.HttpStatusCode
import io.ktor.http.fullPath
import io.ktor.http.isSuccess
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import no.nav.syfo.application.auth.UserPrincipal
import no.nav.syfo.application.exception.UpstreamExceptionType
import no.nav.syfo.application.exception.UpstreamFailureStage
import no.nav.syfo.application.exception.UpstreamRequestException
import no.nav.syfo.application.texas.TexasEnvironment
import no.nav.syfo.texas.client.TexasHttpClient
import no.nav.syfo.texas.client.TexasResponse
import no.nav.syfo.util.httpClientDefault

class AltinnTilgangerClientTest :
    DescribeSpec({

        val mockTexasClient = mockk<TexasHttpClient>()
        beforeTest {
            clearAllMocks(currentThreadOnly = true)
        }

        fun getMockEngine(status: HttpStatusCode, headers: Headers, content: String) = MockEngine.Companion { request ->
            when (request.url.fullPath) {
                "/altinn-tilganger" -> {
                    if (status.isSuccess()) {
                        respond(
                            status = status,
                            headers = headers,
                            content = content.toByteArray(Charsets.UTF_8),
                        )
                    } else {
                        respond(
                            status = status,
                            headers = headers,
                            content = content,
                        )
                    }
                }

                else -> error("Unhandled request ${request.url.fullPath}")
            }
        }
        describe("fetchAltinnTilganger") {
            it("should return AltinnTilgangerResponse when fetchAltinnTilganger responds with 200") {
                val userPrincipal = UserPrincipal("12345678901", "token")
                val getPersonResponse = """
{
  "hierarki": [
    {
      "orgnr": "987654321",
      "erSlettet": false,
      "altinn3Tilganger": [],
      "altinn2Tilganger": [],
      "underenheter": [
        {
          "orgnr": "123456789",
          "erSlettet": false,
          "altinn3Tilganger": [
            "tilgang1",
            "tilgang2"
          ],
          "altinn2Tilganger": [
            "serviceCode:serviceEdition"
          ],
          "underenheter": [],
          "navn": "Organisasjon 2",
          "organisasjonsform": "BEDR"
        }
      ],
      "navn": "Organissjon 1",
      "organisasjonsform": "ORGL"
    }
  ],
  "orgNrTilTilganger": {
    "123456789": [
      "serviceCode:serviceEdition",
      "tilgang1",
      "tilgang2"
    ]
  },
  "tilgangTilOrgNr": {
    "serviceCode:serviceEdition": [
      "123456789"
    ],
    "tilgang1": [
      "123456789"
    ],
    "tilgang2": [
      "123456789"
    ]
  },
  "error": false
}

                """.trimIndent()
                val mockEngine = getMockEngine(
                    status = HttpStatusCode.Companion.OK,
                    headers = Headers.Companion.build {
                        append("Content-Type", "application/json")
                    },
                    content = getPersonResponse,
                )
                coEvery {
                    mockTexasClient.exchangeTokenForIsAltinnTilganger(eq(userPrincipal.token))
                } returns TexasResponse(
                    "token",
                    111,
                    "tokenType"
                )
                val client = AltinnTilgangerClient(mockTexasClient, httpClientDefault(HttpClient(mockEngine)), "")

                val result = client.fetchAltinnTilganger(userPrincipal)

                result?.hierarki?.firstOrNull()?.orgnr shouldBe "987654321"
            }

            it("should throw exception when getPerson responds with 4xx") {
                val userPrincipal = UserPrincipal("12345678901", "token")

                val mockEngine = getMockEngine(
                    status = HttpStatusCode.Companion.BadRequest,
                    headers = Headers.Companion.build {
                        append("Content-Type", "application/json")
                    },
                    content = "invalid request",
                )
                coEvery {
                    mockTexasClient.exchangeTokenForIsAltinnTilganger(eq(userPrincipal.token))
                } returns TexasResponse(
                    "token",
                    111,
                    "tokenType"
                )
                val client = AltinnTilgangerClient(mockTexasClient, httpClientDefault(HttpClient(mockEngine)), "")

                val exception = shouldThrow<UpstreamRequestException> { client.fetchAltinnTilganger(userPrincipal) }
                exception.upstreamStatus shouldBe 400
                exception.failureStage shouldBe UpstreamFailureStage.RESPONSE
                exception.upstreamExceptionType shouldBe UpstreamExceptionType.CLIENT_REQUEST_EXCEPTION
            }

            it("should throw exception when getPerson responds with 5xx") {
                val userPrincipal = UserPrincipal("12345678901", "token")

                val mockEngine = getMockEngine(
                    status = HttpStatusCode.Companion.ServiceUnavailable,
                    headers = Headers.Companion.build {
                        append("Content-Type", "application/json")
                    },
                    content = "invalid request",
                )
                coEvery {
                    mockTexasClient.exchangeTokenForIsAltinnTilganger(eq(userPrincipal.token))
                } returns TexasResponse(
                    "token",
                    111,
                    "tokenType"
                )
                val client = AltinnTilgangerClient(mockTexasClient, httpClientDefault(HttpClient(mockEngine)), "")

                val exception = shouldThrow<UpstreamRequestException> { client.fetchAltinnTilganger(userPrincipal) }
                exception.upstreamStatus shouldBe 503
                exception.failureStage shouldBe UpstreamFailureStage.RESPONSE
                exception.upstreamExceptionType shouldBe UpstreamExceptionType.SERVER_RESPONSE_EXCEPTION
            }

            it("should classify token exchange separately without exposing an invalid status") {
                val userPrincipal = UserPrincipal("12345678901", "privacy-canary-token")
                coEvery {
                    mockTexasClient.exchangeTokenForIsAltinnTilganger(eq(userPrincipal.token))
                } throws IllegalStateException("safe failure")
                val client = AltinnTilgangerClient(
                    texasClient = mockTexasClient,
                    httpClient = HttpClient(MockEngine { error("AltinnTilganger must not be called") }),
                    baseUrl = "",
                )

                val exception = shouldThrow<UpstreamRequestException> { client.fetchAltinnTilganger(userPrincipal) }

                exception.failureStage shouldBe UpstreamFailureStage.TOKEN_EXCHANGE
                exception.upstreamStatus shouldBe null
                exception.upstreamExceptionType shouldBe UpstreamExceptionType.UNEXPECTED_EXCEPTION
            }

            it("should preserve a bounded HTTP status from token exchange") {
                val userPrincipal = UserPrincipal("12345678901", "privacy-canary-token")
                val texasClient = TexasHttpClient(
                    client = httpClientDefault(
                        HttpClient(
                            MockEngine {
                                respond(
                                    content = "safe failure",
                                    status = HttpStatusCode.Unauthorized,
                                    headers = Headers.build { append("Content-Type", "application/json") },
                                )
                            },
                        ),
                    ),
                    environment = TexasEnvironment.createForLocal(),
                )
                val client = AltinnTilgangerClient(
                    texasClient = texasClient,
                    httpClient = HttpClient(MockEngine { error("AltinnTilganger must not be called") }),
                    baseUrl = "",
                )

                val exception = shouldThrow<UpstreamRequestException> { client.fetchAltinnTilganger(userPrincipal) }

                exception.failureStage shouldBe UpstreamFailureStage.TOKEN_EXCHANGE
                exception.upstreamStatus shouldBe 401
                exception.upstreamExceptionType shouldBe UpstreamExceptionType.CLIENT_REQUEST_EXCEPTION
            }

            it("should classify malformed successful responses as response failures with the response status") {
                val userPrincipal = UserPrincipal("12345678901", "token")
                coEvery {
                    mockTexasClient.exchangeTokenForIsAltinnTilganger(eq(userPrincipal.token))
                } returns TexasResponse("token", 111, "tokenType")
                val mockEngine = getMockEngine(
                    status = HttpStatusCode.OK,
                    headers = Headers.build { append("Content-Type", "application/json") },
                    content = "not-json",
                )
                val client = AltinnTilgangerClient(mockTexasClient, httpClientDefault(HttpClient(mockEngine)), "")

                val exception = shouldThrow<UpstreamRequestException> { client.fetchAltinnTilganger(userPrincipal) }

                exception.failureStage shouldBe UpstreamFailureStage.RESPONSE
                exception.upstreamStatus shouldBe 200
                exception.upstreamExceptionType shouldBe UpstreamExceptionType.RESPONSE_DECODING_EXCEPTION
            }

            it("should propagate cancellation without wrapping it as an upstream failure") {
                val userPrincipal = UserPrincipal("12345678901", "token")
                coEvery {
                    mockTexasClient.exchangeTokenForIsAltinnTilganger(eq(userPrincipal.token))
                } returns TexasResponse(
                    "token",
                    111,
                    "tokenType",
                )
                val client = AltinnTilgangerClient(
                    texasClient = mockTexasClient,
                    httpClient = HttpClient(MockEngine { throw CancellationException("Request cancelled") }),
                    baseUrl = "",
                )

                shouldThrow<CancellationException> { client.fetchAltinnTilganger(userPrincipal) }
            }
        }
    })
