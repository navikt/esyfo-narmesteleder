package no.nav.syfo.altinn.dialogporten.service

import kotlinx.coroutines.delay
import no.nav.syfo.altinn.dialogporten.client.DialogPatchResult
import no.nav.syfo.altinn.dialogporten.client.DialogportenClient
import no.nav.syfo.altinn.dialogporten.client.HttpDialogportenClient
import no.nav.syfo.altinn.dialogporten.client.HttpDialogportenClient.DialogportenPatch.OPERATION
import no.nav.syfo.altinn.dialogporten.client.HttpDialogportenClient.DialogportenPatch.PATH
import no.nav.syfo.altinn.dialogporten.client.dialogportenRevisionConflictFailure
import no.nav.syfo.altinn.dialogporten.domain.Dialog
import no.nav.syfo.altinn.dialogporten.domain.DialogStatus
import no.nav.syfo.integration.pdl.Foedselsdato
import no.nav.syfo.integration.pdl.Navn
import no.nav.syfo.logging.logEvent
import no.nav.syfo.logging.rethrowCancellation
import no.nav.syfo.narmesteleder.db.NarmestelederBehovEntity
import no.nav.syfo.narmesteleder.db.NarmestelederDb
import no.nav.syfo.narmesteleder.domain.BehovStatus
import no.nav.syfo.narmestelederbehov.application.BehovPersonName
import no.nav.syfo.narmestelederbehov.application.DialogAction
import no.nav.syfo.narmestelederbehov.application.DialogEventDetails
import no.nav.syfo.narmestelederbehov.application.dialogCreatedForClosedBehov
import no.nav.syfo.narmestelederbehov.application.dialogPersonEnrichmentFailed
import no.nav.syfo.narmestelederbehov.application.dialogportenDialogFailed
import no.nav.syfo.narmestelederbehov.infrastructure.NarmestelederbehovDialogFactory
import no.nav.syfo.pdl.PdlService
import no.nav.syfo.platform.upstream.UpstreamResult
import no.nav.syfo.platform.upstream.getOrThrow
import no.nav.syfo.util.logger
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.time.Duration.Companion.milliseconds

class DialogportenService(
    private val dialogportenClient: DialogportenClient,
    private val narmestelederDb: NarmestelederDb,
    private val dialogFactory: NarmestelederbehovDialogFactory,
    private val pdlService: PdlService,
) {
    private val logger = logger()
    private fun NarmestelederBehovEntity.logDetails() = DialogEventDetails(id, dialogId)

    private fun logDialogFailure(behov: NarmestelederBehovEntity, action: DialogAction, cause: Throwable) {
        logger.logEvent(dialogportenDialogFailed, behov.logDetails().copy(action = action), cause = cause)
    }

    suspend fun sendDocumentsToDialogporten() {
        var batchNum = 0
        var firstCreatedTimestamp: Instant?
        do {
            val behovToSend = getRequirementsToSend()
            batchNum += 1
            firstCreatedTimestamp = if (!behovToSend.isEmpty()) {
                behovToSend.first().created
            } else {
                null
            }
            logger.info("Batch: $batchNum: Found ${behovToSend.size} behov to send to dialogporten. First created at ${firstCreatedTimestamp ?: "N/A"}")

            for (behov in behovToSend) {
                sendToDialogporten(behov)
            }
        } while (behovToSend.size >= BEHOV_BY_STATUS_LIMIT)
    }

    suspend fun sendToDialogporten(behov: NarmestelederBehovEntity) {
        try {
            require(behov.id != null) { "Cannot create Dialogporten Dialog without id" }
            val personInfo = try {
                pdlService.getPersonFor(behov.sykmeldtFnr)
            } catch (ex: Exception) {
                ex.rethrowCancellation()
                logger.logEvent(dialogPersonEnrichmentFailed, behov.logDetails(), cause = ex)
                null
            }
            val dialog = behov.toDialog(personInfo?.name, personInfo?.dateOfBirth)
            dialog.attachments?.firstOrNull()?.let {
                logger.info("Sending behov ${behov.id} to dialogporten, with link ${it.urls.firstOrNull()?.url}")
            }

            val dialogId = dialogportenClient.createDialog(dialog).getOrThrow()
            val marked = narmestelederDb.markDialogCreated(
                id = behov.id,
                dialogId = dialogId,
                fornavn = personInfo?.name?.fornavn,
                mellomnavn = personInfo?.name?.mellomnavn,
                etternavn = personInfo?.name?.etternavn,
            )
            if (!marked) {
                logger.logEvent(dialogCreatedForClosedBehov, DialogEventDetails(behov.id, dialogId))
            }
        } catch (ex: Exception) {
            ex.rethrowCancellation()
            logDialogFailure(behov, DialogAction.CREATE, ex)
        }
    }

    suspend fun setAllFulfilledBehovsAsCompletedInDialogporten() {
        narmestelederDb.getNlBehovByStatus(BehovStatus.BEHOV_FULFILLED, BEHOV_BY_STATUS_LIMIT)
            .also {
                logger.info("Found ${it.size} fulfilled behovs to complete in dialogporten")
            }
            .forEach { behov ->
                behov.dialogId?.let {
                    setToCompletedInDialogporten(behov)
                }
            }
    }

    suspend fun setToCompletedInDialogporten(behov: NarmestelederBehovEntity) {
        if (behov.behovStatus != BehovStatus.BEHOV_FULFILLED) {
            logger.info("Skipping setting dialog to completed for behov ${behov.id} with non-completable status ${behov.behovStatus}")
            return
        }
        behov.dialogId?.let { dialogId ->
            try {
                completeFulfilledDialog(behov)
            } catch (ex: Exception) {
                ex.rethrowCancellation()
                logDialogFailure(behov, DialogAction.COMPLETE, ex)
            }
        }
        logger.info("Completed set ${behov.dialogId} to complete in dialogporten")
    }

    suspend fun completeFulfilledDialog(behov: NarmestelederBehovEntity) {
        require(behov.behovStatus == BehovStatus.BEHOV_FULFILLED)
        val dialogId = requireNotNull(behov.dialogId)
        dialogportenClient.getDialogById(dialogId).getOrThrow().let { existingDialog ->
            dialogportenClient.patchDialog(
                dialogId = dialogId,
                revisionNumber = existingDialog.revision,
                patch = HttpDialogportenClient.DialogportenPatch(
                    operation = OPERATION.REPLACE,
                    path = PATH.STATUS,
                    value = DialogStatus.Completed.name
                )
            ).getOrThrow().requirePatched()
        }
        narmestelederDb.updateNlBehov(behov.copy(behovStatus = BehovStatus.DIALOGPORTEN_STATUS_SET_COMPLETED))
        logger.info("Successfully updated Dialogporten for dialog $dialogId")
    }

    suspend fun setAllExpiredBehovsAsExpiredAndCompletedInDialogporten() {
        var batchNum = 0
        do {
            val behovsToExpire = narmestelederDb.getNlBehovForExpireInDialogporten(
                BEHOV_BY_STATUS_LIMIT,
                listOf(
                    BehovStatus.BEHOV_EXPIRED,
                    BehovStatus.ERROR
                )
            ).also {
                logger.info("Batch: ${++batchNum}:: Found ${it.size} expired behovs to expire in dialogporten")
            }
            behovsToExpire.forEach { behov ->
                try {
                    val expirationTime = OffsetDateTime.now().plusMinutes(10) // Must be set to a future time. Adding some slack for retries and processing.
                    val dialogId = requireNotNull(behov.dialogId)

                    setToExpiredAndCompletedInDialogporten(dialogId, expirationTime)
                    narmestelederDb.updateNlBehov(
                        behov.copy(
                            expiredInDialogporten = expirationTime.toInstant()
                        )
                    )
                    logger.info("Successfully updated expired behov ${behov.id} with expired date: ${expirationTime.toInstant()}")
                } catch (ex: Exception) {
                    ex.rethrowCancellation()
                    logDialogFailure(behov, DialogAction.EXPIRE, ex)
                }
            }
            delay(EXPIRE_BEHOV_LOOP_DELAY_MS.milliseconds)
        } while (behovsToExpire.size >= BEHOV_BY_STATUS_LIMIT || batchNum == MAX_EXPIRE_BEHOV_BATCHES)
    }

    suspend fun setToExpiredAndCompletedInDialogporten(dialogId: UUID, expirationTime: OffsetDateTime) {
        dialogportenClient.getDialogById(dialogId).getOrThrow().let { existingDialog ->
            dialogportenClient.patchDialog(
                dialogId = dialogId,
                revisionNumber = existingDialog.revision,
                patch = listOf(
                    HttpDialogportenClient.DialogportenPatch(
                        OPERATION.REPLACE,
                        PATH.STATUS,
                        DialogStatus.Completed.name
                    ),
                    HttpDialogportenClient.DialogportenPatch(
                        OPERATION.ADD,
                        PATH.EXPIRES_AT,
                        expirationTime.toString()
                    )
                )
            ).getOrThrow().requirePatched()
        }
    }

    private fun DialogPatchResult.requirePatched() {
        when (this) {
            DialogPatchResult.Patched -> Unit
            DialogPatchResult.RevisionConflict -> UpstreamResult.Failure(dialogportenRevisionConflictFailure()).getOrThrow()
        }
    }

    private suspend fun getRequirementsToSend() = narmestelederDb.getNlBehovByStatus(BehovStatus.BEHOV_CREATED, BEHOV_BY_STATUS_LIMIT)

    private fun NarmestelederBehovEntity.toDialog(name: Navn?, foedselsdato: Foedselsdato?): Dialog {
        require(id != null) { "Cannot create Dialogporten Dialog without id" }
        return dialogFactory.create(
            id = id,
            organizationNumber = orgnummer,
            employeeIdent = sykmeldtFnr,
            name = name?.let { BehovPersonName(firstName = it.fornavn, middleName = it.mellomnavn, lastName = it.etternavn) },
            birthDate = foedselsdato?.foedselsdato,
        )
    }

    companion object {
        const val EXPIRE_BEHOV_LOOP_DELAY_MS = 100
        const val BEHOV_BY_STATUS_LIMIT = 100
        const val MAX_EXPIRE_BEHOV_BATCHES = 1000
    }
}
