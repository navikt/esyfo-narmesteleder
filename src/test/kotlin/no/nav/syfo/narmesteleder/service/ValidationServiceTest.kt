package no.nav.syfo.narmesteleder.service

import DefaultSystemPrincipal
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.mockk.clearAllMocks
import io.mockk.coVerify
import io.mockk.spyk
import linemanagerRevoke
import linemanager
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
import no.nav.syfo.pdl.PdlService
import no.nav.syfo.pdl.client.FakePdlClient

class ValidationServiceTest : DescribeSpec({
    val altinnTilgangerClient = FakeAltinnTilgangerClient()
    val altinnTilgangerService = spyk(AltinnTilgangerService(altinnTilgangerClient))
    val dinesykmeldteClient = FakeDinesykmeldteClient()
    val dinesykmeldteService = spyk(DinesykmeldteService(dinesykmeldteClient))

    val aaregClient = FakeAaregClient()
    val aaregService = spyk(AaregService(aaregClient))
    val pdlClient = FakePdlClient()
    val pdlService = spyk(PdlService(pdlClient))
    val pdpClient = FakePdpClient()
    val pdpService = spyk(PdpService(pdpClient))
    val service = ValidationService(
        pdlService = pdlService,
        aaregService = aaregService,
        altinnTilgangerService = altinnTilgangerService,
        dinesykmeldteService = dinesykmeldteService,
        pdpService = pdpService
    )
    beforeTest {
        clearAllMocks()
    }

    describe("validateNarmesteleder") {
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
                    eq(narmestelederRelasjonerWrite.orgnumber)
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
                orgnumber = userWithAccess.second
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
                    eq(narmestelederRelasjonerWrite.orgnumber)
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
                    eq(narmesteLederAvkreft.orgnumber)
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
                orgnumber = userWithAccess.second
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
                    eq(narmesteLederAvkreft.orgnumber)
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
    }
})
