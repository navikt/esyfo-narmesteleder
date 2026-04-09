package no.nav.syfo.altinn.dialogporten.client

import com.fasterxml.jackson.annotation.JsonValue
import com.fasterxml.jackson.databind.ObjectMapper
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import no.nav.syfo.altinn.dialogporten.domain.Content
import no.nav.syfo.altinn.dialogporten.domain.ContentValue
import no.nav.syfo.altinn.dialogporten.domain.ContentValueItem
import no.nav.syfo.altinn.dialogporten.domain.Dialog
import no.nav.syfo.altinn.dialogporten.domain.DialogStatus
import no.nav.syfo.altinn.dialogporten.domain.ExtendedDialog
import no.nav.syfo.texas.AltinnTokenProvider
import no.nav.syfo.util.JSON_PATCH_CONTENT_TYPE
import no.nav.syfo.util.logger
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException

interface IDialogportenClient {
    suspend fun createDialog(dialog: Dialog): UUID
    suspend fun getDialogById(dialogId: UUID): ExtendedDialog
    suspend fun patchDialog(dialogId: UUID, revisionNumber: UUID, patch: DialogportenClient.DialogportenPatch) = patchDialog(dialogId, revisionNumber, listOf(patch))
    suspend fun patchDialog(dialogId: UUID, revisionNumber: UUID, patch: List<DialogportenClient.DialogportenPatch>)
}

private const val GENERIC_DIALOGPORTEN_ERROR_MESSAGE = "Error in request to Dialogporten"

class DialogportenClient(
    baseUrl: String,
    private val httpClient: HttpClient,
    private val altinnTokenProvider: AltinnTokenProvider,
) : IDialogportenClient {
    private val dialogportenUrl = "$baseUrl/dialogporten/api/v1/serviceowner/dialogs"
    private val logger = logger()

    override suspend fun createDialog(dialog: Dialog): UUID = try {
        val token = altinnTokenProvider.token(AltinnTokenProvider.DIALOGPORTEN_TARGET_SCOPE).accessToken
        val response =
            httpClient
                .post(dialogportenUrl) {
                    header(HttpHeaders.ContentType, ContentType.Application.Json)
                    header(HttpHeaders.Accept, ContentType.Application.Json)
                    bearerAuth(token)
                    setBody(dialog)
                }.body<String>()
        UUID.fromString(response.removeSurrounding("\""))
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        logger.error("Error in create dialog request", e)
        throw DialogportenClientException(e.message ?: GENERIC_DIALOGPORTEN_ERROR_MESSAGE)
    }

    override suspend fun getDialogById(
        dialogId: UUID
    ): ExtendedDialog {
        val dialog = try {
            val token = altinnTokenProvider.token(AltinnTokenProvider.DIALOGPORTEN_TARGET_SCOPE).accessToken
            httpClient
                .get("$dialogportenUrl/$dialogId") {
                    header(HttpHeaders.ContentType, ContentType.Application.Json)
                    header(HttpHeaders.Accept, ContentType.Application.Json)
                    bearerAuth(token)
                }.body<ExtendedDialog>()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error("Error on request to Dialogporten on dialog id: $dialogId", e)
            throw DialogportenClientException(e.message ?: GENERIC_DIALOGPORTEN_ERROR_MESSAGE)
        }

        return dialog
    }

    data class DialogportenPatch(
        val operation: OPERATION,
        val path: PATH,
        val value: String
    ) {
        enum class OPERATION(val jsonValue: String) {
            REPLACE("Replace"),
            ADD("Add"),
            REMOVE("Remove");

            @JsonValue
            fun toJson() = jsonValue
        }

        enum class PATH(val jsonValue: String) {
            STATUS("/status"),
            EXPIRES_AT("/expiresAt");

            @JsonValue
            fun toJson() = jsonValue
        }
    }

    override suspend fun deleteDialog(dialogId: UUID): HttpStatusCode {
        val token = altinnTokenProvider.token(AltinnTokenProvider.DIALOGPORTEN_TARGET_SCOPE)
            .accessToken

        return try {
            httpClient
                .post("$dialogportenUrl/$dialogId/actions/purge") {
                    header(HttpHeaders.Accept, ContentType.Application.Json)
                    bearerAuth(token)
                }.status
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error("Feil ved kall til Dialogporten for å slette dialog", e)
            throw DialogportenClientException(e.message ?: "Feil ved kall til Dialogporten:  actions-purge")
        }
    }

    override suspend fun patchDialog(
        dialogId: UUID,
        revisionNumber: UUID,
        patch: List<DialogportenPatch>
    ) {
        try {
            val token = altinnTokenProvider.token(AltinnTokenProvider.DIALOGPORTEN_TARGET_SCOPE).accessToken
            httpClient
                .patch("$dialogportenUrl/$dialogId") {
                    header(HttpHeaders.Accept, ContentType.Application.Json)
                    header(HttpHeaders.IfMatch, revisionNumber.toString())
                    contentType(JSON_PATCH_CONTENT_TYPE)
                    bearerAuth(token)
                    setBody(patch)
                }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error("Error on patch request to Dialogporten on dialogId: $dialogId", e)
            throw DialogportenClientException(e.message ?: GENERIC_DIALOGPORTEN_ERROR_MESSAGE)
        }
    }
}

class FakeDialogportenClient : IDialogportenClient {
    companion object {
        val logger = logger()
    }

    override suspend fun createDialog(dialog: Dialog): UUID {
        logger.info(ObjectMapper().writeValueAsString(dialog))
        return UUID.randomUUID()
    }

    override suspend fun getDialogById(dialogId: UUID): ExtendedDialog = ExtendedDialog(
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

    override suspend fun patchDialog(
        dialogId: UUID,
        revisionNumber: UUID,
        patch: List<DialogportenClient.DialogportenPatch>
    ) {
        logger.info("Fake call patching dialog with id: $dialogId, with patch: ${ObjectMapper().writeValueAsString(patch)}")
        return
    }
}
