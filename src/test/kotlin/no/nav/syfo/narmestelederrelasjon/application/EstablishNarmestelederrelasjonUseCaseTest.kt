package no.nav.syfo.narmestelederrelasjon.application

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CancellationException
import no.nav.syfo.ident.OrganizationNumber
import no.nav.syfo.ident.PersonIdent
import no.nav.syfo.narmestelederrelasjon.domain.ManagerContactInput
import no.nav.syfo.narmestelederrelasjon.domain.ManagerContactNormalization
import no.nav.syfo.narmestelederrelasjon.domain.ManagerLastNameMatch
import no.nav.syfo.narmestelederrelasjon.domain.PersonNameDetails
import no.nav.syfo.narmestelederrelasjon.domain.RegisteredName
import no.nav.syfo.narmestelederrelasjon.domain.RelationManager
import no.nav.syfo.narmestelederrelasjon.domain.RelationPerson
import no.nav.syfo.narmestelederrelasjon.domain.RelationSource
import no.nav.syfo.narmestelederrelasjon.domain.normalize

class EstablishNarmestelederrelasjonUseCaseTest :
    FunSpec({
        test("publishes resolved employee and submitted manager with normalized contact after ordered validations and metric") {
            val effects = mutableListOf<String>()
            val publisher = RecordingPublisher(effects)
            val resolvedEmployee = employee.copy(personIdent = PersonIdent("12121212121"))
            val resolvedManager = manager.copy(personIdent = PersonIdent("34343434343"))
            val useCase = createEstablisher(
                effects = effects,
                people = mapOf(employeeIdent to resolvedEmployee, managerIdent to resolvedManager),
                publisher = publisher,
            )

            useCase.execute(command()) shouldBe EstablishNarmestelederrelasjonResult.Published(ManagerLastNameMatch.Exact(false))
            effects shouldBe listOf("sykmelding", "employment", "employee", "manager", "metric", "publish")
            publisher.command shouldBe PublishNarmestelederrelasjonCommand(
                employee = RelationPerson(resolvedEmployee.personIdent, "Employee", "EmployeeMiddle", "Employee"),
                manager = RelationManager(managerIdent, "Manager", "ManagerMiddle", "Manager", "manager@example.test", "+4799999999"),
                organizationNumber = organizationNumber,
                source = RelationSource.PERSONNEL_MANAGER,
            )
        }

        test("rejects without publishing at the first failing relation check") {
            val mismatch = ManagerLastNameMatch.NoMatch(null, false)
            val mismatchingManager = manager.copy(
                name = manager.name.copy(lastName = "Different", registeredNames = listOf(RegisteredName("Different"))),
            )
            listOf(
                RejectionCase(false, EmploymentResult.IN_ORGANIZATION, defaultPeople, EstablishNarmestelederrelasjonResult.NoActiveSykmelding(organizationNumber), listOf("sykmelding")),
                RejectionCase(true, EmploymentResult.NONE, defaultPeople, EstablishNarmestelederrelasjonResult.NoEmployment(EmploymentResult.NONE), listOf("sykmelding", "employment")),
                RejectionCase(true, EmploymentResult.NOT_IN_ORGANIZATION, defaultPeople, EstablishNarmestelederrelasjonResult.NoEmployment(EmploymentResult.NOT_IN_ORGANIZATION), listOf("sykmelding", "employment")),
                RejectionCase(true, EmploymentResult.IN_ORGANIZATION, mapOf(managerIdent to manager), EstablishNarmestelederrelasjonResult.PersonNotFound, listOf("sykmelding", "employment", "employee")),
                RejectionCase(true, EmploymentResult.IN_ORGANIZATION, mapOf(employeeIdent to employee), EstablishNarmestelederrelasjonResult.PersonNotFound, listOf("sykmelding", "employment", "employee", "manager")),
                RejectionCase(true, EmploymentResult.IN_ORGANIZATION, mapOf(employeeIdent to employee, managerIdent to mismatchingManager), EstablishNarmestelederrelasjonResult.ManagerNameMismatch(mismatch), listOf("sykmelding", "employment", "employee", "manager", "metric")),
            ).forEach { case ->
                val effects = mutableListOf<String>()
                val publisher = RecordingPublisher(effects)
                val recordedMatches = mutableListOf<ManagerLastNameMatch>()
                val useCase = createEstablisher(
                    effects = effects,
                    active = case.active,
                    employment = case.employment,
                    people = case.people,
                    metrics = ManagerNameValidationMetrics { match ->
                        effects += "metric"
                        recordedMatches += match
                    },
                    publisher = publisher,
                )
                val result = useCase.execute(command())
                if (case.expected is EstablishNarmestelederrelasjonResult.ManagerNameMismatch) {
                    result shouldBe EstablishNarmestelederrelasjonResult.ManagerNameMismatch(recordedMatches.single() as ManagerLastNameMatch.NoMatch)
                } else {
                    result shouldBe case.expected
                    recordedMatches shouldBe emptyList()
                }
                effects shouldBe case.effects
                publisher.command shouldBe null
            }
        }

        test("records a name match before a publisher failure and propagates it") {
            val effects = mutableListOf<String>()
            val failure = IllegalStateException("publisher unavailable")
            val publisher = RecordingPublisher(effects, failure)
            val recordedMatches = mutableListOf<ManagerLastNameMatch>()
            val useCase = createEstablisher(
                effects = effects,
                publisher = publisher,
                metrics = ManagerNameValidationMetrics {
                    effects += "metric"
                    recordedMatches += it
                },
            )

            shouldThrow<IllegalStateException> { useCase.execute(command()) } shouldBe failure
            effects shouldBe listOf("sykmelding", "employment", "employee", "manager", "metric", "publish")
            recordedMatches shouldBe listOf(ManagerLastNameMatch.Exact(false))
        }

        test("propagates cancellation before later effects") {
            val effects = mutableListOf<String>()
            val failure = CancellationException("cancelled")
            val useCase = createEstablisher(effects = effects, activeFailure = failure)

            shouldThrow<CancellationException> { useCase.execute(command()) } shouldBe failure
            effects shouldBe listOf("sykmelding")
        }
    })

private val employeeIdent = PersonIdent("12345678901")
private val managerIdent = PersonIdent("10987654321")
private val organizationNumber = OrganizationNumber("123456789")
private val employee = PersonDetails(employeeIdent, PersonNameDetails("Employee", "Employee", "EmployeeMiddle", listOf(RegisteredName("Employee"))))
private val manager = PersonDetails(managerIdent, PersonNameDetails("Manager", "Manager", "ManagerMiddle", listOf(RegisteredName("Manager"))))
private val defaultPeople = mapOf(employeeIdent to employee, managerIdent to manager)

private fun command(): EstablishNarmestelederrelasjonCommand {
    val normalized = ManagerContactInput(managerIdent, "Manager", " manager@example.test ", "+47 99999999").normalize()
    return EstablishNarmestelederrelasjonCommand(
        employeeIdent,
        organizationNumber,
        (normalized as ManagerContactNormalization.Valid).manager,
        RelationSource.PERSONNEL_MANAGER,
    )
}

private fun createEstablisher(
    effects: MutableList<String>,
    active: Boolean = true,
    activeFailure: Throwable? = null,
    employment: EmploymentResult = EmploymentResult.IN_ORGANIZATION,
    people: Map<PersonIdent, PersonDetails> = defaultPeople,
    metrics: ManagerNameValidationMetrics = ManagerNameValidationMetrics { effects += "metric" },
    publisher: RecordingPublisher = RecordingPublisher(effects),
): EstablishNarmestelederrelasjonUseCase = EstablishNarmestelederrelasjonUseCase(
    ActiveSykmeldingLookup { _, _ ->
        effects += "sykmelding"
        activeFailure?.let { throw it }
        active
    },
    EmploymentLookup { _, _ ->
        effects += "employment"
        employment
    },
    PersonLookup { ident ->
        effects += if (ident == employeeIdent) "employee" else "manager"
        people[ident]
    },
    metrics,
    publisher,
)

private class RecordingPublisher(
    private val effects: MutableList<String>,
    private val failure: Throwable? = null,
) : PublishNarmestelederrelasjon {
    var command: PublishNarmestelederrelasjonCommand? = null

    override suspend fun publish(command: PublishNarmestelederrelasjonCommand) {
        effects += "publish"
        failure?.let { throw it }
        this.command = command
    }
}

private data class RejectionCase(
    val active: Boolean,
    val employment: EmploymentResult,
    val people: Map<PersonIdent, PersonDetails>,
    val expected: EstablishNarmestelederrelasjonResult,
    val effects: List<String>,
)
