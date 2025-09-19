package no.nav.syfo.narmestelder.service

import DefaultOrganization
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import defaultMocks
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import io.mockk.coVerify
import io.mockk.mockk
import no.nav.syfo.aareg.client.AaregClient
import no.nav.syfo.aareg.client.FakeAaregClient
import no.nav.syfo.texas.client.TexasHttpClient
import no.nav.syfo.util.httpClientDefault

class AaregClientTest : DescribeSpec({
    val texasHttpClient = mockk<TexasHttpClient>(relaxed = true)
    val arbeidstakerEnhet = DefaultOrganization
    val personIdent = "12345"


    describe("Successfull responses from Aareg") {
        val arbeidsforhold = FakeAaregClient(
            arbeidsstedOrgnummer = arbeidstakerEnhet.ID,
            juridiskOrgnummer = arbeidstakerEnhet.ID,
        ).getArbeidsforhold(personIdent)

        val mockEngine = MockEngine { req ->
            when (req.method) {
                HttpMethod.Post -> respond(
                    content = jacksonObjectMapper().writeValueAsString(arbeidsforhold),
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                )

                else -> mockk()
            }
        }
        val httpClient = httpClientDefault(HttpClient(mockEngine))
        val aaregClient = AaregClient(
            aaregBaseUrl = "base",
            texasHttpClient = texasHttpClient,
            scope = "scope",
            httpClient = httpClient,
        )

        it("Fetches arbeidsforhold in Aareg") {
            texasHttpClient.defaultMocks()
            val res = aaregClient.getArbeidsforhold(personIdent)
            res shouldBe arbeidsforhold
            coVerify(exactly = 1) {
                texasHttpClient.systemToken(any(), any())
            }
            res shouldBe arbeidsforhold
        }

    }
})