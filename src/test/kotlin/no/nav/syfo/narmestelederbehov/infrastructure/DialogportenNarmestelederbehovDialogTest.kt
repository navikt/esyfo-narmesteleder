package no.nav.syfo.narmestelederbehov.infrastructure

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.types.shouldBeSameInstanceAs
import kotlinx.coroutines.CancellationException
import no.nav.syfo.narmestelederbehov.application.DialogCompletion
import no.nav.syfo.narmestelederbehov.infrastructure.dialogporten.client.DialogPatchResult
import no.nav.syfo.narmestelederbehov.infrastructure.dialogporten.client.HttpDialogportenClient
import no.nav.syfo.narmestelederbehov.infrastructure.dialogporten.client.RecordingDialogportenClient
import no.nav.syfo.narmestelederbehov.infrastructure.dialogporten.client.dialogportenRevisionConflictFailure
import no.nav.syfo.narmestelederbehov.infrastructure.dialogporten.domain.Content
import no.nav.syfo.narmestelederbehov.infrastructure.dialogporten.domain.ContentValue
import no.nav.syfo.narmestelederbehov.infrastructure.dialogporten.domain.ContentValueItem
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
            val client = dialogClient()
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
            val client = dialogClient(reads = mutableListOf(dialogResult(DialogStatus.Completed)))
            DialogportenNarmestelederbehovDialog(client).complete(DIALOG_ID) shouldBe DialogCompletion.Completed
            client.calls shouldBe listOf("get")
        }

        listOf(DialogStatus.Completed, DialogStatus.RequiresAttention, null).forEach { status ->
            test("revision conflict rereads once and resolves status $status") {
                val client = dialogClient(
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
                val client = dialogClient(
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
                    val client = dialogClient(
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

private fun dialogClient(
    reads: MutableList<UpstreamResult<ExtendedDialog>> = mutableListOf(dialogResult()),
    patchResult: UpstreamResult<DialogPatchResult> = UpstreamResult.Success(DialogPatchResult.Patched),
    throwAt: String? = null,
    thrown: Throwable? = null,
): RecordingDialogportenClient {
    var readCount = 0
    return RecordingDialogportenClient(
        expectedDialogId = DIALOG_ID,
        read = {
            val step = if (readCount++ == 0) "get" else "reread"
            if (throwAt == step) throw requireNotNull(thrown)
            reads.removeAt(0)
        },
        patch = {
            if (throwAt == "patch") throw requireNotNull(thrown)
            patchResult
        },
    )
}
