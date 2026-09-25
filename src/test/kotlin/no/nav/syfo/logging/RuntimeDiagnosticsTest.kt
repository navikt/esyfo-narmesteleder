package no.nav.syfo.logging

import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.joran.JoranConfigurator
import ch.qos.logback.classic.util.LogbackMDCAdapter
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import no.nav.esyfo.observability.testkit.captureLogs
import no.nav.syfo.pdl.PdlLookupDegradedDetails
import no.nav.syfo.pdl.PdlLookupDegradedReason
import no.nav.syfo.pdl.client.ErrorExtension
import no.nav.syfo.pdl.client.ResponseError
import no.nav.syfo.pdl.pdlLookupDegraded
import org.slf4j.event.Level
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.sql.SQLException
import javax.net.ssl.SSLHandshakeException
import kotlin.coroutines.cancellation.CancellationException

private val diagnosticTestEvent = applicationEvent<Unit>(
    name = "diagnostic_test_failed",
    level = Level.ERROR,
    message = "Test failure",
    upstream = "dialogporten",
)

class RuntimeDiagnosticsTest :
    StringSpec({
        "the production encoder keeps distinct transport causes without exception payloads" {
            val cases = listOf(
                UnknownHostException("person-12345678901.example") to "dns",
                SocketTimeoutException("token-secret") to "timeout",
                ConnectException("request-person-12345678901") to "connection",
                SSLHandshakeException("upstream-body-canary") to "tls",
                IllegalStateException("authorization-canary") to "unknown",
            )
            cases.forEach { (root, expectedKind) ->
                val wrapped = IllegalStateException("request-body-canary", root)
                withProductionLogger { logger ->
                    captureLogs(logger, "stdout_json").use { capture ->
                        logger.logEvent(diagnosticTestEvent, Unit, cause = wrapped)
                        capture.records shouldHaveSize 1
                        val json = capture.records.single()
                        val record = jacksonObjectMapper().readTree(json)
                        record["event_type"].asText() shouldBe "diagnostic_test_failed"
                        record.has("operation") shouldBe false
                        record.has("error_code") shouldBe false
                        record["failure_kind"].asText() shouldBe expectedKind
                        record["cause_type"].asText() shouldBe root.javaClass.simpleName
                        record["cause_types"].size() shouldBe 2
                        record["stack_trace"].asText() shouldContain root.javaClass.name
                        record["upstream"].asText() shouldBe "dialogporten"
                        record.has("outcome") shouldBe false
                        record.has("failure_stage") shouldBe false
                        record.has("upstream_status") shouldBe false
                        listOf("12345678901", "token-secret", "upstream-body-canary", "authorization-canary", "request-body-canary")
                            .forEach { json shouldNotContain it }
                    }
                }
            }
        }

        "cause traversal terminates on cycles and retains the real origin" {
            val first = IllegalStateException("first")
            val second = ConnectException("second")
            first.initCause(second)
            second.initCause(first)
            val diagnostic = first.failureDiagnostics()
            diagnostic.causeTypes shouldBe listOf("IllegalStateException", "ConnectException")
            diagnostic.failureKind shouldBe "connection"
        }

        "unsafe exception type names use the contract-valid fallback" {
            val diagnostic = object : Throwable("private-canary") {}.failureDiagnostics()
            diagnostic.exceptionType shouldBe "Exception"
            diagnostic.causeType shouldBe "Exception"
            diagnostic.causeTypes shouldBe listOf("Throwable")
        }

        "nested and anonymous failures use valid superclass categories without changing cause_types" {
            class OddFailure : RuntimeException()

            val diagnostic = OddFailure().apply {
                initCause(object : IllegalStateException("private-canary") {})
            }.failureDiagnostics()

            diagnostic.exceptionType shouldBe "RuntimeException"
            diagnostic.causeType shouldBe "IllegalStateException"
            diagnostic.causeTypes shouldBe listOf("OddFailure", "Throwable")
        }

        "out-of-range Ktor response statuses are omitted without changing HTTP classification" {
            HttpClient(MockEngine { respond("private-canary", HttpStatusCode(42, "Unknown")) }).use { client ->
                val failure = ClientRequestException(client.get("https://upstream.test/"), "private-canary")
                withProductionLogger { logger ->
                    captureLogs(logger, "stdout_json").use { capture ->
                        logger.logEvent(diagnosticTestEvent, Unit, cause = failure)
                        val serialized = capture.records.single()
                        val record = jacksonObjectMapper().readTree(serialized)
                        record.has("upstream_status") shouldBe false
                        record["failure_kind"].asText() shouldBe "http"
                        record["exception_type"].asText() shouldBe "ClientRequestException"
                        serialized shouldNotContain "private-canary"
                    }
                }
            }
        }

        "diagnostics never throw for wrapped cancellation" {
            withProductionLogger { logger ->
                captureLogs(logger, "stdout_json").use { capture ->
                    logger.logEvent(diagnosticTestEvent, Unit, cause = IllegalStateException(CancellationException("cancelled")))
                    capture.records shouldHaveSize 1
                }
            }
        }

        "fatal errors retain their class but never their message or cause payload" {
            withProductionLogger { logger ->
                captureLogs(logger, "stdout_json").use { capture ->
                    logger.logEvent(diagnosticTestEvent, Unit, cause = LinkageError("private-canary", IllegalStateException("payload-canary")))
                    val record = jacksonObjectMapper().readTree(capture.records.single())
                    record["exception_type"].asText() shouldBe "LinkageError"
                    record["cause_type"].asText() shouldBe "IllegalStateException"
                    capture.records.single() shouldNotContain "private-canary"
                    capture.records.single() shouldNotContain "payload-canary"
                }
            }
        }

        "PDL GraphQL errors retain approved structured diagnostics with the production encoder" {
            withProductionLogger { logger ->
                captureLogs(logger, "stdout_json").use { capture ->
                    logger.logEvent(
                        pdlLookupDegraded,
                        PdlLookupDegradedDetails(
                            reason = PdlLookupDegradedReason.GRAPHQL_ERRORS,
                            pdlErrors = listOf(ResponseError("GraphQL failure", null, listOf("person"), ErrorExtension("NOT_FOUND", null, null))),
                            errorCount = 1,
                        ),
                    )
                    val record = jacksonObjectMapper().readTree(capture.records.single())
                    record["event_type"].asText() shouldBe "pdl_lookup_degraded"
                    record["reason"].asText() shouldBe "GRAPHQL_ERRORS"
                    record["error_count"].asInt() shouldBe 1
                    record["pdl_errors"][0]["message"].asText() shouldBe "GraphQL failure"
                    record["pdl_errors"][0]["path"][0].asText() shouldBe "person"
                    record["pdl_errors"][0]["extensions"]["code"].asText() shouldBe "NOT_FOUND"
                }
            }
        }
        "database SQL state remains actionable" {
            withProductionLogger { logger ->
                captureLogs(logger, "stdout_json").use { capture ->
                    logger.logEvent(diagnosticTestEvent, Unit, cause = SQLException("person-payload-canary", "23505"))
                    val database = jacksonObjectMapper().readTree(capture.records[0])
                    database["sql_state"].asText() shouldBe "23505"
                    capture.records[0] shouldNotContain "person-payload-canary"
                }
            }
        }
    })

private fun withProductionLogger(test: (ch.qos.logback.classic.Logger) -> Unit) {
    val context = LoggerContext().apply { mdcAdapter = LogbackMDCAdapter() }
    try {
        context.putProperty("NAIS_CLUSTER_NAME", "test")
        JoranConfigurator().apply {
            this.context = context
            doConfigure("src/main/resources/logback.xml")
        }
        test(context.getLogger("runtime-diagnostics-test"))
    } finally {
        context.stop()
    }
}
