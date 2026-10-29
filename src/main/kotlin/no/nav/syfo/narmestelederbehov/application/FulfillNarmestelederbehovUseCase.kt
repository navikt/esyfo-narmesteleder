package no.nav.syfo.narmestelederbehov.application

import arrow.core.Either
import arrow.core.raise.Raise
import arrow.core.raise.either
import arrow.core.raise.ensureNotNull
import kotlinx.coroutines.CancellationException
import no.nav.syfo.narmestelederbehov.application.FulfillNarmestelederbehovError.AccessDenied
import no.nav.syfo.narmestelederbehov.application.FulfillNarmestelederbehovError.BehovMissingAfterPublication
import no.nav.syfo.narmestelederbehov.application.FulfillNarmestelederbehovError.InvalidManagerContactDetails
import no.nav.syfo.narmestelederbehov.application.FulfillNarmestelederbehovError.ManagerNameMismatch
import no.nav.syfo.narmestelederbehov.application.FulfillNarmestelederbehovError.NoActiveSykmelding
import no.nav.syfo.narmestelederbehov.application.FulfillNarmestelederbehovError.NoEmployment
import no.nav.syfo.narmestelederbehov.application.FulfillNarmestelederbehovError.NotFound
import no.nav.syfo.narmestelederbehov.application.FulfillNarmestelederbehovError.PersonNotFound
import no.nav.syfo.narmestelederbehov.application.FulfillNarmestelederbehovError.UpstreamUnavailable
import no.nav.syfo.narmestelederbehov.domain.Narmestelederbehov
import no.nav.syfo.narmestelederbehov.domain.NarmestelederbehovId
import no.nav.syfo.narmestelederrelasjon.application.establish.EstablishNarmestelederrelasjon
import no.nav.syfo.narmestelederrelasjon.application.establish.EstablishNarmestelederrelasjonCommand
import no.nav.syfo.narmestelederrelasjon.application.establish.EstablishNarmestelederrelasjonResult
import no.nav.syfo.narmestelederrelasjon.domain.ManagerContactInput
import no.nav.syfo.narmestelederrelasjon.domain.ManagerContactNormalization
import no.nav.syfo.narmestelederrelasjon.domain.NormalizedManagerContact
import no.nav.syfo.narmestelederrelasjon.domain.RelationSource
import no.nav.syfo.narmestelederrelasjon.domain.normalize
import no.nav.syfo.organisasjonstilgang.application.OrganizationAccess
import no.nav.syfo.organisasjonstilgang.application.OrganizationAccessResult
import no.nav.syfo.organisasjonstilgang.application.OrganizationAccessSubject
import no.nav.syfo.platform.observability.applicationLogger
import no.nav.syfo.platform.observability.logEvent

interface FulfillNarmestelederbehovRepository {
    suspend fun findForFulfillment(id: NarmestelederbehovId): Narmestelederbehov?

    suspend fun markFulfilled(id: NarmestelederbehovId): MarkFulfilledResult

    suspend fun markDialogCompleted(id: NarmestelederbehovId): MarkDialogCompletedResult
}

class FulfillNarmestelederbehovUseCase(
    private val behovRepository: FulfillNarmestelederbehovRepository,
    private val organizationAccess: OrganizationAccess,
    private val establishRelation: EstablishNarmestelederrelasjon,
    private val dialog: NarmestelederbehovDialog,
) {
    suspend fun execute(command: FulfillNarmestelederbehovCommand): Either<FulfillNarmestelederbehovError, FulfilledNarmestelederbehov> = either {
        val manager = validManager(command.manager)
        val behov = findBehov(command.behovId)
        requireAccess(command.accessSubject, behov)

        val relationSource = command.accessSubject.relationSource()
        publishRelation(behov, manager, relationSource)

        val marked = markFulfilled(behov)
        FulfilledNarmestelederbehov(
            relationSource = relationSource,
            dialogCompletion = completeDialog(marked),
        )
    }.onLeft { error -> logRejection(error) }
        .onRight { logger.event(fulfillmentCompleted, it) }

    private fun Raise<FulfillNarmestelederbehovError>.validManager(manager: ManagerContactInput): NormalizedManagerContact = when (val normalization = manager.normalize()) {
        is ManagerContactNormalization.Valid -> normalization.manager
        is ManagerContactNormalization.Invalid -> raise(InvalidManagerContactDetails(normalization.issues))
    }

    private suspend fun Raise<FulfillNarmestelederbehovError>.findBehov(id: NarmestelederbehovId): Narmestelederbehov = ensureNotNull(behovRepository.findForFulfillment(id)) { NotFound }

    private suspend fun Raise<FulfillNarmestelederbehovError>.requireAccess(
        subject: OrganizationAccessSubject,
        behov: Narmestelederbehov,
    ) {
        val organizationNumber = behov.employee.organizationNumber
        when (val access = organizationAccess.evaluate(subject, organizationNumber)) {
            is OrganizationAccessResult.Granted -> Unit
            is OrganizationAccessResult.Unavailable -> raise(UpstreamUnavailable(access.failure))
            is OrganizationAccessResult.Denied -> raise(AccessDenied(access.reason, organizationNumber))
        }
    }

    private suspend fun Raise<FulfillNarmestelederbehovError>.publishRelation(
        behov: Narmestelederbehov,
        manager: NormalizedManagerContact,
        source: RelationSource,
    ) {
        val command = EstablishNarmestelederrelasjonCommand(
            employeeIdent = behov.employee.personIdent,
            organizationNumber = behov.employee.organizationNumber,
            manager = manager,
            source = source,
        )
        when (val result = establishRelation.execute(command)) {
            EstablishNarmestelederrelasjonResult.Published -> Unit
            is EstablishNarmestelederrelasjonResult.NoActiveSykmelding -> raise(NoActiveSykmelding(result.organizationNumber))
            is EstablishNarmestelederrelasjonResult.NoEmployment -> raise(NoEmployment(result.reason))
            is EstablishNarmestelederrelasjonResult.UpstreamUnavailable -> raise(UpstreamUnavailable(result.failure))
            EstablishNarmestelederrelasjonResult.PersonNotFound -> raise(PersonNotFound)
            EstablishNarmestelederrelasjonResult.ManagerNameMismatch -> raise(ManagerNameMismatch)
            EstablishNarmestelederrelasjonResult.EmployeeNameMismatch -> error("Fulfillment does not submit an employee last name")
        }
    }

    private suspend fun Raise<FulfillNarmestelederbehovError>.markFulfilled(behov: Narmestelederbehov): MarkFulfilledResult.Marked = when (val result = behovRepository.markFulfilled(behov.id)) {
        is MarkFulfilledResult.Marked -> result
        MarkFulfilledResult.Missing -> raise(BehovMissingAfterPublication)
    }

    private fun logRejection(error: FulfillNarmestelederbehovError) {
        if (error !is UpstreamUnavailable) {
            logger.event(fulfillmentRejected, error)
        }
    }

    private suspend fun completeDialog(marked: MarkFulfilledResult.Marked): DialogportenCompletionAttempt {
        val dialogId = marked.dialogId ?: return DialogportenCompletionAttempt.NotApplicable
        when (val completion = dialog.complete(dialogId)) {
            DialogCompletion.Completed -> Unit

            is DialogCompletion.Unavailable -> {
                logger.logEvent(dialogportenCompletionFailed, marked.id.value.toString(), upstreamFailure = completion.failure)
                return DialogportenCompletionAttempt.Failed
            }
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

    private companion object {
        val logger = applicationLogger(FulfillNarmestelederbehovUseCase::class.java)
    }
}

private fun OrganizationAccessSubject.relationSource(): RelationSource = when (this) {
    is OrganizationAccessSubject.LpsSystemUser -> RelationSource.LPS
    is OrganizationAccessSubject.PersonnelManager -> RelationSource.PERSONNEL_MANAGER
}
