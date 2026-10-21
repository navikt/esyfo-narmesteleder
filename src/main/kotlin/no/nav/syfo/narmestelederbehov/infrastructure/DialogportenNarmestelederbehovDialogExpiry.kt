package no.nav.syfo.narmestelederbehov.infrastructure

import no.nav.syfo.narmestelederbehov.application.DialogExpiry
import no.nav.syfo.narmestelederbehov.application.NarmestelederbehovDialogExpiry
import no.nav.syfo.narmestelederbehov.infrastructure.dialogporten.client.DialogPatchResult
import no.nav.syfo.narmestelederbehov.infrastructure.dialogporten.client.DialogportenClient
import no.nav.syfo.narmestelederbehov.infrastructure.dialogporten.client.HttpDialogportenClient.DialogportenPatch
import no.nav.syfo.narmestelederbehov.infrastructure.dialogporten.client.HttpDialogportenClient.DialogportenPatch.OPERATION
import no.nav.syfo.narmestelederbehov.infrastructure.dialogporten.client.HttpDialogportenClient.DialogportenPatch.PATH
import no.nav.syfo.narmestelederbehov.infrastructure.dialogporten.client.dialogportenRevisionConflictFailure
import no.nav.syfo.narmestelederbehov.infrastructure.dialogporten.domain.DialogStatus
import no.nav.syfo.platform.upstream.getOrElse
import java.time.Instant
import java.util.UUID

class DialogportenNarmestelederbehovDialogExpiry(private val client: DialogportenClient) : NarmestelederbehovDialogExpiry {
    override suspend fun expire(dialogId: UUID, expirationTime: Instant): DialogExpiry {
        val existing = client.getDialogById(dialogId).getOrElse { return DialogExpiry.Unavailable(it) }
        val patched = client.patchDialog(
            dialogId,
            existing.revision,
            listOf(
                DialogportenPatch(OPERATION.REPLACE, PATH.STATUS, DialogStatus.Completed.name),
                DialogportenPatch(OPERATION.ADD, PATH.EXPIRES_AT, expirationTime.toString()),
            ),
        ).getOrElse { return DialogExpiry.Unavailable(it) }
        return when (patched) {
            DialogPatchResult.Patched -> DialogExpiry.Expired
            DialogPatchResult.RevisionConflict -> DialogExpiry.Unavailable(dialogportenRevisionConflictFailure())
        }
    }
}
