package no.nav.syfo.narmestelederrelasjon.application

import no.nav.syfo.narmestelederrelasjon.domain.ManagerLastNameMatch
import no.nav.syfo.narmestelederrelasjon.domain.RelationManager
import no.nav.syfo.narmestelederrelasjon.domain.RelationPerson
import no.nav.syfo.narmestelederrelasjon.domain.matchManagerLastName
import no.nav.syfo.platform.application.Step
import no.nav.syfo.platform.application.orStop

class EstablishNarmestelederrelasjonUseCase(
    private val activeSykmeldingLookup: ActiveSykmeldingLookup,
    private val employmentLookup: EmploymentLookup,
    private val personLookup: PersonLookup,
    private val nameValidationMetrics: ManagerNameValidationMetrics,
    private val relationPublisher: PublishNarmestelederrelasjon,
) : EstablishNarmestelederrelasjon {
    override suspend fun execute(command: EstablishNarmestelederrelasjonCommand): EstablishNarmestelederrelasjonResult {
        verifyActiveSykmelding(command).orStop { return it }
        verifyEmployment(command).orStop { return it }
        val employeeAndManager = findEmployeeAndManager(command).orStop { return it }
        val managerNameMatch = verifyManagerName(employeeAndManager.manager, command).orStop { return it }
        relationPublisher.publish(
            PublishNarmestelederrelasjonCommand(
                employee = RelationPerson(
                    personIdent = employeeAndManager.employee.personIdent,
                    firstName = employeeAndManager.employee.name.firstName,
                    middleName = employeeAndManager.employee.name.middleName,
                    lastName = employeeAndManager.employee.name.lastName,
                ),
                manager = RelationManager(
                    personIdent = command.manager.personIdent,
                    firstName = employeeAndManager.manager.name.firstName,
                    middleName = employeeAndManager.manager.name.middleName,
                    lastName = employeeAndManager.manager.name.lastName,
                    email = command.manager.email.value,
                    mobile = command.manager.mobile.value,
                ),
                organizationNumber = command.organizationNumber,
                source = command.source,
            ),
        )
        return EstablishNarmestelederrelasjonResult.Published(managerNameMatch)
    }

    private suspend fun verifyActiveSykmelding(command: EstablishNarmestelederrelasjonCommand): EstablishStep<Unit> = if (activeSykmeldingLookup.hasActiveSykmelding(command.employeeIdent, command.organizationNumber)) {
        Step.Proceed
    } else {
        Step.Stop(EstablishNarmestelederrelasjonResult.NoActiveSykmelding(command.organizationNumber))
    }

    private suspend fun verifyEmployment(command: EstablishNarmestelederrelasjonCommand): EstablishStep<Unit> = when (val employment = employmentLookup.findEmployment(command.employeeIdent, command.organizationNumber)) {
        EmploymentResult.IN_ORGANIZATION -> Step.Proceed
        EmploymentResult.NONE, EmploymentResult.NOT_IN_ORGANIZATION ->
            Step.Stop(EstablishNarmestelederrelasjonResult.NoEmployment(employment))
    }

    private suspend fun findEmployeeAndManager(command: EstablishNarmestelederrelasjonCommand): EstablishStep<EmployeeAndManager> {
        val employee = personLookup.find(command.employeeIdent)
            ?: return Step.Stop(EstablishNarmestelederrelasjonResult.PersonNotFound)
        val manager = personLookup.find(command.manager.personIdent)
            ?: return Step.Stop(EstablishNarmestelederrelasjonResult.PersonNotFound)
        return Step.Continue(EmployeeAndManager(employee, manager))
    }

    private fun verifyManagerName(manager: PersonDetails, command: EstablishNarmestelederrelasjonCommand): EstablishStep<ManagerLastNameMatch> {
        val match = manager.name.matchManagerLastName(command.manager.lastName)
        nameValidationMetrics.record(match)
        return when (match) {
            is ManagerLastNameMatch.NoMatch -> Step.Stop(EstablishNarmestelederrelasjonResult.ManagerNameMismatch(match))
            else -> Step.Continue(match)
        }
    }
}

private data class EmployeeAndManager(val employee: PersonDetails, val manager: PersonDetails)

private typealias EstablishStep<T> = Step<T, EstablishNarmestelederrelasjonResult>
