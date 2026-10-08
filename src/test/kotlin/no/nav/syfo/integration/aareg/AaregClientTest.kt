package no.nav.syfo.integration.aareg

import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.joran.JoranConfigurator
import ch.qos.logback.classic.util.LogbackMDCAdapter
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.types.shouldBeSameInstanceAs
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.plugins.ServerResponseException
import io.ktor.client.request.get
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import no.nav.esyfo.observability.testkit.RuntimeLogContract
import no.nav.esyfo.observability.testkit.captureLogs
import no.nav.syfo.application.api.NAV_CALL_ID_HEADER
import no.nav.syfo.application.exception.UpstreamRequestException
import no.nav.syfo.integration.TEST_SYSTEM_TOKEN
import no.nav.syfo.integration.respondJson
import no.nav.syfo.integration.respondWithSystemToken
import no.nav.syfo.integration.texasHttpClient
import no.nav.syfo.integration.upstreamHttpClient
import no.nav.syfo.logging.applicationEvent
import no.nav.syfo.logging.logEvent
import no.nav.syfo.platform.upstream.UpstreamFailureStage
import no.nav.syfo.platform.upstream.UpstreamResult
import no.nav.syfo.platform.upstream.getOrThrow
import no.nav.syfo.texas.client.TEXAS
import org.slf4j.MDC
import org.slf4j.event.Level
import java.net.SocketTimeoutException
import java.time.LocalDate
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException

private val aaregDiagnosticTestEvent = applicationEvent<Unit>(
    name = "aareg_diagnostic_test_failed",
    level = Level.ERROR,
    message = "Aareg test failure",
    upstream = "aareg",
)

class AaregClientTest :
    FunSpec({
        val personIdent = "12345678910"

        test("fetches arbeidsforhold from Aareg with a system token") {
            val expected = FakeAaregClient()
                .apply { arbeidsForholdForIdent[personIdent] = listOf("123456789" to "987654321") }
                .getArbeidsforhold(personIdent)
                .getOrThrow()
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

            client.getArbeidsforhold(personIdent) shouldBe UpstreamResult.Success(expected)
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

            client.getArbeidsforholdHistorikk(personIdent) shouldBe UpstreamResult.Success(AaregArbeidsforholdOversikt())
        }

        test("keeps both serialized request bodies byte-identical") {
            val bodies = mutableListOf<String>()
            val client = aaregClient(aareg = { request ->
                bodies += String(request.body.toByteArray(), Charsets.UTF_8)
                respondJson("""{"arbeidsforholdoversikter":[]}""")
            })

            client.getArbeidsforhold(personIdent)
            client.getArbeidsforholdHistorikk(personIdent)

            bodies shouldBe listOf(
                """{"arbeidstakerId":"$personIdent","rapporteringsordninger":["A_ORDNINGEN","FOER_A_ORDNINGEN"]}""",
                """{"arbeidstakerId":"$personIdent","arbeidsforholdtyper":["ordinaertArbeidsforhold","maritimtArbeidsforhold","forenkletOppgjoersordning"],"rapporteringsordninger":["A_ORDNINGEN","FOER_A_ORDNINGEN"],"arbeidsforholdstatuser":["AKTIV","FREMTIDIG","AVSLUTTET"]}""",
            )
        }

        test("sends a non-blank UUID call id on both operations when MDC is missing or blank") {
            listOf(null, "", "   ").forEach { traceId ->
                withTraceId(traceId) {
                    val callIds = mutableListOf<String?>()
                    val client = aaregClient(aareg = { request ->
                        callIds += request.headers[NAV_CALL_ID_HEADER]
                        respondJson("""{"arbeidsforholdoversikter":[]}""")
                    })
                    client.getArbeidsforhold(personIdent)
                    client.getArbeidsforholdHistorikk(personIdent)

                    callIds.size shouldBe 2
                    callIds.forEach { callId ->
                        requireNotNull(callId).isNotBlank() shouldBe true
                        UUID.fromString(callId).toString() shouldBe callId
                    }
                }
            }
        }

        test("propagates the current MDC call id on both operations") {
            val traceId = "aareg-call-id-canary"
            withTraceId(traceId) {
                val callIds = mutableListOf<String?>()
                val client = aaregClient(aareg = { request ->
                    callIds += request.headers[NAV_CALL_ID_HEADER]
                    respondJson("""{"arbeidsforholdoversikter":[]}""")
                })
                client.getArbeidsforhold(personIdent)
                client.getArbeidsforholdHistorikk(personIdent)

                callIds shouldBe listOf(traceId, traceId)
            }
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

            val employments = client.getArbeidsforholdHistorikk(personIdent).getOrThrow().arbeidsforholdoversikter
            employments.map { it.startdato } shouldBe listOf(LocalDate.of(2025, 1, 1), null, null)
            employments.map { it.sluttdato } shouldBe listOf(LocalDate.of(2026, 3, 31), null, null)
        }

        val operations: List<Pair<String, suspend HttpAaregClient.(String) -> UpstreamResult<AaregArbeidsforholdOversikt>>> = listOf(
            "arbeidsforhold" to { getArbeidsforhold(it) },
            "history" to { getArbeidsforholdHistorikk(it) },
        )

        operations.forEach { (operation, fetch) ->
            listOf(HttpStatusCode.Found, HttpStatusCode.BadRequest, HttpStatusCode.NotFound, HttpStatusCode.InternalServerError).forEach { status ->
                test("$operation returns an Aareg response failure for HTTP ${status.value} without exposing the error body") {
                    val client = aaregClient(aareg = { respondJson("sensitive upstream body $personIdent", status) })

                    val result = client.fetch(personIdent).shouldBeInstanceOf<UpstreamResult.Failure>()
                    result.failure.upstream shouldBe AAREG
                    result.failure.stage shouldBe UpstreamFailureStage.RESPONSE
                    result.failure.status shouldBe status.value
                    result.shouldNotExposePersonIdent(personIdent)
                }
            }

            test("$operation returns an Aareg request failure without status for a transport error") {
                val timeout = SocketTimeoutException("sensitive upstream body $personIdent").apply {
                    initCause(IllegalStateException("sensitive upstream cause $personIdent"))
                }
                val client = aaregClient(aareg = { throw timeout })

                val result = client.fetch(personIdent).shouldBeInstanceOf<UpstreamResult.Failure>()
                result.failure.upstream shouldBe AAREG
                result.failure.stage shouldBe UpstreamFailureStage.REQUEST
                result.failure.status shouldBe null
                result.failure.cause shouldBeSameInstanceAs timeout
                result.shouldNotExposePersonIdent(personIdent)
            }

            test("$operation returns an Aareg response failure with the successful status for a malformed body") {
                val client = aaregClient(aareg = { respondJson("""{"arbeidsforholdoversikter":"sensitive upstream body $personIdent"}""") })

                val result = client.fetch(personIdent).shouldBeInstanceOf<UpstreamResult.Failure>()
                result.failure.upstream shouldBe AAREG
                result.failure.stage shouldBe UpstreamFailureStage.RESPONSE
                result.failure.status shouldBe 200
                result.failure.cause.javaClass.simpleName shouldBe "JsonConvertException"
                result.shouldNotExposePersonIdent(personIdent)
            }

            test("$operation propagates a Texas token exchange failure without sending the employee ident") {
                val client = aaregClient(
                    token = { request ->
                        String(request.body.toByteArray()).contains(personIdent) shouldBe false
                        respondJson("token endpoint unavailable", HttpStatusCode.ServiceUnavailable)
                    },
                    aareg = { error("No Aareg request should be made without a token") },
                )

                val result = client.fetch(personIdent).shouldBeInstanceOf<UpstreamResult.Failure>()
                result.failure.upstream shouldBe TEXAS
                result.failure.stage shouldBe UpstreamFailureStage.TOKEN_EXCHANGE
                result.failure.status shouldBe 503
                result.shouldNotExposePersonIdent(personIdent)
            }

            test("$operation rethrows cancellation while acquiring a token or sending the request") {
                val cancelled = CancellationException("cancelled")
                val tokenClient = aaregClient(
                    token = { throw cancelled },
                    aareg = { error("No Aareg request should be made after cancellation") },
                )
                shouldThrow<CancellationException> { tokenClient.fetch(personIdent) } shouldBe cancelled

                val requestClient = aaregClient(aareg = { throw cancelled })
                shouldThrow<CancellationException> { requestClient.fetch(personIdent) } shouldBe cancelled
            }
        }

        test("logs the Aareg failure with upstream status and Ktor cause without the ident canary") {
            val context = LoggerContext().apply { mdcAdapter = LogbackMDCAdapter() }
            try {
                context.putProperty("NAIS_CLUSTER_NAME", "test")
                JoranConfigurator().apply {
                    this.context = context
                    doConfigure("src/main/resources/logback.xml")
                }
                val logger = context.getLogger("aareg-client-diagnostics-test")
                val contract = RuntimeLogContract.forEvents(
                    aaregDiagnosticTestEvent,
                    exceptionTypes = setOf("ClientRequestException", "ServerResponseException"),
                )
                listOf(HttpStatusCode.NotFound, HttpStatusCode.InternalServerError).forEach { status ->
                    HttpClient(MockEngine { respond("sensitive upstream body $personIdent", status) }).use { responseClient ->
                        val response = responseClient.get("http://aareg/")
                        val original = if (status == HttpStatusCode.NotFound) {
                            ClientRequestException(response, "sensitive upstream body $personIdent")
                        } else {
                            ServerResponseException(response, "sensitive upstream body $personIdent")
                        }
                        val client = aaregClient(aareg = { throw original })
                        val result = client.getArbeidsforholdHistorikk(personIdent).shouldBeInstanceOf<UpstreamResult.Failure>()
                        result.failure.cause shouldBeSameInstanceAs original

                        captureLogs(logger, "stdout_json").use { capture ->
                            logger.logEvent(aaregDiagnosticTestEvent, Unit, upstreamFailure = result.failure)
                            contract.assertValid(capture.records, expectedCount = 1)
                            val output = capture.records.single()
                            val record = jacksonObjectMapper().readTree(output)
                            record["upstream"].asText() shouldBe "aareg"
                            record["upstream_status"].asInt() shouldBe status.value
                            record["failure_stage"].asText() shouldBe "response"
                            record["exception_type"].asText() shouldBe original.javaClass.simpleName
                            output shouldNotContain personIdent
                            output shouldNotContain "sensitive upstream body"
                        }
                    }
                }
            } finally {
                context.stop()
            }
        }
    })

private fun UpstreamResult.Failure.shouldNotExposePersonIdent(personIdent: String) {
    toString() shouldNotContain personIdent
    failure.toString() shouldNotContain personIdent
    shouldThrow<UpstreamRequestException> { getOrThrow() }.message.orEmpty() shouldNotContain personIdent
}

private suspend fun withTraceId(traceId: String?, action: suspend () -> Unit) {
    val previous = MDC.get("trace_id")
    try {
        if (traceId == null) MDC.remove("trace_id") else MDC.put("trace_id", traceId)
        action()
    } finally {
        if (previous == null) MDC.remove("trace_id") else MDC.put("trace_id", previous)
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
