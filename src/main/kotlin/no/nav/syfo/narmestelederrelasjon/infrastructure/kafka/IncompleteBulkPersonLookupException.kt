package no.nav.syfo.narmestelederrelasjon.infrastructure.kafka

/** Thrown by [PdlLeesahConsumer] to retry a batch without committing its offsets when the PDL lookup was incomplete. */
internal class IncompleteBulkPersonLookupException(
    val requestedCount: Int,
    val missingCount: Int,
) : RuntimeException("PDL bulk response did not contain all requested results") {
    companion object {
        const val ERROR_CODE = "PDL_BULK_RESPONSE_INCOMPLETE"
    }
}
