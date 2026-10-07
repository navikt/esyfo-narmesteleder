package no.nav.syfo.integration.aareg

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.plugins.ServerResponseException
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import no.nav.syfo.integration.TEST_SYSTEM_TOKEN
import no.nav.syfo.integration.respondJson
import no.nav.syfo.integration.respondWithSystemToken
import no.nav.syfo.integration.texasHttpClient
import no.nav.syfo.integration.upstreamHttpClient
import java.net.SocketTimeoutException
import java.time.LocalDate
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
                    request.method shouldBe HttpMethod.Post
                    jacksonObjectMapper().readTree(request.body.toByteArray()) shouldBe jacksonObjectMapper().readTree(
                        """{"arbeidstakerId":"$personIdent","rapporteringsordninger":["A_ORDNINGEN","FOER_A_ORDNINGEN"]}"""
                    )
                    respondJson(jacksonObjectMapper().writeValueAsString(expected))
                }
            )

            client.getArbeidsforhold(personIdent) shouldBe expected
            authorization shouldBe "Bearer $TEST_SYSTEM_TOKEN"
            path shouldBe HttpAaregClient.ARBEIDSFORHOLD_OVERSIKT_PATH
        }

        test("fetches employment history with exactly the explicit Aareg filters") {
            val client = aaregClient(
                aareg = { request ->
                    request.method shouldBe HttpMethod.Post
                    request.url.encodedPath shouldBe HttpAaregClient.ARBEIDSFORHOLD_OVERSIKT_PATH
                    request.headers[HttpHeaders.Authorization] shouldBe "Bearer $TEST_SYSTEM_TOKEN"
                    jacksonObjectMapper().readTree(request.body.toByteArray()) shouldBe jacksonObjectMapper().readTree(
                        """
                        {
                          "arbeidstakerId":"$personIdent",
                          "arbeidsforholdtyper":["ordinaertArbeidsforhold","maritimtArbeidsforhold","forenkletOppgjoersordning"],
                          "rapporteringsordninger":["A_ORDNINGEN","FOER_A_ORDNINGEN"],
                          "arbeidsforholdstatuser":["AKTIV","FREMTIDIG","AVSLUTTET"]
                        }
                        """.trimIndent()
                    )
                    respondJson("""{"arbeidsforholdoversikter":[]}""")
                }
            )

            client.getArbeidsforholdHistorikk(personIdent) shouldBe AaregArbeidsforholdOversikt()
        }

        test("deserializes ISO employment dates, explicit nulls and omitted dates") {
            val workplace = """
                "arbeidssted":{"type":"Underenhet","identer":[]},
                "opplysningspliktig":{"type":"Hovedenhet","identer":[]}
            """.trimIndent()
            val client = aaregClient(
                aareg = {
                    respondJson(
                        """
                        {"arbeidsforholdoversikter":[
                          {$workplace,"startdato":"2025-01-01","sluttdato":"2026-03-31"},
                          {$workplace,"startdato":null,"sluttdato":null},
                          {$workplace}
                        ]}
                        """.trimIndent()
                    )
                }
            )

            val employments = client.getArbeidsforholdHistorikk(personIdent).arbeidsforholdoversikter
            employments.map { it.startdato } shouldBe listOf(LocalDate.of(2025, 1, 1), null, null)
            employments.map { it.sluttdato } shouldBe listOf(LocalDate.of(2026, 3, 31), null, null)
        }

        test("wraps 4xx responses in AaregClientException") {
            val client = aaregClient(aareg = { respondJson("sensitive upstream body", HttpStatusCode.BadRequest) })

            val failure = shouldThrow<AaregClientException> {
                client.getArbeidsforhold(personIdent)
            }
            failure.reason shouldBe AaregClientException.Reason.UNAVAILABLE
            failure.message shouldBe "An error occurred when fetching arbeidsforhold (status=400)"
            failure.cause shouldBe null
            failure.shouldNotRetainPersonIdent(personIdent)
        }

        test("wraps 404 without retaining personal data in the message or cause") {
            val client = aaregClient(aareg = { respondJson("sensitive upstream body $personIdent", HttpStatusCode.NotFound) })

            val failure = shouldThrow<AaregClientException> {
                client.getArbeidsforhold(personIdent)
            }
            failure.message.orEmpty().contains(personIdent) shouldBe false
            failure.message shouldBe "Person not found when fetching arbeidsforhold (status=404)"
            failure.cause shouldBe null
            failure.reason shouldBe AaregClientException.Reason.PERSON_NOT_FOUND
            failure.shouldNotRetainPersonIdent(personIdent)
        }

        test("history 404 is distinguishable without retaining the upstream body or person ident") {
            val client = aaregClient(aareg = { respondJson("sensitive upstream body $personIdent", HttpStatusCode.NotFound) })

            val failure = shouldThrow<AaregClientException> {
                client.getArbeidsforholdHistorikk(personIdent)
            }
            failure.reason shouldBe AaregClientException.Reason.PERSON_NOT_FOUND
            failure.message shouldBe "Person not found when fetching arbeidsforhold (status=404)"
            failure.cause shouldBe null
            failure.shouldNotRetainPersonIdent(personIdent)
        }

        test("history client and server errors are unavailable without retaining personal data") {
            listOf(HttpStatusCode.BadRequest, HttpStatusCode.InternalServerError).forEach { status ->
                val client = aaregClient(aareg = { respondJson("sensitive upstream body $personIdent", status) })
                val failure = shouldThrow<AaregClientException> {
                    client.getArbeidsforholdHistorikk(personIdent)
                }
                failure.reason shouldBe AaregClientException.Reason.UNAVAILABLE
                failure.message shouldBe when (status) {
                    HttpStatusCode.BadRequest -> "An error occurred when fetching arbeidsforhold (status=400)"
                    else -> "An error occurred when fetching employment history (ServerResponseException)"
                }
                failure.message.orEmpty().contains(personIdent) shouldBe false
                failure.message.orEmpty().contains("sensitive upstream body") shouldBe false
                failure.cause shouldBe null
                failure.shouldNotRetainPersonIdent(personIdent)
            }
        }

        test("malformed history responses are unavailable without retaining the parsing failure") {
            val client = aaregClient(aareg = { respondJson("""{"arbeidsforholdoversikter":"sensitive upstream body $personIdent"}""") })
            val failure = shouldThrow<AaregClientException> {
                client.getArbeidsforholdHistorikk(personIdent)
            }
            failure.reason shouldBe AaregClientException.Reason.UNAVAILABLE
            failure.message shouldBe "An error occurred when fetching employment history (JsonConvertException)"
            failure.cause shouldBe null
            failure.shouldNotRetainPersonIdent(personIdent)
        }

        test("history transport failures retain only the exception class name and not the unsafe cause chain") {
            val timeout = SocketTimeoutException("sensitive upstream body $personIdent").apply {
                initCause(IllegalStateException("sensitive upstream cause $personIdent"))
            }
            val client = aaregClient(aareg = { throw timeout })
            val failure = shouldThrow<AaregClientException> {
                client.getArbeidsforholdHistorikk(personIdent)
            }
            failure.reason shouldBe AaregClientException.Reason.UNAVAILABLE
            failure.message shouldBe "An error occurred when fetching employment history (SocketTimeoutException)"
            failure.cause shouldBe null
            failure.shouldNotRetainPersonIdent(personIdent)
        }

        test("retains system token failure cause for diagnostics without retaining the employee ident") {
            val client = aaregClient(
                token = { request ->
                    String(request.body.toByteArray()).contains(personIdent) shouldBe false
                    respondJson("token endpoint unavailable", HttpStatusCode.InternalServerError)
                },
                aareg = { respondJson("") },
            )

            val failure = shouldThrow<AaregClientException> {
                client.getArbeidsforhold(personIdent)
            }
            failure.reason shouldBe AaregClientException.Reason.UNAVAILABLE
            failure.cause?.javaClass?.simpleName shouldBe "ServerResponseException"
            (failure.cause as ServerResponseException).response.status shouldBe HttpStatusCode.InternalServerError
            failure.shouldNotRetainPersonIdent(personIdent)
        }

        test("history system token failures retain their cause without retaining the employee ident") {
            val client = aaregClient(
                token = { request ->
                    String(request.body.toByteArray()).contains(personIdent) shouldBe false
                    respondJson("token endpoint unavailable", HttpStatusCode.InternalServerError)
                },
                aareg = { error("No Aareg request should be made without a token") },
            )
            val failure = shouldThrow<AaregClientException> {
                client.getArbeidsforholdHistorikk(personIdent)
            }
            failure.reason shouldBe AaregClientException.Reason.UNAVAILABLE
            failure.cause?.javaClass?.simpleName shouldBe "ServerResponseException"
            (failure.cause as ServerResponseException).response.status shouldBe HttpStatusCode.InternalServerError
            failure.shouldNotRetainPersonIdent(personIdent)
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

        test("history propagates cancellation while acquiring a token or fetching history") {
            val cancelled = CancellationException("cancelled")
            val tokenClient = aaregClient(
                token = { throw cancelled },
                aareg = { error("No Aareg request should be made after cancellation") },
            )
            shouldThrow<CancellationException> { tokenClient.getArbeidsforholdHistorikk(personIdent) } shouldBe cancelled

            val historyClient = aaregClient(aareg = { throw cancelled })
            shouldThrow<CancellationException> { historyClient.getArbeidsforholdHistorikk(personIdent) } shouldBe cancelled
        }
    })

private fun Throwable.shouldNotRetainPersonIdent(personIdent: String) {
    generateSequence(this) { it.cause }.forEach { failure ->
        failure.message.orEmpty().contains(personIdent) shouldBe false
    }
}

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
