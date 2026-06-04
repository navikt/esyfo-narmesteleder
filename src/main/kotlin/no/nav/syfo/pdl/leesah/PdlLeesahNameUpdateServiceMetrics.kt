package no.nav.syfo.pdl.leesah

import io.micrometer.core.instrument.Counter
import no.nav.syfo.application.metric.METRICS_NS
import no.nav.syfo.application.metric.METRICS_REGISTRY

const val PDL_LEESAH_PERSON_UPDATE_TOTAL = "${METRICS_NS}_pdl_leesah_person_update_total"

fun countPdlLeesahPersonUpdate(
    result: String,
    count: Int = 1,
) {
    if (count <= 0) {
        return
    }

    Counter.builder(PDL_LEESAH_PERSON_UPDATE_TOTAL)
        .description("Counts outcomes when NAVN_V1 events update existing persons from PDL")
        .tag("result", result)
        .register(METRICS_REGISTRY)
        .increment(count.toDouble())
}
