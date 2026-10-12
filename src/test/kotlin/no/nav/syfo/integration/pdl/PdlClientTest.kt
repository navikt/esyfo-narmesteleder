package no.nav.syfo.integration.pdl

import getMockEngine
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.client.HttpClient
import io.ktor.http.Headers
import io.ktor.http.HttpStatusCode
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import no.nav.syfo.platform.upstream.UpstreamFailureStage
import no.nav.syfo.platform.upstream.UpstreamResult
import no.nav.syfo.platform.upstream.getOrThrow
import no.nav.syfo.texas.client.TexasHttpClient
import no.nav.syfo.texas.client.TexasResponse
import no.nav.syfo.util.httpClientDefault

class PdlClientTest :
    DescribeSpec({

        val mockTexasClient = mockk<TexasHttpClient>()
        beforeTest {
            clearAllMocks(currentThreadOnly = true)
            coEvery { mockTexasClient.azureAdSystemToken(any()) } returns UpstreamResult.Success("token")
        }

        describe("getPerson") {
            it("returns a PdlPerson when getPerson responds with full data") {
                val fnr = "12345678901"
                val getPersonResponse = """
                {
                  "data": {
                    "person": {
                      "navn": [
                        {
                          "fornavn": "Ola",
                          "mellomnavn": null,
                          "etternavn": "Nordmann"
                        }
                      ]
                    },
                    "identer": {
                      "identer": [
                        {
                          "ident": "$fnr",
                          "gruppe": "FOLKEREGISTERIDENT"
                        }
                      ]
                    }
                  }
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
                    mockTexasClient.systemToken(any(), any())
                } returns TexasResponse(
                    "token",
                    111,
                    "tokenType"
                )
                val client = HttpPdlClient(httpClientDefault(HttpClient(mockEngine)), "", mockTexasClient, "scope")

                val result = client.getPerson(fnr).getOrThrow()

                result?.person?.navn?.firstOrNull()?.fornavn shouldBe "Ola"
                result?.person?.navn?.firstOrNull()?.etternavn shouldBe "Nordmann"
                result?.identer?.firstOrNull()?.ident shouldBe fnr
            }

            it("returns failure when an error has no code") {
                val fnr = "12345678901"
                val getPersonResponse = """
                {
                  "errors": [
                    {
                      "message": "Noe gikk galt"
                    }
                  ]
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
                    mockTexasClient.systemToken(any(), any())
                } returns TexasResponse(
                    "token",
                    111,
                    "tokenType"
                )
                val client = HttpPdlClient(httpClientDefault(HttpClient(mockEngine)), "", mockTexasClient, "scope")

                client.getPerson(fnr).shouldBeInstanceOf<UpstreamResult.Failure>().failure.errors.single().code shouldBe "unknown"
            }
            it("returns a response failure when getPerson responds with 4xx") {
                val fnr = "12345678901"

                val mockEngine = getMockEngine(
                    status = HttpStatusCode.Companion.BadRequest,
                    headers = Headers.Companion.build {
                        append("Content-Type", "application/json")
                    },
                    content = "invalid request",
                )
                coEvery {
                    mockTexasClient.systemToken(any(), any())
                } returns TexasResponse(
                    "token",
                    111,
                    "tokenType"
                )
                val client = HttpPdlClient(httpClientDefault(HttpClient(mockEngine)), "", mockTexasClient, "scope")

                val failure = client.getPerson(fnr).shouldBeInstanceOf<UpstreamResult.Failure>().failure
                failure.stage shouldBe UpstreamFailureStage.RESPONSE
                failure.status shouldBe 400
            }

            it("returns a response failure when getPerson responds with 5xx") {
                val fnr = "12345678901"

                val mockEngine = getMockEngine(
                    status = HttpStatusCode.Companion.ServiceUnavailable,
                    headers = Headers.Companion.build {
                        append("Content-Type", "application/json")
                    },
                    content = "invalid request",
                )
                coEvery {
                    mockTexasClient.systemToken(any(), any())
                } returns TexasResponse(
                    "token",
                    111,
                    "tokenType"
                )
                val client = HttpPdlClient(httpClientDefault(HttpClient(mockEngine)), "", mockTexasClient, "scope")

                val failure = client.getPerson(fnr).shouldBeInstanceOf<UpstreamResult.Failure>().failure
                failure.stage shouldBe UpstreamFailureStage.RESPONSE
                failure.status shouldBe 503
            }
        }

        describe("getPersonBolk") {
            it("should return GetPersonBolkResponse when getPersonBolk responds with 200") {
                val fnr1 = "12345678901"
                val fnr2 = "98765432109"
                val responseJson = """
                {
                  "data": {
                    "hentPersonBolk": [
                      {
                        "ident": "$fnr1",
                        "person": {
                          "navn": [{"fornavn": "Ola", "mellomnavn": null, "etternavn": "Nordmann"}],
                          "foedselsdato": []
                        },
                        "code": "ok"
                      },
                      {
                        "ident": "$fnr2",
                        "person": null,
                        "code": "not_found"
                      }
                    ],
                    "hentIdenterBolk": [
                      {
                        "ident": "$fnr1",
                        "identer": [{"ident": "$fnr1", "gruppe": "FOLKEREGISTERIDENT"}],
                        "code": "ok"
                      },
                      {
                        "ident": "$fnr2",
                        "identer": null,
                        "code": "not_found"
                      }
                    ]
                  }
                }
                """.trimIndent()
                val mockEngine = getMockEngine(
                    status = HttpStatusCode.OK,
                    headers = Headers.build { append("Content-Type", "application/json") },
                    content = responseJson,
                )
                coEvery {
                    mockTexasClient.systemToken(any(), any())
                } returns TexasResponse("token", 111, "tokenType")
                val client = HttpPdlClient(httpClientDefault(HttpClient(mockEngine)), "", mockTexasClient, "scope")

                val result = client.getPersonBolk(listOf(fnr1, fnr2), "provided-token")

                result.data?.hentPersonBolk?.find { it.ident == fnr1 }?.person?.navn?.firstOrNull()?.fornavn shouldBe "Ola"
                result.data?.hentPersonBolk?.find { it.ident == fnr2 }?.code shouldBe "not_found"
                result.data?.hentPersonBolk?.find { it.ident == fnr2 }?.person shouldBe null
            }

            it("should throw PdlRequestException when getPersonBolk responds with 4xx") {
                val mockEngine = getMockEngine(
                    status = HttpStatusCode.BadRequest,
                    headers = Headers.build { append("Content-Type", "application/json") },
                    content = "invalid request",
                )
                coEvery {
                    mockTexasClient.systemToken(any(), any())
                } returns TexasResponse("token", 111, "tokenType")
                val client = HttpPdlClient(httpClientDefault(HttpClient(mockEngine)), "", mockTexasClient, "scope")

                shouldThrow<PdlRequestException> { client.getPersonBolk(listOf("12345678901"), "provided-token") }
            }

            it("should throw PdlRequestException when getPersonBolk responds with 5xx") {
                val mockEngine = getMockEngine(
                    status = HttpStatusCode.ServiceUnavailable,
                    headers = Headers.build { append("Content-Type", "application/json") },
                    content = "error",
                )
                coEvery {
                    mockTexasClient.systemToken(any(), any())
                } returns TexasResponse("token", 111, "tokenType")
                val client = HttpPdlClient(httpClientDefault(HttpClient(mockEngine)), "", mockTexasClient, "scope")

                shouldThrow<PdlRequestException> { client.getPersonBolk(listOf("12345678901"), "provided-token") }
            }

            it("should use provided token in overload without fetching system token") {
                val fnr = "12345678901"
                val responseJson = """
                {
                  "data": {
                    "hentPersonBolk": [
                      {
                        "ident": "$fnr",
                        "person": null,
                        "code": "not_found"
                      }
                    ],
                    "hentIdenterBolk": []
                  }
                }
                """.trimIndent()
                val mockEngine = getMockEngine(
                    status = HttpStatusCode.OK,
                    headers = Headers.build { append("Content-Type", "application/json") },
                    content = responseJson,
                )
                val client = HttpPdlClient(httpClientDefault(HttpClient(mockEngine)), "", mockTexasClient, "scope")

                client.getPersonBolk(listOf(fnr), "provided-token")

                coVerify(exactly = 0) { mockTexasClient.systemToken(any(), any()) }
            }
        }
    })
