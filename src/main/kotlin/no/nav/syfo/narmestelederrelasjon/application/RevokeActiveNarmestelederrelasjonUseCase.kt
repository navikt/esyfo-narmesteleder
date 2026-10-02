package no.nav.syfo.narmestelederrelasjon.application

import no.nav.syfo.narmestelederrelasjon.domain.ManagerLastNameMatch
import no.nav.syfo.narmestelederrelasjon.domain.matchManagerLastName
import no.nav.syfo.organisasjonstilgang.application.OrganizationAccess
import no.nav.syfo.organisasjonstilgang.application.OrganizationAccessResult
import no.nav.syfo.organisasjonstilgang.application.OrganizationAccessSubject
import no.nav.syfo.platform.application.Step
import no.nav.syfo.platform.application.orStop

class RevokeActiveNarmestelederrelasjonUseCase(
    private val organizationAccess: OrganizationAccess,
    private val personLookup: PersonLookup,
    private val nameValidationMetrics: ManagerNameValidationMetrics,
    private val activeRelations: ActiveNarmestelederrelasjonRepository,
    private val publisher: PublishNarmestelederrelasjonRevocation,
) {
    suspend fun execute(command: RevokeActiveNarmestelederrelasjonCommand): RevokeActiveNarmestelederrelasjonResult {
        verifyAccess(command).orStop { return it }
        val employee = findEmployee(command).orStop { return it }
        verifyName(employee, command).orStop { return it }
        if (activeRelations.findActive(employee.personIdent, command.organizationNumber).isEmpty()) {
            return RevokeActiveNarmestelederrelasjonResult.NoActiveRelation
        }
        val initiator = when (val subject = command.accessSubject) {
            is OrganizationAccessSubject.LpsSystemUser -> RevocationInitiator.LPS
            is OrganizationAccessSubject.PersonnelManager ->
                if (subject.personIdent == employee.personIdent) RevocationInitiator.EMPLOYEE else RevocationInitiator.PERSONNEL_MANAGER
        }
        publisher.publish(PublishNarmestelederrelasjonRevocationCommand(employee.personIdent, command.organizationNumber, initiator))
        return RevokeActiveNarmestelederrelasjonResult.Revoked(initiator)
    }

    private suspend fun verifyAccess(command: RevokeActiveNarmestelederrelasjonCommand): RevokeActiveStep<Unit> = when (val access = organizationAccess.evaluate(command.accessSubject, command.organizationNumber)) {
        OrganizationAccessResult.Granted -> Step.Proceed
        is OrganizationAccessResult.Denied -> Step.Stop(RevokeActiveNarmestelederrelasjonResult.AccessDenied(access.reason, command.organizationNumber))
    }

    private suspend fun findEmployee(command: RevokeActiveNarmestelederrelasjonCommand): RevokeActiveStep<PersonDetails> = personLookup.find(command.employeeIdent)?.let { Step.Continue(it) }
        ?: Step.Stop(RevokeActiveNarmestelederrelasjonResult.EmployeeNotFound)

    private fun verifyName(employee: PersonDetails, command: RevokeActiveNarmestelederrelasjonCommand): RevokeActiveStep<Unit> {
        val match = employee.name.matchManagerLastName(command.employeeLastName)
        nameValidationMetrics.record(match)
        return when (match) {
            is ManagerLastNameMatch.NoMatch -> Step.Stop(RevokeActiveNarmestelederrelasjonResult.EmployeeNameMismatch)
            else -> Step.Proceed
        }
    }
}

private typealias RevokeActiveStep<T> = Step<T, RevokeActiveNarmestelederrelasjonResult>
