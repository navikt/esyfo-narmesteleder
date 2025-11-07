package no.nav.syfo.dialogporten.client

import com.fasterxml.jackson.annotation.JsonValue
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.content.TextContent
import io.ktor.http.contentType
import io.ktor.http.parameters
import java.util.*
import no.nav.syfo.dialogporten.domain.Content
import no.nav.syfo.dialogporten.domain.ContentValue
import no.nav.syfo.dialogporten.domain.ContentValueItem
import no.nav.syfo.dialogporten.domain.Dialog
import no.nav.syfo.dialogporten.domain.DialogStatus
import no.nav.syfo.dialogporten.domain.ExtendedDialog
import no.nav.syfo.texas.client.TexasHttpClient
import no.nav.syfo.util.JSON_PATCH_CONTENT_TYPE
import no.nav.syfo.util.logger


interface IDialogportenClient {
    suspend fun createDialog(dialog: Dialog): UUID
    suspend fun getDialogportenToken(): String
    suspend fun updateDialogStatus(dialogId: UUID, revisionNumber: UUID, dialogStatus: DialogStatus)
    suspend fun getDialogById(dialogId: UUID): ExtendedDialog
}

private const val GENERIC_DIALOGPORTEN_ERROR_MESSAGE = "Error in request to Dialogporten"
private const val DIGDIR_TARGET_SCOPE = "digdir:dialogporten.serviceprovider"
private const val DIALOG_ID_PARAM_NAME = "externalReference"

class DialogportenClient(
    private val baseUrl: String,
    private val httpClient: HttpClient,
    private val texasHttpClient: TexasHttpClient,
) : IDialogportenClient {
    private val dialogportenUrl = "$baseUrl/dialogporten/api/v1/serviceowner/dialogs"
    private val logger = logger()

    override suspend fun createDialog(dialog: Dialog): UUID {
        val texasResponse = texasHttpClient.systemToken("maskinporten", DIGDIR_TARGET_SCOPE)
        val token = altinnExchange(texasResponse.accessToken)

        return runCatching<DialogportenClient, UUID> {
            val response =
                httpClient
                    .post(dialogportenUrl) {
                        header(HttpHeaders.ContentType, ContentType.Application.Json)
                        header(HttpHeaders.Accept, ContentType.Application.Json)
                        bearerAuth(token)

                        setBody(dialog)
                    }.body<String>()
            UUID.fromString(response.removeSurrounding("\""))
        }.getOrElse { e ->
            logger.error("Error in create dialog request", e)
            throw DialogportenClientException(e.message ?: GENERIC_DIALOGPORTEN_ERROR_MESSAGE)
        }
    }

    override suspend fun updateDialogStatus(
        dialogId: UUID,
        revisionNumber: UUID,
        dialogStatus: DialogStatus
    ) {
        val texasResponse = texasHttpClient.systemToken("maskinporten", DIGDIR_TARGET_SCOPE)
        val token = altinnExchange(texasResponse.accessToken)

        runCatching {
            httpClient
                .patch("$dialogportenUrl/$dialogId") {
                    header(HttpHeaders.Accept, ContentType.Application.Json)
                    header(HttpHeaders.IfMatch, revisionNumber.toString())
                    contentType(JSON_PATCH_CONTENT_TYPE)
                    bearerAuth(token)
                    setBody(
                        listOf(
                            DialogportenPatch(
                                DialogportenPatch.OPERATION.REPLACE,
                                "/status",
                                dialogStatus.name
                            )
                        )
                    )
                }
        }.onFailure { e ->
            logger.error("Error on update request to Dialogporten on dialogId: $dialogId", e)
            throw DialogportenClientException(e.message ?: GENERIC_DIALOGPORTEN_ERROR_MESSAGE)
        }
    }

    override suspend fun getDialogById(
        // our dialogId here is "externalReference" in Dialogporten
        dialogId: UUID
    ): ExtendedDialog {
        val texasResponse = texasHttpClient.systemToken("maskinporten", DIGDIR_TARGET_SCOPE)
        val token = altinnExchange(texasResponse.accessToken)

        val dialog = runCatching {
            httpClient
                .get(dialogportenUrl) {
                    parameters {
                        append(DIALOG_ID_PARAM_NAME, dialogId.toString())
                    }
                    header(HttpHeaders.ContentType, ContentType.Application.Json)
                    header(HttpHeaders.Accept, ContentType.Application.Json)
                    bearerAuth(token)
                }.body<List<ExtendedDialog>>()
        }.getOrElse { e ->
            logger.error("Error on request to Dialogporten on dialog id: $dialogId", e)
            throw DialogportenClientException(e.message ?: GENERIC_DIALOGPORTEN_ERROR_MESSAGE)
        }

        if (dialog.isEmpty()) {
            throw DialogportenClientException("Could not find dialog with id: $dialogId")
        } else if (dialog.size > 1) {
            throw DialogportenClientException("Found more than one dialog with id: $dialogId")
        }

        return dialog.first()
    }

    private suspend fun altinnExchange(token: String): String =
        httpClient
            .get("$baseUrl/authentication/api/v1/exchange/maskinporten") {
                bearerAuth(token)
            }.bodyAsText()
            .replace("\"", "")

    override suspend fun getDialogportenToken(): String {
        val texasResponse = texasHttpClient.systemToken("maskinporten", DIGDIR_TARGET_SCOPE)
        return altinnExchange(texasResponse.accessToken)
    }

    // internal for access in tests
    internal class DialogportenPatch(
        val op: OPERATION,
        val path: String,
        val value: String,
    ) {
        enum class OPERATION(val jsonValue: String) {
            REPLACE("Replace"),
            ADD("Add"),
            REMOVE("Remove");

            @JsonValue
            fun toJson() = jsonValue
        }
    }
}

class FakeDialogportenClient : IDialogportenClient {
    override suspend fun createDialog(dialog: Dialog): UUID {
        logger().info(ObjectMapper().writeValueAsString(dialog))
        return UUID.randomUUID()
    }

    override suspend fun getDialogportenToken(): String {
        throw NotImplementedError("Not implemented for local application")
    }

    override suspend fun updateDialogStatus(
        dialogId: UUID,
        revisionNumber: UUID,
        dialogStatus: DialogStatus
    ) {
        return
    }

    override suspend fun getDialogById(dialogId: UUID): ExtendedDialog =
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

}
