package no.nav.syfo.narmestelederrelasjon.application

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CancellationException
import no.nav.syfo.ident.OrganizationNumber
import no.nav.syfo.ident.PersonalIdentificationNumber
import no.nav.syfo.narmestelederrelasjon.domain.LastNameMatch
import no.nav.syfo.narmestelederrelasjon.domain.ManagerContactInput
import no.nav.syfo.narmestelederrelasjon.domain.ManagerContactNormalization
import no.nav.syfo.narmestelederrelasjon.domain.PersonNameDetails
import no.nav.syfo.narmestelederrelasjon.domain.RegisteredName
import no.nav.syfo.narmestelederrelasjon.domain.RelationManager
import no.nav.syfo.narmestelederrelasjon.domain.RelationPerson
import no.nav.syfo.narmestelederrelasjon.domain.RelationSource
import no.nav.syfo.narmestelederrelasjon.domain.normalize
import no.nav.syfo.narmestelederrelasjon.infrastructure.CachedPersonLookup
import no.nav.syfo.narmestelederrelasjon.infrastructure.PersonDetailsCache
import no.nav.syfo.platform.upstream.UpstreamError
import no.nav.syfo.platform.upstream.UpstreamFailure
import no.nav.syfo.platform.upstream.UpstreamFailureStage
import no.nav.syfo.platform.upstream.UpstreamName

class EstablishNarmestelederrelasjonUseCaseTest :
    FunSpec({
        test("publishes resolved employee and submitted manager with normalized contact after ordered validations and metric") {
            val effects = mutableListOf<String>()
            val publisher = RecordingPublisher(effects)
            val resolvedEmployee = employee.copy(personIdent = PersonalIdentificationNumber("12121212121"))
            val resolvedManager = manager.copy(personIdent = PersonalIdentificationNumber("34343434343"))
            val useCase = createEstablisher(
                effects = effects,
                people = mapOf(employeeIdent to resolvedEmployee, managerIdent to resolvedManager),
                personIdentifiers = PersonIdentifiersLookup { _, _ ->
                    identifiersResult(PersonIdentifiers(resolvedEmployee.personIdent, listOf(resolvedEmployee.personIdent)))
                },
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

        test("publishes when only a third historical ident has active sick leave") {
            val effects = mutableListOf<String>()
            val publisher = RecordingPublisher(effects)
            val currentIdent = PersonalIdentificationNumber("12121212121")
            val historicalIdent = PersonalIdentificationNumber("13131313131")
            val thirdHistoricalIdent = PersonalIdentificationNumber("14141414141")
            val identifiers = PersonIdentifiers(currentIdent, listOf(currentIdent, historicalIdent, thirdHistoricalIdent))
            val useCase = createEstablisher(
                effects = effects,
                publisher = publisher,
                people = mapOf(
                    employeeIdent to employee.copy(personIdent = currentIdent),
                    managerIdent to manager,
                ),
                personIdentifiers = PersonIdentifiersLookup { _, _ -> identifiersResult(identifiers) },
                activeLookup = { personIdents, _ ->
                    if (thirdHistoricalIdent in personIdents) ActiveSykmeldingResult.Found else ActiveSykmeldingResult.NotFound
                },
            )

            useCase.execute(command()) shouldBe EstablishNarmestelederrelasjonResult.Published
            publisher.command?.employee?.personIdent shouldBe currentIdent
        }

        test("publishes the fresh current ident when cached person details contain the historical ident") {
            val currentIdent = PersonalIdentificationNumber("12121212121")
            val cachedEmployee = employee.copy(personIdent = employeeIdent)
            val personLookup = CachedPersonLookup(
                delegate = PersonLookup { ident ->
                    if (ident == managerIdent) {
                        PersonLookupResult.Found(manager)
                    } else {
                        error("The employee should be resolved from the cache")
                    }
                },
                cache = InMemoryPersonDetailsCache(mapOf(employeeIdent to cachedEmployee)),
            )
            val publisher = RecordingPublisher(mutableListOf())
            val useCase = createEstablisher(
                effects = mutableListOf(),
                publisher = publisher,
                personIdentifiers = PersonIdentifiersLookup { _, _ ->
                    identifiersResult(PersonIdentifiers(currentIdent, listOf(currentIdent, employeeIdent)))
                },
                personLookup = personLookup,
            )

            useCase.execute(command()) shouldBe EstablishNarmestelederrelasjonResult.Published
            publisher.command?.employee?.personIdent shouldBe currentIdent
        }

        test("returns PersonNotFound when PDL cannot resolve the employee identities") {
            val effects = mutableListOf<String>()
            val publisher = RecordingPublisher(effects)
            val useCase = createEstablisher(
                effects = effects,
                publisher = publisher,
                personIdentifiers = PersonIdentifiersLookup { _, _ -> PersonIdentifiersLookupResult.NotFound },
            )

            useCase.execute(command()) shouldBe EstablishNarmestelederrelasjonResult.PersonNotFound
            effects shouldBe emptyList()
            publisher.command shouldBe null
        }

        test("returns UpstreamUnavailable when PDL identity resolution fails") {
            val effects = mutableListOf<String>()
            val publisher = RecordingPublisher(effects)
            val failure = UpstreamFailure(UpstreamName("pdl"), UpstreamFailureStage.RESPONSE, 503, IllegalStateException())
            val useCase = createEstablisher(
                effects = effects,
                publisher = publisher,
                personIdentifiers = PersonIdentifiersLookup { _, _ -> PersonIdentifiersLookupResult.Unavailable(failure) },
            )

            useCase.execute(command()) shouldBe EstablishNarmestelederrelasjonResult.UpstreamUnavailable(failure)
            effects shouldBe emptyList()
            publisher.command shouldBe null
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

        test("employee and manager Unavailable stop before validation or publishing") {
            val failure = UpstreamFailure(UpstreamName("pdl"), UpstreamFailureStage.RESPONSE, 200, errors = listOf(UpstreamError("server_error")))
            listOf(employeeIdent, managerIdent).forEach { unavailableIdent ->
                val effects = mutableListOf<String>()
                val publisher = RecordingPublisher(effects)
                val useCase = createEstablisher(effects, publisher = publisher, unavailable = unavailableIdent to failure)
                useCase.execute(command()) shouldBe EstablishNarmestelederrelasjonResult.UpstreamUnavailable(failure)
                effects shouldBe if (unavailableIdent == employeeIdent) listOf("sykmelding", "employment", "employee") else listOf("sykmelding", "employment", "employee", "manager")
                publisher.command shouldBe null
            }
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

private val employeeIdent = PersonalIdentificationNumber("12345678901")
private val managerIdent = PersonalIdentificationNumber("10987654321")
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
    activeLookup: ((List<PersonalIdentificationNumber>, OrganizationNumber) -> ActiveSykmeldingResult)? = null,
    activeFailure: Throwable? = null,
    employment: EmploymentResult = EmploymentResult.InOrganization,
    people: Map<PersonalIdentificationNumber, PersonDetails> = defaultPeople,
    unavailable: Pair<PersonalIdentificationNumber, UpstreamFailure>? = null,
    personIdentifiers: PersonIdentifiersLookup = PersonIdentifiersLookup { ident, _ ->
        identifiersResult(PersonIdentifiers(ident, listOf(ident)))
    },
    personLookup: PersonLookup? = null,
    metrics: NameValidationMetrics = NameValidationMetrics { effects += "metric" },
    publisher: RecordingPublisher = RecordingPublisher(effects),
): EstablishNarmestelederrelasjonUseCase = EstablishNarmestelederrelasjonUseCase(
    personIdentifiers,
    ActiveSykmeldingLookup { personIdents, organization, _ ->
        effects += "sykmelding"
        activeFailure?.let { throw it }
        activeLookup?.invoke(personIdents, organization) ?: if (active) ActiveSykmeldingResult.Found else ActiveSykmeldingResult.NotFound
    },
    EmploymentLookup { _, _ ->
        effects += "employment"
        employment
    },
    personLookup ?: PersonLookup { ident ->
        effects += if (ident == employeeIdent) "employee" else "manager"
        if (ident == unavailable?.first) PersonLookupResult.Unavailable(unavailable.second) else people[ident]?.let { PersonLookupResult.Found(it) } ?: PersonLookupResult.NotFound
    },
    metrics,
    publisher,
)

private fun identifiersResult(identifiers: PersonIdentifiers) = PersonIdentifiersLookupResult.Found(identifiers)

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

private class InMemoryPersonDetailsCache(
    initial: Map<PersonalIdentificationNumber, PersonDetails>,
) : PersonDetailsCache {
    private val entries = initial.toMutableMap()

    override fun get(personIdent: PersonalIdentificationNumber): PersonDetails? = entries[personIdent]

    override fun put(personIdent: PersonalIdentificationNumber, personDetails: PersonDetails) {
        entries[personIdent] = personDetails
    }
}

private data class RejectionCase(
    val active: Boolean,
    val employment: EmploymentResult,
    val people: Map<PersonalIdentificationNumber, PersonDetails>,
    val expected: EstablishNarmestelederrelasjonResult,
    val effects: List<String>,
)
