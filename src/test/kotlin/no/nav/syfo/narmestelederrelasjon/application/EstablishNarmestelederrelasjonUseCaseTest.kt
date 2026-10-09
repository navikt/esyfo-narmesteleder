package no.nav.syfo.narmestelederrelasjon.application

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CancellationException
import no.nav.syfo.ident.OrganizationNumber
import no.nav.syfo.ident.PersonIdent
import no.nav.syfo.narmestelederrelasjon.domain.LastNameMatch
import no.nav.syfo.narmestelederrelasjon.domain.ManagerContactInput
import no.nav.syfo.narmestelederrelasjon.domain.ManagerContactNormalization
import no.nav.syfo.narmestelederrelasjon.domain.PersonNameDetails
import no.nav.syfo.narmestelederrelasjon.domain.RegisteredName
import no.nav.syfo.narmestelederrelasjon.domain.RelationManager
import no.nav.syfo.narmestelederrelasjon.domain.RelationPerson
import no.nav.syfo.narmestelederrelasjon.domain.RelationSource
import no.nav.syfo.narmestelederrelasjon.domain.normalize
import no.nav.syfo.platform.upstream.UpstreamFailure
import no.nav.syfo.platform.upstream.UpstreamFailureStage
import no.nav.syfo.platform.upstream.UpstreamName

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

            useCase.execute(command()) shouldBe EstablishNarmestelederrelasjonResult.Published
            effects shouldBe listOf("sykmelding", "employment", "employee", "manager", "metric", "publish")
            publisher.command shouldBe PublishNarmestelederrelasjonCommand(
                employee = RelationPerson(resolvedEmployee.personIdent, "Employee", "EmployeeMiddle", "Employee"),
                manager = RelationManager(managerIdent, "Manager", "ManagerMiddle", "Manager", "manager@example.test", "+4799999999"),
                organizationNumber = organizationNumber,
                source = RelationSource.PERSONNEL_MANAGER,
            )
        }

        test("rejects without publishing at the first failing relation check") {
            val mismatchingManager = manager.copy(
                name = manager.name.copy(lastName = "Different", registeredNames = listOf(RegisteredName("Different"))),
            )
            listOf(
                RejectionCase(false, EmploymentResult.InOrganization, defaultPeople, EstablishNarmestelederrelasjonResult.NoActiveSykmelding(organizationNumber), listOf("sykmelding")),
                RejectionCase(true, EmploymentResult.None, defaultPeople, EstablishNarmestelederrelasjonResult.NoEmployment(EmploymentResult.None), listOf("sykmelding", "employment")),
                RejectionCase(true, EmploymentResult.NotInOrganization, defaultPeople, EstablishNarmestelederrelasjonResult.NoEmployment(EmploymentResult.NotInOrganization), listOf("sykmelding", "employment")),
                RejectionCase(true, EmploymentResult.InOrganization, mapOf(managerIdent to manager), EstablishNarmestelederrelasjonResult.PersonNotFound, listOf("sykmelding", "employment", "employee")),
                RejectionCase(true, EmploymentResult.InOrganization, mapOf(employeeIdent to employee), EstablishNarmestelederrelasjonResult.PersonNotFound, listOf("sykmelding", "employment", "employee", "manager")),
                RejectionCase(true, EmploymentResult.InOrganization, mapOf(employeeIdent to employee, managerIdent to mismatchingManager), EstablishNarmestelederrelasjonResult.ManagerNameMismatch, listOf("sykmelding", "employment", "employee", "manager", "metric")),
            ).forEach { case ->
                val effects = mutableListOf<String>()
                val publisher = RecordingPublisher(effects)
                val recordedMatches = mutableListOf<LastNameMatch>()
                val useCase = createEstablisher(
                    effects = effects,
                    active = case.active,
                    employment = case.employment,
                    people = case.people,
                    metrics = NameValidationMetrics { match ->
                        effects += "metric"
                        recordedMatches += match
                    },
                    publisher = publisher,
                )
                val result = useCase.execute(command())
                result shouldBe case.expected
                if (case.expected == EstablishNarmestelederrelasjonResult.ManagerNameMismatch) {
                    recordedMatches.single().shouldBeInstanceOf<LastNameMatch.NoMatch>()
                } else {
                    recordedMatches shouldBe emptyList()
                }
                effects shouldBe case.effects
                publisher.command shouldBe null
            }
        }

        test("returns upstream unavailable before person lookup or publication") {
            val effects = mutableListOf<String>()
            val publisher = RecordingPublisher(effects)
            val failure = UpstreamFailure(UpstreamName("aareg"), UpstreamFailureStage.RESPONSE, 503, IllegalStateException())
            val useCase = createEstablisher(
                effects = effects,
                employment = EmploymentResult.Unavailable(failure),
                publisher = publisher,
            )

            useCase.execute(command()) shouldBe EstablishNarmestelederrelasjonResult.UpstreamUnavailable(failure)
            effects shouldBe listOf("sykmelding", "employment")
            publisher.command shouldBe null
        }

        test("records a name match before a publisher failure and propagates it") {
            val effects = mutableListOf<String>()
            val failure = IllegalStateException("publisher unavailable")
            val publisher = RecordingPublisher(effects, failure)
            val recordedMatches = mutableListOf<LastNameMatch>()
            val useCase = createEstablisher(
                effects = effects,
                publisher = publisher,
                metrics = NameValidationMetrics {
                    effects += "metric"
                    recordedMatches += it
                },
            )

            shouldThrow<IllegalStateException> { useCase.execute(command()) } shouldBe failure
            effects shouldBe listOf("sykmelding", "employment", "employee", "manager", "metric", "publish")
            recordedMatches shouldBe listOf(LastNameMatch.Exact(false))
        }

        test("propagates cancellation before later effects") {
            val effects = mutableListOf<String>()
            val failure = CancellationException("cancelled")
            val useCase = createEstablisher(effects = effects, activeFailure = failure)

            shouldThrow<CancellationException> { useCase.execute(command()) } shouldBe failure
            effects shouldBe listOf("sykmelding")
        }

        test("publishes after a matching submitted employee last name and records both name matches") {
            val effects = mutableListOf<String>()
            val publisher = RecordingPublisher(effects)
            val recordedMatches = mutableListOf<LastNameMatch>()
            val useCase = createEstablisher(
                effects = effects,
                publisher = publisher,
                metrics = NameValidationMetrics {
                    effects += "metric"
                    recordedMatches += it
                },
            )

            useCase.execute(command(employeeLastName = "employee")) shouldBe EstablishNarmestelederrelasjonResult.Published
            effects shouldBe listOf("sykmelding", "employment", "employee", "manager", "metric", "metric", "publish")
            recordedMatches shouldBe listOf(LastNameMatch.Exact(false), LastNameMatch.Exact(false))
        }

        test("rejects a mismatching submitted employee last name after the manager name check without publishing") {
            val effects = mutableListOf<String>()
            val publisher = RecordingPublisher(effects)
            val recordedMatches = mutableListOf<LastNameMatch>()
            val useCase = createEstablisher(
                effects = effects,
                publisher = publisher,
                metrics = NameValidationMetrics {
                    effects += "metric"
                    recordedMatches += it
                },
            )

            val result = useCase.execute(command(employeeLastName = "Different"))

            result shouldBe EstablishNarmestelederrelasjonResult.EmployeeNameMismatch
            recordedMatches.size shouldBe 2
            recordedMatches.last().shouldBeInstanceOf<LastNameMatch.NoMatch>()
            recordedMatches.first() shouldBe LastNameMatch.Exact(false)
            effects shouldBe listOf("sykmelding", "employment", "employee", "manager", "metric", "metric")
            publisher.command shouldBe null
        }

        test("reports a manager name mismatch before checking the submitted employee last name") {
            val effects = mutableListOf<String>()
            val mismatchingManager = manager.copy(
                name = manager.name.copy(lastName = "Different", registeredNames = listOf(RegisteredName("Different"))),
            )
            val useCase = createEstablisher(
                effects = effects,
                people = mapOf(employeeIdent to employee, managerIdent to mismatchingManager),
            )

            val result = useCase.execute(command(employeeLastName = "Different"))

            result.shouldBeInstanceOf<EstablishNarmestelederrelasjonResult.ManagerNameMismatch>()
            effects shouldBe listOf("sykmelding", "employment", "employee", "manager", "metric")
        }
    })

private val employeeIdent = PersonIdent("12345678901")
private val managerIdent = PersonIdent("10987654321")
private val organizationNumber = OrganizationNumber("123456789")
private val employee = PersonDetails(employeeIdent, PersonNameDetails("Employee", "Employee", "EmployeeMiddle", listOf(RegisteredName("Employee"))))
private val manager = PersonDetails(managerIdent, PersonNameDetails("Manager", "Manager", "ManagerMiddle", listOf(RegisteredName("Manager"))))
private val defaultPeople = mapOf(employeeIdent to employee, managerIdent to manager)

private fun command(employeeLastName: String? = null): EstablishNarmestelederrelasjonCommand {
    val normalized = ManagerContactInput(managerIdent, "Manager", " manager@example.test ", "+47 99999999").normalize()
    return EstablishNarmestelederrelasjonCommand(
        employeeIdent,
        organizationNumber,
        (normalized as ManagerContactNormalization.Valid).manager,
        RelationSource.PERSONNEL_MANAGER,
        employeeLastName,
    )
}

private fun createEstablisher(
    effects: MutableList<String>,
    active: Boolean = true,
    activeFailure: Throwable? = null,
    employment: EmploymentResult = EmploymentResult.InOrganization,
    people: Map<PersonIdent, PersonDetails> = defaultPeople,
    metrics: NameValidationMetrics = NameValidationMetrics { effects += "metric" },
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
