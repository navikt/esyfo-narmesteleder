package no.nav.syfo.narmesteleder.service

import DefaultSystemPrincipal
import faker
import io.kotest.assertions.throwables.shouldNotThrow
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.spyk
import linemanager
import linemanagerRevoke
import no.nav.syfo.aareg.AaregService
import no.nav.syfo.aareg.client.FakeAaregClient
import no.nav.syfo.altinn.pdp.client.FakePdpClient
import no.nav.syfo.altinn.pdp.client.System
import no.nav.syfo.altinn.pdp.service.PdpService
import no.nav.syfo.altinntilganger.AltinnTilgangerService
import no.nav.syfo.altinntilganger.client.FakeAltinnTilgangerClient
import no.nav.syfo.application.api.ErrorType
import no.nav.syfo.application.auth.UserPrincipal
import no.nav.syfo.application.exception.ApiErrorException
import no.nav.syfo.application.valkey.EregCache
import no.nav.syfo.application.valkey.PdlCache
import no.nav.syfo.dinesykmeldte.DinesykmeldteService
import no.nav.syfo.dinesykmeldte.client.FakeDinesykmeldteClient
import no.nav.syfo.ereg.EregService
import no.nav.syfo.ereg.client.FakeEregClient
import no.nav.syfo.narmesteleder.domain.Linemanager
import no.nav.syfo.narmesteleder.domain.LinemanagerRevoke
import no.nav.syfo.narmesteleder.service.validators.PrincipalAccessValidator
import no.nav.syfo.narmesteleder.service.validators.SickLeaveValidator
import no.nav.syfo.pdl.PdlService
import no.nav.syfo.pdl.client.FakePdlClient
import prepareGetPersonResponse

class ValidationServiceTest :
    DescribeSpec({
        val altinnTilgangerClient = FakeAltinnTilgangerClient()
        val altinnTilgangerService = spyk(AltinnTilgangerService(altinnTilgangerClient))
        val dinesykmeldteClient = FakeDinesykmeldteClient()
        val dinesykmeldteService = spyk(DinesykmeldteService(dinesykmeldteClient))

        val aaregClient = FakeAaregClient()
        val aaregService = spyk(AaregService(aaregClient))
        val eregClient = FakeEregClient()
        val eregCache = mockk<EregCache>(relaxed = true)
        val eregService = spyk(EregService(eregClient, eregCache))
        val pdlClient = FakePdlClient()
        val pdlCacheMock = mockk<PdlCache>(relaxed = true)
        val pdlService = spyk(PdlService(pdlClient, pdlCacheMock))
        val pdpClient = FakePdpClient()
        val pdpService = spyk(PdpService(pdpClient))
        val principalAccessValidator = PrincipalAccessValidator(
            altinnTilgangerService = altinnTilgangerService,
            pdpService = pdpService,
            eregService = eregService,
        )
        val sickLeaveValidator = SickLeaveValidator(
            dinesykmeldteService = dinesykmeldteService,
        )
        val service = ValidationService(
            pdlService = pdlService,
            aaregService = aaregService,
            principalAccessValidator = principalAccessValidator,
            sickLeaveValidator = sickLeaveValidator,
        )

        fun differentLastName(lastName: String): String = faker.name().lastName().let {
            if (it != lastName) it else lastName.reversed()
        }

        fun differentOrgNumber(orgNumber: String): String = faker.numerify("#########").let {
            if (it != orgNumber) it else orgNumber.reversed()
        }

        fun prepareValidLinemanagerValidation(
            linemanager: Linemanager,
            principal: UserPrincipal,
        ) {
            altinnTilgangerClient.accessPolicy.clear()
            altinnTilgangerClient.addAccess(principal.ident, linemanager.orgNumber)
            aaregClient.arbeidsForholdForIdent[linemanager.manager.nationalIdentificationNumber] =
                listOf(linemanager.orgNumber to "hovedenhet")
            aaregClient.arbeidsForholdForIdent[linemanager.employeeIdentificationNumber] =
                listOf(linemanager.orgNumber to "hovedenhet")
            pdlService.prepareGetPersonResponse(linemanager.employeeIdentificationNumber, linemanager.lastName)
            pdlService.prepareGetPersonResponse(linemanager.manager)
        }

        fun prepareValidLinemanagerRevoke(
            linemanagerRevoke: LinemanagerRevoke,
            principal: UserPrincipal,
        ) {
            altinnTilgangerClient.accessPolicy.clear()
            altinnTilgangerClient.addAccess(principal.ident, linemanagerRevoke.orgNumber)
            aaregClient.arbeidsForholdForIdent[linemanagerRevoke.employeeIdentificationNumber] =
                listOf(linemanagerRevoke.orgNumber to "hovedenhet")
            pdlService.prepareGetPersonResponse(
                linemanagerRevoke.employeeIdentificationNumber,
                linemanagerRevoke.lastName,
            )
        }

        fun prepareCommonValidLinemanagerRevoke(linemanagerRevoke: LinemanagerRevoke) {
            aaregClient.arbeidsForholdForIdent[linemanagerRevoke.employeeIdentificationNumber] =
                listOf(linemanagerRevoke.orgNumber to "hovedenhet")
            pdlService.prepareGetPersonResponse(
                linemanagerRevoke.employeeIdentificationNumber,
                linemanagerRevoke.lastName,
            )
        }

        beforeTest {
            clearAllMocks()
            altinnTilgangerClient.reset()
            coEvery { pdlCacheMock.getPerson(any()) } returns null
            coEvery { eregCache.getOrganisasjon(any()) } returns null
            aaregClient.arbeidsForholdForIdent.clear()
            eregClient.organisasjoner.clear()
        }
        describe("validateNarmesteleder") {
            it("should not thrown when all validation passes and principal is BrukerPrincipal") {
                val fnr = altinnTilgangerClient.accessPolicy.first().hasAccess.first()
                val principal = UserPrincipal(fnr, "token")
                val narmestelederRelasjonerWrite = linemanager().copy(employeeIdentificationNumber = fnr)

                prepareValidLinemanagerValidation(narmestelederRelasjonerWrite, principal)

                val result = service.validateLinemanager(narmestelederRelasjonerWrite, principal)

                result.employee.nationalIdentificationNumber shouldBe narmestelederRelasjonerWrite.employeeIdentificationNumber
                result.manager.nationalIdentificationNumber shouldBe narmestelederRelasjonerWrite.manager.nationalIdentificationNumber
                coVerify(exactly = 1) {
                    altinnTilgangerService.validateTilgangToOrganization(
                        userPrincipal = eq(principal),
                        orgnummer = eq(narmestelederRelasjonerWrite.orgNumber),
                    )
                    pdlService.getPersonOrThrowApiError(narmestelederRelasjonerWrite.manager.nationalIdentificationNumber)
                    pdlService.getPersonOrThrowApiError(narmestelederRelasjonerWrite.employeeIdentificationNumber)
                    aaregService.findArbeidsforholdByPersonIdent(narmestelederRelasjonerWrite.employeeIdentificationNumber)
                }
                coVerify(exactly = 0) {
                    pdpService.hasAccessToResource(any(), any(), any())
                }
            }

            it("should throw BadRequestException when lastName of manager does mot match value in PDL") {
                val fnr = altinnTilgangerClient.accessPolicy.first().hasAccess.first()
                val principal = UserPrincipal(fnr, "token")
                val narmestelederRelasjonerWrite = linemanager().copy(employeeIdentificationNumber = fnr)

                prepareValidLinemanagerValidation(narmestelederRelasjonerWrite, principal)
                pdlService.prepareGetPersonResponse(
                    narmestelederRelasjonerWrite.manager.nationalIdentificationNumber,
                    differentLastName(narmestelederRelasjonerWrite.manager.lastName),
                )

                val exception = shouldThrow<ApiErrorException.BadRequestException> {
                    service.validateLinemanager(narmestelederRelasjonerWrite, principal)
                }

                coVerify(exactly = 1) {
                    altinnTilgangerService.validateTilgangToOrganization(
                        userPrincipal = eq(principal),
                        orgnummer = eq(narmestelederRelasjonerWrite.orgNumber),
                    )
                    pdlService.getPersonOrThrowApiError(narmestelederRelasjonerWrite.manager.nationalIdentificationNumber)
                    pdlService.getPersonOrThrowApiError(narmestelederRelasjonerWrite.employeeIdentificationNumber)
                    aaregService.findArbeidsforholdByPersonIdent(narmestelederRelasjonerWrite.employeeIdentificationNumber)
                }
                coVerify(exactly = 0) {
                    pdpService.hasAccessToResource(any(), any(), any())
                }

                exception.message shouldBe "Last name for linemanager does not correspond with registered value for the given national identification number"
            }

            it("should throw BadRequestException with NO_ACTIVE_SICK_LEAVE when no active sick leave exists") {
                val fnr = altinnTilgangerClient.accessPolicy.first().hasAccess.first()
                val principal = UserPrincipal(fnr, "token")
                val narmestelederRelasjonerWrite = linemanager().copy(employeeIdentificationNumber = fnr)

                altinnTilgangerClient.accessPolicy.clear()
                altinnTilgangerClient.addAccess(principal.ident, narmestelederRelasjonerWrite.orgNumber)
                coEvery {
                    dinesykmeldteService.getIsActiveSykmelding(
                        narmestelederRelasjonerWrite.employeeIdentificationNumber,
                        narmestelederRelasjonerWrite.orgNumber,
                    )
                } returns false

                val exception = shouldThrow<ApiErrorException.BadRequestException> {
                    service.validateLinemanager(narmestelederRelasjonerWrite, principal)
                }

                exception.type shouldBe ErrorType.NO_ACTIVE_SICK_LEAVE
                coVerify(exactly = 1) {
                    altinnTilgangerService.validateTilgangToOrganization(
                        userPrincipal = eq(principal),
                        orgnummer = eq(narmestelederRelasjonerWrite.orgNumber),
                    )
                }
                coVerify(exactly = 0) {
                    aaregService.findArbeidsforholdByPersonIdent(any())
                    pdlService.getPersonOrThrowApiError(any())
                }
            }

            it("should skip employee last name validation when validateEmployeeLastName is false") {
                val fnr = altinnTilgangerClient.accessPolicy.first().hasAccess.first()
                val principal = UserPrincipal(fnr, "token")
                val narmestelederRelasjonerWrite = linemanager().copy(employeeIdentificationNumber = fnr)

                altinnTilgangerClient.accessPolicy.clear()
                altinnTilgangerClient.addAccess(principal.ident, narmestelederRelasjonerWrite.orgNumber)
                aaregClient.arbeidsForholdForIdent[narmestelederRelasjonerWrite.manager.nationalIdentificationNumber] =
                    listOf(narmestelederRelasjonerWrite.orgNumber to "hovedenhet")
                aaregClient.arbeidsForholdForIdent[narmestelederRelasjonerWrite.employeeIdentificationNumber] =
                    listOf(narmestelederRelasjonerWrite.orgNumber to "hovedenhet")
                pdlService.prepareGetPersonResponse(
                    narmestelederRelasjonerWrite.employeeIdentificationNumber,
                    differentLastName(narmestelederRelasjonerWrite.lastName),
                )
                pdlService.prepareGetPersonResponse(narmestelederRelasjonerWrite.manager)

                shouldNotThrow<ApiErrorException.BadRequestException> {
                    service.validateLinemanager(
                        narmestelederRelasjonerWrite,
                        principal,
                        validateEmployeeLastName = false,
                    )
                }
            }

            it("should call AltinnTilgangerService first when principal is BrukerPrincipal") {
                val fnr = altinnTilgangerClient.accessPolicy.first().hasAccess.first()
                val principal = UserPrincipal(fnr, "token")
                val narmestelederRelasjonerWrite = linemanager().copy(employeeIdentificationNumber = fnr)

                shouldThrow<ApiErrorException.ForbiddenException> {
                    service.validateLinemanager(narmestelederRelasjonerWrite, principal)
                }
                coVerify(exactly = 1) {
                    altinnTilgangerService.validateTilgangToOrganization(
                        userPrincipal = eq(principal),
                        orgnummer = eq(narmestelederRelasjonerWrite.orgNumber),
                    )
                }
                coVerify(exactly = 0) {
                    aaregService.findArbeidsforholdByPersonIdent(any())
                    pdpService.hasAccessToResource(any(), any(), any())
                    pdlService.getPersonOrThrowApiError(any())
                }
            }

            it("should not call AltinnTilgangerService when principal is SystemPrincipal") {
                val userWithAccess = altinnTilgangerClient.accessPolicy.first()
                val requestOrgnumber = userWithAccess.altinnTilgangerResponse.hierarki.first().orgnr
                val systemUserOrgnumber = requestOrgnumber.reversed()
                val narmestelederRelasjonerWrite = linemanager().copy(
                    employeeIdentificationNumber = userWithAccess.hasAccess.first(),
                    orgNumber = userWithAccess.altinnTilgangerResponse.hierarki.first().orgnr,
                )
                val principal = DefaultSystemPrincipal.copy(
                    ident = "0192:$systemUserOrgnumber",
                )

                shouldThrow<ApiErrorException.BadRequestException> {
                    service.validateLinemanager(narmestelederRelasjonerWrite, principal)
                }
                coVerify(exactly = 0) {
                    altinnTilgangerService.validateTilgangToOrganization(
                        userPrincipal = any<UserPrincipal>(),
                        orgnummer = eq(narmestelederRelasjonerWrite.orgNumber),
                    )
                }
                coVerify(exactly = 0) {
                    pdlService.getPersonOrThrowApiError(eq(narmestelederRelasjonerWrite.employeeIdentificationNumber))
                    pdlService.getPersonOrThrowApiError(eq(narmestelederRelasjonerWrite.manager.nationalIdentificationNumber))
                }
                coVerify(exactly = 1) {
                    pdpService.hasAccessToResource(
                        user = match<System> { it.id == "systemId" },
                        orgNumberSet = eq(setOf(narmestelederRelasjonerWrite.orgNumber)),
                        resource = eq("nav_syfo_oppgi-narmesteleder"),
                    )
                    aaregService.findArbeidsforholdByPersonIdent(eq(narmestelederRelasjonerWrite.employeeIdentificationNumber))
                }
            }
        }

        describe("validateNarmestelederAvkreft") {
            it("should call AltinnTilgangerService when principal is BrukerPrincipal") {
                val fnr = altinnTilgangerClient.accessPolicy.first().hasAccess.first()
                val principal = UserPrincipal(fnr, "token")
                val narmesteLederAvkreft = linemanagerRevoke().copy(employeeIdentificationNumber = fnr)

                shouldThrow<ApiErrorException.ForbiddenException> {
                    service.validateLinemanagerRevoke(narmesteLederAvkreft, principal)
                }
                coVerify(exactly = 1) {
                    altinnTilgangerService.validateTilgangToOrganization(
                        userPrincipal = eq(principal),
                        orgnummer = eq(narmesteLederAvkreft.orgNumber),
                    )
                    aaregService.findArbeidsforholdByPersonIdent(any())
                }
                coVerify(exactly = 0) {
                    pdpService.hasAccessToResource(any(), any(), any())
                    pdlService.getPersonOrThrowApiError(any())
                }
            }

            it("should return employee when validateLinemanagerRevoke succeeds for user principal") {
                val fnr = altinnTilgangerClient.accessPolicy.first().hasAccess.first()
                val principal = UserPrincipal(fnr, "token")
                val narmesteLederAvkreft = linemanagerRevoke()

                prepareValidLinemanagerRevoke(narmesteLederAvkreft, principal)

                val result = service.validateLinemanagerRevoke(narmesteLederAvkreft, principal)

                result.nationalIdentificationNumber shouldBe narmesteLederAvkreft.employeeIdentificationNumber
                result.name.etternavn shouldBe narmesteLederAvkreft.lastName
                coVerify(exactly = 1) {
                    altinnTilgangerService.validateTilgangToOrganization(
                        userPrincipal = eq(principal),
                        orgnummer = eq(narmesteLederAvkreft.orgNumber),
                    )
                }
                coVerify(exactly = 0) {
                    pdpService.hasAccessToResource(any(), any(), any())
                }
            }

            it("should return employee when validateLinemanagerRevoke succeeds for system principal") {
                val principal = DefaultSystemPrincipal
                val narmesteLederAvkreft = linemanagerRevoke()

                prepareCommonValidLinemanagerRevoke(narmesteLederAvkreft)

                val result = service.validateLinemanagerRevoke(narmesteLederAvkreft, principal)

                result.nationalIdentificationNumber shouldBe narmesteLederAvkreft.employeeIdentificationNumber
                result.name.etternavn shouldBe narmesteLederAvkreft.lastName
                coVerify(exactly = 1) {
                    pdpService.hasAccessToResource(
                        user = match<System> { it.id == "systemId" },
                        orgNumberSet = eq(setOf(narmesteLederAvkreft.orgNumber)),
                        resource = eq("nav_syfo_oppgi-narmesteleder"),
                    )
                }
                coVerify(exactly = 0) {
                    altinnTilgangerService.validateTilgangToOrganization(
                        userPrincipal = any<UserPrincipal>(),
                        orgnummer = any(),
                    )
                }
            }

            it("should throw BadRequestException when employee last name does not match in validateLinemanagerRevoke") {
                val fnr = altinnTilgangerClient.accessPolicy.first().hasAccess.first()
                val principal = UserPrincipal(fnr, "token")
                val narmesteLederAvkreft = linemanagerRevoke()

                altinnTilgangerClient.accessPolicy.clear()
                altinnTilgangerClient.addAccess(principal.ident, narmesteLederAvkreft.orgNumber)
                aaregClient.arbeidsForholdForIdent[narmesteLederAvkreft.employeeIdentificationNumber] =
                    listOf(narmesteLederAvkreft.orgNumber to "hovedenhet")
                pdlService.prepareGetPersonResponse(
                    narmesteLederAvkreft.employeeIdentificationNumber,
                    differentLastName(narmesteLederAvkreft.lastName),
                )

                val exception = shouldThrow<ApiErrorException.BadRequestException> {
                    service.validateLinemanagerRevoke(narmesteLederAvkreft, principal)
                }
                exception.type shouldBe ErrorType.EMPLOYEE_NAME_NATIONAL_IDENTIFICATION_NUMBER_MISMATCH
            }

            it("should throw BadRequestException when employee arbeidsforhold does not match request in validateLinemanagerRevoke") {
                val fnr = altinnTilgangerClient.accessPolicy.first().hasAccess.first()
                val principal = UserPrincipal(fnr, "token")
                val narmesteLederAvkreft = linemanagerRevoke()

                altinnTilgangerClient.accessPolicy.clear()
                altinnTilgangerClient.addAccess(principal.ident, narmesteLederAvkreft.orgNumber)
                aaregClient.arbeidsForholdForIdent[narmesteLederAvkreft.employeeIdentificationNumber] =
                    listOf(differentOrgNumber(narmesteLederAvkreft.orgNumber) to "hovedenhet")

                val exception = shouldThrow<ApiErrorException.BadRequestException> {
                    service.validateLinemanagerRevoke(narmesteLederAvkreft, principal)
                }
                exception.type shouldBe ErrorType.EMPLOYEE_MISSING_EMPLOYMENT_IN_ORG

                coVerify(exactly = 0) {
                    pdlService.getPersonOrThrowApiError(any())
                }
            }
        }
    })
