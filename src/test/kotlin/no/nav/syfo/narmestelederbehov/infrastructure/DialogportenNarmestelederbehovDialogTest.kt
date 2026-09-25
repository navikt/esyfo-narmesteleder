package no.nav.syfo.narmestelederbehov.infrastructure

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CancellationException
import no.nav.syfo.altinn.dialogporten.client.DialogportenClient
import no.nav.syfo.altinn.dialogporten.client.HttpDialogportenClient
import no.nav.syfo.altinn.dialogporten.domain.Content
import no.nav.syfo.altinn.dialogporten.domain.ContentValue
import no.nav.syfo.altinn.dialogporten.domain.ContentValueItem
import no.nav.syfo.altinn.dialogporten.domain.Dialog
import no.nav.syfo.altinn.dialogporten.domain.DialogStatus
import no.nav.syfo.altinn.dialogporten.domain.ExtendedDialog
import java.util.UUID

class DialogportenNarmestelederbehovDialogTest :
    FunSpec({
        val dialogId = UUID.fromString("00000000-0000-0000-0000-000000000002")
        val revision = UUID.fromString("00000000-0000-0000-0000-000000000003")

        test("fetches the revision and patches completed status") {
            val client = RecordingDialogportenClient(dialogId, revision)

            DialogportenNarmestelederbehovDialog(client).complete(dialogId)

            client.calls shouldBe listOf(
                "get" to dialogId,
                "patch" to dialogId,
            )
            client.patchRevision shouldBe revision
            client.patches shouldBe listOf(
                HttpDialogportenClient.DialogportenPatch(
                    HttpDialogportenClient.DialogportenPatch.OPERATION.REPLACE,
                    HttpDialogportenClient.DialogportenPatch.PATH.STATUS,
                    DialogStatus.Completed.name,
                ),
            )
        }

        test("lookup failure propagates without patching") {
            val failure = IllegalStateException("lookup failed")
            val client = RecordingDialogportenClient(dialogId, revision, lookupFailure = failure)

            shouldThrow<IllegalStateException> { DialogportenNarmestelederbehovDialog(client).complete(dialogId) } shouldBe failure
            client.calls shouldBe listOf("get" to dialogId)
        }

        test("patch failure propagates") {
            val failure = IllegalStateException("patch failed")
            val client = RecordingDialogportenClient(dialogId, revision, patchFailure = failure)

            shouldThrow<IllegalStateException> { DialogportenNarmestelederbehovDialog(client).complete(dialogId) } shouldBe failure
            client.calls shouldBe listOf("get" to dialogId, "patch" to dialogId)
        }

        listOf("lookup", "patch").forEach { failingStep ->
            test("$failingStep cancellation propagates") {
                val failure = CancellationException("cancelled")
                val client = RecordingDialogportenClient(
                    dialogId,
                    revision,
                    lookupFailure = failure.takeIf { failingStep == "lookup" },
                    patchFailure = failure.takeIf { failingStep == "patch" },
                )

                shouldThrow<CancellationException> { DialogportenNarmestelederbehovDialog(client).complete(dialogId) } shouldBe failure
            }
        }
    })

private class RecordingDialogportenClient(
    private val dialogId: UUID,
    private val revision: UUID,
    private val lookupFailure: Throwable? = null,
    private val patchFailure: Throwable? = null,
) : DialogportenClient {
    val calls = mutableListOf<Pair<String, UUID>>()
    var patchRevision: UUID? = null
    var patches: List<HttpDialogportenClient.DialogportenPatch>? = null

    override suspend fun createDialog(dialog: Dialog): UUID = error("Unexpected createDialog")

    override suspend fun getDialogById(dialogId: UUID): ExtendedDialog {
        calls += "get" to dialogId
        lookupFailure?.let { throw it }
        return ExtendedDialog(
            revision = revision,
            id = this.dialogId,
            party = "urn:altinn:organization:identifier-no:123456789",
            serviceResource = "service:resource",
            externalReference = this.dialogId.toString(),
            status = DialogStatus.RequiresAttention,
            content = Content(
                title = ContentValue(value = listOf(ContentValueItem(value = "Title"))),
                summary = ContentValue(value = listOf(ContentValueItem(value = "Summary"))),
            ),
        )
    }

    override suspend fun patchDialog(
        dialogId: UUID,
        revisionNumber: UUID,
        patch: List<HttpDialogportenClient.DialogportenPatch>,
    ) {
        calls += "patch" to dialogId
        patchRevision = revisionNumber
        patches = patch
        patchFailure?.let { throw it }
    }
}
