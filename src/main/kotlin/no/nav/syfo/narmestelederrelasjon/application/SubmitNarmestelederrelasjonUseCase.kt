package no.nav.syfo.narmestelederrelasjon.application

import no.nav.syfo.ident.OrganizationNumber
import no.nav.syfo.logging.applicationLogger
import no.nav.syfo.logging.logEvent
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

class SubmitNarmestelederrelasjonUseCase(
    private val organizationAccess: OrganizationAccess,
    private val establishRelation: EstablishNarmestelederrelasjon,
) {
    suspend fun execute(command: SubmitNarmestelederrelasjonCommand): SubmitNarmestelederrelasjonResult {
        val manager = normalizeContact(command.manager).orStop { return it }
        verifyAccess(command.accessSubject, command.organizationNumber).orStop { return it }
        val source = when (command.accessSubject) {
            is OrganizationAccessSubject.LpsSystemUser -> RelationSource.LPS
            is OrganizationAccessSubject.PersonnelManager -> RelationSource.PERSONNEL_MANAGER
        }
        return when (
            val result = establishRelation.execute(
                EstablishNarmestelederrelasjonCommand(
                    employeeIdent = command.employeeIdent,
                    organizationNumber = command.organizationNumber,
                    manager = manager,
                    source = source,
                    employeeLastName = command.employeeLastName,
                ),
            )
        ) {
            is EstablishNarmestelederrelasjonResult.Published -> SubmitNarmestelederrelasjonResult.Established(source)
            else -> SubmitNarmestelederrelasjonResult.EstablishRejected(result)
        }
    }

    private fun normalizeContact(input: ManagerContactInput): SubmitStep<NormalizedManagerContact> = when (val result = input.normalize()) {
        is ManagerContactNormalization.Valid -> Step.Continue(result.manager)
        is ManagerContactNormalization.Invalid -> {
            logger.logEvent(contactValidationRejected, ContactValidationRejectedDetails(result.issues))
            Step.Stop(SubmitNarmestelederrelasjonResult.InvalidManagerContactDetails(result.issues))
        }
    }

    private suspend fun verifyAccess(subject: OrganizationAccessSubject, organizationNumber: OrganizationNumber): SubmitStep<Unit> = when (val result = organizationAccess.evaluate(subject, organizationNumber)) {
        is OrganizationAccessResult.Granted -> Step.Proceed
        is OrganizationAccessResult.Denied -> Step.Stop(SubmitNarmestelederrelasjonResult.AccessDenied(result.reason, organizationNumber))
    }

    private companion object {
        val logger = applicationLogger(SubmitNarmestelederrelasjonUseCase::class.java)
    }
}

private typealias SubmitStep<T> = Step<T, SubmitNarmestelederrelasjonResult>
