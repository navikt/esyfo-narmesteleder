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
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import no.nav.syfo.aareg.AaregService
import no.nav.syfo.aareg.client.TestAaregClient
import no.nav.syfo.altinn.dialogporten.client.DialogportenClient
import no.nav.syfo.altinn.dialogporten.client.IDialogportenClient
import no.nav.syfo.altinn.dialogporten.domain.DialogStatus
import no.nav.syfo.altinn.dialogporten.domain.ExtendedDialog
import no.nav.syfo.altinn.dialogporten.service.DialogportenService
import no.nav.syfo.application.api.ErrorType
import no.nav.syfo.application.auth.SystemPrincipal
import no.nav.syfo.application.auth.UserPrincipal
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
        val dialogportenClient = mockk<IDialogportenClient>()
        val dialogportenService = spyk(
            DialogportenService(
                dialogportenClient = dialogportenClient,
                narmestelederDb = fakeDb,
                otherEnvironmentProperties = mockk(),
                pdlService = pdlService,
            ),
        )
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
            testAaregClient.clear()
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

        test("should publish the relation before fulfilling the behov and attempting Dialogporten completion") {
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

        test("should preserve normalized contact details and the PDL employee identity in the published relation") {
            val fixtureEntity = fakeDb.insertRequirement(requirement.copy(etternavn = "Outdated name"))
            val currentEmployeeIdent = "22345678901"
            coEvery { pdlClient.getPerson(employeePersonIdent) } returns getPersonResponse(currentEmployeeIdent, "Employee")
            coEvery { pdlClient.getPerson(managerPersonIdent) } returns getPersonResponse("20987654321", manager.lastName)

            handler.handleUpdatedRequirement(
                requirementId = fixtureEntity.id!!,
                manager = manager.copy(email = " mail@manager.no ", mobile = "+47 99999999"),
                principal = systemPrincipal(employeeOrgNumber),
                context = "test",
            )

            verify(exactly = 1) {
                kafkaProducer.sendSykmeldingNLRelasjon(
                    match {
                        it.sykmeldt.fnr == currentEmployeeIdent &&
                            it.sykmeldt.navn == "Test Employee" &&
                            it.leder.fnr == managerPersonIdent &&
                            it.leder.epost == "mail@manager.no" &&
                            it.leder.mobil == "+4799999999" &&
                            it.orgnummer == employeeOrgNumber &&
                            it.utbetalesLonn == true
                    },
                    NlResponseSource.LPS,
                )
            }
        }

        test("should identify fulfillment by a personnel manager in the published relation") {
            val fixtureEntity = fakeDb.insertRequirement(requirement)

            handler.handleUpdatedRequirement(
                requirementId = fixtureEntity.id!!,
                manager = manager,
                principal = UserPrincipal(ident = managerPersonIdent, token = "test-token"),
                context = "test",
            )

            verify(exactly = 1) { kafkaProducer.sendSykmeldingNLRelasjon(any(), NlResponseSource.PERSONALLEDER) }
        }

        test("should reject invalid manager contact details before looking up the requirement") {
            val exception = shouldThrow<ApiErrorException.BadRequestException> {
                handler.handleUpdatedRequirement(
                    requirementId = UUID.randomUUID(),
                    manager = manager.copy(email = "invalid"),
                    principal = systemPrincipal(employeeOrgNumber),
                    context = "test",
                )
            }

            exception.type shouldBe ErrorType.INVALID_FORMAT
            coVerify(exactly = 0) {
                fakeDb.findBehovById(any())
                principalAccessValidator.validatePrincipalAccessToOrgnumber(any(), any())
            }
            verifyNoFulfillmentSideEffects(narmestelederKafkaService, fakeDb, dialogportenService)
        }

        test("should report a missing requirement before checking organization access") {
            shouldThrow<ApiErrorException.NotFoundException> {
                handler.handleUpdatedRequirement(
                    requirementId = UUID.randomUUID(),
                    manager = manager,
                    principal = systemPrincipal(employeeOrgNumber),
                    context = "test",
                )
            }

            coVerify(exactly = 0) { principalAccessValidator.validatePrincipalAccessToOrgnumber(any(), any()) }
            verifyNoFulfillmentSideEffects(narmestelederKafkaService, fakeDb, dialogportenService)
        }

        test("should reject missing organization access before sick leave employment and person lookups") {
            val fixtureEntity = fakeDb.insertRequirement(requirement)
            val denied = ApiErrorException.ForbiddenException()
            coEvery { principalAccessValidator.validatePrincipalAccessToOrgnumber(any(), employeeOrgNumber) } throws denied

            shouldThrow<ApiErrorException.ForbiddenException> {
                handler.handleUpdatedRequirement(
                    requirementId = fixtureEntity.id!!,
                    manager = manager,
                    principal = systemPrincipal(employeeOrgNumber),
                    context = "test",
                )
            } shouldBe denied

            coVerify(exactly = 0) {
                dinesykmeldteService.getIsActiveSykmelding(any(), any())
                aaregService.findArbeidsforholdByPersonIdent(any())
                pdlClient.getPerson(any())
            }
            verifyNoFulfillmentSideEffects(narmestelederKafkaService, fakeDb, dialogportenService)
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
            testAaregClient.clear()

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

        test("should leave the requirement unchanged and skip Dialogporten when publication fails") {
            val fixtureEntity = fakeDb.insertRequirement(requirement)
            val failure = IllegalStateException("Publication failed")
            every { kafkaProducer.sendSykmeldingNLRelasjon(any(), any()) } throws failure

            val exception = shouldThrow<ApiErrorException.InternalServerErrorException> {
                handler.handleUpdatedRequirement(
                    requirementId = fixtureEntity.id!!,
                    manager = manager,
                    principal = systemPrincipal(employeeOrgNumber),
                    context = "test",
                )
            }

            exception.cause shouldBe failure
            fakeDb.findBehovById(fixtureEntity.id!!)?.behovStatus shouldBe BehovStatus.BEHOV_CREATED
            coVerify(exactly = 0) {
                fakeDb.updateNlBehov(any())
                dialogportenService.setToCompletedInDialogporten(any())
            }
        }

        test("should publish before a failed requirement update and skip Dialogporten") {
            val fixtureEntity = fakeDb.insertRequirement(requirement)
            val failure = IllegalStateException("Database update failed")
            coEvery { fakeDb.updateNlBehov(any()) } throws failure

            val exception = shouldThrow<ApiErrorException.InternalServerErrorException> {
                handler.handleUpdatedRequirement(
                    requirementId = fixtureEntity.id!!,
                    manager = manager,
                    principal = systemPrincipal(employeeOrgNumber),
                    context = "test",
                )
            }

            exception.cause shouldBe failure
            coVerifyOrder {
                kafkaProducer.sendSykmeldingNLRelasjon(any(), NlResponseSource.LPS)
                fakeDb.updateNlBehov(match { it.behovStatus == BehovStatus.BEHOV_FULFILLED })
            }
            fakeDb.findBehovById(fixtureEntity.id!!)?.behovStatus shouldBe BehovStatus.BEHOV_CREATED
            coVerify(exactly = 0) { dialogportenService.setToCompletedInDialogporten(any()) }
        }

        test("should persist completed status only after Dialogporten accepts completion") {
            val dialogId = UUID.randomUUID()
            val revision = UUID.randomUUID()
            val fixtureEntity = fakeDb.insertRequirement(requirement.copy(dialogId = dialogId))
            coEvery { dialogportenClient.getDialogById(dialogId) } returns mockk<ExtendedDialog> {
                every { this@mockk.revision } returns revision
            }
            coEvery { dialogportenClient.patchDialog(dialogId, revision, any<DialogportenClient.DialogportenPatch>()) } just Runs

            handler.handleUpdatedRequirement(
                requirementId = fixtureEntity.id!!,
                manager = manager,
                principal = systemPrincipal(employeeOrgNumber),
                context = "test",
            )

            coVerifyOrder {
                fakeDb.updateNlBehov(match { it.behovStatus == BehovStatus.BEHOV_FULFILLED })
                dialogportenClient.patchDialog(
                    dialogId,
                    revision,
                    DialogportenClient.DialogportenPatch(
                        operation = DialogportenClient.DialogportenPatch.OPERATION.REPLACE,
                        path = DialogportenClient.DialogportenPatch.PATH.STATUS,
                        value = DialogStatus.Completed.name,
                    ),
                )
                fakeDb.updateNlBehov(match { it.behovStatus == BehovStatus.DIALOGPORTEN_STATUS_SET_COMPLETED })
            }
            fakeDb.findBehovById(fixtureEntity.id)?.behovStatus shouldBe BehovStatus.DIALOGPORTEN_STATUS_SET_COMPLETED
        }

        test("should retain fulfilled status for retry when Dialogporten is unavailable") {
            val dialogId = UUID.randomUUID()
            val fixtureEntity = fakeDb.insertRequirement(requirement.copy(dialogId = dialogId))
            coEvery { dialogportenClient.getDialogById(dialogId) } throws IllegalStateException("Dialogporten unavailable")

            handler.handleUpdatedRequirement(
                requirementId = fixtureEntity.id!!,
                manager = manager,
                principal = systemPrincipal(employeeOrgNumber),
                context = "test",
            )

            verify(exactly = 1) { kafkaProducer.sendSykmeldingNLRelasjon(any(), NlResponseSource.LPS) }
            coVerify(exactly = 1) { dialogportenClient.getDialogById(dialogId) }
            fakeDb.findBehovById(fixtureEntity.id)?.behovStatus shouldBe BehovStatus.BEHOV_FULFILLED
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
