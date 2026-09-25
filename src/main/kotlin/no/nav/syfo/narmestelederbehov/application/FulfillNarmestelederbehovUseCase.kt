package no.nav.syfo.narmestelederbehov.application

import kotlinx.coroutines.CancellationException
import no.nav.syfo.logging.applicationEvent
import no.nav.syfo.logging.applicationLogger
import no.nav.syfo.logging.logEvent
import no.nav.syfo.narmestelederbehov.domain.ManagerContactNormalization
import no.nav.syfo.narmestelederbehov.domain.ManagerLastNameMatch
import no.nav.syfo.narmestelederbehov.domain.NarmestelederbehovId
import no.nav.syfo.narmestelederbehov.domain.matchManagerLastName
import no.nav.syfo.narmestelederbehov.domain.normalize
import no.nav.syfo.narmestelederrelasjon.application.EstablishNarmestelederrelasjon
import no.nav.syfo.narmestelederrelasjon.application.EstablishNarmestelederrelasjonCommand
import no.nav.syfo.narmestelederrelasjon.domain.RelationManager
import no.nav.syfo.narmestelederrelasjon.domain.RelationPerson
import no.nav.syfo.narmestelederrelasjon.domain.RelationSource
import no.nav.syfo.organisasjonstilgang.application.OrganizationAccess
import no.nav.syfo.organisasjonstilgang.application.OrganizationAccessResult
import no.nav.syfo.organisasjonstilgang.application.OrganizationAccessSubject
import org.slf4j.event.Level
import java.util.UUID

private val dialogStatusPersistenceFailed = applicationEvent<String>(
    name = "narmestelederbehov_dialog_status_persistence_failed",
    level = Level.WARN,
    message = "Dialogporten completed but behov status could not be persisted",
    operation = "fulfill_narmestelederbehov",
    fields = mapOf("behov_id" to { it }),
)

private val dialogportenCompletionFailed = applicationEvent<String>(
    name = "narmestelederbehov_dialogporten_completion_failed",
    level = Level.WARN,
    message = "Dialogporten completion failed; pending behov remains retryable",
    operation = "fulfill_narmestelederbehov",
    upstream = "dialogporten",
    fields = mapOf("behov_id" to { it }),
)

class FulfillNarmestelederbehovUseCase(
    private val behovRepository: NarmestelederbehovRepository,
    private val organizationAccess: OrganizationAccess,
    private val activeSykmeldingLookup: ActiveSykmeldingLookup,
    private val employmentLookup: EmploymentLookup,
    private val personLookup: PersonLookup,
    private val establishNarmestelederrelasjon: EstablishNarmestelederrelasjon,
    private val dialog: NarmestelederbehovDialog,
    private val nameValidationMetrics: ManagerNameValidationMetrics,
) {
    suspend fun execute(command: FulfillNarmestelederbehovCommand): FulfillNarmestelederbehovResult {
        val manager = when (val normalization = command.manager.normalize()) {
            is ManagerContactNormalization.Valid -> normalization.manager

            is ManagerContactNormalization.Invalid -> {
                return FulfillNarmestelederbehovResult.InvalidManagerContactDetails(normalization.issues).log()
            }
        }
        val behov = behovRepository.findForFulfillment(command.behovId)
            ?: return FulfillNarmestelederbehovResult.NotFound.log()

        when (val access = organizationAccess.evaluate(command.accessSubject, behov.employee.organizationNumber)) {
            OrganizationAccessResult.Granted -> Unit

            is OrganizationAccessResult.Denied -> return FulfillNarmestelederbehovResult.AccessDenied(
                access.reason,
                behov.employee.organizationNumber,
            ).log()
        }
        if (!activeSykmeldingLookup.hasActiveSykmelding(behov.employee.personIdent, behov.employee.organizationNumber)) {
            return FulfillNarmestelederbehovResult.NoActiveSykmelding(behov.employee.organizationNumber).log()
        }
        when (employmentLookup.findEmployment(behov.employee.personIdent, behov.employee.organizationNumber)) {
            EmploymentResult.IN_ORGANIZATION -> Unit

            EmploymentResult.NONE -> return FulfillNarmestelederbehovResult.NoEmployment(EmploymentResult.NONE).log()

            EmploymentResult.NOT_IN_ORGANIZATION ->
                return FulfillNarmestelederbehovResult.NoEmployment(EmploymentResult.NOT_IN_ORGANIZATION).log()
        }

        val employee = personLookup.find(behov.employee.personIdent)
            ?: return FulfillNarmestelederbehovResult.PersonNotFound.log()
        val managerPerson = personLookup.find(manager.personIdent)
            ?: return FulfillNarmestelederbehovResult.PersonNotFound.log()
        val managerNameMatch = managerPerson.name.matchManagerLastName(manager.lastName)
        nameValidationMetrics.record(managerNameMatch)
        if (managerNameMatch is ManagerLastNameMatch.NoMatch) {
            return FulfillNarmestelederbehovResult.ManagerNameMismatch(managerNameMatch).log()
        }

        val relationSource = command.accessSubject.relationSource()
        establishNarmestelederrelasjon.establish(
            EstablishNarmestelederrelasjonCommand(
                employee = RelationPerson(
                    personIdent = employee.personIdent,
                    firstName = employee.name.firstName,
                    middleName = employee.name.middleName,
                    lastName = employee.name.lastName,
                ),
                manager = RelationManager(
                    personIdent = manager.personIdent,
                    firstName = managerPerson.name.firstName,
                    middleName = managerPerson.name.middleName,
                    lastName = managerPerson.name.lastName,
                    email = manager.email.value,
                    mobile = manager.mobile.value,
                ),
                organizationNumber = behov.employee.organizationNumber,
                source = relationSource,
            ),
        )
        val marked = when (val result = behovRepository.markFulfilled(behov.id)) {
            is MarkFulfilledResult.Marked -> result
            MarkFulfilledResult.Missing -> return FulfillNarmestelederbehovResult.BehovMissingAfterPublication.log()
        }
        val dialogCompletion = marked.dialogId?.let { dialogId -> completeDialog(marked.id, dialogId) }
            ?: DialogportenCompletionAttempt.NotApplicable
        return FulfillNarmestelederbehovResult.Fulfilled(
            relationSource = relationSource,
            dialogCompletion = dialogCompletion,
            managerNameMatch = managerNameMatch,
        )
            .log()
    }

    private suspend fun completeDialog(behovId: NarmestelederbehovId, dialogId: UUID): DialogportenCompletionAttempt {
        try {
            dialog.complete(dialogId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.logEvent(dialogportenCompletionFailed, behovId.value.toString(), cause = e)
            return DialogportenCompletionAttempt.Failed
        }
        return try {
            behovRepository.markDialogCompleted(behovId)
            DialogportenCompletionAttempt.Completed
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.logEvent(dialogStatusPersistenceFailed, behovId.value.toString(), cause = e)
            DialogportenCompletionAttempt.Failed
        }
    }

    private fun <T : FulfillNarmestelederbehovResult> T.log(): T = also { result ->
        when (result) {
            is FulfillNarmestelederbehovResult.Fulfilled -> logger.event(fulfillmentCompleted, result)
            else -> logger.event(fulfillmentRejected, result)
        }
    }

    private companion object {
        val logger = applicationLogger(FulfillNarmestelederbehovUseCase::class.java)
    }
}

private fun OrganizationAccessSubject.relationSource(): RelationSource = when (this) {
    is OrganizationAccessSubject.LpsSystemUser -> RelationSource.LPS
    is OrganizationAccessSubject.PersonnelManager -> RelationSource.PERSONNEL_MANAGER
}
