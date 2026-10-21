package no.nav.syfo.narmestelederbehov.application

import no.nav.syfo.logging.applicationEvent
import org.slf4j.event.Level
import java.util.UUID

// Shared by behov dialog creation, scheduled sending and the remaining legacy update/expire flow.

internal enum class DialogAction {
    CREATE,
    COMPLETE,
    EXPIRE,
}

internal data class DialogEventDetails(val behovId: UUID?, val dialogId: UUID? = null, val action: DialogAction? = null)

private val dialogEventFields: Map<String, (DialogEventDetails) -> Any?> = mapOf(
    "behov_id" to { it.behovId?.toString() },
    "dialog_id" to { it.dialogId?.toString() },
)

internal val dialogPersonEnrichmentFailed = applicationEvent(
    name = "dialog_person_enrichment_failed",
    level = Level.WARN,
    message = "PDL enrichment failed; creating the dialog without person details",
    upstream = "pdl",
    fields = dialogEventFields,
)

internal val dialogportenDialogFailed = applicationEvent(
    name = "dialogporten_dialog_failed",
    level = Level.ERROR,
    message = "Dialogporten dialog operation failed",
    upstream = "dialogporten",
    fields = dialogEventFields + ("action" to { it: DialogEventDetails -> it.action?.name }),
)

internal val dialogCreatedForClosedBehov = applicationEvent(
    name = "dialog_created_for_closed_behov",
    level = Level.WARN,
    message = "Behov left BEHOV_CREATED while its dialog was created; the behov was not updated",
    fields = dialogEventFields,
)
