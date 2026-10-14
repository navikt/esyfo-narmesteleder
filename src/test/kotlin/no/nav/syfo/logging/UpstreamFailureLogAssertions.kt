package no.nav.syfo.logging

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.joran.JoranConfigurator
import ch.qos.logback.classic.util.LogbackMDCAdapter
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import no.nav.esyfo.observability.testkit.RuntimeLogContract
import no.nav.esyfo.observability.testkit.captureLogs
import no.nav.syfo.application.api.STATUS_PAGES_LOGGER_NAME
import no.nav.syfo.narmestelederbehov.application.GetNarmestelederbehovUseCase
import no.nav.syfo.narmestelederbehov.application.ListNarmestelederbehovUseCase
import no.nav.syfo.narmestelederrelasjon.application.RevokeNarmestelederrelasjonUseCase
import no.nav.syfo.narmestelederrelasjon.application.SearchActiveNarmestelederrelasjonerUseCase
import no.nav.syfo.narmestelederstatistikk.application.GetNarmestelederstatistikkUseCase
import org.slf4j.LoggerFactory

/** Captures both the HTTP edge and the owners that must not log a second rejection. */
internal fun assertSingleUpstreamFailureLog(
    upstream: String = "pdl",
    block: () -> Unit,
) {
    val context = LoggerContext().apply { mdcAdapter = LogbackMDCAdapter() }
    val loggers = listOf(
        STATUS_PAGES_LOGGER_NAME,
        GetNarmestelederbehovUseCase::class.java.name,
        ListNarmestelederbehovUseCase::class.java.name,
        RevokeNarmestelederrelasjonUseCase::class.java.name,
        SearchActiveNarmestelederrelasjonerUseCase::class.java.name,
        GetNarmestelederstatistikkUseCase::class.java.name,
    ).map { LoggerFactory.getLogger(it) as Logger }
    val original = loggers.map { it.level to it.isAdditive }
    context.putProperty("NAIS_CLUSTER_NAME", "test")
    JoranConfigurator().apply {
        this.context = context
        doConfigure("src/main/resources/logback.xml")
    }
    val appender = requireNotNull(context.getLogger(Logger.ROOT_LOGGER_NAME).getAppender("stdout_json"))
    try {
        loggers.forEach {
            it.level = Level.TRACE
            it.isAdditive = false
            it.addAppender(appender)
        }
        captureLogs(loggers.first(), "stdout_json").use { capture ->
            block()
            RuntimeLogContract(mapOf("event_type" to setOf("api_request_failed"))).assertValid(capture.records, expectedCount = 1)
            val serialized = capture.records.single()
            serialized shouldNotContain "12345678901"
            val record = jacksonObjectMapper().readTree(serialized)
            record["event_type"].asText() shouldBe "api_request_failed"
            record["failure_kind"].asText() shouldBe "error_response"
            record["upstream"].asText() shouldBe upstream
            record["upstream_errors"][0]["code"].asText() shouldBe "server_error"
            record.has("stack_trace") shouldBe false
        }
    } finally {
        loggers.forEachIndexed { index, logger ->
            logger.detachAppender(appender)
            logger.level = original[index].first
            logger.isAdditive = original[index].second
        }
        context.stop()
    }
}
