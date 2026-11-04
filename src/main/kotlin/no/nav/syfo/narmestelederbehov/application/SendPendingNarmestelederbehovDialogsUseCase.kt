package no.nav.syfo.narmestelederbehov.application

import kotlinx.coroutines.CancellationException
import no.nav.syfo.narmestelederbehov.domain.NarmestelederbehovId
import no.nav.syfo.platform.observability.applicationLogger
import no.nav.syfo.platform.observability.logEvent
import java.time.Clock
import java.time.Instant
import java.util.UUID

interface SendPendingNarmestelederbehovDialogsRepository {
    /**
     * BEHOV_CREATED behov created strictly before [highWaterMark] and after [after] in (created, id)
     * order, oldest first, up to [limit].
     */
    suspend fun findPendingDialogs(after: DialogScanPosition?, highWaterMark: Instant, limit: Int): List<PendingNarmestelederbehovDialog>

    /** Saves the dialog and employee name only while the behov is BEHOV_CREATED. */
    suspend fun markDialogCreated(
        id: NarmestelederbehovId,
        dialogId: UUID,
        name: BehovPersonName?,
    ): MarkDialogCreatedResult
}

class SendPendingNarmestelederbehovDialogsUseCase(
    private val repository: SendPendingNarmestelederbehovDialogsRepository,
    progressRepository: DialogScanProgressRepository,
    private val personLookup: EmployeePersonLookup,
    private val dialog: NarmestelederbehovDialogCreation,
    private val clock: Clock,
) {
    private val scan = BoundedDialogScan(
        flow = DialogScanFlow.SEND,
        progressRepository = progressRepository,
        batchSize = BATCH_SIZE,
        maxBatches = MAX_BATCHES,
    )

    suspend fun execute() {
        // A new cycle covers the behov that are already old enough; younger behov wait for the next cycle.
        scan.runOnce(
            newCycleHighWaterMark = clock.instant().minusSeconds(ELIGIBILITY_AGE_SECONDS),
            fetch = repository::findPendingDialogs,
            attempt = ::send,
        )
    }

    private suspend fun send(behov: PendingNarmestelederbehovDialog): DialogAttemptOutcome {
        val details = DialogEventDetails(behov.id.value, behov.dialogId)
        return try {
            val person = findPerson(behov, details)
            when (val created = dialog.create(behov.id, behov.employee, person)) {
                is NarmestelederbehovDialogCreationResult.Created -> {
                    markDialogCreated(behov, created.dialogId, person)
                    DialogAttemptOutcome.SUCCEEDED
                }
                is NarmestelederbehovDialogCreationResult.Unavailable -> {
                    logger.logEvent(dialogportenDialogFailed, details.copy(action = DialogAction.CREATE), upstreamFailure = created.failure)
                    DialogAttemptOutcome.FAILED
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.logEvent(dialogportenDialogFailed, details.copy(action = DialogAction.CREATE), cause = e)
            DialogAttemptOutcome.FAILED
        }
    }

    private suspend fun markDialogCreated(behov: PendingNarmestelederbehovDialog, dialogId: UUID, person: EmployeePerson?) {
        val marked = repository.markDialogCreated(behov.id, dialogId, person?.name)
        if (marked == MarkDialogCreatedResult.NotCreated) {
            logger.logEvent(dialogCreatedForClosedBehov, DialogEventDetails(behov.id.value, dialogId))
        }
    }

    private suspend fun findPerson(behov: PendingNarmestelederbehovDialog, details: DialogEventDetails): EmployeePerson? = try {
        when (val lookup = personLookup.find(behov.employee.personIdent)) {
            is EmployeePersonLookupResult.Found -> lookup.person
            EmployeePersonLookupResult.NotFound -> null
            is EmployeePersonLookupResult.Unavailable -> {
                logger.logEvent(dialogPersonEnrichmentFailed, details, upstreamFailure = lookup.failure)
                null
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        logger.logEvent(dialogPersonEnrichmentFailed, details, cause = e)
        null
    }

    private companion object {
        const val BATCH_SIZE = 100
        const val MAX_BATCHES = 10
        const val ELIGIBILITY_AGE_SECONDS: Long = 10
        val logger = applicationLogger(SendPendingNarmestelederbehovDialogsUseCase::class.java)
    }
}
