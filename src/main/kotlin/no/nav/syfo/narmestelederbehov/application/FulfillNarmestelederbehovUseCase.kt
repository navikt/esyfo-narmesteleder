package no.nav.syfo.narmestelederbehov.application

import arrow.core.Either
import arrow.core.raise.either
import arrow.core.raise.ensureNotNull
import kotlinx.coroutines.CancellationException
import no.nav.syfo.narmestelederbehov.domain.Narmestelederbehov
import no.nav.syfo.narmestelederbehov.domain.NarmestelederbehovId
import no.nav.syfo.narmestelederrelasjon.application.establish.EstablishNarmestelederrelasjon
import no.nav.syfo.narmestelederrelasjon.application.establish.EstablishNarmestelederrelasjonCommand
import no.nav.syfo.narmestelederrelasjon.application.establish.EstablishNarmestelederrelasjonResult
import no.nav.syfo.narmestelederrelasjon.domain.ManagerContactNormalization
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
        val manager = when (val normalization = command.manager.normalize()) {
            is ManagerContactNormalization.Valid -> normalization.manager

            is ManagerContactNormalization.Invalid ->
                raise(FulfillNarmestelederbehovError.InvalidManagerContactDetails(normalization.issues))
        }

        val behov = ensureNotNull(behovRepository.findForFulfillment(command.behovId)) { FulfillNarmestelederbehovError.NotFound }
        val organizationNumber = behov.employee.organizationNumber
        when (val access = organizationAccess.evaluate(command.accessSubject, organizationNumber)) {
            is OrganizationAccessResult.Granted -> Unit

            is OrganizationAccessResult.Unavailable -> raise(FulfillNarmestelederbehovError.UpstreamUnavailable(access.failure))

            is OrganizationAccessResult.Denied ->
                raise(FulfillNarmestelederbehovError.AccessDenied(access.reason, organizationNumber))
        }

        val relationSource = command.accessSubject.relationSource()
        when (
            val result = establishRelation.execute(
                EstablishNarmestelederrelasjonCommand(
                    employeeIdent = behov.employee.personIdent,
                    organizationNumber = organizationNumber,
                    manager = manager,
                    source = relationSource,
                ),
            )
        ) {
            EstablishNarmestelederrelasjonResult.Published -> Unit

            is EstablishNarmestelederrelasjonResult.NoActiveSykmelding ->
                raise(FulfillNarmestelederbehovError.NoActiveSykmelding(result.organizationNumber))

            is EstablishNarmestelederrelasjonResult.NoEmployment ->
                raise(FulfillNarmestelederbehovError.NoEmployment(result.reason))

            is EstablishNarmestelederrelasjonResult.UpstreamUnavailable ->
                raise(FulfillNarmestelederbehovError.UpstreamUnavailable(result.failure))

            EstablishNarmestelederrelasjonResult.PersonNotFound -> raise(FulfillNarmestelederbehovError.PersonNotFound)

            EstablishNarmestelederrelasjonResult.ManagerNameMismatch -> raise(FulfillNarmestelederbehovError.ManagerNameMismatch)

            EstablishNarmestelederrelasjonResult.EmployeeNameMismatch ->
                error("Fulfillment does not submit an employee last name")
        }
        val marked = when (val result = behovRepository.markFulfilled(behov.id)) {
            is MarkFulfilledResult.Marked -> result
            MarkFulfilledResult.Missing -> raise(FulfillNarmestelederbehovError.BehovMissingAfterPublication)
        }
        FulfilledNarmestelederbehov(
            relationSource = relationSource,
            dialogCompletion = completeDialog(marked),
        )
    }.onLeft { error ->
        if (error !is FulfillNarmestelederbehovError.UpstreamUnavailable) {
            logger.event(fulfillmentRejected, error)
        }
    }.onRight { logger.event(fulfillmentCompleted, it) }

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
