package no.nav.syfo.pdl.client

import getMockEngine
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.*
import io.ktor.http.*
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.mockk
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
                status = HttpStatusCode.Companion.OK,
                headers = Headers.Companion.build {
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
                status = HttpStatusCode.Companion.OK,
                headers = Headers.Companion.build {
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

            shouldThrow<PdlResourceNotFoundException> { client.getPerson(fnr) }
        }
        it("should throw exception when getPerson responds with non 4xx") {
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
                "token", 111, "tokenType"
            )
            val client = PdlClient(httpClientDefault(HttpClient(mockEngine)), "", mockTexasClient, "scope")

            shouldThrow<PdlRequestException> { client.getPerson(fnr) }
        }

        it("should throw server exception when getPerson responds with 5xx") {
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
                "token", 111, "tokenType"
            )
            val client = PdlClient(httpClientDefault(HttpClient(mockEngine)), "", mockTexasClient, "scope")

            shouldThrow<PdlRequestException> { client.getPerson(fnr) }
        }
    }
})
