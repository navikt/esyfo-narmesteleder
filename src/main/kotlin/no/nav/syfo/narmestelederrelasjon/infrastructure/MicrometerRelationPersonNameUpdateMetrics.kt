package no.nav.syfo.narmestelederrelasjon.infrastructure

import io.micrometer.core.instrument.Counter
import no.nav.syfo.application.metric.METRICS_NS
import no.nav.syfo.application.metric.METRICS_REGISTRY
import no.nav.syfo.narmestelederrelasjon.application.RelationPersonNameUpdateMetrics
import no.nav.syfo.narmestelederrelasjon.application.RelationPersonNameUpdateResult

internal const val PDL_LEESAH_PERSON_UPDATE_TOTAL = "${METRICS_NS}_pdl_leesah_person_update_total"

class MicrometerRelationPersonNameUpdateMetrics : RelationPersonNameUpdateMetrics {
    override fun recordLookupFailed(count: Int) = countPdlLeesahPersonUpdate(RESULT_PDL_ERROR, count)

    companion object {
        internal const val RESULT_UPDATED = "updated"
        internal const val RESULT_NOT_FOUND_IN_REGISTER = "not_found_in_register"
        internal const val RESULT_PDL_NOT_FOUND = "pdl_not_found"
        internal const val RESULT_PDL_ERROR = "pdl_error"
    }
}

/** Emitted by the consumer after the batch offsets have been committed. */
internal fun RelationPersonNameUpdateResult.emitPersonUpdateMetrics() {
    countPdlLeesahPersonUpdate(MicrometerRelationPersonNameUpdateMetrics.RESULT_UPDATED, updatedCount)
    countPdlLeesahPersonUpdate(MicrometerRelationPersonNameUpdateMetrics.RESULT_NOT_FOUND_IN_REGISTER, notFoundInRegisterCount)
    countPdlLeesahPersonUpdate(MicrometerRelationPersonNameUpdateMetrics.RESULT_PDL_NOT_FOUND, pdlNotFoundCount)
}

private fun countPdlLeesahPersonUpdate(
    result: String,
    count: Int,
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
