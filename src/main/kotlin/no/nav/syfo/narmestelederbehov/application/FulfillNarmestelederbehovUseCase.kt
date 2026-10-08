package no.nav.syfo.narmestelederbehov.application

import kotlinx.coroutines.CancellationException
import no.nav.syfo.logging.applicationLogger
import no.nav.syfo.logging.logEvent
import no.nav.syfo.narmestelederbehov.domain.Narmestelederbehov
import no.nav.syfo.narmestelederbehov.domain.NarmestelederbehovId
import no.nav.syfo.narmestelederrelasjon.application.EstablishNarmestelederrelasjon
import no.nav.syfo.narmestelederrelasjon.application.EstablishNarmestelederrelasjonCommand
import no.nav.syfo.narmestelederrelasjon.application.EstablishNarmestelederrelasjonResult
import no.nav.syfo.narmestelederrelasjon.domain.ManagerContactInput
import no.nav.syfo.narmestelederrelasjon.domain.ManagerContactNormalization
import no.nav.syfo.narmestelederrelasjon.domain.NormalizedManagerContact
import no.nav.syfo.narmestelederrelasjon.domain.RelationSource
import no.nav.syfo.narmestelederrelasjon.domain.normalize
import no.nav.syfo.organisasjonstilgang.application.OrganizationAccess
import no.nav.syfo.organisasjonstilgang.application.OrganizationAccessResult
import no.nav.syfo.organisasjonstilgang.application.OrganizationAccessSubject
import no.nav.syfo.platform.application.Step
import no.nav.syfo.platform.application.orStop

class FulfillNarmestelederbehovUseCase(
    private val behovRepository: NarmestelederbehovRepository,
    private val organizationAccess: OrganizationAccess,
    private val establishRelation: EstablishNarmestelederrelasjon,
    private val dialog: NarmestelederbehovDialog,
) {
    suspend fun execute(command: FulfillNarmestelederbehovCommand): FulfillNarmestelederbehovResult = fulfill(command).log()

    private suspend fun fulfill(command: FulfillNarmestelederbehovCommand): FulfillNarmestelederbehovResult {
        val manager = validateManagerContact(command.manager).orStop { return it }
        val behov = findBehov(command.behovId).orStop { return it }
        verifyOrganizationAccess(command.accessSubject, behov).orStop { return it }
        val relationSource = command.accessSubject.relationSource()
        val published = establish(
            EstablishNarmestelederrelasjonCommand(
                employeeIdent = behov.employee.personIdent,
                organizationNumber = behov.employee.organizationNumber,
                manager = manager,
                source = relationSource,
            ),
        ).orStop { return it }
        val marked = markFulfilled(behov).orStop { return it }
        return FulfillNarmestelederbehovResult.Fulfilled(
            relationSource = relationSource,
            dialogCompletion = completeDialog(marked),
            managerNameMatch = published.managerNameMatch,
        )
    }

    private fun validateManagerContact(input: ManagerContactInput): FulfillStep<NormalizedManagerContact> = when (val normalization = input.normalize()) {
        is ManagerContactNormalization.Valid -> Step.Continue(normalization.manager)
        is ManagerContactNormalization.Invalid ->
            Step.Stop(FulfillNarmestelederbehovResult.InvalidManagerContactDetails(normalization.issues))
    }

    private suspend fun findBehov(id: NarmestelederbehovId): FulfillStep<Narmestelederbehov> = behovRepository.findForFulfillment(id)?.let { Step.Continue(it) }
        ?: Step.Stop(FulfillNarmestelederbehovResult.NotFound)

    private suspend fun verifyOrganizationAccess(subject: OrganizationAccessSubject, behov: Narmestelederbehov): FulfillStep<Unit> {
        val organizationNumber = behov.employee.organizationNumber
        return when (val access = organizationAccess.evaluate(subject, organizationNumber)) {
            is OrganizationAccessResult.Granted -> Step.Proceed
            is OrganizationAccessResult.Denied ->
                Step.Stop(FulfillNarmestelederbehovResult.AccessDenied(access.reason, organizationNumber))
        }
    }

    private suspend fun establish(command: EstablishNarmestelederrelasjonCommand): FulfillStep<EstablishNarmestelederrelasjonResult.Published> = when (val result = establishRelation.execute(command)) {
        is EstablishNarmestelederrelasjonResult.Published -> Step.Continue(result)
        is EstablishNarmestelederrelasjonResult.NoActiveSykmelding ->
            Step.Stop(FulfillNarmestelederbehovResult.NoActiveSykmelding(result.organizationNumber))
        is EstablishNarmestelederrelasjonResult.NoEmployment ->
            Step.Stop(FulfillNarmestelederbehovResult.NoEmployment(result.reason))
        is EstablishNarmestelederrelasjonResult.UpstreamUnavailable ->
            Step.Stop(FulfillNarmestelederbehovResult.UpstreamUnavailable(result.failure))
        EstablishNarmestelederrelasjonResult.PersonNotFound -> Step.Stop(FulfillNarmestelederbehovResult.PersonNotFound)
        is EstablishNarmestelederrelasjonResult.ManagerNameMismatch ->
            Step.Stop(FulfillNarmestelederbehovResult.ManagerNameMismatch(result.managerNameMatch))
        is EstablishNarmestelederrelasjonResult.EmployeeNameMismatch ->
            error("Fulfillment does not submit an employee last name")
    }

    private suspend fun markFulfilled(behov: Narmestelederbehov): FulfillStep<MarkFulfilledResult.Marked> = when (val result = behovRepository.markFulfilled(behov.id)) {
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
            when (behovRepository.markDialogCompleted(marked.id)) {
                MarkDialogCompletedResult.Marked,
                MarkDialogCompletedResult.NotFulfilled,
                -> DialogportenCompletionAttempt.Completed
            }
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
            is FulfillNarmestelederbehovResult.UpstreamUnavailable -> Unit
            else -> logger.event(fulfillmentRejected, result)
        }
    }

    private companion object {
        val logger = applicationLogger(FulfillNarmestelederbehovUseCase::class.java)
    }
}

private typealias FulfillStep<T> = Step<T, FulfillNarmestelederbehovResult>

private fun OrganizationAccessSubject.relationSource(): RelationSource = when (this) {
    is OrganizationAccessSubject.LpsSystemUser -> RelationSource.LPS
    is OrganizationAccessSubject.PersonnelManager -> RelationSource.PERSONNEL_MANAGER
}
