package no.nav.syfo.narmestelederbehov.infrastructure

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.types.shouldBeSameInstanceAs
import kotlinx.coroutines.CancellationException
import no.nav.syfo.narmestelederbehov.application.DialogCompletion
import no.nav.syfo.narmestelederbehov.infrastructure.dialogporten.client.DialogPatchResult
import no.nav.syfo.narmestelederbehov.infrastructure.dialogporten.client.DialogportenClient
import no.nav.syfo.narmestelederbehov.infrastructure.dialogporten.client.HttpDialogportenClient
import no.nav.syfo.narmestelederbehov.infrastructure.dialogporten.client.dialogportenRevisionConflictFailure
import no.nav.syfo.narmestelederbehov.infrastructure.dialogporten.domain.Content
import no.nav.syfo.narmestelederbehov.infrastructure.dialogporten.domain.ContentValue
import no.nav.syfo.narmestelederbehov.infrastructure.dialogporten.domain.ContentValueItem
import no.nav.syfo.narmestelederbehov.infrastructure.dialogporten.domain.Dialog
import no.nav.syfo.narmestelederbehov.infrastructure.dialogporten.domain.DialogStatus
import no.nav.syfo.narmestelederbehov.infrastructure.dialogporten.domain.ExtendedDialog
import no.nav.syfo.platform.upstream.UpstreamError
import no.nav.syfo.platform.upstream.UpstreamFailure
import no.nav.syfo.platform.upstream.UpstreamFailureStage
import no.nav.syfo.platform.upstream.UpstreamName
import no.nav.syfo.platform.upstream.UpstreamResult
import java.util.UUID

class DialogportenNarmestelederbehovDialogTest :
    FunSpec({
        test("fetches the revision and patches completed status") {
            val client = RecordingDialogportenClient()
            DialogportenNarmestelederbehovDialog(client).complete(DIALOG_ID) shouldBe DialogCompletion.Completed
            client.calls shouldBe listOf("get", "patch")
            client.patchRevision shouldBe REVISION
            client.patches shouldBe listOf(
                HttpDialogportenClient.DialogportenPatch(
                    HttpDialogportenClient.DialogportenPatch.OPERATION.REPLACE,
                    HttpDialogportenClient.DialogportenPatch.PATH.STATUS,
                    DialogStatus.Completed.name,
                ),
            )
        }

        test("already completed returns Completed without PATCH") {
            val client = RecordingDialogportenClient(reads = mutableListOf(dialogResult(DialogStatus.Completed)))
            DialogportenNarmestelederbehovDialog(client).complete(DIALOG_ID) shouldBe DialogCompletion.Completed
            client.calls shouldBe listOf("get")
        }

        listOf<DialogStatus?>(DialogStatus.Completed, DialogStatus.RequiresAttention, null).forEach { status ->
            test("revision conflict rereads once and resolves status $status") {
                val client = RecordingDialogportenClient(
                    reads = mutableListOf(dialogResult(), dialogResult(status)),
                    patchResult = UpstreamResult.Success(DialogPatchResult.RevisionConflict),
                )
                val result = DialogportenNarmestelederbehovDialog(client).complete(DIALOG_ID)
                result shouldBe if (status == DialogStatus.Completed) {
                    DialogCompletion.Completed
                } else {
                    DialogCompletion.Unavailable(dialogportenRevisionConflictFailure())
                }
                if (result is DialogCompletion.Unavailable) {
                    result.failure.errors shouldBe listOf(UpstreamError("revision_conflict"))
                    result.failure.status shouldBe 412
                    result.failure.stage shouldBe UpstreamFailureStage.RESPONSE
                    result.failure.upstream shouldBe UpstreamName("dialogporten")
                    result.failure.cause shouldBe null
                }
                client.calls shouldBe listOf("get", "patch", "get")
            }
        }

        listOf("get", "patch", "reread").forEach { step ->
            test("$step failure is preserved without later calls") {
                val failure = UpstreamFailure(UpstreamName("texas"), UpstreamFailureStage.TOKEN_EXCHANGE, 503, errors = listOf(UpstreamError("server_error")))
                val client = RecordingDialogportenClient(
                    reads = when (step) {
                        "get" -> mutableListOf(UpstreamResult.Failure(failure))
                        "reread" -> mutableListOf(dialogResult(), UpstreamResult.Failure(failure))
                        else -> mutableListOf(dialogResult())
                    },
                    patchResult = if (step == "patch") UpstreamResult.Failure(failure) else UpstreamResult.Success(DialogPatchResult.RevisionConflict),
                )
                val result = DialogportenNarmestelederbehovDialog(client).complete(DIALOG_ID).shouldBeInstanceOf<DialogCompletion.Unavailable>()
                result.failure shouldBeSameInstanceAs failure
                client.calls shouldBe when (step) {
                    "get" -> listOf("get")
                    "patch" -> listOf("get", "patch")
                    else -> listOf("get", "patch", "get")
                }
            }

            listOf(IllegalStateException("unexpected"), CancellationException("cancelled")).forEach { failure ->
                test("$step propagates ${failure.javaClass.simpleName} without catching it") {
                    val client = RecordingDialogportenClient(
                        reads = mutableListOf(dialogResult(), dialogResult()),
                        patchResult = UpstreamResult.Success(DialogPatchResult.RevisionConflict),
                        throwAt = step,
                        thrown = failure,
                    )
                    shouldThrow<Exception> { DialogportenNarmestelederbehovDialog(client).complete(DIALOG_ID) } shouldBeSameInstanceAs failure
                    client.calls shouldBe when (step) {
                        "get" -> listOf("get")
                        "patch" -> listOf("get", "patch")
                        else -> listOf("get", "patch", "get")
                    }
                }
            }
        }
    })

private val DIALOG_ID = UUID.fromString("00000000-0000-0000-0000-000000000002")
private val REVISION = UUID.fromString("00000000-0000-0000-0000-000000000003")

private fun dialogResult(status: DialogStatus? = DialogStatus.RequiresAttention) = UpstreamResult.Success(
    ExtendedDialog(
        revision = REVISION,
        id = DIALOG_ID,
        party = "urn:altinn:organization:identifier-no:910000001",
        serviceResource = "service:resource",
        externalReference = DIALOG_ID.toString(),
        status = status,
        content = Content(
            title = ContentValue(value = listOf(ContentValueItem("Title"))),
            summary = ContentValue(value = listOf(ContentValueItem("Summary"))),
        ),
    ),
)

private class RecordingDialogportenClient(
    private val reads: MutableList<UpstreamResult<ExtendedDialog>> = mutableListOf(dialogResult()),
    private val patchResult: UpstreamResult<DialogPatchResult> = UpstreamResult.Success(DialogPatchResult.Patched),
    private val throwAt: String? = null,
    private val thrown: Throwable? = null,
) : DialogportenClient {
    val calls = mutableListOf<String>()
    var patchRevision: UUID? = null
    var patches: List<HttpDialogportenClient.DialogportenPatch>? = null

    override suspend fun createDialog(dialog: Dialog): UpstreamResult<UUID> = error("Unexpected createDialog")

    override suspend fun getDialogById(dialogId: UUID): UpstreamResult<ExtendedDialog> {
        dialogId shouldBe DIALOG_ID
        val step = if ("get" in calls) "reread" else "get"
        calls += "get"
        if (throwAt == step) throw requireNotNull(thrown)
        return reads.removeAt(0)
    }

    override suspend fun patchDialog(dialogId: UUID, revisionNumber: UUID, patch: List<HttpDialogportenClient.DialogportenPatch>): UpstreamResult<DialogPatchResult> {
        dialogId shouldBe DIALOG_ID
        calls += "patch"
        patchRevision = revisionNumber
        patches = patch
        if (throwAt == "patch") throw requireNotNull(thrown)
        return patchResult
    }
}
