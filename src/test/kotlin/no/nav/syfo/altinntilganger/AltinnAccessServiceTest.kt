package no.nav.syfo.altinntilganger

import io.kotest.assertions.throwables.shouldNotThrow
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.spyk
import no.nav.syfo.altinntilganger.AltinnAccessService.Companion.OPPGI_NARMESTELEDER_RESOURCE
import no.nav.syfo.altinntilganger.AltinnAccessService.Companion.OPPRETT_NL_REALASJON_RESOURCE
import no.nav.syfo.altinntilganger.client.AltinnTilgang
import no.nav.syfo.altinntilganger.client.AltinnTilgangerResponse
import no.nav.syfo.altinntilganger.client.FakeAltinnTilgangerClient
import no.nav.syfo.application.auth.UserPrincipal
import no.nav.syfo.application.exception.ApiErrorException
import no.nav.syfo.application.exception.UpstreamRequestException

class AltinnAccessServiceTest :
    DescribeSpec({
        val altinnTilgangerClient = spyk(FakeAltinnTilgangerClient())
        val altinnAccessService = AltinnAccessService(altinnTilgangerClient)

        beforeTest {
            clearAllMocks()
            altinnTilgangerClient.reset()
        }

        fun altinnTilgang(
            orgnr: String,
            altinn3Tilganger: Set<String> = emptySet(),
            altinn2Tilganger: Set<String> = emptySet(),
            underenheter: List<AltinnTilgang> = emptyList(),
            navn: String = "Org $orgnr",
        ) = AltinnTilgang(
            orgnr = orgnr,
            altinn3Tilganger = altinn3Tilganger,
            altinn2Tilganger = altinn2Tilganger,
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

        describe("validateTilgangToOrganization") {
            it("should not throw when user has access to org") {
                val fnr = altinnTilgangerClient.accessPolicy.first().hasAccess.first()
                val orgnummer = altinnTilgangerClient.accessPolicy.first().altinnTilgangerResponse.hierarki.first().orgnr
                val userPrincipal = UserPrincipal(fnr, "token")
                shouldNotThrow<ApiErrorException.ForbiddenException> {
                    altinnAccessService.validateTilgangToOrganization(userPrincipal, orgnummer)
                }
            }

            it("should not throw when user has access to org through altinn2") {
                val fnr = altinnTilgangerClient.accessPolicy.first().hasAccess.first()
                val orgnummer = altinnTilgangerClient.accessPolicy.first().altinnTilgangerResponse.hierarki.first().orgnr
                val userPrincipal = UserPrincipal(fnr, "token")
                val tilgang = altinnTilgangerClient.fetchAltinnTilganger(userPrincipal)
                val adjustedTilgang = tilgang.hierarki.first()
                    .copy(altinn2Tilganger = setOf(OPPRETT_NL_REALASJON_RESOURCE), altinn3Tilganger = emptySet())
                coEvery { altinnTilgangerClient.fetchAltinnTilganger(any()) } returns AltinnTilgangerResponse(
                    hierarki = listOf(
                        adjustedTilgang
                    ),
                    isError = false,
                    orgNrTilTilganger = mapOf(),
                    tilgangTilOrgNr = mapOf()
                )
                shouldNotThrow<ApiErrorException.ForbiddenException> {
                    altinnAccessService.validateTilgangToOrganization(userPrincipal, orgnummer)
                }
            }

            it("should throw Forbidden when user lacks access to org") {
                val accessPolicy = altinnTilgangerClient.accessPolicy.first()
                val userPrincipal = UserPrincipal(accessPolicy.hasAccess.first(), "token")
                altinnTilgangerClient.accessPolicy.clear()
                shouldThrow<ApiErrorException.ForbiddenException> {
                    altinnAccessService.validateTilgangToOrganization(userPrincipal, accessPolicy.altinnTilgangerResponse.hierarki.first().orgnr)
                }
            }

            it("should throw Internal Server Error when client fails to make request") {
                val mockAltinnTilgangerClient = mockk<FakeAltinnTilgangerClient>()
                coEvery { mockAltinnTilgangerClient.fetchAltinnTilganger(any()) } throws UpstreamRequestException("Forced failure")
                val altinnAccessServiceWithMock = AltinnAccessService(mockAltinnTilgangerClient)
                val accessPolicy = altinnTilgangerClient.accessPolicy.first()
                val userPrincipal = UserPrincipal(accessPolicy.hasAccess.first(), "token")
                shouldThrow<ApiErrorException.InternalServerErrorException> {
                    altinnAccessServiceWithMock.validateTilgangToOrganization(userPrincipal, accessPolicy.altinnTilgangerResponse.hierarki.first().orgnr)
                }
            }
        }

        describe("getFilteredOrganizations") {
            val userPrincipal = UserPrincipal("12345678910", "token")

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

                altinnAccessService.getFilteredOrganizations(userPrincipal) shouldBe listOf(
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

            it("should keep leaf organization with narmesteleder access and empty subOrganizations") {
                val leafWithAccess = altinnTilgang(
                    orgnr = "333333333",
                    altinn2Tilganger = setOf(OPPRETT_NL_REALASJON_RESOURCE),
                )

                coEvery { altinnTilgangerClient.fetchAltinnTilganger(any()) } returns altinnTilgangerResponse(leafWithAccess)

                altinnAccessService.getFilteredOrganizations(userPrincipal) shouldBe listOf(
                    AccessibleOrganization(
                        orgNumber = "333333333",
                        name = "Org 333333333",
                        subOrganizations = emptyList(),
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

                altinnAccessService.getFilteredOrganizations(userPrincipal) shouldBe listOf(
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

                altinnAccessService.getFilteredOrganizations(userPrincipal) shouldBe emptyList()
            }
        }
    })
