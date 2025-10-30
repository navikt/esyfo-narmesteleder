package no.nav.syfo.dialogporten.service

import io.ktor.http.ContentType
import no.nav.syfo.API_V1_PATH
import no.nav.syfo.application.OtherEnvironmentProperties
import no.nav.syfo.dialogporten.client.IDialogportenClient
import no.nav.syfo.dialogporten.domain.Attachment
import no.nav.syfo.dialogporten.domain.AttachmentUrlConsumerType
import no.nav.syfo.dialogporten.domain.Content
import no.nav.syfo.dialogporten.domain.ContentValueItem
import no.nav.syfo.dialogporten.domain.Dialog
import no.nav.syfo.dialogporten.domain.Url
import no.nav.syfo.dialogporten.domain.create
import no.nav.syfo.narmesteleder.api.v1.RECUIREMENT_PATH
import no.nav.syfo.narmesteleder.db.INarmestelederDb
import no.nav.syfo.narmesteleder.db.NarmestelederBehovEntity
import no.nav.syfo.narmesteleder.domain.BehovStatus
import no.nav.syfo.util.logger

class DialogportenService(
    private val dialogportenClient: IDialogportenClient,
    private val narmestelederDb: INarmestelederDb,
    private val otherDialogportenClient: OtherEnvironmentProperties,
) {
    private val logger = logger()
    private val dialogRessurs = "nav_syfo_dialog"

    val dialogTitle = "Dere har en sykmeldt med behov for å bli tildelt nærmeste leder"
    val dialogSummary = "Vennligst tildel nærmeste leder for sykmeldt"
    val guiUrlTitle = "Naviger til nærmeste leder skjema"
    val apiUrlTitle = "Endpoint for LinemanagerRequirement request"

    suspend fun sendDocumentsToDialogporten() {
        val behovToSend = getRequirementsToSend()
        logger.info("Found ${behovToSend.size} documents to send to dialogporten")

        for (behov in behovToSend) {
            val fullDocumentLink = createApiLink(behov.id.toString())
            try {
                val dialog = behov.toDialog()
                val dialogId = dialogportenClient.createDialog(dialog)
                narmestelederDb.updateNlBehov(
                    behov.copy(
                        dialogId = dialogId,
                        behovStatus = BehovStatus.PENDING
                    )
                )
                logger.info("Sent document ${behov.id} to dialogporten, with link $fullDocumentLink")
            } catch (ex: Exception) {
                logger.error("Failed to send document ${behov.id} to dialogporten", ex)
            }
        }
    }

    private fun getRequirementsToSend() = narmestelederDb.getNlBehovByStatus(BehovStatus.RECEIVED)

    private fun createApiLink(id: String): String =
        "${otherDialogportenClient.publicIngressUrl}$API_V1_PATH$RECUIREMENT_PATH/$id"

    private fun createGuiLink(id: String): String =
        "${otherDialogportenClient.frontendBaseUrl}/ansatte/narmesteleder/$id"
    //https://www.ekstern.dev.nav.no/arbeidgsgiver/ansatte/narmesteleder/ce48ec37-7cba-432d-8d2e-645389d7d6b5

    private fun NarmestelederBehovEntity.toDialog(): Dialog {
        return Dialog(
            serviceResource = "urn:altinn:resource:$dialogRessurs",
            party = "urn:altinn:organization:identifier-no:$orgnummer",
            externalReference = id.toString(),
            content = Content.create(
                title = dialogTitle,
                summary = dialogSummary,
            ),
            isApiOnly = false,
            attachments = listOf(
                Attachment(
                    displayName = listOf(
                        ContentValueItem(
                            apiUrlTitle,
                            "en"
                        ),
                    ),
                    urls = listOf(
                        Url(
                            url = createApiLink(id.toString()),
                            mediaType = ContentType.Application.Json.toString(),
                            consumerType = AttachmentUrlConsumerType.Api,
                        ),
                    ),
                ),
                Attachment(
                    displayName = listOf(
                        ContentValueItem(
                            guiUrlTitle,
                            "nb"
                        ),
                    ),
                    urls = listOf(
                        Url(
                            url = createGuiLink(id.toString()),
                            mediaType = ContentType.Text.Html.toString(),
                            consumerType = AttachmentUrlConsumerType.Gui,
                        ),
                    ),
                ),
            ),
        )
    }
}
