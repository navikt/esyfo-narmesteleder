package no.nav.syfo.narmestelederbehov.infrastructure.dialogporten.client

import no.nav.syfo.narmestelederbehov.infrastructure.dialogporten.domain.Content
import no.nav.syfo.narmestelederbehov.infrastructure.dialogporten.domain.ContentValue
import no.nav.syfo.narmestelederbehov.infrastructure.dialogporten.domain.ContentValueItem
import no.nav.syfo.narmestelederbehov.infrastructure.dialogporten.domain.Dialog
import no.nav.syfo.narmestelederbehov.infrastructure.dialogporten.domain.DialogStatus
import no.nav.syfo.narmestelederbehov.infrastructure.dialogporten.domain.ExtendedDialog
import no.nav.syfo.platform.observability.applicationLogger
import no.nav.syfo.platform.upstream.UpstreamResult
import java.util.UUID

class FakeDialogportenClient : DialogportenClient {
    companion object {
        val logger = applicationLogger(FakeDialogportenClient::class.java)
    }

    override suspend fun createDialog(dialog: Dialog): UpstreamResult<DialogCreateResult> {
        logger.info("Fake call creating dialog")
        return UpstreamResult.Success(DialogCreateResult.Created(UUID.randomUUID()))
    }

    override suspend fun getDialogById(dialogId: UUID): UpstreamResult<ExtendedDialog> = UpstreamResult.Success(
        ExtendedDialog(
            id = dialogId,
            externalReference = dialogId.toString(),
            party = "urn:altinn:organization:identifier-no:123456789",
            status = DialogStatus.RequiresAttention,
            isApiOnly = false,
            attachments = emptyList(),
            revision = UUID.randomUUID(),
            content = Content(
                title = ContentValue(value = listOf(ContentValueItem(value = "Test content title"))),
                summary = ContentValue(value = listOf(ContentValueItem(value = "Test content summary")))
            ),
            serviceResource = "service:resource",
            transmissions = listOf(),
        )
    )

    override suspend fun patchDialog(
        dialogId: UUID,
        revisionNumber: UUID,
        patch: List<HttpDialogportenClient.DialogportenPatch>
    ): UpstreamResult<DialogPatchResult> {
        logger.info("Fake call patching dialog with id: $dialogId")
        return UpstreamResult.Success(DialogPatchResult.Patched)
    }
}
