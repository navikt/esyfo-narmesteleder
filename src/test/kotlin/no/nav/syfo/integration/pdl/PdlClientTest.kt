package no.nav.syfo.integration.pdl

import getMockEngine
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.http.Headers
import io.ktor.http.HttpStatusCode
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.mockk
import no.nav.syfo.integration.httpClientDefaultWithoutRetryDelay
import no.nav.syfo.platform.upstream.UpstreamFailureStage
import no.nav.syfo.platform.upstream.UpstreamResult
import no.nav.syfo.platform.upstream.getOrThrow
import no.nav.syfo.texas.client.TexasHttpClient
import no.nav.syfo.texas.client.TexasResponse

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
                val client = HttpPdlClient(httpClientDefaultWithoutRetryDelay(mockEngine), "", mockTexasClient, "scope")

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
                val client = HttpPdlClient(httpClientDefaultWithoutRetryDelay(mockEngine), "", mockTexasClient, "scope")

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
                val client = HttpPdlClient(httpClientDefaultWithoutRetryDelay(mockEngine), "", mockTexasClient, "scope")

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
                val client = HttpPdlClient(httpClientDefaultWithoutRetryDelay(mockEngine), "", mockTexasClient, "scope")

                val failure = client.getPerson(fnr).shouldBeInstanceOf<UpstreamResult.Failure>().failure
                failure.stage shouldBe UpstreamFailureStage.RESPONSE
                failure.status shouldBe 503
            }
        }
    })
