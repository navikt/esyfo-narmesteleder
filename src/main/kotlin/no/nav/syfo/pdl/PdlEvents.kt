package no.nav.syfo.pdl

import no.nav.syfo.logging.applicationEvent
import no.nav.syfo.pdl.client.ResponseError
import org.slf4j.event.Level

internal enum class PdlLookupDegradedReason {
    GRAPHQL_ERRORS,
    BATCH_FAILED
}

internal data class PdlLookupDegradedDetails(
    val reason: PdlLookupDegradedReason,
    val pdlErrors: List<ResponseError>? = null,
    val errorCount: Int? = null,
    val recordCount: Int? = null,
)

internal val pdlLookupDegraded = applicationEvent<PdlLookupDegradedDetails>(
    name = "pdl_lookup_degraded",
    level = Level.WARN,
    message = "PDL lookup was degraded",
    upstream = "pdl",
    fields = mapOf(
        "reason" to { it.reason.name },
        "pdl_errors" to { it.pdlErrors },
        "error_count" to { it.errorCount },
        "record_count" to { it.recordCount },
    ),
)
