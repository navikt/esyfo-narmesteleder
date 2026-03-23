package no.nav.syfo.narmesteleder.service.validators

import DefaultSystemPrincipal
import faker
import io.kotest.assertions.throwables.shouldNotThrow
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.spyk
import no.nav.syfo.aareg.Arbeidsforhold
import no.nav.syfo.aareg.client.ArbeidsstedType
import no.nav.syfo.aareg.client.OpplysningspliktigType
import no.nav.syfo.altinn.pdp.client.FakePdpClient
import no.nav.syfo.altinn.pdp.client.System
import no.nav.syfo.altinn.pdp.service.PdpService
import no.nav.syfo.altinntilganger.AltinnTilgangerService
import no.nav.syfo.altinntilganger.client.AltinnTilgang
import no.nav.syfo.altinntilganger.client.FakeAltinnTilgangerClient
import no.nav.syfo.application.auth.UserPrincipal
import no.nav.syfo.application.exception.ApiErrorException
import no.nav.syfo.application.valkey.EregCache
import no.nav.syfo.ereg.EregService
import no.nav.syfo.ereg.client.FakeEregClient

class PrincipalAccessValidatorTest :
    DescribeSpec({
        val altinnTilgangerClient = FakeAltinnTilgangerClient()
        val altinnTilgangerService = spyk(AltinnTilgangerService(altinnTilgangerClient))
        val pdpClient = FakePdpClient()
        val pdpService = spyk(PdpService(pdpClient))
        val eregClient = FakeEregClient()
        val eregCache = mockk<EregCache>(relaxed = true)
        val eregService = spyk(EregService(eregClient, eregCache))
        val validator = PrincipalAccessValidator(altinnTilgangerService, pdpService, eregService)

        beforeTest {
            clearAllMocks()
            coEvery { eregCache.getOrganisasjon(any()) } returns null
        }

        describe("validatePrincipalAccessToOrgnumber") {
            it("should call AltinnTilgangerService when principal is BrukerPrincipal") {
                val fnr = altinnTilgangerClient.accessPolicy.first().hasAccess.first()
                val orgNumber = faker.numerify("#########")
                val principal = UserPrincipal(fnr, "token")

                shouldThrow<ApiErrorException.ForbiddenException> {
                    validator.validatePrincipalAccessToOrgnumber(principal = principal, orgNumber = orgNumber)
                }

                coVerify(exactly = 1) {
                    altinnTilgangerService.validateTilgangToOrganization(
                        eq(principal),
                        eq(orgNumber),
                    )
                }
                coVerify(exactly = 0) {
                    pdpService.hasAccessToResource(any(), any(), any())
                }
            }

            it("should not call AltinnTilgangerService when principal is Systemprincipal") {
                val orgNumber = eregClient.organisasjoner.keys.first()
                val principal = DefaultSystemPrincipal.copy(
                    ident = "0192:${orgNumber.reversed()}",
                )

                shouldThrow<ApiErrorException.ForbiddenException> {
                    validator.validatePrincipalAccessToOrgnumber(principal, orgNumber)
                }

                coVerify(exactly = 0) {
                    altinnTilgangerService.validateTilgangToOrganization(
                        any<AltinnTilgang>(),
                        any(),
                    )
                    pdpService.hasAccessToResource(
                        match<System> { it.id == "systemId" },
                        eq(setOf(orgNumber.reversed(), "systemowner")),
                        eq("nav_syfo_oppgi-narmesteleder"),
                    )
                }
            }

            it("should not throw when access is OK") {
                val orgNumber = faker.numerify("#########")
                val principal = DefaultSystemPrincipal.copy(
                    ident = "0192:$orgNumber",
                )

                shouldNotThrow<ApiErrorException.ForbiddenException> {
                    validator.validatePrincipalAccessToOrgnumber(principal, orgNumber)
                }

                coVerify(exactly = 0) {
                    altinnTilgangerService.validateTilgangToOrganization(
                        any<AltinnTilgang>(),
                        eq(orgNumber),
                    )
                }
                coVerify(exactly = 1) {
                    pdpService.hasAccessToResource(
                        match<System> { it.id == "systemId" },
                        eq(setOf(orgNumber, "systemowner")),
                        eq("nav_syfo_oppgi-narmesteleder"),
                    )
                }
            }

            it("should not throw when access is OK due to system user on parent orgnumber") {
                val organization =
                    eregClient.organisasjoner.filter { it.value.inngaarIJuridiskEnheter != null }.values.first()
                val orgNumber = organization.organisasjonsnummer
                val systemUserOrgnumber = organization.inngaarIJuridiskEnheter!!.first().organisasjonsnummer
                val principal = DefaultSystemPrincipal.copy(
                    ident = "0192:$systemUserOrgnumber",
                )

                shouldNotThrow<ApiErrorException.ForbiddenException> {
                    validator.validatePrincipalAccessToOrgnumber(principal, orgNumber)
                }

                coVerify(exactly = 0) {
                    altinnTilgangerService.validateTilgangToOrganization(
                        any<AltinnTilgang>(),
                        eq(orgNumber),
                    )
                }
                coVerify(exactly = 1) {
                    pdpService.hasAccessToResource(
                        match<System> { it.id == "systemId" },
                        eq(setOf(systemUserOrgnumber, "systemowner")),
                        eq("nav_syfo_oppgi-narmesteleder"),
                    )
                }
                coVerify(exactly = 1) {
                    eregService.getOrganization(
                        match<String> { it == orgNumber },
                    )
                }
            }

            it("should validate access from arbeidsforhold orgnumbers without calling Ereg") {
                val orgNumber = faker.numerify("#########")
                val opplysningspliktigOrgnummer = faker.numerify("#########")
                val principal = DefaultSystemPrincipal.copy(
                    ident = "0192:$opplysningspliktigOrgnummer",
                )
                val arbeidsforhold = Arbeidsforhold(
                    orgnummer = orgNumber,
                    arbeidsstedType = ArbeidsstedType.Underenhet,
                    opplysningspliktigOrgnummer = opplysningspliktigOrgnummer,
                    opplysningspliktigType = OpplysningspliktigType.Hovedenhet,
                )

                shouldNotThrow<ApiErrorException.ForbiddenException> {
                    validator.validatePrincipalAccessToOrgnumber(
                        principal = principal,
                        orgNumber = orgNumber,
                        arbeidsforhold = arbeidsforhold,
                    )
                }

                coVerify(exactly = 0) {
                    eregService.getOrganization(any())
                }
                coVerify(exactly = 1) {
                    pdpService.hasAccessToResource(
                        match<System> { it.id == "systemId" },
                        eq(setOf(opplysningspliktigOrgnummer, "systemowner")),
                        eq("nav_syfo_oppgi-narmesteleder"),
                    )
                }
            }
        }
    })
