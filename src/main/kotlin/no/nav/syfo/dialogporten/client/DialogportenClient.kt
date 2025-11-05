package no.nav.syfo.dialogporten.client

import com.fasterxml.jackson.databind.ObjectMapper
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
import io.ktor.http.parameters
import no.nav.syfo.dialogporten.domain.Dialog
import no.nav.syfo.texas.client.TexasHttpClient
import no.nav.syfo.util.logger
import java.util.UUID
import no.nav.syfo.dialogporten.domain.DialogStatus
import no.nav.syfo.dialogporten.domain.ExtendedDialog

const val DIALOG_ID_PARAM_NAME = "externalReference"
private const val JSON_PATCH_CONTENT_TYPE = "application/json-patch+json"

interface IDialogportenClient {
    suspend fun createDialog(dialog: Dialog): UUID
    suspend fun getDialogportenToken(): String
    suspend fun updateDialogStatus(dialogId: String, dialogStatus: DialogStatus)
}

private const val DIGDIR_TARGET_SCOPE = "digdir:dialogporten.serviceprovider"

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
            logger.error("Feil ved kall til Dialogporten for å opprette dialog", e)
            throw DialogportenClientException(e.message ?: "Feil ved kall til Dialogporten")
        }
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

    override suspend fun updateDialogStatus(
        dialogId: String,
        dialogStatus: DialogStatus
    ) {
        val texasResponse = texasHttpClient.systemToken("maskinporten", DIGDIR_TARGET_SCOPE)
        val token = altinnExchange(texasResponse.accessToken)

        runCatching {
            httpClient
                .patch("dialogportenUrl/$dialogId") {
                    header(HttpHeaders.ContentType, JSON_PATCH_CONTENT_TYPE)
                    header(HttpHeaders.Accept, ContentType.Application.Json)
                    bearerAuth(token)
                    setBody(
                        listOf(
                            mapOf(
                                "op" to "replace",
                                "path" to "/status",
                                "value" to dialogStatus.name
                            )
                        )
                    )
                }
        }.onFailure { e ->
            logger.error("Feil ved kall til Dialogporten for å opprette dialog", e)
            throw DialogportenClientException(e.message ?: "Feil ved kall til Dialogporten")
        }
    }

    // dialogId here is "externalReference" in Dialogporten
    suspend fun getDialogById(
        dialogId: String
    ): ExtendedDialog {
        val texasResponse = texasHttpClient.systemToken("maskinporten", DIGDIR_TARGET_SCOPE)
        val token = altinnExchange(texasResponse.accessToken)

        val dialog = runCatching {
            httpClient
                .get(dialogportenUrl) {
                    parameters {
                        append(DIALOG_ID_PARAM_NAME, dialogId)
                    }
                    header(HttpHeaders.ContentType, ContentType.Application.Json)
                    header(HttpHeaders.Accept, ContentType.Application.Json)
                    bearerAuth(token)
                }.body<List<ExtendedDialog>>()
        }.getOrElse { e ->
            logger.error("Feil ved kall til Dialogporten for å hente dialog med id: $dialogId", e)
            throw DialogportenClientException(e.message ?: "Feil ved kall til Dialogporten")
        }

        if (dialog.isEmpty()) {
            throw DialogportenClientException("Fant ingen dialog med id: $dialogId")
        } else if (dialog.size > 1) {
            throw DialogportenClientException("Flere enn en dialog funnet med id: $dialogId")
        }

        return dialog.first()
    }
}

class FakeDialogportenClient() : IDialogportenClient {
    override suspend fun createDialog(dialog: Dialog): UUID {
        logger().info(ObjectMapper().writeValueAsString(dialog))
        return UUID.randomUUID()
    }

    override suspend fun getDialogportenToken(): String {
        throw NotImplementedError("Not implemented for local application")
    }

    override suspend fun updateDialogStatus(
        dialogId: String,
        dialogStatus: DialogStatus
    ) {
        TODO("Not yet implemented")
    }
}
