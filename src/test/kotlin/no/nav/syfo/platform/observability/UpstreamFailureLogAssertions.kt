package no.nav.syfo.platform.observability

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import no.nav.esyfo.observability.testkit.RuntimeLogContract
import no.nav.syfo.narmestelederbehov.application.GetNarmestelederbehovUseCase
import no.nav.syfo.narmestelederbehov.application.ListNarmestelederbehovUseCase
import no.nav.syfo.narmestelederrelasjon.application.get.GetNarmestelederrelasjonUseCase
import no.nav.syfo.narmestelederrelasjon.application.revoke.RevokeNarmestelederrelasjonUseCase
import no.nav.syfo.narmestelederrelasjon.application.search.SearchActiveNarmestelederrelasjonerUseCase
import no.nav.syfo.narmestelederrelasjon.application.submit.SubmitNarmestelederrelasjonUseCase
import no.nav.syfo.narmestelederstatistikk.application.GetNarmestelederstatistikkUseCase
import no.nav.syfo.platform.api.STATUS_PAGES_LOGGER_NAME

/** Captures both the HTTP edge and the owners that must not log a second rejection. */
internal fun assertSingleUpstreamFailureLog(
    upstream: String = "pdl",
    block: () -> Unit,
) {
    val productionLogs = ProductionLogs(
        listOf(
            STATUS_PAGES_LOGGER_NAME,
            GetNarmestelederbehovUseCase::class.java.name,
            ListNarmestelederbehovUseCase::class.java.name,
            GetNarmestelederrelasjonUseCase::class.java.name,
            RevokeNarmestelederrelasjonUseCase::class.java.name,
            SearchActiveNarmestelederrelasjonerUseCase::class.java.name,
            SubmitNarmestelederrelasjonUseCase::class.java.name,
            GetNarmestelederstatistikkUseCase::class.java.name,
        ),
    )
    productionLogs.install()
    try {
        productionLogs.capture().use { capture ->
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
        productionLogs.restore()
    }
}
