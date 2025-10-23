package no.nav.syfo.narmestelder.service

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
import no.nav.syfo.narmesteleder.service.ValidateActiveSykmeldingException
import no.nav.syfo.narmesteleder.service.ValidationService
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
            val narmestelederRelasjonerWrite = narmesteLederRelasjon().copy(sykmeldtFnr = fnr)

            // Act
            shouldThrow<ApiErrorException.ForbiddenException> {
                service.validateNarmesteleder(narmestelederRelasjonerWrite, principal)
            }
            // Assert
            coVerify(exactly = 1) {
                altinnTilgangerService.validateTilgangToOrganisasjon(
                    eq(principal),
                    eq(narmestelederRelasjonerWrite.organisasjonsnummer)
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
                sykmeldtFnr = userWithAccess.first,
                organisasjonsnummer = userWithAccess.second
            )
            val principal = OrganisasjonPrincipal("0192:${userWithAccess.second}", "token")

            // Act
            shouldThrow<ApiErrorException.BadRequestException> {
                service.validateNarmesteleder(narmestelederRelasjonerWrite, principal)
            }
            // Assert
            coVerify(exactly = 0) {
                altinnTilgangerService.validateTilgangToOrganisasjon(
                    any(),
                    eq(narmestelederRelasjonerWrite.organisasjonsnummer)
                )
            }
            coVerify(exactly = 1) {
                aaregService.findOrgNumbersByPersonIdent(eq(narmestelederRelasjonerWrite.sykmeldtFnr))
                aaregService.findOrgNumbersByPersonIdent(eq(narmestelederRelasjonerWrite.leder.fnr))
                pdlService.getPersonOrThrowApiError(eq(narmestelederRelasjonerWrite.sykmeldtFnr))
                pdlService.getPersonOrThrowApiError(eq(narmestelederRelasjonerWrite.leder.fnr))
            }
        }

        it("should return true when calling the validateActiveSykmelding") {
            service.validataActiveSykmelding("12345678901", "FAKE_ORGNR") shouldBe true
        }

        it("should return false when calling the validateActiveSykmelding") {
            shouldThrow< ValidateActiveSykmeldingException> {
                service.validataActiveSykmelding("11111111111", "FAKE_ORGNR") shouldBe false
            }
        }
    }

    describe("validateNarmestelederAvkreft") {
        it("should call AltinnTilgangerService when principal is BrukerPrincipal") {
            // Arrange
            val fnr = altinnTilgangerClient.usersWithAccess.first().first
            val principal = BrukerPrincipal(fnr, "token")
            val narmesteLederAvkreft = narmesteLederAvkreft().copy(sykmeldtFnr = fnr)

            // Act
            shouldThrow<ApiErrorException.ForbiddenException> {
                service.validateNarmestelederAvkreft(narmesteLederAvkreft, principal)
            }
            // Assert
            coVerify(exactly = 1) {
                altinnTilgangerService.validateTilgangToOrganisasjon(
                    eq(principal),
                    eq(narmesteLederAvkreft.organisasjonsnummer)
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
                sykmeldtFnr = userWithAccess.first,
                organisasjonsnummer = userWithAccess.second
            )
            val principal = OrganisasjonPrincipal("0192:${userWithAccess.second}", "token")

            // Act
            shouldThrow<ApiErrorException.BadRequestException> {
                service.validateNarmestelederAvkreft(narmesteLederAvkreft, principal)
            }
            // Assert
            coVerify(exactly = 0) {
                altinnTilgangerService.validateTilgangToOrganisasjon(
                    any(),
                    eq(narmesteLederAvkreft.organisasjonsnummer)
                )
            }
            coVerify(exactly = 1) {
                aaregService.findOrgNumbersByPersonIdent(eq(narmesteLederAvkreft.sykmeldtFnr))
                pdlService.getPersonOrThrowApiError(eq(narmesteLederAvkreft.sykmeldtFnr))
            }
        }
    }
})
