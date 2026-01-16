package no.nav.syfo.altinn.dialogporten.service

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.*
import kotlinx.coroutines.delay
import no.nav.syfo.API_V1_PATH
import no.nav.syfo.altinn.dialogporten.client.IDialogportenClient
import no.nav.syfo.altinn.dialogporten.domain.Attachment
import no.nav.syfo.altinn.dialogporten.domain.AttachmentUrlConsumerType
import no.nav.syfo.altinn.dialogporten.domain.Content
import no.nav.syfo.altinn.dialogporten.domain.ContentValueItem
import no.nav.syfo.altinn.dialogporten.domain.Dialog
import no.nav.syfo.altinn.dialogporten.domain.DialogStatus
import no.nav.syfo.altinn.dialogporten.domain.Url
import no.nav.syfo.altinn.dialogporten.domain.create
import no.nav.syfo.application.environment.OtherEnvironmentProperties
import no.nav.syfo.narmesteleder.api.v1.RECUIREMENT_PATH
import no.nav.syfo.narmesteleder.db.INarmestelederDb
import no.nav.syfo.narmesteleder.db.NarmestelederBehovEntity
import no.nav.syfo.narmesteleder.domain.BehovStatus
import no.nav.syfo.pdl.PdlService
import no.nav.syfo.pdl.client.Navn
import no.nav.syfo.util.logger

const val NARMESTE_LEDER_RESOURCE = "nav_syfo_oppgi-narmesteleder"

class DialogportenService(
    private val dialogportenClient: IDialogportenClient,
    private val narmestelederDb: INarmestelederDb,
    private val otherEnvironmentProperties: OtherEnvironmentProperties,
    private val pdlService: PdlService,
) {
    private val logger = logger()

    /**
     * This function can be removed after we have fixed requirements and dialogs due to incorrect url in
     * dialog attachment
     */
    suspend fun resendDocumentsToDialogporten() {
        do {
            val behovToSend = getRequirementsToResend()
            logger.info("Found ${behovToSend.size} documents to resend to dialogporten")

            for (behov in behovToSend) {
                sendToDialogporten(behov)
            }
            delay(otherEnvironmentProperties.deleteDialogportenDialogsTaskProperties.deleteDialogerSleepAfterPage)
        } while (behovToSend.size >= BEHOV_BY_STATUS_LIMIT)
    }

    suspend fun sendDocumentsToDialogporten() {
        do {

            val behovToSend = getRequirementsToSend()
            logger.info("Found ${behovToSend.size} documents to send to dialogporten")

            for (behov in behovToSend) {
                sendToDialogporten(behov)
            }
            delay(5000)
        } while (behovToSend.size >= BEHOV_BY_STATUS_LIMIT)
    }

    suspend fun sendToDialogporten(behov: NarmestelederBehovEntity) {
        try {
            require(behov.id != null) { "Cannot create Dialogporten Dialog without id" }
            val personInfo = try {
                pdlService.getPersonFor(behov.sykmeldtFnr)
            } catch (ex: Exception) {
                logger.error("Failed to get person info for behov ${behov.id}", ex)
                null
            }
            val dialog = behov.toDialog(personInfo?.name)
            dialog.attachments?.firstOrNull()?.let {
                logger.info("Sending behov ${behov.id} to dialogporten, with link ${it.urls.firstOrNull()?.url}")
            }

            val dialogId = dialogportenClient.createDialog(dialog)
            narmestelederDb.updateNlBehov(
                behov.copy(
                    dialogId = dialogId,
                    behovStatus = BehovStatus.DIALOGPORTEN_STATUS_SET_REQUIRES_ATTENTION,
                    fornavn = personInfo?.name?.fornavn,
                    mellomnavn = personInfo?.name?.mellomnavn,

                    etternavn = personInfo?.name?.etternavn,
                )
            )
        } catch (ex: Exception) {
            logger.error("Failed to send behov ${behov.id} to dialogporten", ex)
        }
    }


    suspend fun setAllFulfilledBehovsAsCompletedInDialogporten() {
        narmestelederDb.getNlBehovByStatus(BehovStatus.BEHOV_FULFILLED, BEHOV_BY_STATUS_LIMIT)
            .also {
                logger.info("Found ${it.size} fulfilled behovs to complete in dialogporten")
            }
            .forEach { behov ->
                behov.dialogId?.let {
                    setToCompletedInDialogportenIfFulfilled(behov)
                }
            }
    }

    suspend fun setToCompletedInDialogportenIfFulfilled(behov: NarmestelederBehovEntity) {
        if (behov.behovStatus != BehovStatus.BEHOV_FULFILLED) {
            logger.info("Skipping setting dialog to completed for behov ${behov.id} with status ${behov.behovStatus}")
            return
        }
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
                logger.info("Successfully updated Dialogporten for dialog $dialogId")
            } catch (ex: Exception) {
                logger.error("Failed to update dialog status for dialogId: $dialogId", ex)
            }
        }
        logger.info("Completed set ${behov.dialogId} to complete in dialogporten")
    }

    suspend fun deleteDialogsInDialogporten() {
        do {
            val dialogsToDeleteInDialogporten = getDialogsToDelete()
            logger.info("Found ${dialogsToDeleteInDialogporten.size} documents to delete from to dialogporten")

            for (dialog in dialogsToDeleteInDialogporten) {
                dialog.dialogId?.let { uuid ->
                    try {
                        val status = dialogportenClient.deleteDialog(uuid)
                        if (status.isSuccess()) {
                            narmestelederDb.updateNlBehov(
                                dialog.copy(
                                    dialogId = null,
                                    updated = Instant.now(),
                                    dialogDeletePerformed = Instant.now(),
                                )
                            )
                        } else if (
                            status == HttpStatusCode.Gone) {
                            logger.info("Skipping setting properties to null, dialog ${dialog.id} with dialogportenUUID $uuid already deleted in dialogporten")
                            narmestelederDb.updateNlBehov(
                                dialog.copy(
                                    updated = Instant.now(),
                                    dialogDeletePerformed = Instant.now(),
                                )
                            )
                        } else {
                            logger.error("Failed to delete dialog ${dialog.id} with dialogportenUUID $uuid from dialogporten, received status $status")
                            throw RuntimeException("Failed to delete dialog ${dialog.id} with dialogportenUUID $uuid")
                        }
                    } catch (ex: Exception) {
                        logger.error("Failed to delete dialog ${dialog.id} from dialogporten", ex)
                        throw ex
                    }
                }
            }
            delay(otherEnvironmentProperties.deleteDialogportenDialogsTaskProperties.deleteDialogerSleepAfterPage) // small delay to avoid hammering dialogporten
        } while (dialogsToDeleteInDialogporten.size == otherEnvironmentProperties.deleteDialogportenDialogsTaskProperties.deleteDialogerLimit)
    }

    private fun getDialogTitle(name: Navn?, nationalIdentityNumber: String): String =
        name?.let {
            "$DIALOG_TITLE_WITH_NAME ${it.navnFullt()} ${ninToInfoString(nationalIdentityNumber)}"
        } ?: DIALOG_TITLE_NO_NAME

    private fun getSummary(name: Navn?): String =
        name?.let {
            "${it.navnFullt()} $DIALOG_SUMMARY"
        } ?: "En ansatt $DIALOG_SUMMARY"

    private suspend fun getRequirementsToSend() =
        narmestelederDb.getNlBehovByStatus(BehovStatus.BEHOV_CREATED, BEHOV_BY_STATUS_LIMIT)

    private suspend fun getDialogsToDelete() =
        narmestelederDb.getNlBehovForDelete(otherEnvironmentProperties.deleteDialogportenDialogsTaskProperties.deleteDialogerLimit)

    private suspend fun getRequirementsToResend() =
        narmestelederDb.getNlBehovForResendToDialogporten(
            BehovStatus.DIALOGPORTEN_STATUS_SET_REQUIRES_ATTENTION,
            BEHOV_BY_STATUS_LIMIT
        )

    private fun createApiLink(id: UUID): String =
        "${otherEnvironmentProperties.publicIngressUrl}$API_V1_PATH$RECUIREMENT_PATH/$id"

    private fun createGuiLink(id: UUID): String =
        "${otherEnvironmentProperties.frontendBaseUrl}/ansatte/narmesteleder/$id"
    //https://www.ekstern.dev.nav.no/arbeidsgiver/ansatte/narmesteleder/ce48ec37-7cba-432d-8d2e-645389d7d6b5

    private fun NarmestelederBehovEntity.toDialog(name: Navn?): Dialog {
        require(id != null) { "Cannot create Dialogporten Dialog without id" }
        return Dialog(
            serviceResource = "urn:altinn:resource:$NARMESTE_LEDER_RESOURCE",
            status = DialogStatus.RequiresAttention,
            party = "urn:altinn:organization:identifier-no:$orgnummer",
            externalReference = id.toString(),
            content = Content.create(
                title = getDialogTitle(name, sykmeldtFnr),
                summary = getSummary(name),
            ),
            isApiOnly = otherEnvironmentProperties.dialogportenIsApiOnly,
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

    private fun ninToInfoString(nationalIdentityNumber: String): String {
        val birthDate = ninToBirthDate(nationalIdentityNumber)
        return if (birthDate != null) {
            "(f. ${birthDate.format(DateTimeFormatter.ofPattern("dd.MM.yyyy"))})"
        } else {
            "(d-nummer: $nationalIdentityNumber)"
        }
    }

    private fun ninToBirthDate(nationalIdentityNumber: String): LocalDate? {
        try {
            val day = nationalIdentityNumber.take(2)
            val month = nationalIdentityNumber.substring(2, 4)
            val year = nationalIdentityNumber.substring(4, 8)
            return LocalDate.parse("$year-$month-$day")
        } catch (e: DateTimeParseException) {
            return null
        }
    }

    companion object {
        const val DIALOG_TITLE_NO_NAME = "Dere har en sykmeldt med behov for å bli tildelt nærmeste leder"
        const val DIALOG_TITLE_WITH_NAME = "Oppgi nærmeste leder for"
        const val DIALOG_SUMMARY =
            "er sykmeldt. Nav trenger informasjon om hvem som er nærmeste leder for å kunne gi tilgang til oppfølginstjenestene på \"Dine sykmeldte\" hos Nav"
        const val URL_TITLE_GUI = "Naviger til nærmeste leder skjema"
        const val URL_TITLE_API = "Endpoint for LinemanagerRequirement request"

        const val BEHOV_BY_STATUS_LIMIT = 100

    }
}
