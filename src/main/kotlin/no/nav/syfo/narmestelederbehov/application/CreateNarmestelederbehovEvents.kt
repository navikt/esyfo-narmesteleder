package no.nav.syfo.narmestelederbehov.application

import no.nav.syfo.logging.applicationEvent
import no.nav.syfo.narmestelederbehov.domain.NarmestelederbehovId
import org.slf4j.event.Level
import java.util.UUID

// Log values for behov_source; kept equal to the Kafka topic names the behov originates from.
internal const val SENDT_SYKMELDING_SOURCE_NAME = "teamsykmelding.syfo-sendt-sykmelding"
internal const val NARMESTELEDER_LEESAH_SOURCE_NAME = "teamsykmelding.syfo-narmesteleder-leesah"

internal enum class StoredDegradedReason {
    EMPLOYMENT_MISSING,
    EMPLOYMENT_MAIN_ORG_MISSING,
    SICK_LEAVE_MAIN_ORG_MISSING,
}

internal data class StoredDegradedDetails(
    val source: NarmestelederbehovSource,
    val reason: StoredDegradedReason,
)

internal val narmestelederbehovStoredDegraded = applicationEvent<StoredDegradedDetails>(
    name = "narmestelederbehov_stored_degraded",
    level = Level.WARN,
    message = "Nearest leader need was stored for follow-up without required employment or organization data",
    fields = mapOf(
        "behov_source" to {
            when (it.source) {
                is NarmestelederbehovSource.SendtSykmelding -> SENDT_SYKMELDING_SOURCE_NAME
                is NarmestelederbehovSource.NarmestelederLeesah -> NARMESTELEDER_LEESAH_SOURCE_NAME
            }
        },
        "sykmelding_id" to { details ->
            (details.source as? NarmestelederbehovSource.SendtSykmelding)?.sykmeldingId?.let { id ->
                runCatching { UUID.fromString(id).toString() }.getOrNull()
            }
        },
        "narmesteleder_id" to { (it.source as? NarmestelederbehovSource.NarmestelederLeesah)?.relationId?.toString() },
        "reason" to { it.reason.name },
    ),
)

internal data class DialogCreationDetails(val id: NarmestelederbehovId, val dialogId: UUID? = null)

private val dialogCreationFields: Map<String, (DialogCreationDetails) -> Any?> = mapOf(
    "behov_id" to { it.id.value.toString() },
    "dialog_id" to { it.dialogId?.toString() },
)

internal val dialogPersonEnrichmentFailed = applicationEvent(
    name = "dialog_person_enrichment_failed",
    level = Level.WARN,
    message = "PDL enrichment failed; creating the dialog without person details",
    upstream = "pdl",
    fields = dialogCreationFields,
)

internal val dialogCreationFailed = applicationEvent(
    name = "dialogporten_dialog_failed",
    level = Level.ERROR,
    message = "Dialogporten dialog operation failed",
    upstream = "dialogporten",
    fields = dialogCreationFields + ("action" to { _: DialogCreationDetails -> "CREATE" }),
)

internal val dialogCreatedForClosedBehov = applicationEvent(
    name = "dialog_created_for_closed_behov",
    level = Level.WARN,
    message = "Behov left BEHOV_CREATED while its dialog was created; the behov was not updated",
    fields = dialogCreationFields,
)
