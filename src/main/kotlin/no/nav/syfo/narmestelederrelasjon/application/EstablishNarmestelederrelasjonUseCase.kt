package no.nav.syfo.narmestelederrelasjon.application

import no.nav.syfo.ident.PersonalIdentificationNumber
import no.nav.syfo.narmestelederrelasjon.domain.LastNameMatch
import no.nav.syfo.narmestelederrelasjon.domain.RelationManager
import no.nav.syfo.narmestelederrelasjon.domain.RelationPerson
import no.nav.syfo.narmestelederrelasjon.domain.matchLastName
import no.nav.syfo.platform.application.Step
import no.nav.syfo.platform.application.orStop

class EstablishNarmestelederrelasjonUseCase(
    private val personIdentifiersLookup: PersonIdentifiersLookup,
    private val activeSykmeldingLookup: ActiveSykmeldingLookup,
    private val employmentLookup: EmploymentLookup,
    private val personLookup: PersonLookup,
    private val nameValidationMetrics: NameValidationMetrics,
    private val relationPublisher: PublishNarmestelederrelasjon,
) : EstablishNarmestelederrelasjon {
    override suspend fun execute(command: EstablishNarmestelederrelasjonCommand): EstablishNarmestelederrelasjonResult {
        val identifiers = findPersonIdentifiers(command.employeeIdent).orStop { return it }
        verifyActiveSykmelding(command, identifiers).orStop { return it }
        verifyEmployment(command).orStop { return it }
        val employeeAndManager = findEmployeeAndManager(command).orStop { return it }
        verifyManagerName(employeeAndManager.manager, command).orStop { return it }
        verifyEmployeeName(employeeAndManager.employee, command).orStop { return it }
        relationPublisher.publish(
            PublishNarmestelederrelasjonCommand(
                employee = RelationPerson(
                    personIdent = identifiers.current,
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
        return EstablishNarmestelederrelasjonResult.Published
    }

    private suspend fun findPersonIdentifiers(personIdent: PersonalIdentificationNumber): EstablishStep<PersonIdentifiers> = when (
        val result = personIdentifiersLookup.find(personIdent, PersonIdentifiersLookupOperation.ESTABLISH_RELATION)
    ) {
        is PersonIdentifiersLookupResult.Found -> Step.Continue(result.identifiers)
        PersonIdentifiersLookupResult.NotFound -> Step.Stop(EstablishNarmestelederrelasjonResult.PersonNotFound)
        is PersonIdentifiersLookupResult.Unavailable -> Step.Stop(EstablishNarmestelederrelasjonResult.UpstreamUnavailable(result.failure))
    }

    private suspend fun verifyActiveSykmelding(
        command: EstablishNarmestelederrelasjonCommand,
        identifiers: PersonIdentifiers,
    ): EstablishStep<Unit> = when (
        val result = activeSykmeldingLookup.findActiveSykmelding(
            personIdents = identifiers.all,
            organizationNumber = command.organizationNumber,
            operation = ActiveSykmeldingOperation.ESTABLISH_RELATION,
        )
    ) {
        ActiveSykmeldingResult.Found -> Step.Proceed
        ActiveSykmeldingResult.NotFound -> Step.Stop(EstablishNarmestelederrelasjonResult.NoActiveSykmelding(command.organizationNumber))
        is ActiveSykmeldingResult.Unavailable ->
            Step.Stop(EstablishNarmestelederrelasjonResult.UpstreamUnavailable(result.failure))
    }

    private suspend fun verifyEmployment(command: EstablishNarmestelederrelasjonCommand): EstablishStep<Unit> = when (val employment = employmentLookup.findEmployment(command.employeeIdent, command.organizationNumber)) {
        EmploymentResult.InOrganization -> Step.Proceed
        is EmploymentResult.Missing ->
            Step.Stop(EstablishNarmestelederrelasjonResult.NoEmployment(employment))
        is EmploymentResult.Unavailable ->
            Step.Stop(EstablishNarmestelederrelasjonResult.UpstreamUnavailable(employment.failure))
    }

    private suspend fun findEmployeeAndManager(command: EstablishNarmestelederrelasjonCommand): EstablishStep<EmployeeAndManager> {
        val employee = findPerson(command.employeeIdent).orStop { return Step.Stop(it) }
        val manager = findPerson(command.manager.personIdent).orStop { return Step.Stop(it) }
        return Step.Continue(EmployeeAndManager(employee, manager))
    }

    private suspend fun findPerson(ident: PersonalIdentificationNumber): EstablishStep<PersonDetails> = when (val result = personLookup.find(ident)) {
        is PersonLookupResult.Found -> Step.Continue(result.person)
        PersonLookupResult.NotFound -> Step.Stop(EstablishNarmestelederrelasjonResult.PersonNotFound)
        is PersonLookupResult.Unavailable -> Step.Stop(EstablishNarmestelederrelasjonResult.UpstreamUnavailable(result.failure))
    }

    private fun verifyManagerName(manager: PersonDetails, command: EstablishNarmestelederrelasjonCommand): EstablishStep<Unit> {
        val match = manager.name.matchLastName(command.manager.lastName)
        nameValidationMetrics.record(match)
        return when (match) {
            is LastNameMatch.NoMatch -> Step.Stop(EstablishNarmestelederrelasjonResult.ManagerNameMismatch)
            else -> Step.Proceed
        }
    }

    private fun verifyEmployeeName(employee: PersonDetails, command: EstablishNarmestelederrelasjonCommand): EstablishStep<Unit> {
        val lastName = command.employeeLastName ?: return Step.Proceed
        val match = employee.name.matchLastName(lastName)
        nameValidationMetrics.record(match)
        return when (match) {
            is LastNameMatch.NoMatch -> Step.Stop(EstablishNarmestelederrelasjonResult.EmployeeNameMismatch)
            else -> Step.Proceed
        }
    }
}

private data class EmployeeAndManager(val employee: PersonDetails, val manager: PersonDetails)

private typealias EstablishStep<T> = Step<T, EstablishNarmestelederrelasjonResult>
