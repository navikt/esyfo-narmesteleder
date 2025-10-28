package no.nav.syfo.narmesteleder.service

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.clearAllMocks
import io.mockk.coVerify
import io.mockk.spyk
import narmesteLederAvkreft
import narmesteLederRelasjon
import no.nav.syfo.aareg.AaregService
import no.nav.syfo.aareg.client.FakeAaregClient
import no.nav.syfo.altinntilganger.AltinnTilgangerService
import no.nav.syfo.altinntilganger.client.FakeAltinnTilgangerClient
import no.nav.syfo.application.auth.BrukerPrincipal
import no.nav.syfo.application.auth.OrganisasjonPrincipal
import no.nav.syfo.application.exception.ApiErrorException
import no.nav.syfo.dinesykmeldte.DinesykmeldteService
import no.nav.syfo.dinesykmeldte.client.FakeDinesykmeldteClient
import no.nav.syfo.pdl.client.FakePdlClient

class ValidationServiceTest : DescribeSpec({
    val altinnTilgangerClient = FakeAltinnTilgangerClient()
    val altinnTilgangerService = spyk(AltinnTilgangerService(altinnTilgangerClient))
    val dinesykmeldteClient = FakeDinesykmeldteClient()
    val dinesykmeldteService = spyk(DinesykmeldteService(dinesykmeldteClient))

    val aaregClient = FakeAaregClient()
    val aaregService = spyk(AaregService(aaregClient))
    val pdlClient = FakePdlClient()
    val pdlService = spyk(no.nav.syfo.pdl.PdlService(pdlClient))
    val service = ValidationService(
        pdlService = pdlService,
        aaregService = aaregService,
        altinnTilgangerService = altinnTilgangerService,
        dinesykmeldteService = dinesykmeldteService
    )
    beforeTest {
        clearAllMocks()
    }

    describe("validateNarmesteleder") {
        it("should call AltinnTilgangerService when principal is BrukerPrincipal") {
            // Arrange
            val fnr = altinnTilgangerClient.usersWithAccess.first().first
            val principal = BrukerPrincipal(fnr, "token")
            val narmestelederRelasjonerWrite = narmesteLederRelasjon().copy(employeeIdentificationNumber = fnr)

            // Act
            shouldThrow<ApiErrorException.ForbiddenException> {
                service.validateEmployeLeaderConnection(narmestelederRelasjonerWrite, principal)
            }
            // Assert
            coVerify(exactly = 1) {
                altinnTilgangerService.validateTilgangToOrganisasjon(
                    eq(principal),
                    eq(narmestelederRelasjonerWrite.orgnumber)
                )
            }
            coVerify(exactly = 0) {
                aaregService.findOrgNumbersByPersonIdent(any())
                pdlService.getPersonOrThrowApiError(any())
            }
        }

        it("should not call AltinnTilgangerService when principal is OrganizationPrincipal") {
            // Arrange
            val userWithAccess = altinnTilgangerClient.usersWithAccess.first()
            val narmestelederRelasjonerWrite = narmesteLederRelasjon().copy(
                employeeIdentificationNumber = userWithAccess.first,
                orgnumber = userWithAccess.second
            )
            val principal = OrganisasjonPrincipal("0192:${userWithAccess.second}", "token")

            // Act
            shouldThrow<ApiErrorException.BadRequestException> {
                service.validateEmployeLeaderConnection(narmestelederRelasjonerWrite, principal)
            }
            // Assert
            coVerify(exactly = 0) {
                altinnTilgangerService.validateTilgangToOrganisasjon(
                    any(),
                    eq(narmestelederRelasjonerWrite.orgnumber)
                )
            }
            coVerify(exactly = 1) {
                aaregService.findOrgNumbersByPersonIdent(eq(narmestelederRelasjonerWrite.employeeIdentificationNumber))
                aaregService.findOrgNumbersByPersonIdent(eq(narmestelederRelasjonerWrite.leader.nationalIdentificationNumber))
                pdlService.getPersonOrThrowApiError(eq(narmestelederRelasjonerWrite.employeeIdentificationNumber))
                pdlService.getPersonOrThrowApiError(eq(narmestelederRelasjonerWrite.leader.nationalIdentificationNumber))
            }
        }

        it("should return true when calling the validateActiveSykmelding") {
            service.validataActiveSickLeave("12345678901", "FAKE_ORGNR") shouldBe true
        }
    }

    describe("validateNarmestelederAvkreft") {
        it("should call AltinnTilgangerService when principal is BrukerPrincipal") {
            // Arrange
            val fnr = altinnTilgangerClient.usersWithAccess.first().first
            val principal = BrukerPrincipal(fnr, "token")
            val narmesteLederAvkreft = narmesteLederAvkreft().copy(employeeIdentificationNumber = fnr)

            // Act
            shouldThrow<ApiErrorException.ForbiddenException> {
                service.validateEmployeeLeaderConnectionDiscontinue(narmesteLederAvkreft, principal)
            }
            // Assert
            coVerify(exactly = 1) {
                altinnTilgangerService.validateTilgangToOrganisasjon(
                    eq(principal),
                    eq(narmesteLederAvkreft.orgnumber)
                )
            }
            coVerify(exactly = 0) {
                aaregService.findOrgNumbersByPersonIdent(any())
                pdlService.getPersonOrThrowApiError(any())
            }
        }

        it("should not call AltinnTilgangerService when principal is OrganizationPrincipal") {
            // Arrange
            val userWithAccess = altinnTilgangerClient.usersWithAccess.first()
            val narmesteLederAvkreft = narmesteLederAvkreft().copy(
                employeeIdentificationNumber = userWithAccess.first,
                orgnumber = userWithAccess.second
            )
            val principal = OrganisasjonPrincipal("0192:${userWithAccess.second}", "token")

            // Act
            shouldThrow<ApiErrorException.BadRequestException> {
                service.validateEmployeeLeaderConnectionDiscontinue(narmesteLederAvkreft, principal)
            }
            // Assert
            coVerify(exactly = 0) {
                altinnTilgangerService.validateTilgangToOrganisasjon(
                    any(),
                    eq(narmesteLederAvkreft.orgnumber)
                )
            }
            coVerify(exactly = 1) {
                aaregService.findOrgNumbersByPersonIdent(eq(narmesteLederAvkreft.employeeIdentificationNumber))
                pdlService.getPersonOrThrowApiError(eq(narmesteLederAvkreft.employeeIdentificationNumber))
            }
        }
    }
})
