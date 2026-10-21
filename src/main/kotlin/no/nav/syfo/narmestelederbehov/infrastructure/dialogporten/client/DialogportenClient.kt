package no.nav.syfo.narmestelederbehov.infrastructure.dialogporten.client

import com.fasterxml.jackson.annotation.JsonValue
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.ResponseException
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import no.nav.syfo.narmestelederbehov.infrastructure.dialogporten.domain.Content
import no.nav.syfo.narmestelederbehov.infrastructure.dialogporten.domain.ContentValue
import no.nav.syfo.narmestelederbehov.infrastructure.dialogporten.domain.ContentValueItem
import no.nav.syfo.narmestelederbehov.infrastructure.dialogporten.domain.Dialog
import no.nav.syfo.narmestelederbehov.infrastructure.dialogporten.domain.DialogStatus
import no.nav.syfo.narmestelederbehov.infrastructure.dialogporten.domain.ExtendedDialog
import no.nav.syfo.platform.api.JSON_PATCH_CONTENT_TYPE
import no.nav.syfo.platform.auth.texas.AltinnTokenProvider
import no.nav.syfo.platform.observability.applicationLogger
import no.nav.syfo.platform.upstream.UpstreamError
import no.nav.syfo.platform.upstream.UpstreamFailure
import no.nav.syfo.platform.upstream.UpstreamFailureStage
import no.nav.syfo.platform.upstream.UpstreamName
import no.nav.syfo.platform.upstream.UpstreamResult
import no.nav.syfo.platform.upstream.getOrElse
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException

interface DialogportenClient {
    suspend fun createDialog(dialog: Dialog): UpstreamResult<UUID>
    suspend fun getDialogById(dialogId: UUID): UpstreamResult<ExtendedDialog>
    suspend fun patchDialog(dialogId: UUID, revisionNumber: UUID, patch: HttpDialogportenClient.DialogportenPatch) = patchDialog(dialogId, revisionNumber, listOf(patch))
    suspend fun patchDialog(dialogId: UUID, revisionNumber: UUID, patch: List<HttpDialogportenClient.DialogportenPatch>): UpstreamResult<DialogPatchResult>
}

sealed interface DialogPatchResult {
    data object Patched : DialogPatchResult
    data object RevisionConflict : DialogPatchResult
}

internal val DIALOGPORTEN = UpstreamName("dialogporten")

internal fun dialogportenRevisionConflictFailure() = UpstreamFailure(
    DIALOGPORTEN,
    UpstreamFailureStage.RESPONSE,
    412,
    errors = listOf(UpstreamError("revision_conflict")),
)

class HttpDialogportenClient(
    baseUrl: String,
    private val httpClient: HttpClient,
    private val altinnTokenProvider: AltinnTokenProvider,
) : DialogportenClient {
    private val dialogportenUrl = "$baseUrl/dialogporten/api/v1/serviceowner/dialogs"

    override suspend fun createDialog(dialog: Dialog): UpstreamResult<UUID> {
        val token = when (val result = altinnTokenProvider.token(AltinnTokenProvider.DIALOGPORTEN_TARGET_SCOPE)) {
            is UpstreamResult.Success -> result.value.accessToken
            is UpstreamResult.Failure -> return result
        }
        val response = request {
            httpClient
                .post(dialogportenUrl) {
                    header(HttpHeaders.ContentType, ContentType.Application.Json)
                    header(HttpHeaders.Accept, ContentType.Application.Json)
                    bearerAuth(token)
                    setBody(dialog)
                }
        }.getOrElse { return UpstreamResult.Failure(it) }
        return decode(response) { UUID.fromString(response.body<String>().removeSurrounding("\"")) }
    }

    override suspend fun getDialogById(
        dialogId: UUID
    ): UpstreamResult<ExtendedDialog> {
        val token = when (val result = altinnTokenProvider.token(AltinnTokenProvider.DIALOGPORTEN_TARGET_SCOPE)) {
            is UpstreamResult.Success -> result.value.accessToken
            is UpstreamResult.Failure -> return result
        }
        val response = request {
            httpClient
                .get("$dialogportenUrl/$dialogId") {
                    header(HttpHeaders.ContentType, ContentType.Application.Json)
                    header(HttpHeaders.Accept, ContentType.Application.Json)
                    bearerAuth(token)
                }
        }.getOrElse { return UpstreamResult.Failure(it) }
        return decode(response) { response.body<ExtendedDialog>() }
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

    override suspend fun patchDialog(
        dialogId: UUID,
        revisionNumber: UUID,
        patch: List<DialogportenPatch>
    ): UpstreamResult<DialogPatchResult> {
        val token = when (val result = altinnTokenProvider.token(AltinnTokenProvider.DIALOGPORTEN_TARGET_SCOPE)) {
            is UpstreamResult.Success -> result.value.accessToken
            is UpstreamResult.Failure -> return result
        }
        return when (
            val result = request {
                httpClient
                    .patch("$dialogportenUrl/$dialogId") {
                        header(HttpHeaders.Accept, ContentType.Application.Json)
                        header(HttpHeaders.IfMatch, revisionNumber.toString())
                        contentType(JSON_PATCH_CONTENT_TYPE)
                        bearerAuth(token)
                        setBody(patch)
                    }
            }
        ) {
            is UpstreamResult.Success -> UpstreamResult.Success(DialogPatchResult.Patched)
            is UpstreamResult.Failure -> if (result.failure.status == 412) {
                UpstreamResult.Success(DialogPatchResult.RevisionConflict)
            } else {
                result
            }
        }
    }

    private suspend fun request(block: suspend () -> HttpResponse): UpstreamResult<HttpResponse> = try {
        val response = block()
        if (response.status.value in 200..299) {
            UpstreamResult.Success(response)
        } else {
            UpstreamResult.Failure(UpstreamFailure(DIALOGPORTEN, UpstreamFailureStage.RESPONSE, response.status.value, errors = listOf(UpstreamError("unexpected_status"))))
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: ResponseException) {
        UpstreamResult.Failure(UpstreamFailure(DIALOGPORTEN, UpstreamFailureStage.RESPONSE, e.response.status.value, e))
    } catch (e: Exception) {
        UpstreamResult.Failure(UpstreamFailure(DIALOGPORTEN, UpstreamFailureStage.REQUEST, null, e))
    }

    private suspend fun <T> decode(response: HttpResponse, block: suspend () -> T): UpstreamResult<T> = try {
        UpstreamResult.Success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        UpstreamResult.Failure(UpstreamFailure(DIALOGPORTEN, UpstreamFailureStage.RESPONSE, response.status.value, e))
    }
}

class FakeDialogportenClient : DialogportenClient {
    companion object {
        val logger = applicationLogger(FakeDialogportenClient::class.java)
    }

    override suspend fun createDialog(dialog: Dialog): UpstreamResult<UUID> {
        logger.info("Fake call creating dialog")
        return UpstreamResult.Success(UUID.randomUUID())
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
