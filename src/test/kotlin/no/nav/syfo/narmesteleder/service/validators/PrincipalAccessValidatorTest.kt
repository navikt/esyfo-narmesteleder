package no.nav.syfo.narmesteleder.service.validators

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
import no.nav.syfo.altinn.pdp.client.FakePdpClient
import no.nav.syfo.altinn.pdp.client.System
import no.nav.syfo.altinn.pdp.service.PdpService
import no.nav.syfo.altinntilganger.AltinnTilgangerService
import no.nav.syfo.altinntilganger.client.FakeAltinnTilgangerClient
import no.nav.syfo.application.api.ErrorType
import no.nav.syfo.application.auth.UserPrincipal
import no.nav.syfo.application.exception.ApiErrorException
import no.nav.syfo.application.valkey.EregCache
import no.nav.syfo.ereg.EregService
import no.nav.syfo.ereg.client.FakeEregClient
import no.nav.syfo.ereg.client.Organisasjon
import organisasjon

class PrincipalAccessValidatorTest :
    DescribeSpec({
        val altinnTilgangerClient = FakeAltinnTilgangerClient()
        val altinnTilgangerService = spyk(AltinnTilgangerService(altinnTilgangerClient))
        val pdpClient = FakePdpClient()
        val pdpService = spyk(PdpService(pdpClient))
        val eregClient = FakeEregClient()
        val eregCache = mockk<EregCache>(relaxed = true)
        val eregService = spyk(EregService(eregClient, eregCache))
        val validator = PrincipalAccessValidator(altinnTilgangerService, pdpService, eregService = eregService)

        beforeTest {
            clearAllMocks()
            altinnTilgangerClient.reset()
            eregClient.organisasjoner.clear()
            coEvery { eregCache.getOrganisasjon(any()) } returns null
        }

        describe("validatePrincipalAccessToOrgnumber") {
            it("should return trimmed organization name when user principal has access") {
                val fnr = altinnTilgangerClient.accessPolicy.first().hasAccess.first()
                val orgNumber = faker.numerify("#########")
                val principal = UserPrincipal(fnr, "token")

                altinnTilgangerClient.accessPolicy.clear()
                altinnTilgangerClient.addAccess(principal.ident, orgNumber)

                val result = validator.validatePrincipalAccessToOrgnumber(principal = principal, orgNumber = orgNumber)

                result shouldBe "Test Org"
                coVerify(exactly = 1) {
                    altinnTilgangerService.validateTilgangToOrganization(
                        userPrincipal = eq(principal),
                        orgnummer = eq(orgNumber),
                    )
                }
            }

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
                val orgNumber = faker.numerify("#########")
                val principal = DefaultSystemPrincipal.copy(
                    ident = "0192:${orgNumber.reversed()}",
                )

                val result = validator.validatePrincipalAccessToOrgnumber(principal, orgNumber)

                result shouldBe null
                coVerify(exactly = 0) {
                    altinnTilgangerService.validateTilgangToOrganization(
                        userPrincipal = any<UserPrincipal>(),
                        orgnummer = any(),
                    )
                }
                coVerify(exactly = 1) {
                    pdpService.hasAccessToResource(
                        user = match<System> { it.id == "systemId" },
                        orgNumberSet = eq(setOf(orgNumber)),
                        resource = eq("nav_syfo_oppgi-narmesteleder"),
                    )
                }
                coVerify(exactly = 0) {
                    eregService.getOrganization(any())
                }
            }

            it("should throw ForbiddenException when system principal is denied direct access and principal org is not in hierarchy") {
                val requestedOrgnumber = faker.numerify("#########")
                val principalOrgnumber = faker.numerify("#########")
                val principal = DefaultSystemPrincipal.copy(ident = "0192:$principalOrgnumber")

                eregClient.organisasjoner[requestedOrgnumber] = organisasjon().copy(
                    organisasjonsnummer = requestedOrgnumber,
                    inngaarIJuridiskEnheter = listOf(
                        Organisasjon(organisasjonsnummer = faker.numerify("#########")),
                    ),
                )
                coEvery {
                    pdpService.hasAccessToResource(any(), eq(setOf(requestedOrgnumber)), any())
                } returns false

                val exception = shouldThrow<ApiErrorException.ForbiddenException> {
                    validator.validatePrincipalAccessToOrgnumber(principal, requestedOrgnumber)
                }

                exception.type shouldBe ErrorType.MISSING_ALITINN_RESOURCE_ACCESS
                coVerify(exactly = 1) {
                    pdpService.hasAccessToResource(
                        user = match<System> { it.id == "systemId" },
                        orgNumberSet = eq(setOf(requestedOrgnumber)),
                        resource = eq("nav_syfo_oppgi-narmesteleder"),
                    )
                }
                coVerify(exactly = 0) {
                    pdpService.hasAccessToResource(
                        user = any(),
                        orgNumberSet = eq(setOf(principalOrgnumber)),
                        resource = any(),
                    )
                }
            }

            it("should allow system principal access through org hierarchy fallback") {
                val requestedOrgnumber = faker.numerify("#########")
                val principalOrgnumber = faker.numerify("#########")
                val principal = DefaultSystemPrincipal.copy(ident = "0192:$principalOrgnumber")

                eregClient.organisasjoner[requestedOrgnumber] = organisasjon().copy(
                    organisasjonsnummer = requestedOrgnumber,
                    inngaarIJuridiskEnheter = listOf(
                        Organisasjon(organisasjonsnummer = principalOrgnumber),
                    ),
                )
                coEvery {
                    pdpService.hasAccessToResource(any(), eq(setOf(requestedOrgnumber)), any())
                } returns false
                coEvery {
                    pdpService.hasAccessToResource(any(), eq(setOf(principalOrgnumber)), any())
                } returns true

                val result = validator.validatePrincipalAccessToOrgnumber(principal, requestedOrgnumber)

                result shouldBe null
                coVerify(exactly = 1) {
                    pdpService.hasAccessToResource(
                        user = match<System> { it.id == "systemId" },
                        orgNumberSet = eq(setOf(requestedOrgnumber)),
                        resource = eq("nav_syfo_oppgi-narmesteleder"),
                    )
                }
                coVerify(exactly = 1) {
                    pdpService.hasAccessToResource(
                        user = match<System> { it.id == "systemId" },
                        orgNumberSet = eq(setOf(principalOrgnumber)),
                        resource = eq("nav_syfo_oppgi-narmesteleder"),
                    )
                }
            }

            it("should throw ForbiddenException when system principal is denied both direct access and hierarchy fallback") {
                val requestedOrgnumber = faker.numerify("#########")
                val principalOrgnumber = faker.numerify("#########")
                val principal = DefaultSystemPrincipal.copy(ident = "0192:$principalOrgnumber")

                eregClient.organisasjoner[requestedOrgnumber] = organisasjon().copy(
                    organisasjonsnummer = requestedOrgnumber,
                    inngaarIJuridiskEnheter = listOf(
                        Organisasjon(organisasjonsnummer = principalOrgnumber),
                    ),
                )
                coEvery {
                    pdpService.hasAccessToResource(any(), eq(setOf(requestedOrgnumber)), any())
                } returns false
                coEvery {
                    pdpService.hasAccessToResource(any(), eq(setOf(principalOrgnumber)), any())
                } returns false

                val exception = shouldThrow<ApiErrorException.ForbiddenException> {
                    validator.validatePrincipalAccessToOrgnumber(principal, requestedOrgnumber)
                }

                exception.type shouldBe ErrorType.MISSING_ALITINN_RESOURCE_ACCESS
                coVerify(exactly = 1) {
                    pdpService.hasAccessToResource(
                        user = match<System> { it.id == "systemId" },
                        orgNumberSet = eq(setOf(requestedOrgnumber)),
                        resource = eq("nav_syfo_oppgi-narmesteleder"),
                    )
                }
                coVerify(exactly = 1) {
                    pdpService.hasAccessToResource(
                        user = match<System> { it.id == "systemId" },
                        orgNumberSet = eq(setOf(principalOrgnumber)),
                        resource = eq("nav_syfo_oppgi-narmesteleder"),
                    )
                }
            }
        }
    })
