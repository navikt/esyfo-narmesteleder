package no.nav.syfo.altinntilganger

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.spyk
import no.nav.syfo.altinntilganger.AltinnTilgangerService.Companion.OPPGI_NARMESTELEDER_RESOURCE
import no.nav.syfo.altinntilganger.client.AltinnTilgang
import no.nav.syfo.altinntilganger.client.AltinnTilgangerClient
import no.nav.syfo.altinntilganger.client.AltinnTilgangerResponse
import no.nav.syfo.altinntilganger.client.FakeAltinnTilgangerClient
import no.nav.syfo.application.auth.UserPrincipal

class AltinnTilgangerServiceTest :
    DescribeSpec({
        val altinnTilgangerClient = spyk(FakeAltinnTilgangerClient())
        val altinnTilgangerService = AltinnTilgangerService(altinnTilgangerClient)

        beforeTest {
            clearAllMocks(currentThreadOnly = true)
            altinnTilgangerClient.reset()
        }

        fun altinnTilgang(
            orgnr: String,
            altinn3Tilganger: Set<String> = emptySet(),
            underenheter: List<AltinnTilgang> = emptyList(),
            navn: String = "Org $orgnr",
        ) = AltinnTilgang(
            orgnr = orgnr,
            altinn3Tilganger = altinn3Tilganger,
            underenheter = underenheter,
            navn = navn,
            organisasjonsform = "BEDR",
        )

        fun altinnTilgangerResponse(vararg hierarki: AltinnTilgang) = AltinnTilgangerResponse(
            isError = false,
            hierarki = hierarki.toList(),
            orgNrTilTilganger = emptyMap(),
            tilgangTilOrgNr = emptyMap(),
        )

        describe("getFilteredOrganizations") {
            val userPrincipal = UserPrincipal("12345678910", "token")

            it("should preserve empty results when the client returns null") {
                val nullableClient = mockk<AltinnTilgangerClient>()
                coEvery { nullableClient.fetchAltinnTilganger(any()) } returns null
                val service = AltinnTilgangerService(nullableClient)

                service.getAltinnTilgangForOrgnr(userPrincipal, "999999999") shouldBe null
                service.getFilteredOrganizations(userPrincipal) shouldBe emptyList()
            }

            it("should keep parent as context when only child has narmesteleder access to document OR semantics") {
                val childWithAccess = altinnTilgang(
                    orgnr = "222222222",
                    altinn3Tilganger = setOf(OPPGI_NARMESTELEDER_RESOURCE),
                )
                val parentWithoutAccess = altinnTilgang(
                    orgnr = "111111111",
                    underenheter = listOf(childWithAccess),
                )

                coEvery { altinnTilgangerClient.fetchAltinnTilganger(any()) } returns altinnTilgangerResponse(parentWithoutAccess)

                altinnTilgangerService.getFilteredOrganizations(userPrincipal) shouldBe listOf(
                    AccessibleOrganization(
                        orgNumber = "111111111",
                        name = "Org 111111111",
                        subOrganizations = listOf(
                            AccessibleOrganization(
                                orgNumber = "222222222",
                                name = "Org 222222222",
                                subOrganizations = emptyList(),
                            )
                        ),
                    )
                )
            }

            it("should keep parent with access while filtering out child without access") {
                val childWithoutAccess = altinnTilgang(orgnr = "555555555")
                val parentWithAccess = altinnTilgang(
                    orgnr = "444444444",
                    altinn3Tilganger = setOf(OPPGI_NARMESTELEDER_RESOURCE),
                    underenheter = listOf(childWithoutAccess),
                )

                coEvery { altinnTilgangerClient.fetchAltinnTilganger(any()) } returns altinnTilgangerResponse(parentWithAccess)

                altinnTilgangerService.getFilteredOrganizations(userPrincipal) shouldBe listOf(
                    AccessibleOrganization(
                        orgNumber = "444444444",
                        name = "Org 444444444",
                        subOrganizations = emptyList(),
                    )
                )
            }

            it("should return empty list when neither parent nor children have narmesteleder access") {
                val childWithoutAccess = altinnTilgang(orgnr = "777777777")
                val parentWithoutAccess = altinnTilgang(
                    orgnr = "666666666",
                    underenheter = listOf(childWithoutAccess),
                )

                coEvery { altinnTilgangerClient.fetchAltinnTilganger(any()) } returns altinnTilgangerResponse(parentWithoutAccess)

                altinnTilgangerService.getFilteredOrganizations(userPrincipal) shouldBe emptyList()
            }
        }
    })
