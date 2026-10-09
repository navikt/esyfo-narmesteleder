package no.nav.syfo.integration.ereg

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
import io.ktor.client.plugins.ResponseException
import io.ktor.client.statement.HttpResponsePipeline
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import no.nav.esyfo.observability.causeChain
import no.nav.esyfo.observability.testkit.RuntimeLogContract
import no.nav.esyfo.observability.testkit.captureLogs
import no.nav.syfo.application.exception.toUpstreamUnavailableException
import no.nav.syfo.integration.respondJson
import no.nav.syfo.logging.applicationEvent
import no.nav.syfo.logging.logEvent
import no.nav.syfo.platform.upstream.UpstreamFailureStage
import no.nav.syfo.platform.upstream.UpstreamResult
import no.nav.syfo.util.httpClientDefault
import org.slf4j.event.Level
import java.io.IOException
import java.net.SocketTimeoutException
import kotlin.coroutines.cancellation.CancellationException

private val eregDiagnosticTestEvent = applicationEvent<Unit>(
    name = "ereg_diagnostic_test_failed",
    level = Level.ERROR,
    message = "Ereg test failure",
    upstream = "ereg",
)

class EregClientTest :
    FunSpec({
        val orgNumber = "910000001"

        test("fetches the organization hierarchy from Ereg without a token") {
            val expected = Organisasjon(orgNumber, Navn(sammensattnavn = "Organization"))
            eregHttpClient { request ->
                request.method shouldBe HttpMethod.Get
                request.url.encodedPath shouldBe "/ereg/api/v2/organisasjon/$orgNumber"
                request.url.parameters["inkluderHierarki"] shouldBe "true"
                request.headers[HttpHeaders.ContentType] shouldBe ContentType.Application.Json.toString()
                request.headers[HttpHeaders.Authorization] shouldBe null
                respondJson(jacksonObjectMapper().writeValueAsString(expected))
            }.use { httpClient ->
                HttpEregClient("http://ereg", httpClient).getOrganisasjon(orgNumber) shouldBe UpstreamResult.Success(expected)
            }
        }

        test("returns Success(null) for HTTP 404 without retrying") {
            var requests = 0
            eregHttpClient {
                requests++
                respondJson("private response body $orgNumber", HttpStatusCode.NotFound)
            }.use { httpClient ->
                HttpEregClient("http://ereg", httpClient).getOrganisasjon(orgNumber) shouldBe UpstreamResult.Success(null)
            }
            requests shouldBe 1
        }

        listOf(
            HttpStatusCode.BadRequest,
            HttpStatusCode.Forbidden,
            HttpStatusCode.InternalServerError,
            HttpStatusCode.ServiceUnavailable,
        ).forEach { status ->
            test("returns an Ereg response failure with status for HTTP ${status.value}") {
                eregHttpClient { respondJson("private response body $orgNumber", status) }.use { httpClient ->
                    val result = HttpEregClient("http://ereg", httpClient).getOrganisasjon(orgNumber)
                        .shouldBeInstanceOf<UpstreamResult.Failure>()

                    result.failure.upstream shouldBe EREG
                    result.failure.stage shouldBe UpstreamFailureStage.RESPONSE
                    result.failure.status shouldBe status.value
                    result.failure.cause.shouldBeInstanceOf<ResponseException>().response.status shouldBe status
                    result.shouldNotExposeOrganizationNumber(orgNumber)
                }
            }
        }

        listOf(
            "timeout" to SocketTimeoutException("private request $orgNumber"),
            "IO error" to IOException("private request $orgNumber"),
            "unexpected request error" to IllegalStateException("private request $orgNumber"),
        ).forEach { (description, original) ->
            test("returns an Ereg request failure without status for $description after the configured retries") {
                var requests = 0
                eregHttpClient {
                    requests++
                    throw original
                }.use { httpClient ->
                    val result = HttpEregClient("http://ereg", httpClient).getOrganisasjon(orgNumber)
                        .shouldBeInstanceOf<UpstreamResult.Failure>()

                    result.failure.upstream shouldBe EREG
                    result.failure.stage shouldBe UpstreamFailureStage.REQUEST
                    result.failure.status shouldBe null
                    result.failure.cause.shouldRetainThrownException(original)
                    result.shouldNotExposeOrganizationNumber(orgNumber)
                }
                requests shouldBe 3
            }
        }

        listOf(HttpStatusCode.OK, HttpStatusCode.Created).forEach { status ->
            test("returns an Ereg response failure with status ${status.value} for a malformed successful body") {
                eregHttpClient {
                    respondJson("""{"organisasjonsnummer":"$orgNumber","navn":"private response body $orgNumber"}""", status)
                }.use { httpClient ->
                    val result = HttpEregClient("http://ereg", httpClient).getOrganisasjon(orgNumber)
                        .shouldBeInstanceOf<UpstreamResult.Failure>()

                    result.failure.upstream shouldBe EREG
                    result.failure.stage shouldBe UpstreamFailureStage.RESPONSE
                    result.failure.status shouldBe status.value
                    result.failure.cause.javaClass.simpleName shouldBe "JsonConvertException"
                    result.shouldNotExposeOrganizationNumber(orgNumber)
                }
            }
        }

        test("rethrows cancellation while sending the request") {
            val cancelled = CancellationException("Request cancelled")
            eregHttpClient { throw cancelled }.use { httpClient ->
                shouldThrow<CancellationException> {
                    HttpEregClient("http://ereg", httpClient).getOrganisasjon(orgNumber)
                }.shouldRetainThrownException(cancelled)
            }
        }

        test("rethrows cancellation while receiving the organization body") {
            val cancelled = CancellationException("Response cancelled")
            var receivingBody = false
            eregHttpClient { respondJson("""{"organisasjonsnummer":"$orgNumber"}""") }.use { httpClient ->
                httpClient.responsePipeline.intercept(HttpResponsePipeline.Receive) {
                    if (subject.expectedType.type == Organisasjon::class) {
                        receivingBody = true
                        throw cancelled
                    }
                }

                shouldThrow<CancellationException> {
                    HttpEregClient("http://ereg", httpClient).getOrganisasjon(orgNumber)
                } shouldBeSameInstanceAs cancelled
            }
            receivingBody shouldBe true
        }

        test("logs Ereg failure metadata without the organization number or upstream body") {
            val context = LoggerContext().apply { mdcAdapter = LogbackMDCAdapter() }
            try {
                context.putProperty("NAIS_CLUSTER_NAME", "test")
                JoranConfigurator().apply {
                    this.context = context
                    doConfigure("src/main/resources/logback.xml")
                }
                val logger = context.getLogger("ereg-client-diagnostics-test")
                val contract = RuntimeLogContract.forEvents(
                    eregDiagnosticTestEvent,
                    exceptionTypes = setOf("ClientRequestException", "ServerResponseException"),
                )
                listOf(HttpStatusCode.BadRequest, HttpStatusCode.InternalServerError).forEach { status ->
                    eregHttpClient { respondJson("private response body $orgNumber", status) }.use { httpClient ->
                        val result = HttpEregClient("http://ereg", httpClient).getOrganisasjon(orgNumber)
                            .shouldBeInstanceOf<UpstreamResult.Failure>()

                        captureLogs(logger, "stdout_json").use { capture ->
                            logger.logEvent(eregDiagnosticTestEvent, Unit, upstreamFailure = result.failure)
                            contract.assertValid(capture.records, expectedCount = 1)
                            val output = capture.records.single()
                            val record = jacksonObjectMapper().readTree(output)
                            record["upstream"].asText() shouldBe "ereg"
                            record["upstream_status"].asInt() shouldBe status.value
                            record["failure_stage"].asText() shouldBe "response"
                            record["exception_type"].asText() shouldBe result.failure.cause.javaClass.simpleName
                            output shouldNotContain orgNumber
                            output shouldNotContain "private response body"
                            output shouldNotContain "UpstreamRequestException"
                        }
                    }
                }
            } finally {
                context.stop()
            }
        }
    })

private fun UpstreamResult.Failure.shouldNotExposeOrganizationNumber(orgNumber: String) {
    toString() shouldNotContain orgNumber
    failure.toString() shouldNotContain orgNumber
    failure.toUpstreamUnavailableException().message.orEmpty() shouldNotContain orgNumber
}

// Coroutine stack-trace recovery may copy exceptions across MockEngine coroutine boundaries.
private fun Throwable.shouldRetainThrownException(original: Throwable) {
    javaClass shouldBe original.javaClass
    causeChain().any { it === original } shouldBe true
}

private fun eregHttpClient(handler: MockRequestHandler): HttpClient = httpClientDefault(
    HttpClient(MockEngine { request -> handler(request) })
)
