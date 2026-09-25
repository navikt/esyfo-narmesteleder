package no.nav.syfo.narmestelederbehov.application

import kotlinx.coroutines.CancellationException
import no.nav.syfo.logging.applicationLogger
import no.nav.syfo.logging.logEvent
import no.nav.syfo.narmestelederbehov.domain.ManagerContactInput
import no.nav.syfo.narmestelederbehov.domain.ManagerContactNormalization
import no.nav.syfo.narmestelederbehov.domain.ManagerLastNameMatch
import no.nav.syfo.narmestelederbehov.domain.Narmestelederbehov
import no.nav.syfo.narmestelederbehov.domain.NarmestelederbehovId
import no.nav.syfo.narmestelederbehov.domain.NormalizedManagerContact
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
    suspend fun execute(command: FulfillNarmestelederbehovCommand): FulfillNarmestelederbehovResult = fulfill(command).log()

    private suspend fun fulfill(command: FulfillNarmestelederbehovCommand): FulfillNarmestelederbehovResult {
        val manager = validateManagerContact(command.manager).orStop { return it }
        val behov = findBehov(command.behovId).orStop { return it }
        verifyOrganizationAccess(command.accessSubject, behov).orStop { return it }
        verifyActiveSykmelding(behov).orStop { return it }
        verifyEmployment(behov).orStop { return it }
        val employeeAndManager = findEmployeeAndManager(behov, manager).orStop { return it }
        val managerNameMatch = verifyManagerName(employeeAndManager.manager, manager).orStop { return it }
        val relationSource = publishNarmestelederrelasjon(command.accessSubject, behov, manager, employeeAndManager)
        val marked = markFulfilled(behov).orStop { return it }
        return FulfillNarmestelederbehovResult.Fulfilled(
            relationSource = relationSource,
            dialogCompletion = completeDialog(marked),
            managerNameMatch = managerNameMatch,
        )
    }

    private fun validateManagerContact(input: ManagerContactInput): Step<NormalizedManagerContact> = when (val normalization = input.normalize()) {
        is ManagerContactNormalization.Valid -> Step.Continue(normalization.manager)
        is ManagerContactNormalization.Invalid ->
            Step.Stop(FulfillNarmestelederbehovResult.InvalidManagerContactDetails(normalization.issues))
    }

    private suspend fun findBehov(id: NarmestelederbehovId): Step<Narmestelederbehov> = behovRepository.findForFulfillment(id)?.let { Step.Continue(it) }
        ?: Step.Stop(FulfillNarmestelederbehovResult.NotFound)

    private suspend fun verifyOrganizationAccess(subject: OrganizationAccessSubject, behov: Narmestelederbehov): Step<Unit> {
        val organizationNumber = behov.employee.organizationNumber
        return when (val access = organizationAccess.evaluate(subject, organizationNumber)) {
            OrganizationAccessResult.Granted -> Step.Proceed
            is OrganizationAccessResult.Denied ->
                Step.Stop(FulfillNarmestelederbehovResult.AccessDenied(access.reason, organizationNumber))
        }
    }

    private suspend fun verifyActiveSykmelding(behov: Narmestelederbehov): Step<Unit> {
        val employee = behov.employee
        return if (activeSykmeldingLookup.hasActiveSykmelding(employee.personIdent, employee.organizationNumber)) {
            Step.Proceed
        } else {
            Step.Stop(FulfillNarmestelederbehovResult.NoActiveSykmelding(employee.organizationNumber))
        }
    }

    private suspend fun verifyEmployment(behov: Narmestelederbehov): Step<Unit> = when (val employment = employmentLookup.findEmployment(behov.employee.personIdent, behov.employee.organizationNumber)) {
        EmploymentResult.IN_ORGANIZATION -> Step.Proceed
        EmploymentResult.NONE, EmploymentResult.NOT_IN_ORGANIZATION ->
            Step.Stop(FulfillNarmestelederbehovResult.NoEmployment(employment))
    }

    private suspend fun findEmployeeAndManager(behov: Narmestelederbehov, manager: NormalizedManagerContact): Step<EmployeeAndManager> {
        val employee = personLookup.find(behov.employee.personIdent)
            ?: return Step.Stop(FulfillNarmestelederbehovResult.PersonNotFound)
        val managerPerson = personLookup.find(manager.personIdent)
            ?: return Step.Stop(FulfillNarmestelederbehovResult.PersonNotFound)
        return Step.Continue(EmployeeAndManager(employee, managerPerson))
    }

    private fun verifyManagerName(managerPerson: PersonDetails, manager: NormalizedManagerContact): Step<ManagerLastNameMatch> {
        val managerNameMatch = managerPerson.name.matchManagerLastName(manager.lastName)
        nameValidationMetrics.record(managerNameMatch)
        return when (managerNameMatch) {
            is ManagerLastNameMatch.NoMatch -> Step.Stop(FulfillNarmestelederbehovResult.ManagerNameMismatch(managerNameMatch))
            else -> Step.Continue(managerNameMatch)
        }
    }

    private suspend fun publishNarmestelederrelasjon(
        subject: OrganizationAccessSubject,
        behov: Narmestelederbehov,
        manager: NormalizedManagerContact,
        employeeAndManager: EmployeeAndManager,
    ): RelationSource {
        val relationSource = subject.relationSource()
        establishNarmestelederrelasjon.establish(
            EstablishNarmestelederrelasjonCommand(
                employee = RelationPerson(
                    personIdent = employeeAndManager.employee.personIdent,
                    firstName = employeeAndManager.employee.name.firstName,
                    middleName = employeeAndManager.employee.name.middleName,
                    lastName = employeeAndManager.employee.name.lastName,
                ),
                manager = RelationManager(
                    personIdent = manager.personIdent,
                    firstName = employeeAndManager.manager.name.firstName,
                    middleName = employeeAndManager.manager.name.middleName,
                    lastName = employeeAndManager.manager.name.lastName,
                    email = manager.email.value,
                    mobile = manager.mobile.value,
                ),
                organizationNumber = behov.employee.organizationNumber,
                source = relationSource,
            ),
        )
        return relationSource
    }

    private suspend fun markFulfilled(behov: Narmestelederbehov): Step<MarkFulfilledResult.Marked> = when (val result = behovRepository.markFulfilled(behov.id)) {
        is MarkFulfilledResult.Marked -> Step.Continue(result)
        MarkFulfilledResult.Missing -> Step.Stop(FulfillNarmestelederbehovResult.BehovMissingAfterPublication)
    }

    private suspend fun completeDialog(marked: MarkFulfilledResult.Marked): DialogportenCompletionAttempt {
        val dialogId = marked.dialogId ?: return DialogportenCompletionAttempt.NotApplicable
        try {
            dialog.complete(dialogId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.logEvent(dialogportenCompletionFailed, marked.id.value.toString(), cause = e)
            return DialogportenCompletionAttempt.Failed
        }
        return try {
            behovRepository.markDialogCompleted(marked.id)
            DialogportenCompletionAttempt.Completed
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.logEvent(dialogStatusPersistenceFailed, marked.id.value.toString(), cause = e)
            DialogportenCompletionAttempt.Failed
        }
    }

    private fun FulfillNarmestelederbehovResult.log(): FulfillNarmestelederbehovResult = also { result ->
        when (result) {
            is FulfillNarmestelederbehovResult.Fulfilled -> logger.event(fulfillmentCompleted, result)
            else -> logger.event(fulfillmentRejected, result)
        }
    }

    private companion object {
        val logger = applicationLogger(FulfillNarmestelederbehovUseCase::class.java)
    }
}

private data class EmployeeAndManager(
    val employee: PersonDetails,
    val manager: PersonDetails,
)

private sealed interface Step<out T> {
    data class Continue<out T>(val value: T) : Step<T>

    data class Stop(val result: FulfillNarmestelederbehovResult) : Step<Nothing>

    companion object {
        val Proceed: Step<Unit> = Continue(Unit)
    }
}

private inline fun <T> Step<T>.orStop(stop: (FulfillNarmestelederbehovResult) -> Nothing): T = when (this) {
    is Step.Continue -> value
    is Step.Stop -> stop(result)
}

private fun OrganizationAccessSubject.relationSource(): RelationSource = when (this) {
    is OrganizationAccessSubject.LpsSystemUser -> RelationSource.LPS
    is OrganizationAccessSubject.PersonnelManager -> RelationSource.PERSONNEL_MANAGER
}
