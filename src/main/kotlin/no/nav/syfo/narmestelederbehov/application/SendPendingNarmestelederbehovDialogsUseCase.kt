package no.nav.syfo.narmestelederbehov.application

import kotlinx.coroutines.CancellationException
import no.nav.syfo.platform.observability.applicationLogger
import no.nav.syfo.platform.observability.logEvent
import java.time.Clock
import java.util.UUID

class SendPendingNarmestelederbehovDialogsUseCase(
    private val repository: NarmestelederbehovRepository,
    private val personLookup: EmployeePersonLookup,
    private val dialog: NarmestelederbehovDialogCreation,
    private val clock: Clock,
) {
    suspend fun execute() {
        var batchNum = 0
        do {
            val batch = repository.findPendingDialogs(clock.instant().minusSeconds(10), BATCH_SIZE)
            logger.info("Batch: ${++batchNum}: Found ${batch.size} behov to send to dialogporten. First created at ${batch.firstOrNull()?.created ?: "N/A"}")
            for (behov in batch) {
                send(behov)
            }
            // Preserve legacy refetching, including failed behov, until #711 is addressed.
        } while (batch.size >= BATCH_SIZE)
    }

    private suspend fun send(behov: PendingNarmestelederbehovDialog) {
        val details = DialogEventDetails(behov.id.value, behov.dialogId)
        try {
            val person = findPerson(behov, details)
            when (val created = dialog.create(behov.id, behov.employee, person)) {
                is NarmestelederbehovDialogCreationResult.Created -> markDialogCreated(behov, created.dialogId, person)
                is NarmestelederbehovDialogCreationResult.Unavailable ->
                    logger.logEvent(dialogportenDialogFailed, details.copy(action = DialogAction.CREATE), upstreamFailure = created.failure)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.logEvent(dialogportenDialogFailed, details.copy(action = DialogAction.CREATE), cause = e)
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
        val logger = applicationLogger(SendPendingNarmestelederbehovDialogsUseCase::class.java)
    }
}
