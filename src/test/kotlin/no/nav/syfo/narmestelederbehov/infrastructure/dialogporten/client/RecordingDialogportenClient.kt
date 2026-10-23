package no.nav.syfo.narmestelederbehov.infrastructure.dialogporten.client

import io.kotest.matchers.shouldBe
import no.nav.syfo.narmestelederbehov.infrastructure.dialogporten.domain.Dialog
import no.nav.syfo.narmestelederbehov.infrastructure.dialogporten.domain.ExtendedDialog
import no.nav.syfo.platform.upstream.UpstreamResult
import java.util.UUID

/**
 * Dialogporten client stub with configurable answers per operation. Unconfigured operations fail the test.
 * Records operations in [calls] ("create", "get", "patch") and, when [expectedDialogId] is set, asserts it on reads and patches.
 */
internal class RecordingDialogportenClient(
    private val expectedDialogId: UUID? = null,
    var create: suspend () -> UpstreamResult<UUID> = { error("Unexpected createDialog") },
    var read: suspend () -> UpstreamResult<ExtendedDialog> = { error("Unexpected getDialogById") },
    var patch: suspend () -> UpstreamResult<DialogPatchResult> = { error("Unexpected patchDialog") },
) : DialogportenClient {
    val calls = mutableListOf<String>()
    val created = mutableListOf<Dialog>()
    var patchRevision: UUID? = null
    var patches: List<HttpDialogportenClient.DialogportenPatch>? = null

    override suspend fun createDialog(dialog: Dialog): UpstreamResult<UUID> {
        calls += "create"
        created += dialog
        return create()
    }

    override suspend fun getDialogById(dialogId: UUID): UpstreamResult<ExtendedDialog> {
        expectedDialogId?.let { dialogId shouldBe it }
        calls += "get"
        return read()
    }

    override suspend fun patchDialog(
        dialogId: UUID,
        revisionNumber: UUID,
        patch: List<HttpDialogportenClient.DialogportenPatch>,
    ): UpstreamResult<DialogPatchResult> {
        expectedDialogId?.let { dialogId shouldBe it }
        calls += "patch"
        patchRevision = revisionNumber
        patches = patch
        return this.patch()
    }
}
