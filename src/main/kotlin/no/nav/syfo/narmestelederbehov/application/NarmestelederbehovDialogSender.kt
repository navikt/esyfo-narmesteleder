package no.nav.syfo.narmestelederbehov.application

import kotlinx.coroutines.CancellationException
import no.nav.esyfo.observability.ApplicationLogger
import no.nav.syfo.logging.logEvent
import no.nav.syfo.narmestelederbehov.domain.Employee
import no.nav.syfo.narmestelederbehov.domain.NarmestelederbehovId
import java.util.UUID

/**
 * Creates the Dialogporten dialog for a behov and records it. Failures are logged and swallowed;
 * SendDialogTask retries behov still in BEHOV_CREATED. The caller's logger keeps log ownership unchanged.
 */
internal class NarmestelederbehovDialogSender(
    private val repository: NarmestelederbehovRepository,
    private val personLookup: EmployeePersonLookup,
    private val dialog: NarmestelederbehovDialogCreation,
    private val logger: ApplicationLogger,
) {
    suspend fun send(id: NarmestelederbehovId, employee: Employee, existingDialogId: UUID? = null) {
        val details = DialogEventDetails(id.value, existingDialogId)
        try {
            val person = findPerson(employee, details)
            when (val created = dialog.create(id, employee, person)) {
                is NarmestelederbehovDialogCreationResult.Created -> markDialogCreated(id, created.dialogId, person)
                is NarmestelederbehovDialogCreationResult.Unavailable ->
                    logger.logEvent(dialogportenDialogFailed, details.copy(action = DialogAction.CREATE), upstreamFailure = created.failure)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.logEvent(dialogportenDialogFailed, details.copy(action = DialogAction.CREATE), cause = e)
        }
    }

    private suspend fun markDialogCreated(id: NarmestelederbehovId, dialogId: UUID, person: EmployeePerson?) {
        if (repository.markDialogCreated(id, dialogId, person?.name) == MarkDialogCreatedResult.NotCreated) {
            logger.logEvent(dialogCreatedForClosedBehov, DialogEventDetails(id.value, dialogId))
        }
    }

    /** Person details only enrich the dialog, so any lookup failure falls back to a dialog without them. */
    private suspend fun findPerson(employee: Employee, details: DialogEventDetails): EmployeePerson? = try {
        when (val result = personLookup.find(employee.personIdent)) {
            is EmployeePersonLookupResult.Found -> result.person
            EmployeePersonLookupResult.NotFound -> null
            is EmployeePersonLookupResult.Unavailable -> {
                logger.logEvent(dialogPersonEnrichmentFailed, details, upstreamFailure = result.failure)
                null
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        logger.logEvent(dialogPersonEnrichmentFailed, details, cause = e)
        null
    }
}
