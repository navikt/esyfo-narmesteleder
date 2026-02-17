package no.nav.syfo.altinntilganger

import io.kotest.assertions.throwables.shouldNotThrow
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.spyk
import no.nav.syfo.altinntilganger.AltinnTilgangerService.Companion.OPPRETT_NL_REALASJON_RESOURCE
import no.nav.syfo.altinntilganger.client.AltinnTilgangerResponse
import no.nav.syfo.altinntilganger.client.FakeAltinnTilgangerClient
import no.nav.syfo.application.auth.UserPrincipal
import no.nav.syfo.application.exception.ApiErrorException
import no.nav.syfo.application.exception.UpstreamRequestException

class AltinnTilgangerServiceTest :
    DescribeSpec({
        val altinnTilgangerClient = spyk(FakeAltinnTilgangerClient())
        val altinnTilgangerService = AltinnTilgangerService(altinnTilgangerClient)

        beforeTest {
            clearAllMocks()
            altinnTilgangerClient.reset()
        }

        describe("validateTilgangToOrganization") {
            it("should not throw when user has access to org") {
                val fnr = altinnTilgangerClient.accessPolicy.first().hasAccess.first()
                val orgnummer = altinnTilgangerClient.accessPolicy.first().altinnTilgangerResponse.hierarki.first().orgnr
                val userPrincipal = UserPrincipal(fnr, "token")
                shouldNotThrow<ApiErrorException.ForbiddenException> {
                    altinnTilgangerService.validateTilgangToOrganization(userPrincipal, orgnummer)
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
                    altinnTilgangerService.validateTilgangToOrganization(userPrincipal, orgnummer)
                }
            }

            it("should throw Forbidden when user lacks access to org") {
                val accessPolicy = altinnTilgangerClient.accessPolicy.first()
                val userPrincipal = UserPrincipal(accessPolicy.hasAccess.first(), "token")
                altinnTilgangerClient.accessPolicy.clear()
                shouldThrow<ApiErrorException.ForbiddenException> {
                    altinnTilgangerService.validateTilgangToOrganization(userPrincipal, accessPolicy.altinnTilgangerResponse.hierarki.first().orgnr)
                }
            }

            it("should throw Internal Server Error when client fails to make request") {
                val mockAltinnTilgangerClient = mockk<FakeAltinnTilgangerClient>()
                coEvery { mockAltinnTilgangerClient.fetchAltinnTilganger(any()) } throws UpstreamRequestException("Forced failure")
                val altinnTilgangerServiceWithMock = AltinnTilgangerService(mockAltinnTilgangerClient)
                val accessPolicy = altinnTilgangerClient.accessPolicy.first()
                val userPrincipal = UserPrincipal(accessPolicy.hasAccess.first(), "token")
                shouldThrow<ApiErrorException.InternalServerErrorException> {
                    altinnTilgangerServiceWithMock.validateTilgangToOrganization(userPrincipal, accessPolicy.altinnTilgangerResponse.hierarki.first().orgnr)
                }
            }
        }
    })
