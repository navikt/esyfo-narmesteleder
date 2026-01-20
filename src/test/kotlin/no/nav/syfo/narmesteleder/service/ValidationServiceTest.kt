package no.nav.syfo.narmesteleder.service

import DefaultSystemPrincipal
import faker
import io.kotest.assertions.throwables.shouldNotThrow
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.clearAllMocks
import io.mockk.coVerify
import io.mockk.spyk
import linemanager
import linemanagerRevoke
import no.nav.syfo.aareg.AaregService
import no.nav.syfo.aareg.client.FakeAaregClient
import no.nav.syfo.altinn.pdp.client.FakePdpClient
import no.nav.syfo.altinn.pdp.client.System
import no.nav.syfo.altinn.pdp.service.PdpService
import no.nav.syfo.altinntilganger.AltinnTilgangerService
import no.nav.syfo.altinntilganger.client.AltinnTilgang
import no.nav.syfo.altinntilganger.client.FakeAltinnTilgangerClient
import no.nav.syfo.application.auth.UserPrincipal
import no.nav.syfo.application.exception.ApiErrorException
import no.nav.syfo.dinesykmeldte.DinesykmeldteService
import no.nav.syfo.dinesykmeldte.client.FakeDinesykmeldteClient
import no.nav.syfo.ereg.EregService
import no.nav.syfo.ereg.client.FakeEregClient
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
        val eregService = spyk(EregService(eregClient))
        val pdlClient = FakePdlClient()
        val pdlService = spyk(PdlService(pdlClient))
        val pdpClient = FakePdpClient()
        val pdpService = spyk(PdpService(pdpClient))
        val service = ValidationService(
            pdlService = pdlService,
            aaregService = aaregService,
            altinnTilgangerService = altinnTilgangerService,
            dinesykmeldteService = dinesykmeldteService,
            pdpService = pdpService,
            eregService = eregService,
        )
        beforeTest {
            clearAllMocks()
        }

        describe("validateNarmesteleder") {
            it("should not thrown when all validation passes and principal is BrukerPrincipal") {
                // Arrange
                val fnr = altinnTilgangerClient.usersWithAccess.first().first
                val principal = UserPrincipal(fnr, "token")
                val narmestelederRelasjonerWrite = linemanager().copy(employeeIdentificationNumber = fnr)

                aaregClient.arbeidsForholdForIdent[narmestelederRelasjonerWrite.manager.nationalIdentificationNumber] =
                    listOf(narmestelederRelasjonerWrite.orgNumber to "hovedenhet")
                aaregClient.arbeidsForholdForIdent[narmestelederRelasjonerWrite.employeeIdentificationNumber] =
                    listOf(narmestelederRelasjonerWrite.orgNumber to "hovedenhet")
                pdlService.prepareGetPersonResponse(
                    narmestelederRelasjonerWrite.employeeIdentificationNumber,
                    narmestelederRelasjonerWrite.lastName
                )
                pdlService.prepareGetPersonResponse(narmestelederRelasjonerWrite.manager)
                altinnTilgangerClient.usersWithAccess.clear()
                altinnTilgangerClient.usersWithAccess.add(principal.ident to narmestelederRelasjonerWrite.orgNumber)
                // Act
                shouldNotThrow<Exception> {
                    service.validateLinemanager(narmestelederRelasjonerWrite, principal)
                }
                // Assert
                coVerify(exactly = 1) {
                    altinnTilgangerService.validateTilgangToOrganization(
                        eq(principal),
                        eq(narmestelederRelasjonerWrite.orgNumber)
                    )
                    pdlService.getPersonOrThrowApiError(narmestelederRelasjonerWrite.manager.nationalIdentificationNumber)
                    pdlService.getPersonOrThrowApiError(narmestelederRelasjonerWrite.employeeIdentificationNumber)
                    aaregService.findOrgNumbersByPersonIdent(narmestelederRelasjonerWrite.employeeIdentificationNumber)
                    aaregService.findOrgNumbersByPersonIdent(narmestelederRelasjonerWrite.manager.nationalIdentificationNumber)
                }
                coVerify(exactly = 0) {
                    pdpService.hasAccessToResource(any(), any(), any())
                }
            }

            it("should throw BadRequestException when lastName of manager does mot match value in PDL") {
                // Arrange
                val fnr = altinnTilgangerClient.usersWithAccess.first().first
                val principal = UserPrincipal(fnr, "token")
                val narmestelederRelasjonerWrite = linemanager().copy(employeeIdentificationNumber = fnr)

                altinnTilgangerClient.usersWithAccess.clear()
                altinnTilgangerClient.usersWithAccess.add(principal.ident to narmestelederRelasjonerWrite.orgNumber)
                // Act
                val exception = shouldThrow<ApiErrorException.BadRequestException> {
                    service.validateLinemanager(narmestelederRelasjonerWrite, principal)
                }
                // Assert
                coVerify(exactly = 1) {
                    altinnTilgangerService.validateTilgangToOrganization(
                        eq(principal),
                        eq(narmestelederRelasjonerWrite.orgNumber)
                    )
                    pdlService.getPersonOrThrowApiError(narmestelederRelasjonerWrite.manager.nationalIdentificationNumber)
                    pdlService.getPersonOrThrowApiError(narmestelederRelasjonerWrite.employeeIdentificationNumber)
                    aaregService.findOrgNumbersByPersonIdent(narmestelederRelasjonerWrite.employeeIdentificationNumber)
                    aaregService.findOrgNumbersByPersonIdent(narmestelederRelasjonerWrite.manager.nationalIdentificationNumber)
                }
                coVerify(exactly = 0) {
                    pdpService.hasAccessToResource(any(), any(), any())
                }

                exception.message shouldBe "Last name for linemanager does not correspond with registered value for the given national identification number"
            }

            it("should call AltinnTilgangerService when principal is BrukerPrincipal") {
                // Arrange
                val fnr = altinnTilgangerClient.usersWithAccess.first().first
                val principal = UserPrincipal(fnr, "token")
                val narmestelederRelasjonerWrite = linemanager().copy(employeeIdentificationNumber = fnr)

                // Act
                shouldThrow<ApiErrorException.ForbiddenException> {
                    service.validateLinemanager(narmestelederRelasjonerWrite, principal)
                }
                // Assert
                coVerify(exactly = 1) {
                    altinnTilgangerService.validateTilgangToOrganization(
                        eq(principal),
                        eq(narmestelederRelasjonerWrite.orgNumber)
                    )
                }
                coVerify(exactly = 0) {
                    pdpService.hasAccessToResource(any(), any(), any())
                    aaregService.findOrgNumbersByPersonIdent(any())
                    pdlService.getPersonOrThrowApiError(any())
                }
            }

            it("should not call AltinnTilgangerService when principal is OrganizationPrincipal") {
                // Arrange
                val userWithAccess = altinnTilgangerClient.usersWithAccess.first()
                val narmestelederRelasjonerWrite = linemanager().copy(
                    employeeIdentificationNumber = userWithAccess.first,
                    orgNumber = userWithAccess.second
                )
                val principal = DefaultSystemPrincipal.copy(
                    ident = "0192:${userWithAccess.second}",
                )

                // Act
                shouldThrow<ApiErrorException.BadRequestException> {
                    service.validateLinemanager(narmestelederRelasjonerWrite, principal)
                }
                // Assert
                coVerify(exactly = 0) {
                    altinnTilgangerService.validateTilgangToOrganization(
                        any<AltinnTilgang>(),
                        eq(narmestelederRelasjonerWrite.orgNumber)
                    )
                }
                coVerify(exactly = 1) {
                    pdpService.hasAccessToResource(
                        match<System> { it.id == "systemId" },
                        eq(setOf(userWithAccess.second, "systemowner")),
                        eq("nav_syfo_oppgi-narmesteleder")
                    )
                    aaregService.findOrgNumbersByPersonIdent(eq(narmestelederRelasjonerWrite.employeeIdentificationNumber))
                    aaregService.findOrgNumbersByPersonIdent(eq(narmestelederRelasjonerWrite.manager.nationalIdentificationNumber))
                    pdlService.getPersonOrThrowApiError(eq(narmestelederRelasjonerWrite.employeeIdentificationNumber))
                    pdlService.getPersonOrThrowApiError(eq(narmestelederRelasjonerWrite.manager.nationalIdentificationNumber))
                }
            }
        }

        describe("validateNarmestelederAvkreft") {
            it("should call AltinnTilgangerService when principal is BrukerPrincipal") {
                // Arrange
                val fnr = altinnTilgangerClient.usersWithAccess.first().first
                val principal = UserPrincipal(fnr, "token")
                val narmesteLederAvkreft = linemanagerRevoke().copy(employeeIdentificationNumber = fnr)

                // Act
                shouldThrow<ApiErrorException.ForbiddenException> {
                    service.validateLinemanagerRevoke(narmesteLederAvkreft, principal)
                }
                // Assert
                coVerify(exactly = 1) {
                    altinnTilgangerService.validateTilgangToOrganization(
                        eq(principal),
                        eq(narmesteLederAvkreft.orgNumber)
                    )
                }
                coVerify(exactly = 0) {
                    pdpService.hasAccessToResource(any(), any(), any())
                    aaregService.findOrgNumbersByPersonIdent(any())
                    pdlService.getPersonOrThrowApiError(any())
                }
            }

            it("should not call AltinnTilgangerService when principal is OrganizationPrincipal") {
                // Arrange
                val userWithAccess = altinnTilgangerClient.usersWithAccess.first()
                val narmesteLederAvkreft = linemanagerRevoke().copy(
                    employeeIdentificationNumber = userWithAccess.first,
                    orgNumber = userWithAccess.second
                )
                val principal = DefaultSystemPrincipal.copy(
                    ident = "0192:${userWithAccess.second}",
                )

                // Act
                shouldThrow<ApiErrorException.BadRequestException> {
                    service.validateLinemanagerRevoke(narmesteLederAvkreft, principal)
                }
                // Assert
                coVerify(exactly = 0) {
                    altinnTilgangerService.validateTilgangToOrganization(
                        any<AltinnTilgang>(),
                        eq(narmesteLederAvkreft.orgNumber)
                    )
                }
                coVerify(exactly = 1) {
                    pdpService.hasAccessToResource(
                        match<System> { it.id == "systemId" },
                        eq(setOf(userWithAccess.second, "systemowner")),
                        eq("nav_syfo_oppgi-narmesteleder")
                    )
                    aaregService.findOrgNumbersByPersonIdent(eq(narmesteLederAvkreft.employeeIdentificationNumber))
                    pdlService.getPersonOrThrowApiError(eq(narmesteLederAvkreft.employeeIdentificationNumber))
                }
            }

            describe("validateLinemanagerCollectionAccess") {
                it("should call AltinnTilgangerService when principal is BrukerPrincipal") {
                    // Arrange
                    val fnr = altinnTilgangerClient.usersWithAccess.first().first
                    val orgNumber = faker.numerify("#########")
                    val principal = UserPrincipal(fnr, "token")

                    // Act
                    shouldThrow<ApiErrorException.ForbiddenException> {
                        service.validateLinemanagerRequirementCollectionAccess(principal = principal, orgNumber = orgNumber)
                    }
                    // Assert
                    coVerify(exactly = 1) {
                        altinnTilgangerService.validateTilgangToOrganization(
                            eq(principal),
                            eq(orgNumber)
                        )
                    }
                    coVerify(exactly = 0) {
                        pdpService.hasAccessToResource(any(), any(), any())
                        aaregService.findOrgNumbersByPersonIdent(any())
                        pdlService.getPersonOrThrowApiError(any())
                    }
                }

                it("should not call AltinnTilgangerService when principal is Systemprincipal") {
                    // Arrange
                    val orgNumber = eregClient.organisasjoner.keys.first()
                    val principal = DefaultSystemPrincipal.copy(
                        ident = "0192:${orgNumber.reversed()}",
                    )

                    // Act
                    shouldThrow<ApiErrorException.ForbiddenException> {
                        service.validateLinemanagerRequirementCollectionAccess(principal, orgNumber)
                    }
                    // Assert
                    coVerify(exactly = 0) {
                        altinnTilgangerService.validateTilgangToOrganization(
                            any<AltinnTilgang>(),
                            any()
                        )
                    }
                    coVerify(exactly = 1) {
                        pdpService.hasAccessToResource(
                            match<System> { it.id == "systemId" },
                            eq(setOf(orgNumber.reversed(), "systemowner")),
                            eq("nav_syfo_oppgi-narmesteleder")
                        )
                    }
                }

                it("should not throw when access is OK") {
                    // Arrange
                    val orgNumber = faker.numerify("#########")
                    val principal = DefaultSystemPrincipal.copy(
                        ident = "0192:$orgNumber",
                    )

                    // Act
                    shouldNotThrow<ApiErrorException.ForbiddenException> {
                        service.validateLinemanagerRequirementCollectionAccess(principal, orgNumber)
                    }
                    // Assert
                    coVerify(exactly = 0) {
                        altinnTilgangerService.validateTilgangToOrganization(
                            any<AltinnTilgang>(),
                            eq(orgNumber)
                        )
                    }
                    coVerify(exactly = 1) {
                        pdpService.hasAccessToResource(
                            match<System> { it.id == "systemId" },
                            eq(setOf(orgNumber, "systemowner")),
                            eq("nav_syfo_oppgi-narmesteleder")
                        )
                    }
                }

                it("should not throw when access is OK due to system user on parent orgnumber") {
                    // Arrange
                    val organization =
                        eregClient.organisasjoner.filter { it.value.inngaarIJuridiskEnheter != null }.values.first()
                    val orgNumber = organization.organisasjonsnummer
                    val systemUserOrgnumber = organization.inngaarIJuridiskEnheter!!.first().organisasjonsnummer
                    val principal = DefaultSystemPrincipal.copy(
                        ident = "0192:$systemUserOrgnumber",
                    )

                    // Act
                    shouldNotThrow<ApiErrorException.ForbiddenException> {
                        service.validateLinemanagerRequirementCollectionAccess(principal, orgNumber)
                    }
                    // Assert
                    coVerify(exactly = 0) {
                        altinnTilgangerService.validateTilgangToOrganization(
                            any<AltinnTilgang>(),
                            eq(orgNumber)
                        )
                    }
                    coVerify(exactly = 1) {
                        pdpService.hasAccessToResource(
                            match<System> { it.id == "systemId" },
                            eq(setOf(systemUserOrgnumber, "systemowner")),
                            eq("nav_syfo_oppgi-narmesteleder")
                        )
                    }
                    coVerify(exactly = 1) {
                        eregService.getOrganization(
                            match<String> { it == orgNumber },
                        )
                    }
                }
            }
        }
    })
