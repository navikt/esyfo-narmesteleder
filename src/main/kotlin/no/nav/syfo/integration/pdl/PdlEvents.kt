package no.nav.syfo.integration.pdl

import no.nav.syfo.logging.applicationEvent
import no.nav.syfo.platform.upstream.UpstreamError
import org.slf4j.event.Level

internal enum class PdlLookupDegradedReason {
    GRAPHQL_ERRORS,
    BATCH_FAILED
}

internal data class PdlLookupDegradedDetails(
    val reason: PdlLookupDegradedReason,
    val pdlErrors: List<UpstreamError>? = null,
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
        "pdl_errors" to { details ->
            details.pdlErrors?.map { mapOf("code" to it.code, "detail" to it.detail, "path" to it.path, "message" to it.message) }
        },
        "error_count" to { it.errorCount },
        "record_count" to { it.recordCount },
    ),
)
