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
import no.nav.syfo.narmestelederbehov.domain.Narmestelederbehov
import no.nav.syfo.narmestelederbehov.domain.NarmestelederbehovId
import no.nav.syfo.narmestelederbehov.domain.PersonNameDetails
import no.nav.syfo.narmestelederbehov.domain.RegisteredName
import no.nav.syfo.narmestelederrelasjon.application.RelationSource
import no.nav.syfo.organisasjonstilgang.application.OrganizationAccessResult
import no.nav.syfo.organisasjonstilgang.application.OrganizationAccessSubject
import java.util.UUID

class FulfillNarmestelederbehovUseCaseTest :
    FunSpec({
        test("fulfills in preserved side-effect order and normalizes manager contact details") {
            val effects = mutableListOf<String>()
            val repository = FakeBehovRepository(behov)
            val relation = FakeRelationEstablisher()
            val outcomeLogger = FakeFulfillNarmestelederbehovOutcomeLogger()
            val useCase = createUseCase(
                repository = repository,
                relation = relation,
                outcomeLogger = outcomeLogger,
                effects = effects,
            )

            executeAndAssertLoggedOutcome(
                useCase = useCase,
                outcomeLogger = outcomeLogger,
                expectedResult = FulfillNarmestelederbehovResult.Fulfilled,
                expectedOutcome = FulfillNarmestelederbehovOutcome.Fulfilled(
                    relationSource = RelationSource.LPS,
                    dialogCompletion = DialogportenCompletionAttempt.Completed,
                ),
            )

            effects shouldBe listOf(
                "load", "access", "sykmelding", "employment", "person:${employeeIdent.value}",
                "person:${managerIdent.value}", "establish", "fulfilled", "dialog", "log",
            )
            requireNotNull(relation.command).manager.email shouldBe "manager@example.test"
            requireNotNull(relation.command).manager.mobile shouldBe "+4799999999"
            requireNotNull(relation.command).source shouldBe RelationSource.LPS
        }

        test("derives personnel manager relation source") {
            val effects = mutableListOf<String>()
            val relation = FakeRelationEstablisher()
            val useCase = createUseCase(relation = relation, effects = effects)

            useCase.execute(command(accessSubject = personnelManager)) shouldBe FulfillNarmestelederbehovResult.Fulfilled

            requireNotNull(relation.command).source shouldBe RelationSource.PERSONNEL_MANAGER
        }

        test("returns combined invalid contact issues without loading the behov") {
            val effects = mutableListOf<String>()
            val outcomeLogger = FakeFulfillNarmestelederbehovOutcomeLogger()
            val useCase = createUseCase(outcomeLogger = outcomeLogger, effects = effects)

            executeAndAssertLoggedOutcome(
                useCase = useCase,
                outcomeLogger = outcomeLogger,
                command = command(email = "invalid", mobile = "+47-99999999"),
                expectedResult = FulfillNarmestelederbehovResult.InvalidManagerContactDetails(
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
                ),
                expectedOutcome = FulfillNarmestelederbehovOutcome.InvalidManagerContactDetails,
            )

            effects shouldBe listOf("log")
        }

        test("returns expected failures before later effects") {
            val cases = listOf(
                Case(
                    result = FulfillNarmestelederbehovResult.NotFound,
                    outcome = FulfillNarmestelederbehovOutcome.NotFound,
                    repository = FakeBehovRepository(null),
                    expectedEffects = listOf("load"),
                ),
                Case(
                    result = FulfillNarmestelederbehovResult.AccessDenied,
                    outcome = FulfillNarmestelederbehovOutcome.AccessDenied,
                    access = FakeOrganizationAccess(OrganizationAccessResult.Denied),
                    expectedEffects = listOf("load", "access"),
                ),
                Case(
                    result = FulfillNarmestelederbehovResult.NoActiveSykmelding,
                    outcome = FulfillNarmestelederbehovOutcome.NoActiveSykmelding,
                    sykmelding = FakeActiveSykmeldingLookup(false),
                    expectedEffects = listOf("load", "access", "sykmelding"),
                ),
                Case(
                    result = FulfillNarmestelederbehovResult.NoEmployment,
                    outcome = FulfillNarmestelederbehovOutcome.NoEmployment,
                    employment = FakeEmploymentLookup(false),
                    expectedEffects = listOf("load", "access", "sykmelding", "employment"),
                ),
                Case(
                    result = FulfillNarmestelederbehovResult.PersonNotFound,
                    outcome = FulfillNarmestelederbehovOutcome.PersonNotFound,
                    personLookup = FakePersonLookup(mapOf(managerIdent to manager)),
                    expectedEffects = listOf(
                        "load",
                        "access",
                        "sykmelding",
                        "employment",
                        "person:${employeeIdent.value}",
                    ),
                ),
                Case(
                    result = FulfillNarmestelederbehovResult.ManagerNameMismatch,
                    outcome = FulfillNarmestelederbehovOutcome.ManagerNameMismatch,
                    personLookup = FakePersonLookup(
                        mapOf(
                            employeeIdent to employee,
                            managerIdent to manager.copy(
                                primaryLastName = "Other",
                                registeredNames = listOf(RegisteredName("Other")),
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
                    ),
                ),
            )

            cases.forEach { case ->
                val effects = mutableListOf<String>()
                val outcomeLogger = FakeFulfillNarmestelederbehovOutcomeLogger()
                val useCase = createUseCase(
                    repository = case.repository,
                    access = case.access,
                    sykmelding = case.sykmelding,
                    employment = case.employment,
                    personLookup = case.personLookup,
                    outcomeLogger = outcomeLogger,
                    effects = effects,
                )
                executeAndAssertLoggedOutcome(
                    useCase = useCase,
                    outcomeLogger = outcomeLogger,
                    expectedResult = case.result,
                    expectedOutcome = case.outcome,
                )
                effects shouldBe case.expectedEffects + "log"
            }
        }

        test("keeps fulfillment successful when Dialogporten completion fails") {
            val effects = mutableListOf<String>()
            val outcomeLogger = FakeFulfillNarmestelederbehovOutcomeLogger()
            val useCase = createUseCase(
                dialog = FakeDialog(DialogportenCompletionAttempt.Failed),
                outcomeLogger = outcomeLogger,
                effects = effects,
            )

            executeAndAssertLoggedOutcome(
                useCase = useCase,
                outcomeLogger = outcomeLogger,
                expectedResult = FulfillNarmestelederbehovResult.Fulfilled,
                expectedOutcome = FulfillNarmestelederbehovOutcome.Fulfilled(
                    relationSource = RelationSource.LPS,
                    dialogCompletion = DialogportenCompletionAttempt.Failed,
                ),
            )

            effects.takeLast(4) shouldBe listOf("establish", "fulfilled", "dialog", "log")
        }

        test("propagates cancellation without later effects") {
            val effects = mutableListOf<String>()
            val useCase = createUseCase(
                sykmelding = FakeActiveSykmeldingLookup(failure = CancellationException("cancelled")),
                effects = effects,
            )

            shouldThrow<CancellationException> { useCase.execute(command()) }

            effects shouldBe listOf("load", "access", "sykmelding")
        }
    })

private val employeeIdent = PersonIdent("12345678901")
private val managerIdent = PersonIdent("10987654321")
private val organizationNumber = OrganizationNumber("123456789")
private val behovId = NarmestelederbehovId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
private val behov = Narmestelederbehov(behovId, Employee(employeeIdent, organizationNumber))
private val employee = PersonNameDetails("Employee", "Employee", listOf(RegisteredName("Employee")))
private val manager = PersonNameDetails("Manager", "Manager", listOf(RegisteredName("Manager")))
private val personnelManager = OrganizationAccessSubject.PersonnelManager(
    personIdent = PersonIdent("11223344556"),
    accessToken = no.nav.syfo.organisasjonstilgang.application.AccessToken("test-token"),
)
private val lpsSystemUser = OrganizationAccessSubject.LpsSystemUser(
    systemUserId = "system-user",
    systemUserOrganizationNumber = organizationNumber,
)

private fun command(
    email: String = " manager@example.test ",
    mobile: String = "+47 99999999",
    accessSubject: OrganizationAccessSubject = lpsSystemUser,
) = FulfillNarmestelederbehovCommand(
    behovId = behovId,
    manager = ManagerContactInput(managerIdent, "Manager", email, mobile),
    accessSubject = accessSubject,
)

private fun createUseCase(
    repository: FakeBehovRepository = FakeBehovRepository(behov),
    access: FakeOrganizationAccess = FakeOrganizationAccess(),
    sykmelding: FakeActiveSykmeldingLookup = FakeActiveSykmeldingLookup(),
    employment: FakeEmploymentLookup = FakeEmploymentLookup(),
    personLookup: FakePersonLookup = FakePersonLookup(mapOf(employeeIdent to employee, managerIdent to manager)),
    relation: FakeRelationEstablisher = FakeRelationEstablisher(),
    dialog: FakeDialog = FakeDialog(),
    outcomeLogger: FakeFulfillNarmestelederbehovOutcomeLogger = FakeFulfillNarmestelederbehovOutcomeLogger(),
    effects: MutableList<String>,
) = FulfillNarmestelederbehovUseCase(
    behovRepository = repository.withEffects(effects),
    organizationAccess = access.withEffects(effects),
    activeSykmeldingLookup = sykmelding.withEffects(effects),
    employmentLookup = employment.withEffects(effects),
    personLookup = personLookup.withEffects(effects),
    establishNarmestelederrelasjon = relation.withEffects(effects),
    dialog = dialog.withEffects(effects),
    outcomeLogger = outcomeLogger.withEffects(effects),
)

private suspend fun executeAndAssertLoggedOutcome(
    useCase: FulfillNarmestelederbehovUseCase,
    outcomeLogger: FakeFulfillNarmestelederbehovOutcomeLogger,
    expectedResult: FulfillNarmestelederbehovResult,
    expectedOutcome: FulfillNarmestelederbehovOutcome,
    command: FulfillNarmestelederbehovCommand = command(),
) {
    useCase.execute(command) shouldBe expectedResult
    outcomeLogger.outcomes shouldBe listOf(expectedOutcome)
}

private data class Case(
    val result: FulfillNarmestelederbehovResult,
    val outcome: FulfillNarmestelederbehovOutcome,
    val repository: FakeBehovRepository = FakeBehovRepository(behov),
    val access: FakeOrganizationAccess = FakeOrganizationAccess(),
    val sykmelding: FakeActiveSykmeldingLookup = FakeActiveSykmeldingLookup(),
    val employment: FakeEmploymentLookup = FakeEmploymentLookup(),
    val personLookup: FakePersonLookup = FakePersonLookup(mapOf(employeeIdent to employee, managerIdent to manager)),
    val expectedEffects: List<String>,
)
