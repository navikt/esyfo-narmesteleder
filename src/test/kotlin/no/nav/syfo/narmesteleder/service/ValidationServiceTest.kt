package no.nav.syfo.narmesteleder.service

import DefaultSystemPrincipal
import faker
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.spyk
import linemanagerRevoke
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
import no.nav.syfo.ereg.EregService
import no.nav.syfo.ereg.client.FakeEregClient
import no.nav.syfo.narmesteleder.domain.LinemanagerRevoke
import no.nav.syfo.narmesteleder.domain.PersonalIdentificationNumber
import no.nav.syfo.narmesteleder.service.validators.PrincipalAccessValidator
import no.nav.syfo.pdl.PdlService
import no.nav.syfo.pdl.client.FakePdlClient
import prepareGetPersonResponse

class ValidationServiceTest :
    DescribeSpec({
        val altinnTilgangerClient = FakeAltinnTilgangerClient()
        val altinnTilgangerService = spyk(AltinnTilgangerService(altinnTilgangerClient))
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
        val service = ValidationService(
            pdlService = pdlService,
            principalAccessValidator = principalAccessValidator,
        )

        fun differentLastName(lastName: String): String = faker.name().lastName().let {
            if (it != lastName) it else lastName.reversed()
        }

        fun prepareValidLinemanagerRevoke(
            linemanagerRevoke: LinemanagerRevoke,
            principal: UserPrincipal,
        ) {
            altinnTilgangerClient.accessPolicy.clear()
            altinnTilgangerClient.addAccess(principal.ident, linemanagerRevoke.orgNumber.value)
            pdlService.prepareGetPersonResponse(
                linemanagerRevoke.employeeIdentificationNumber.value,
                linemanagerRevoke.lastName,
            )
        }

        fun prepareCommonValidLinemanagerRevoke(linemanagerRevoke: LinemanagerRevoke) {
            pdlService.prepareGetPersonResponse(
                linemanagerRevoke.employeeIdentificationNumber.value,
                linemanagerRevoke.lastName,
            )
        }

        beforeTest {
            clearAllMocks(currentThreadOnly = true)
            altinnTilgangerClient.reset()
            coEvery { pdlCacheMock.getPerson(any()) } returns null
            coEvery { eregCache.getOrganisasjon(any()) } returns null
            eregClient.organisasjoner.clear()
        }

        describe("validateLinemanagerRevoke") {
            it("should call AltinnTilgangerService when principal is BrukerPrincipal") {
                val fnr = altinnTilgangerClient.accessPolicy.first().hasAccess.first()
                val principal = UserPrincipal(fnr, "token")
                val narmesteLederAvkreft = linemanagerRevoke().copy(employeeIdentificationNumber = PersonalIdentificationNumber(fnr))

                shouldThrow<ApiErrorException.ForbiddenException> {
                    service.validateLinemanagerRevoke(narmesteLederAvkreft, principal)
                }
                coVerify(exactly = 1) {
                    altinnTilgangerService.getAuthorizedAltinnTilgang(
                        userPrincipal = eq(principal),
                        orgnummer = eq(narmesteLederAvkreft.orgNumber.value),
                    )
                }
                coVerify(exactly = 0) {
                    pdpService.accessDecisionForResource(any(), any(), any())
                    pdlService.getPersonOrThrowApiError(any())
                }
            }

            it("should return employee when validateLinemanagerRevoke succeeds for user principal") {
                val fnr = altinnTilgangerClient.accessPolicy.first().hasAccess.first()
                val principal = UserPrincipal(fnr, "token")
                val narmesteLederAvkreft = linemanagerRevoke()

                prepareValidLinemanagerRevoke(narmesteLederAvkreft, principal)

                val result = service.validateLinemanagerRevoke(narmesteLederAvkreft, principal)

                result.nationalIdentificationNumber.value shouldBe narmesteLederAvkreft.employeeIdentificationNumber.value
                result.name.etternavn shouldBe narmesteLederAvkreft.lastName
                coVerify(exactly = 1) {
                    altinnTilgangerService.getAuthorizedAltinnTilgang(
                        userPrincipal = eq(principal),
                        orgnummer = eq(narmesteLederAvkreft.orgNumber.value),
                    )
                }
                coVerify(exactly = 0) {
                    pdpService.accessDecisionForResource(any(), any(), any())
                }
            }

            it("should return employee when validateLinemanagerRevoke succeeds for system principal") {
                val principal = DefaultSystemPrincipal
                val narmesteLederAvkreft = linemanagerRevoke()

                prepareCommonValidLinemanagerRevoke(narmesteLederAvkreft)

                val result = service.validateLinemanagerRevoke(narmesteLederAvkreft, principal)

                result.nationalIdentificationNumber.value shouldBe narmesteLederAvkreft.employeeIdentificationNumber.value
                result.name.etternavn shouldBe narmesteLederAvkreft.lastName
                coVerify(exactly = 1) {
                    pdpService.accessDecisionForResource(
                        user = match<System> { it.id == "systemId" },
                        orgNumberSet = eq(setOf(narmesteLederAvkreft.orgNumber.value)),
                        resource = eq("nav_syfo_oppgi-narmesteleder"),
                    )
                }
                coVerify(exactly = 0) {
                    altinnTilgangerService.getAuthorizedAltinnTilgang(
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
                altinnTilgangerClient.addAccess(principal.ident, narmesteLederAvkreft.orgNumber.value)
                pdlService.prepareGetPersonResponse(
                    narmesteLederAvkreft.employeeIdentificationNumber.value,
                    differentLastName(narmesteLederAvkreft.lastName),
                )

                val exception = shouldThrow<ApiErrorException.BadRequestException> {
                    service.validateLinemanagerRevoke(narmesteLederAvkreft, principal)
                }
                exception.type shouldBe ErrorType.EMPLOYEE_NAME_NATIONAL_IDENTIFICATION_NUMBER_MISMATCH
            }

            it("should return employee without looking up employment in validateLinemanagerRevoke") {
                val fnr = altinnTilgangerClient.accessPolicy.first().hasAccess.first()
                val principal = UserPrincipal(fnr, "token")
                val narmesteLederAvkreft = linemanagerRevoke()

                altinnTilgangerClient.accessPolicy.clear()
                altinnTilgangerClient.addAccess(principal.ident, narmesteLederAvkreft.orgNumber.value)
                pdlService.prepareGetPersonResponse(
                    narmesteLederAvkreft.employeeIdentificationNumber.value,
                    narmesteLederAvkreft.lastName,
                )

                val employee = service.validateLinemanagerRevoke(narmesteLederAvkreft, principal)

                employee.nationalIdentificationNumber shouldBe narmesteLederAvkreft.employeeIdentificationNumber
            }
        }
    })
