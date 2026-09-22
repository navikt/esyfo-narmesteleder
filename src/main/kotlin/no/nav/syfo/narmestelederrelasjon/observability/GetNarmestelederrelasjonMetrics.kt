package no.nav.syfo.narmestelederrelasjon.observability

import io.micrometer.core.instrument.Counter
import no.nav.syfo.application.metric.METRICS_NS
import no.nav.syfo.application.metric.METRICS_REGISTRY
import no.nav.syfo.narmestelederrelasjon.application.GetNarmestelederrelasjonResult

const val GET_NARMESTELEDERRELASJON_TOTAL = "${METRICS_NS}_get_narmestelederrelasjon_total"

private fun getNarmestelederrelasjonCounter(outcome: String): Counter = Counter.builder(GET_NARMESTELEDERRELASJON_TOTAL)
    .description("Counts outcomes for reading an active narmestelederrelasjon by id")
    .tag("outcome", outcome)
    .register(METRICS_REGISTRY)

private val foundCounter = getNarmestelederrelasjonCounter("found")
private val notFoundCounter = getNarmestelederrelasjonCounter("not_found")

fun countGetNarmestelederrelasjon(result: GetNarmestelederrelasjonResult) {
    when (result) {
        is GetNarmestelederrelasjonResult.Found -> foundCounter.increment()
        GetNarmestelederrelasjonResult.NotFound -> notFoundCounter.increment()
    }
}
