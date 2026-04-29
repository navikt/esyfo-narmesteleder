package no.nav.syfo.narmesteleder.service.validators

import DefaultSystemPrincipal
import faker
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.mockk.clearAllMocks
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.spyk
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
import no.nav.syfo.narmesteleder.service.ApiVersion

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
        }

        describe("validatePrincipalAccessToOrgnumber") {
            it("should call AltinnTilgangerService when principal is BrukerPrincipal") {
                val fnr = altinnTilgangerClient.accessPolicy.first().hasAccess.first()
                val orgNumber = faker.numerify("#########")
                val principal = UserPrincipal(fnr, "token")

                shouldThrow<ApiErrorException.ForbiddenException> {
                    validator.validatePrincipalAccessToOrgnumber(
                        principal = principal,
                        orgNumber = orgNumber,
                        apiVersion = ApiVersion.V1
                    )
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

                validator.validatePrincipalAccessToOrgnumber(principal, orgNumber, apiVersion = ApiVersion.V1)
                coVerify(exactly = 0) {
                    altinnTilgangerService.validateTilgangToOrganization(
                        any<AltinnTilgang>(),
                        any(),
                    )
                }
                coVerify(exactly = 1) {
                    pdpService.hasAccessToResource(
                        user = match<System> { it.id == "systemId" },
                        orgNumberSet = eq(setOf(orgNumber)),
                        resource = eq("nav_syfo_oppgi-narmesteleder"),
                    )
                }
            }
        }
    })
