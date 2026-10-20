package no.nav.syfo.narmestelederbehov.infrastructure

import no.nav.syfo.narmestelederbehov.application.DialogCompletion
import no.nav.syfo.narmestelederbehov.application.NarmestelederbehovDialog
import no.nav.syfo.narmestelederbehov.infrastructure.dialogporten.client.DialogPatchResult
import no.nav.syfo.narmestelederbehov.infrastructure.dialogporten.client.DialogportenClient
import no.nav.syfo.narmestelederbehov.infrastructure.dialogporten.client.HttpDialogportenClient
import no.nav.syfo.narmestelederbehov.infrastructure.dialogporten.client.dialogportenRevisionConflictFailure
import no.nav.syfo.narmestelederbehov.infrastructure.dialogporten.domain.DialogStatus
import no.nav.syfo.platform.upstream.getOrElse
import java.util.UUID

class DialogportenNarmestelederbehovDialog(private val client: DialogportenClient) : NarmestelederbehovDialog {
    override suspend fun complete(dialogId: UUID): DialogCompletion {
        val existing = client.getDialogById(dialogId).getOrElse { return DialogCompletion.Unavailable(it) }
        if (existing.status == DialogStatus.Completed) return DialogCompletion.Completed
        val patched = client.patchDialog(
            dialogId,
            existing.revision,
            HttpDialogportenClient.DialogportenPatch(
                HttpDialogportenClient.DialogportenPatch.OPERATION.REPLACE,
                HttpDialogportenClient.DialogportenPatch.PATH.STATUS,
                DialogStatus.Completed.name,
            ),
        ).getOrElse { return DialogCompletion.Unavailable(it) }
        return when (patched) {
            DialogPatchResult.Patched -> DialogCompletion.Completed
            DialogPatchResult.RevisionConflict -> {
                val current = client.getDialogById(dialogId).getOrElse { return DialogCompletion.Unavailable(it) }
                if (current.status == DialogStatus.Completed) {
                    DialogCompletion.Completed
                } else {
                    DialogCompletion.Unavailable(dialogportenRevisionConflictFailure())
                }
            }
        }
    }
}
