package no.nav.syfo.narmestelederbehov.infrastructure

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CancellationException
import no.nav.syfo.narmestelederbehov.application.DialogExpiry
import no.nav.syfo.narmestelederbehov.infrastructure.dialogporten.client.DialogPatchResult
import no.nav.syfo.narmestelederbehov.infrastructure.dialogporten.client.HttpDialogportenClient.DialogportenPatch
import no.nav.syfo.narmestelederbehov.infrastructure.dialogporten.client.HttpDialogportenClient.DialogportenPatch.OPERATION
import no.nav.syfo.narmestelederbehov.infrastructure.dialogporten.client.HttpDialogportenClient.DialogportenPatch.PATH
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
import java.time.Instant
import java.util.UUID

class DialogportenNarmestelederbehovDialogExpiryTest :
    FunSpec({
        listOf(DialogStatus.RequiresAttention, DialogStatus.Completed).forEach { status ->
            test("fetches revision and patches completed status plus expiresAt even when status is $status") {
                val client = expiryClient(status)

                DialogportenNarmestelederbehovDialogExpiry(client).expire(EXPIRY_DIALOG_ID, EXPIRATION_TIME) shouldBe DialogExpiry.Expired

                client.calls shouldBe listOf("get", "patch")
                client.patchRevision shouldBe EXPIRY_REVISION
                client.patches shouldBe listOf(
                    DialogportenPatch(OPERATION.REPLACE, PATH.STATUS, DialogStatus.Completed.name),
                    DialogportenPatch(OPERATION.ADD, PATH.EXPIRES_AT, EXPIRATION_TIME.toString()),
                )
            }
        }

        test("412 becomes Unavailable with revision conflict metadata without rereading or retrying") {
            val client = expiryClient()
            client.patch = { UpstreamResult.Success(DialogPatchResult.RevisionConflict) }

            DialogportenNarmestelederbehovDialogExpiry(client).expire(EXPIRY_DIALOG_ID, EXPIRATION_TIME) shouldBe
                DialogExpiry.Unavailable(dialogportenRevisionConflictFailure())
            client.calls shouldBe listOf("get", "patch")
        }

        listOf("get", "patch").forEach { step ->
            test("$step upstream failure is returned without exceptions or subsequent calls") {
                val failure = UpstreamFailure(UpstreamName("texas"), UpstreamFailureStage.TOKEN_EXCHANGE, 503, errors = listOf(UpstreamError("unavailable")))
                val client = expiryClient()
                if (step == "get") client.read = { UpstreamResult.Failure(failure) }
                if (step == "patch") client.patch = { UpstreamResult.Failure(failure) }

                DialogportenNarmestelederbehovDialogExpiry(client).expire(EXPIRY_DIALOG_ID, EXPIRATION_TIME) shouldBe DialogExpiry.Unavailable(failure)
                client.calls shouldBe if (step == "get") listOf("get") else listOf("get", "patch")
            }

            test("cancellation at $step is propagated") {
                val cancellation = CancellationException("cancel test")
                val client = expiryClient()
                if (step == "get") client.read = { throw cancellation }
                if (step == "patch") client.patch = { throw cancellation }

                shouldThrow<CancellationException> {
                    DialogportenNarmestelederbehovDialogExpiry(client).expire(EXPIRY_DIALOG_ID, EXPIRATION_TIME)
                } shouldBe cancellation
            }
        }
    })

private val EXPIRY_DIALOG_ID = UUID.fromString("00000000-0000-0000-0000-000000000004")
private val EXPIRY_REVISION = UUID.fromString("00000000-0000-0000-0000-000000000005")
private val EXPIRATION_TIME = Instant.parse("2026-10-01T10:10:00Z")

private fun expiryClient(status: DialogStatus = DialogStatus.RequiresAttention) = RecordingDialogportenClient(
    expectedDialogId = EXPIRY_DIALOG_ID,
    read = {
        UpstreamResult.Success(
            ExtendedDialog(
                revision = EXPIRY_REVISION,
                id = EXPIRY_DIALOG_ID,
                party = "urn:altinn:organization:identifier-no:910000001",
                serviceResource = "service:resource",
                externalReference = EXPIRY_DIALOG_ID.toString(),
                status = status,
                content = Content(
                    title = ContentValue(value = listOf(ContentValueItem("Title"))),
                    summary = ContentValue(value = listOf(ContentValueItem("Summary"))),
                ),
            ),
        )
    },
    patch = { UpstreamResult.Success(DialogPatchResult.Patched) },
)
