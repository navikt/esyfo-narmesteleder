package no.nav.syfo.pdl

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
import no.nav.syfo.pdl.client.PdlClient
import no.nav.syfo.pdl.exception.PdlRequestException
import no.nav.syfo.pdl.exception.PdlResourceNotFoundException
import no.nav.syfo.texas.client.TexasHttpClient
import no.nav.syfo.texas.client.TexasResponse
import no.nav.syfo.util.httpClientDefault

class PdlClientTest : DescribeSpec({

    val mockTexasClient = mockk<TexasHttpClient>()
    beforeTest {
        clearAllMocks()
    }

    fun getMockEngine(status: HttpStatusCode, headers: Headers, content: String) = MockEngine.Companion { request ->
        when (request.url.fullPath) {
            "" -> {
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
    describe("getPerson") {
        it("should return GetPersonResponse when getPerson responds with 200") {
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
                          "ident": "${fnr}",
                          "gruppe": "FOLKEREGISTERIDENT"
                        }
                      ]
                    }
                  }
                }
                """.trimIndent()
            val mockEngine = getMockEngine(
                status = HttpStatusCode.OK,
                headers = Headers.build {
                    append("Content-Type", "application/json")
                },
                content = getPersonResponse,
            )
            coEvery {
                mockTexasClient.systemToken(any(), any())
            } returns TexasResponse(
                "token", 111, "tokenType"
            )
            val client = PdlClient(httpClientDefault(HttpClient(mockEngine)), "", mockTexasClient, "scope")

            val result = client.getPerson(fnr)

            result.data?.person?.navn?.firstOrNull()?.fornavn shouldBe "Ola"
            result.data?.person?.navn?.firstOrNull()?.etternavn shouldBe "Nordmann"
            result.data?.identer?.identer?.firstOrNull()?.ident shouldBe fnr
        }

        it("should throw exception when response contains error") {
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
                status = HttpStatusCode.OK,
                headers = Headers.build {
                    append("Content-Type", "application/json")
                },
                content = getPersonResponse,
            )
            coEvery {
                mockTexasClient.systemToken(any(), any())
            } returns TexasResponse(
                "token", 111, "tokenType"
            )
            val client = PdlClient(httpClientDefault(HttpClient(mockEngine)), "", mockTexasClient, "scope")

            shouldThrow< PdlResourceNotFoundException> { client.getPerson(fnr) }
        }
        it("should throw exception when getPerson responds with non 4xx") {
            val fnr = "12345678901"

            val mockEngine = getMockEngine(
                status = HttpStatusCode.BadRequest,
                headers = Headers.build {
                    append("Content-Type", "application/json")
                },
                content = "invalid request",
            )
            coEvery {
                mockTexasClient.systemToken(any(), any())
            } returns TexasResponse(
                "token", 111, "tokenType"
            )
            val client = PdlClient(httpClientDefault(HttpClient(mockEngine)), "", mockTexasClient, "scope")

            shouldThrow<PdlRequestException> { client.getPerson(fnr) }
        }

        it("should throw server exception when getPerson responds with 5xx") {
            val fnr = "12345678901"

            val mockEngine = getMockEngine(
                status = HttpStatusCode.ServiceUnavailable,
                headers = Headers.build {
                    append("Content-Type", "application/json")
                },
                content = "invalid request",
            )
            coEvery {
                mockTexasClient.systemToken(any(), any())
            } returns TexasResponse(
                "token", 111, "tokenType"
            )
            val client = PdlClient(httpClientDefault(HttpClient(mockEngine)), "", mockTexasClient, "scope")

            shouldThrow<PdlRequestException> { client.getPerson(fnr) }
        }
    }
})
