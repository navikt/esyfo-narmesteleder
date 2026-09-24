package no.nav.syfo.narmestelederbehov.application

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CancellationException
import no.nav.syfo.ident.OrganizationNumber
import no.nav.syfo.ident.PersonIdent
import no.nav.syfo.narmestelederbehov.domain.Employee
import no.nav.syfo.narmestelederbehov.domain.ManagerContactField
import no.nav.syfo.narmestelederbehov.domain.ManagerContactInput
import no.nav.syfo.narmestelederbehov.domain.ManagerContactValidationIssue
import no.nav.syfo.narmestelederbehov.domain.ManagerContactValidationReason
import no.nav.syfo.narmestelederbehov.domain.ManagerLastNameMatch
import no.nav.syfo.narmestelederbehov.domain.Narmestelederbehov
import no.nav.syfo.narmestelederbehov.domain.NarmestelederbehovId
import no.nav.syfo.narmestelederbehov.domain.PersonNameDetails
import no.nav.syfo.narmestelederbehov.domain.RegisteredName
import no.nav.syfo.narmestelederrelasjon.domain.RelationSource
import no.nav.syfo.organisasjonstilgang.application.DenialReason
import no.nav.syfo.organisasjonstilgang.application.OrganizationAccessResult
import no.nav.syfo.organisasjonstilgang.application.OrganizationAccessSubject
import java.util.UUID

class FulfillNarmestelederbehovUseCaseTest :
    FunSpec({
        test("fulfills in preserved side-effect order and retains normalized contact and middle names") {
            val effects = mutableListOf<String>()
            val relation = FakeRelationEstablisher(effects)

            createUseCase(relation = relation, effects = effects).execute(command()) shouldBe fulfilledResult()

            effects shouldBe listOf(
                "load", "access", "sykmelding", "employment", "person:${employeeIdent.value}",
                "person:${managerIdent.value}", "metric", "establish", "fulfilled", "dialog",
            )
            requireNotNull(relation.command).manager.let {
                it.email shouldBe "manager@example.test"
                it.mobile shouldBe "+4799999999"
                it.middleName shouldBe "ManagerMiddle"
                it.firstName shouldBe "Manager"
            }
            requireNotNull(relation.command).employee.let {
                it.middleName shouldBe "EmployeeMiddle"
                it.firstName shouldBe "Employee"
            }
            requireNotNull(relation.command).source shouldBe RelationSource.LPS
        }

        test("derives personnel manager relation source") {
            val relation = FakeRelationEstablisher()

            createUseCase(relation = relation).execute(command(accessSubject = personnelManager)) shouldBe fulfilledResult(
                relationSource = RelationSource.PERSONNEL_MANAGER,
            )

            requireNotNull(relation.command).source shouldBe RelationSource.PERSONNEL_MANAGER
        }

        test("publishes the resolved employee ident and submitted manager ident") {
            val relation = FakeRelationEstablisher()
            val resolvedEmployeeIdent = PersonIdent("12121212121")
            val resolvedManagerIdent = PersonIdent("34343434343")

            createUseCase(
                personLookup = FakePersonLookup(
                    mapOf(
                        employeeIdent to employee.copy(personIdent = resolvedEmployeeIdent),
                        managerIdent to manager.copy(personIdent = resolvedManagerIdent),
                    ),
                ),
                relation = relation,
            ).execute(command()) shouldBe fulfilledResult()

            requireNotNull(relation.command).employee.personIdent shouldBe resolvedEmployeeIdent
            requireNotNull(relation.command).manager.personIdent shouldBe managerIdent
        }

        test("returns combined invalid contact issues without loading the behov") {
            val effects = mutableListOf<String>()

            createUseCase(effects = effects).execute(command(email = "invalid", mobile = "+47-99999999")) shouldBe
                FulfillNarmestelederbehovResult.InvalidManagerContactDetails(
                    listOf(
                        ManagerContactValidationIssue(
                            ManagerContactField.MOBILE,
                            ManagerContactValidationReason.PHONE_NUMBER_MUST_CONTAIN_ONLY_DIGITS,
                        ),
                        ManagerContactValidationIssue(
                            ManagerContactField.EMAIL,
                            ManagerContactValidationReason.EMAIL_ADDRESS_MUST_BE_VALID,
                        ),
                    ),
                )

            effects shouldBe emptyList()
        }

        test("returns expected failures before later effects") {
            val cases = listOf(
                Case(
                    result = FulfillNarmestelederbehovResult.NotFound,
                    behovForFulfillment = null,
                    expectedEffects = listOf("load"),
                ),
                Case(
                    result = FulfillNarmestelederbehovResult.AccessDenied(DenialReason.MISSING_ORGANIZATION_ACCESS, organizationNumber),
                    accessResult = OrganizationAccessResult.Denied(DenialReason.MISSING_ORGANIZATION_ACCESS),
                    expectedEffects = listOf("load", "access"),
                ),
                Case(
                    result = FulfillNarmestelederbehovResult.NoActiveSykmelding(organizationNumber),
                    hasActiveSykmelding = false,
                    expectedEffects = listOf("load", "access", "sykmelding"),
                ),
                Case(
                    result = FulfillNarmestelederbehovResult.NoEmployment(EmploymentResult.NONE),
                    employment = EmploymentResult.NONE,
                    expectedEffects = listOf("load", "access", "sykmelding", "employment"),
                ),
                Case(
                    result = FulfillNarmestelederbehovResult.PersonNotFound,
                    people = mapOf(managerIdent to manager),
                    expectedEffects = listOf(
                        "load",
                        "access",
                        "sykmelding",
                        "employment",
                        "person:${employeeIdent.value}",
                    ),
                ),
                Case(
                    result = FulfillNarmestelederbehovResult.PersonNotFound,
                    people = mapOf(employeeIdent to employee),
                    expectedEffects = listOf(
                        "load",
                        "access",
                        "sykmelding",
                        "employment",
                        "person:${employeeIdent.value}",
                        "person:${managerIdent.value}",
                    ),
                ),
                Case(
                    result = FulfillNarmestelederbehovResult.ManagerNameMismatch(
                        ManagerLastNameMatch.NoMatch(0.0, hasParallelNames = false),
                    ),
                    people = mapOf(
                        employeeIdent to employee,
                        managerIdent to manager.copy(
                            name = manager.name.copy(
                                lastName = "Zzzzzz",
                                registeredNames = listOf(RegisteredName("Zzzzzz")),
                            ),
                        ),
                    ),
                    expectedEffects = listOf(
                        "load",
                        "access",
                        "sykmelding",
                        "employment",
                        "person:${employeeIdent.value}",
                        "person:${managerIdent.value}",
                        "metric",
                    ),
                ),
            )

            cases.forEach { case ->
                val effects = mutableListOf<String>()
                createUseCase(
                    repository = FakeBehovRepository(case.behovForFulfillment, effects),
                    access = FakeOrganizationAccess(case.accessResult, effects),
                    sykmelding = FakeActiveSykmeldingLookup(case.hasActiveSykmelding, effects = effects),
                    employment = FakeEmploymentLookup(case.employment, effects),
                    personLookup = FakePersonLookup(case.people, effects),
                    effects = effects,
                ).execute(command()) shouldBe case.result

                effects shouldBe case.expectedEffects
            }
        }

        test("keeps fulfillment successful when Dialogporten completion fails") {
            val effects = mutableListOf<String>()

            createUseCase(
                dialog = FakeDialog(DialogportenCompletionAttempt.Failed, effects),
                effects = effects,
            ).execute(command()) shouldBe fulfilledResult(dialogCompletion = DialogportenCompletionAttempt.Failed)

            effects.takeLast(3) shouldBe listOf("establish", "fulfilled", "dialog")
        }

        test("records the name outcome even when relation publication fails") {
            val effects = mutableListOf<String>()
            val failure = IllegalStateException("publisher unavailable")
            val useCase = createUseCase(
                relation = FakeRelationEstablisher(effects, failure),
                effects = effects,
            )

            shouldThrow<IllegalStateException> { useCase.execute(command()) } shouldBe failure
            effects.takeLast(2) shouldBe listOf("metric", "establish")
        }

        test("records a rejected name before returning mismatch") {
            val effects = mutableListOf<String>()
            val people = mapOf(
                employeeIdent to employee,
                managerIdent to manager.copy(
                    name = manager.name.copy(
                        lastName = "Different",
                        registeredNames = listOf(RegisteredName("Different")),
                    )
                ),
            )
            val result = createUseCase(personLookup = FakePersonLookup(people, effects), effects = effects).execute(command())

            (result is FulfillNarmestelederbehovResult.ManagerNameMismatch) shouldBe true
            effects.last() shouldBe "metric"
        }

        listOf("establish", "fulfilled", "dialog").forEach { failingEffect ->
            listOf(IllegalStateException("upstream failed"), CancellationException("cancelled")).forEach { failure ->
                test("propagates ${failure::class.simpleName} at $failingEffect without later effects") {
                    val effects = mutableListOf<String>()
                    val useCase = createUseCase(
                        repository = FakeBehovRepository(behov, effects, failure.takeIf { failingEffect == "fulfilled" }),
                        relation = FakeRelationEstablisher(effects, failure.takeIf { failingEffect == "establish" }),
                        dialog = FakeDialog(effects = effects, failure = failure.takeIf { failingEffect == "dialog" }),
                        effects = effects,
                    )

                    shouldThrow<Exception> { useCase.execute(command()) } shouldBe failure

                    val allEffects = listOf(
                        "load", "access", "sykmelding", "employment",
                        "person:${employeeIdent.value}", "person:${managerIdent.value}", "metric",
                        "establish", "fulfilled", "dialog",
                    )
                    effects shouldBe allEffects.take(allEffects.indexOf(failingEffect) + 1)
                }
            }
        }

        test("fakes record effects without explicit effect-list attachment") {
            FakeBehovRepository(behov).findForFulfillment(behovId) shouldBe behov
            FakeOrganizationAccess().evaluate(lpsSystemUser, organizationNumber) shouldBe OrganizationAccessResult.Granted
            FakeActiveSykmeldingLookup().hasActiveSykmelding(employeeIdent, organizationNumber) shouldBe true
            FakeEmploymentLookup().findEmployment(employeeIdent, organizationNumber) shouldBe EmploymentResult.IN_ORGANIZATION
            FakePersonLookup(mapOf(employeeIdent to employee)).find(employeeIdent) shouldBe employee
            FakeRelationEstablisher().establish(
                no.nav.syfo.narmestelederrelasjon.application.EstablishNarmestelederrelasjonCommand(
                    employee = no.nav.syfo.narmestelederrelasjon.domain.RelationPerson(
                        employeeIdent,
                        "Employee",
                        "EmployeeMiddle",
                        "Employee",
                    ),
                    manager = no.nav.syfo.narmestelederrelasjon.domain.RelationManager(
                        managerIdent,
                        "Manager",
                        "ManagerMiddle",
                        "Manager",
                        "email@example.test",
                        "+4799999999",
                    ),
                    organizationNumber = organizationNumber,
                    source = RelationSource.LPS,
                ),
            )
            FakeDialog().attemptCompletion(behovId) shouldBe DialogportenCompletionAttempt.Completed
        }

        test("propagates cancellation without later effects") {
            val effects = mutableListOf<String>()
            val useCase = createUseCase(
                sykmelding = FakeActiveSykmeldingLookup(
                    failure = CancellationException("cancelled"),
                    effects = effects,
                ),
                effects = effects,
            )

            shouldThrow<CancellationException> { useCase.execute(command()) }

            effects shouldBe listOf("load", "access", "sykmelding")
        }
    })

internal val employeeIdent = PersonIdent("12345678901")
internal val managerIdent = PersonIdent("10987654321")
internal val organizationNumber = OrganizationNumber("123456789")
internal val behovId = NarmestelederbehovId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
internal val behov = Narmestelederbehov(behovId, Employee(employeeIdent, organizationNumber))
internal val employee = PersonDetails(
    employeeIdent,
    PersonNameDetails(
        firstName = "Employee",
        lastName = "Employee",
        middleName = "EmployeeMiddle",
        registeredNames = listOf(RegisteredName("Employee")),
    )
)
internal val manager = PersonDetails(
    managerIdent,
    PersonNameDetails(
        firstName = "Manager",
        lastName = "Manager",
        middleName = "ManagerMiddle",
        registeredNames = listOf(RegisteredName("Manager")),
    )
)
internal val personnelManager = OrganizationAccessSubject.PersonnelManager(
    personIdent = PersonIdent("11223344556"),
    accessToken = no.nav.syfo.organisasjonstilgang.application.AccessToken("test-token"),
)
internal val lpsSystemUser = OrganizationAccessSubject.LpsSystemUser(
    systemUserId = "system-user",
    systemUserOrganizationNumber = organizationNumber,
)

internal fun command(
    email: String = " manager@example.test ",
    mobile: String = "+47 99999999",
    accessSubject: OrganizationAccessSubject = lpsSystemUser,
) = FulfillNarmestelederbehovCommand(
    behovId = behovId,
    manager = ManagerContactInput(managerIdent, "Manager", email, mobile),
    accessSubject = accessSubject,
)

internal fun fulfilledResult(
    relationSource: RelationSource = RelationSource.LPS,
    dialogCompletion: DialogportenCompletionAttempt = DialogportenCompletionAttempt.Completed,
) = FulfillNarmestelederbehovResult.Fulfilled(
    relationSource = relationSource,
    dialogCompletion = dialogCompletion,
    managerNameMatch = ManagerLastNameMatch.Exact(hasParallelNames = false),
)

internal fun createUseCase(
    repository: FakeBehovRepository? = null,
    access: FakeOrganizationAccess? = null,
    sykmelding: FakeActiveSykmeldingLookup? = null,
    employment: FakeEmploymentLookup? = null,
    personLookup: FakePersonLookup? = null,
    relation: FakeRelationEstablisher? = null,
    dialog: FakeDialog? = null,
    metrics: ManagerNameValidationMetrics? = null,
    effects: MutableList<String> = mutableListOf(),
) = FulfillNarmestelederbehovUseCase(
    behovRepository = repository ?: FakeBehovRepository(behov, effects),
    organizationAccess = access ?: FakeOrganizationAccess(effects = effects),
    activeSykmeldingLookup = sykmelding ?: FakeActiveSykmeldingLookup(effects = effects),
    employmentLookup = employment ?: FakeEmploymentLookup(effects = effects),
    personLookup = personLookup ?: FakePersonLookup(mapOf(employeeIdent to employee, managerIdent to manager), effects),
    establishNarmestelederrelasjon = relation ?: FakeRelationEstablisher(effects),
    dialog = dialog ?: FakeDialog(effects = effects),
    nameValidationMetrics = metrics ?: ManagerNameValidationMetrics { effects += "metric" },
)

private data class Case(
    val result: FulfillNarmestelederbehovResult,
    val behovForFulfillment: Narmestelederbehov? = behov,
    val accessResult: OrganizationAccessResult = OrganizationAccessResult.Granted,
    val hasActiveSykmelding: Boolean = true,
    val employment: EmploymentResult = EmploymentResult.IN_ORGANIZATION,
    val people: Map<PersonIdent, PersonDetails> = mapOf(employeeIdent to employee, managerIdent to manager),
    val expectedEffects: List<String>,
)
