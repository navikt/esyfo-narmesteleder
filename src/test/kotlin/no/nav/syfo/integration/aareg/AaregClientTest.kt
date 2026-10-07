package no.nav.syfo.integration.aareg

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import no.nav.syfo.integration.TEST_SYSTEM_TOKEN
import no.nav.syfo.integration.respondJson
import no.nav.syfo.integration.respondWithSystemToken
import no.nav.syfo.integration.texasHttpClient
import no.nav.syfo.integration.upstreamHttpClient
import kotlin.coroutines.cancellation.CancellationException

class AaregClientTest :
    FunSpec({
        val personIdent = "12345678910"

        test("fetches arbeidsforhold from Aareg with a system token") {
            val expected = FakeAaregClient()
                .apply { arbeidsForholdForIdent[personIdent] = listOf("123456789" to "987654321") }
                .getArbeidsforhold(personIdent)
            var authorization: String? = null
            var path: String? = null
            val client = aaregClient(
                aareg = { request ->
                    authorization = request.headers[HttpHeaders.Authorization]
                    path = request.url.encodedPath
                    respondJson(jacksonObjectMapper().writeValueAsString(expected))
                }
            )

            client.getArbeidsforhold(personIdent) shouldBe expected
            authorization shouldBe "Bearer $TEST_SYSTEM_TOKEN"
            path shouldBe HttpAaregClient.ARBEIDSFORHOLD_OVERSIKT_PATH
        }

        test("wraps 4xx responses in AaregClientException") {
            val client = aaregClient(aareg = { respondJson("", HttpStatusCode.BadRequest) })

            shouldThrow<AaregClientException> {
                client.getArbeidsforhold(personIdent)
            }
        }

        test("wraps 404 responses in AaregClientException") {
            val client = aaregClient(aareg = { respondJson("", HttpStatusCode.NotFound) })

            shouldThrow<AaregClientException> {
                client.getArbeidsforhold(personIdent)
            }
        }

        test("wraps system token failures in AaregClientException") {
            val client = aaregClient(
                token = { respondJson("", HttpStatusCode.InternalServerError) },
                aareg = { respondJson("") },
            )

            shouldThrow<AaregClientException> {
                client.getArbeidsforhold(personIdent)
            }
        }

        test("propagates cancellation while acquiring system token") {
            val client = aaregClient(
                token = { throw CancellationException("cancelled") },
                aareg = { respondJson("") },
            )

            shouldThrow<CancellationException> {
                client.getArbeidsforhold(personIdent)
            }
        }
    })

private fun aaregClient(
    token: MockRequestHandler = respondWithSystemToken,
    aareg: MockRequestHandler,
): HttpAaregClient {
    val httpClient = upstreamHttpClient(token = token, upstream = aareg)
    return HttpAaregClient(
        aaregBaseUrl = "http://aareg",
        texasHttpClient = texasHttpClient(httpClient),
        scope = "scope",
        httpClient = httpClient,
    )
}
