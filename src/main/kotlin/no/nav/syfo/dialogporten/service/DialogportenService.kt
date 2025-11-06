package no.nav.syfo.dialogporten.service

import io.ktor.http.ContentType
import java.util.*
import no.nav.syfo.API_V1_PATH
import no.nav.syfo.application.OtherEnvironmentProperties
import no.nav.syfo.dialogporten.client.IDialogportenClient
import no.nav.syfo.dialogporten.domain.Attachment
import no.nav.syfo.dialogporten.domain.AttachmentUrlConsumerType
import no.nav.syfo.dialogporten.domain.Content
import no.nav.syfo.dialogporten.domain.ContentValueItem
import no.nav.syfo.dialogporten.domain.Dialog
import no.nav.syfo.dialogporten.domain.DialogStatus
import no.nav.syfo.dialogporten.domain.Url
import no.nav.syfo.dialogporten.domain.create
import no.nav.syfo.narmesteleder.api.v1.RECUIREMENT_PATH
import no.nav.syfo.narmesteleder.db.INarmestelederDb
import no.nav.syfo.narmesteleder.db.NarmestelederBehovEntity
import no.nav.syfo.narmesteleder.domain.BehovStatus
import no.nav.syfo.pdl.PdlService
import no.nav.syfo.pdl.client.Navn
import no.nav.syfo.util.logger

class DialogportenService(
    private val dialogportenClient: IDialogportenClient,
    private val narmestelederDb: INarmestelederDb,
    private val otherEnvironmentProperties: OtherEnvironmentProperties,
    private val pdlService: PdlService,
) {
    private val logger = logger()
    private val dialogRessurs = "nav_syfo_dialog"


    suspend fun sendDocumentsToDialogporten() {
        val behovToSend = getRequirementsToSend()
        logger.info("Found ${behovToSend.size} documents to send to dialogporten")

        for (behov in behovToSend) {
            require(behov.id != null) { "Cannot create Dialogporten Dialog without id" }
            try {
                val personInfo = try {
                    pdlService.getPersonFor(behov.sykmeldtFnr)
                } catch (ex: Exception) {
                    logger.error("Failed to get person info for behov ${behov.id}", ex)
                    null
                }
                val dialog = behov.toDialog(personInfo?.name)
                dialog.attachments?.firstOrNull()?.let {
                    logger.info("Sending document ${behov.id} to dialogporten, with link ${it.urls.firstOrNull()?.url}")
                }

                val dialogId = dialogportenClient.createDialog(dialog)
                narmestelederDb.updateNlBehov(
                    behov.copy(
                        dialogId = dialogId,
                        behovStatus = BehovStatus.PENDING,
                        fornavn = personInfo?.name?.fornavn,
                        mellomnavn = personInfo?.name?.mellomnavn,
                        etternavn = personInfo?.name?.etternavn,
                    )
                )
            } catch (ex: Exception) {
                logger.error("Failed to send document ${behov.id} to dialogporten", ex)
            }
        }
    }

    suspend fun setAllFulfilledBehovsAsCompletedInDialogporten() {
        narmestelederDb.getNlBehovByStatus(BehovStatus.BEHOV_FULFILLED).forEach { behov ->
            behov.dialogId?.let { dialogId ->
                try {
                    dialogportenClient.getDialogById(dialogId).let { existingDialog ->
                        dialogportenClient.updateDialogStatus(
                            dialogId = dialogId,
                            revisionNumber = existingDialog.revision,
                            dialogStatus = DialogStatus.Completed
                        )
                    }
                    narmestelederDb.updateNlBehov(
                        behov.copy(
                            behovStatus = BehovStatus.DIALOGPORTEN_STATUS_SET_COMPLETED
                        )
                    )
                } catch (ex: Exception) {
                    logger.error("Failed to update dialog status for dialogId: $dialogId", ex)
                }
            }
        }
    }
    private fun getDialogTitle(name: Navn?): String =
        name?.let {
            "${it.navnFullt()} $DIALOG_TITLE_WITH_NAME"
        } ?: DIALOG_TITLE_NO_NAME

    private fun getSummary(name: Navn?): String =
        name?.let {
            "$DIALOG_SUMMARY ${it.navnFullt()}"
        } ?: "$DIALOG_SUMMARY ansatt som er sykmeldt"

    private suspend fun getRequirementsToSend() = narmestelederDb.getNlBehovByStatus(BehovStatus.RECEIVED)

    private fun createApiLink(id: UUID): String =
        "${otherEnvironmentProperties.publicIngressUrl}$API_V1_PATH$RECUIREMENT_PATH/$id"

    private fun createGuiLink(id: UUID): String =
        "${otherEnvironmentProperties.frontendBaseUrl}/ansatte/narmesteleder/$id"
    //https://www.ekstern.dev.nav.no/arbeidsgiver/ansatte/narmesteleder/ce48ec37-7cba-432d-8d2e-645389d7d6b5

    private fun NarmestelederBehovEntity.toDialog(name: Navn?): Dialog {
        require(id != null) { "Cannot create Dialogporten Dialog without id" }
        return Dialog(
            serviceResource = "urn:altinn:resource:$dialogRessurs",
            status = DialogStatus.RequiresAttention,
            party = "urn:altinn:organization:identifier-no:$orgnummer",
            externalReference = id.toString(),
            content = Content.create(
                title = getDialogTitle(name),
                summary = getSummary(name),
            ),
            isApiOnly = false,
            attachments = listOf(
                createAttachement(AttachmentUrlConsumerType.Api, URL_TITLE_API, id),
                createAttachement(AttachmentUrlConsumerType.Gui, URL_TITLE_GUI, id),
            ),
        )
    }

    private fun createAttachement(type: AttachmentUrlConsumerType, name: String, id: UUID): Attachment =
        Attachment(
            displayName = listOf(
                ContentValueItem(
                    name, if (type == AttachmentUrlConsumerType.Api) "en" else "nb"
                ),
            ),
            urls = listOf(createUrl(type, id)),
        )

    private fun createUrl(type: AttachmentUrlConsumerType, id: UUID): Url =
        when (type) {
            AttachmentUrlConsumerType.Gui -> Url(
                url = createGuiLink(id),
                mediaType = ContentType.Text.Html.toString(),
                consumerType = type,
            )

            AttachmentUrlConsumerType.Api -> Url(
                url = createApiLink(id),
                mediaType = ContentType.Application.Json.toString(),
                consumerType = type,
            )
        }
    companion object {
        const val DIALOG_TITLE_NO_NAME = "Dere har en sykmeldt med behov for å bli tildelt nærmeste leder"
        const val DIALOG_TITLE_WITH_NAME = "er sykmeldt og har behov for å bli tildelt nærmeste leder"
        const val DIALOG_SUMMARY = "Vennligst tildel nærmeste leder for"

        const val URL_TITLE_GUI = "Naviger til nærmeste leder skjema"
        const val URL_TITLE_API = "Endpoint for LinemanagerRequirement request"
    }
}
