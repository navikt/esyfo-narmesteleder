package no.nav.syfo.organisasjonstilgang.infrastructure.altinntilganger

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.spyk
import no.nav.syfo.application.auth.UserPrincipal
import no.nav.syfo.application.exception.UpstreamRequestException
import no.nav.syfo.ident.PersonIdent
import no.nav.syfo.organisasjonstilgang.application.AccessToken
import no.nav.syfo.organisasjonstilgang.application.AccessibleOrganization
import no.nav.syfo.organisasjonstilgang.application.ListAccessibleOrganizationsResult
import no.nav.syfo.organisasjonstilgang.application.OPPGI_NARMESTELEDER_RESOURCE
import no.nav.syfo.organisasjonstilgang.application.OrganizationAccessSubject

class AltinnTilgangerServiceTest :
    DescribeSpec({
        val altinnTilgangerClient = spyk(FakeAltinnTilgangerClient())
        val altinnTilgangerService = AltinnTilgangerService(altinnTilgangerClient)

        beforeTest {
            clearAllMocks(currentThreadOnly = true)
            altinnTilgangerClient.reset()
        }

        describe("find") {
            val userPrincipal = UserPrincipal("12345678910", "token")
            val subject = OrganizationAccessSubject.PersonnelManager(PersonIdent(userPrincipal.ident), AccessToken(userPrincipal.token))

            it("should preserve empty results when the client returns null") {
                val nullableClient = mockk<AltinnTilgangerClient>()
                coEvery { nullableClient.fetchAltinnTilganger(any()) } returns null
                val service = AltinnTilgangerService(nullableClient)

                service.getAltinnTilgangForOrgnr(userPrincipal, "999999999") shouldBe null
                service.find(subject) shouldBe ListAccessibleOrganizationsResult.Listed(emptyList())
            }

            it("should return unavailable when the upstream request fails") {
                coEvery { altinnTilgangerClient.fetchAltinnTilganger(any()) } throws UpstreamRequestException("Upstream unavailable")

                altinnTilgangerService.find(subject) shouldBe ListAccessibleOrganizationsResult.Unavailable
            }

            it("should pass the personnel manager identity and access token to the client") {
                coEvery { altinnTilgangerClient.fetchAltinnTilganger(userPrincipal) } returns altinnTilgangerResponse()

                altinnTilgangerService.find(subject) shouldBe ListAccessibleOrganizationsResult.Listed(emptyList())
            }

            it("should return an empty list when the upstream response reports an error") {
                coEvery { altinnTilgangerClient.fetchAltinnTilganger(any()) } returns altinnTilgangerResponse(
                    altinnTilgang("999999999", setOf(OPPGI_NARMESTELEDER_RESOURCE)),
                ).copy(isError = true)

                altinnTilgangerService.find(subject) shouldBe ListAccessibleOrganizationsResult.Listed(emptyList())
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

                altinnTilgangerService.find(subject) shouldBe ListAccessibleOrganizationsResult.Listed(
                    listOf(
                        AccessibleOrganization(
                            organizationNumber = "111111111",
                            name = "Org 111111111",
                            subOrganizations = listOf(
                                AccessibleOrganization(
                                    organizationNumber = "222222222",
                                    name = "Org 222222222",
                                    subOrganizations = emptyList(),
                                ),
                            ),
                        ),
                    ),
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

                altinnTilgangerService.find(subject) shouldBe ListAccessibleOrganizationsResult.Listed(
                    listOf(
                        AccessibleOrganization(
                            organizationNumber = "444444444",
                            name = "Org 444444444",
                            subOrganizations = emptyList(),
                        ),
                    ),
                )
            }

            it("should return empty list when neither parent nor children have narmesteleder access") {
                val childWithoutAccess = altinnTilgang(orgnr = "777777777")
                val parentWithoutAccess = altinnTilgang(
                    orgnr = "666666666",
                    underenheter = listOf(childWithoutAccess),
                )

                coEvery { altinnTilgangerClient.fetchAltinnTilganger(any()) } returns altinnTilgangerResponse(parentWithoutAccess)

                altinnTilgangerService.find(subject) shouldBe ListAccessibleOrganizationsResult.Listed(emptyList())
            }
        }
    })

private fun altinnTilgang(
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

private fun altinnTilgangerResponse(vararg hierarki: AltinnTilgang) = AltinnTilgangerResponse(
    isError = false,
    hierarki = hierarki.toList(),
    orgNrTilTilganger = emptyMap(),
    tilgangTilOrgNr = emptyMap(),
)
