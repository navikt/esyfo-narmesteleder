package no.nav.syfo.narmestelederbehov.infrastructure

import no.nav.syfo.altinn.dialogporten.client.DialogportenClient
import no.nav.syfo.altinn.dialogporten.client.HttpDialogportenClient
import no.nav.syfo.altinn.dialogporten.domain.DialogStatus
import no.nav.syfo.narmestelederbehov.application.NarmestelederbehovDialog
import java.util.UUID

class DialogportenNarmestelederbehovDialog(private val client: DialogportenClient) : NarmestelederbehovDialog {
    override suspend fun complete(dialogId: UUID) {
        val existing = client.getDialogById(dialogId)
        client.patchDialog(
            dialogId,
            existing.revision,
            HttpDialogportenClient.DialogportenPatch(
                HttpDialogportenClient.DialogportenPatch.OPERATION.REPLACE,
                HttpDialogportenClient.DialogportenPatch.PATH.STATUS,
                DialogStatus.Completed.name,
            ),
        )
    }
}
