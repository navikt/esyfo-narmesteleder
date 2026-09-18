package no.nav.syfo.narmesteleder.api.v1

import createMockToken
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.Runs
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.spyk
import kotlinx.coroutines.CancellationException
import no.nav.syfo.aareg.AaregService
import no.nav.syfo.aareg.client.TestAaregClient
import no.nav.syfo.altinn.dialogporten.service.DialogportenService
import no.nav.syfo.application.api.ErrorType
import no.nav.syfo.application.auth.SystemPrincipal
import no.nav.syfo.application.exception.ApiErrorException
import no.nav.syfo.application.valkey.PdlCache
import no.nav.syfo.dinesykmeldte.IDinesykmeldteService
import no.nav.syfo.narmesteleder.db.FakeNarmestelederDb
import no.nav.syfo.narmesteleder.db.NarmestelederBehovEntity
import no.nav.syfo.narmesteleder.domain.BehovReason
import no.nav.syfo.narmesteleder.domain.BehovStatus
import no.nav.syfo.narmesteleder.domain.Manager
import no.nav.syfo.narmesteleder.domain.PersonalIdentificationNumber
import no.nav.syfo.narmesteleder.kafka.ISykmeldingNLKafkaProducer
import no.nav.syfo.narmesteleder.kafka.model.NlResponseSource
import no.nav.syfo.narmesteleder.service.NarmestelederKafkaService
import no.nav.syfo.narmesteleder.service.NarmestelederService
import no.nav.syfo.narmesteleder.service.ValidationService
import no.nav.syfo.narmesteleder.service.validators.PrincipalAccessValidator
import no.nav.syfo.narmesteleder.service.validators.SickLeaveValidator
import no.nav.syfo.pdl.PdlService
import no.nav.syfo.pdl.client.GetPersonResponse
import no.nav.syfo.pdl.client.IPdlClient
import no.nav.syfo.pdl.client.Ident
import no.nav.syfo.pdl.client.IdentResponse
import no.nav.syfo.pdl.client.Navn
import no.nav.syfo.pdl.client.PersonResponse
import no.nav.syfo.pdl.client.ResponseData
import java.util.UUID

class FulfillNarmestelederbehovTest :
    FunSpec({
        val testAaregClient = TestAaregClient()
        val fakeDb = spyk(FakeNarmestelederDb())
        val pdlCache = mockk<PdlCache>(relaxed = true)
        val pdlClient = mockk<IPdlClient>()
        val pdlService = PdlService(pdlClient, pdlCache)
        val dinesykmeldteService = mockk<IDinesykmeldteService>()
        val aaregService = spyk(AaregService(testAaregClient))
        val principalAccessValidator = mockk<PrincipalAccessValidator>()
        val validationService = ValidationService(
            pdlService = pdlService,
            aaregService = aaregService,
            principalAccessValidator = principalAccessValidator,
            sickLeaveValidator = SickLeaveValidator(dinesykmeldteService),
        )
        val dialogportenService = mockk<DialogportenService>(relaxed = true)
        val narmestelederService = NarmestelederService(
            nlDb = fakeDb,
            persistLeesahNlBehov = true,
            aaregService = aaregService,
            pdlService = pdlService,
            dinesykmeldteService = dinesykmeldteService,
            dialogportenService = dialogportenService,
        )
        val kafkaProducer = mockk<ISykmeldingNLKafkaProducer>()
        val narmestelederKafkaService = spyk(
            NarmestelederKafkaService(
                kafkaSykemeldingProducer = kafkaProducer,
            ),
        )
        val handler = LinemanagerRequirementRESTHandler(
            narmesteLederService = narmestelederService,
            validationService = validationService,
            narmestelederKafkaService = narmestelederKafkaService,
        )
        val managerPersonIdent = "10987654321"
        val employeePersonIdent = "12345678901"
        val employeeOrgNumber = "123456789"
        val employeeMainOrgNumber = "987654321"
        val manager = Manager(
            nationalIdentificationNumber = PersonalIdentificationNumber(managerPersonIdent),
            mobile = "99999999",
            email = "mail@manager.no",
            lastName = "Jensen",
        )
        val requirement = NarmestelederBehovEntity(
            id = UUID.randomUUID(),
            orgnummer = employeeOrgNumber,
            hovedenhetOrgnummer = employeeMainOrgNumber,
            sykmeldtFnr = employeePersonIdent,
            narmestelederFnr = "123456789",
            behovReason = BehovReason.DEAKTIVERT_LEDER,
            avbruttNarmesteLederId = UUID.randomUUID(),
        )

        beforeTest {
            clearAllMocks(currentThreadOnly = true)
            fakeDb.clear()
            testAaregClient.seedEmployment(
                personIdent = employeePersonIdent,
                orgNumber = employeeOrgNumber,
                mainOrgNumber = employeeMainOrgNumber,
            )
            coEvery { pdlCache.getPerson(any()) } returns null
            coEvery { pdlClient.getPerson(managerPersonIdent) } returns getPersonResponse(managerPersonIdent, manager.lastName)
            coEvery { pdlClient.getPerson(employeePersonIdent) } returns getPersonResponse(employeePersonIdent, "Employee")
            coEvery { dinesykmeldteService.getIsActiveSykmelding(any(), any()) } returns true
            coEvery { principalAccessValidator.validatePrincipalAccessToOrgnumber(any(), any()) } returns null
            every { kafkaProducer.sendSykmeldingNLRelasjon(any(), any()) } just Runs
        }

        test("should publish the relation before fulfilling the behov and completing Dialogporten") {
            val fixtureEntity = fakeDb.insertRequirement(requirement)
            val principal = systemPrincipal(employeeOrgNumber)

            handler.handleUpdatedRequirement(
                requirementId = fixtureEntity.id!!,
                manager = manager,
                principal = principal,
                context = "test",
            )

            coVerifyOrder {
                narmestelederKafkaService.sendNarmesteLederRelasjon(
                    any(),
                    any(),
                    NlResponseSource.LPS,
                )
                fakeDb.updateNlBehov(
                    match { it.id == fixtureEntity.id && it.behovStatus == BehovStatus.BEHOV_FULFILLED },
                )
                dialogportenService.setToCompletedInDialogporten(
                    match { it.id == fixtureEntity.id && it.behovStatus == BehovStatus.BEHOV_FULFILLED },
                )
            }
            fakeDb.findBehovById(fixtureEntity.id)?.behovStatus shouldBe BehovStatus.BEHOV_FULFILLED
        }

        test("should reject missing active sykmelding before any fulfillment side effect") {
            val fixtureEntity = fakeDb.insertRequirement(requirement)
            val principal = systemPrincipal(employeeOrgNumber)
            coEvery {
                dinesykmeldteService.getIsActiveSykmelding(employeePersonIdent, employeeOrgNumber)
            } returns false

            val exception = shouldThrow<ApiErrorException.BadRequestException> {
                handler.handleUpdatedRequirement(
                    requirementId = fixtureEntity.id!!,
                    manager = manager,
                    principal = principal,
                    context = "test",
                )
            }

            exception.type shouldBe ErrorType.NO_ACTIVE_SICK_LEAVE
            verifyNoFulfillmentSideEffects(narmestelederKafkaService, fakeDb, dialogportenService)
        }

        test("should reject missing employment before any fulfillment side effect") {
            val fixtureEntity = fakeDb.insertRequirement(requirement)
            val principal = systemPrincipal(employeeOrgNumber)
            coEvery {
                aaregService.findArbeidsforholdByPersonIdent(employeePersonIdent)
            } returns emptyList()

            val exception = shouldThrow<ApiErrorException.BadRequestException> {
                handler.handleUpdatedRequirement(
                    requirementId = fixtureEntity.id!!,
                    manager = manager,
                    principal = principal,
                    context = "test",
                )
            }

            exception.type shouldBe ErrorType.EMPLOYEE_MISSING_EMPLOYMENT_IN_ORG
            verifyNoFulfillmentSideEffects(narmestelederKafkaService, fakeDb, dialogportenService)
        }

        test("should reject a manager name mismatch before any fulfillment side effect") {
            val fixtureEntity = fakeDb.insertRequirement(requirement)
            val principal = systemPrincipal(employeeOrgNumber)
            coEvery { pdlClient.getPerson(managerPersonIdent) } returns getPersonResponse(managerPersonIdent, "Berg")

            val exception = shouldThrow<ApiErrorException.BadRequestException> {
                handler.handleUpdatedRequirement(
                    requirementId = fixtureEntity.id!!,
                    manager = manager,
                    principal = principal,
                    context = "test",
                )
            }

            exception.type shouldBe ErrorType.LINEMANAGER_NAME_NATIONAL_IDENTIFICATION_NUMBER_MISMATCH
            verifyNoFulfillmentSideEffects(narmestelederKafkaService, fakeDb, dialogportenService)
        }

        test("should propagate cancellation without any fulfillment side effect") {
            val fixtureEntity = fakeDb.insertRequirement(requirement)
            val principal = systemPrincipal(employeeOrgNumber)
            coEvery {
                dinesykmeldteService.getIsActiveSykmelding(employeePersonIdent, employeeOrgNumber)
            } throws CancellationException("Request cancelled")

            shouldThrow<CancellationException> {
                handler.handleUpdatedRequirement(
                    requirementId = fixtureEntity.id!!,
                    manager = manager,
                    principal = principal,
                    context = "test",
                )
            }

            verifyNoFulfillmentSideEffects(narmestelederKafkaService, fakeDb, dialogportenService)
        }
    })

private fun systemPrincipal(orgNumber: String) = SystemPrincipal(
    ident = "0192:$orgNumber",
    token = createMockToken(
        ident = "0192:$orgNumber",
    ),
    systemOwner = "0192:systemOwner",
    systemUserId = "systemUserId",
)

private suspend fun FakeNarmestelederDb.insertRequirement(requirement: NarmestelederBehovEntity) = insertNlBehov(requirement)

private fun getPersonResponse(personIdent: String, lastName: String) = GetPersonResponse(
    data = ResponseData(
        person = PersonResponse(
            navn = listOf(Navn(fornavn = "Test", mellomnavn = null, etternavn = lastName)),
        ),
        identer = IdentResponse(
            identer = listOf(Ident(ident = personIdent, gruppe = Ident.GRUPPE_IDENT_FNR)),
        ),
    ),
    errors = null,
)

private fun verifyNoFulfillmentSideEffects(
    narmestelederKafkaService: NarmestelederKafkaService,
    fakeDb: FakeNarmestelederDb,
    dialogportenService: DialogportenService,
) {
    coVerify(exactly = 0) {
        narmestelederKafkaService.sendNarmesteLederRelasjon(any(), any(), any())
        fakeDb.updateNlBehov(
            match { it.behovStatus == BehovStatus.BEHOV_FULFILLED },
        )
        dialogportenService.setToCompletedInDialogporten(any())
    }
}
