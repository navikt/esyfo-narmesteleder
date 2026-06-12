package no.nav.syfo.pdl.kafka

internal data class RelevantNameEventMetricKey(
    val opplysningstype: String,
    val endringstype: String,
)

internal sealed interface RecordProcessingResult {
    data class RelevantNameRecord(
        val personidenter: List<String>,
        val metricKey: RelevantNameEventMetricKey,
    ) : RecordProcessingResult

    data class Metrics(
        val bufferedEventMetric: BufferedLeesahEventMetric,
    ) : RecordProcessingResult
}

internal data class BufferedLeesahEventMetric(
    val opplysningstype: String,
    val endringstype: String,
    val result: String,
    val count: Int = 1,
)

internal fun PdlLeesahNameUpdateResult.emitPersonUpdateMetrics() {
    countPdlLeesahPersonUpdate(PdlLeesahNameUpdateService.RESULT_UPDATED, updatedCount)
    countPdlLeesahPersonUpdate(PdlLeesahNameUpdateService.RESULT_NOT_FOUND_IN_REGISTER, notFoundInRegisterCount)
    countPdlLeesahPersonUpdate(PdlLeesahNameUpdateService.RESULT_PDL_NOT_FOUND, pdlNotFoundCount)
}
