package no.nav.syfo.altinntilganger

import io.kotest.assertions.throwables.shouldNotThrow
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.mockk
import no.nav.syfo.altinntilganger.client.FakeAltinnTilgangerClient
import no.nav.syfo.application.auth.BrukerPrincipal
import no.nav.syfo.application.exception.ApiErrorException
import no.nav.syfo.application.exception.UpstreamRequestException

class AltinnTilgangerServiceTest : DescribeSpec({
    val altinnTilgangerClient = FakeAltinnTilgangerClient()
    val altinnTilgangerService = AltinnTilgangerService(altinnTilgangerClient)

    beforeTest {
        clearAllMocks()
    }

    describe("validateTilgangToOrganisasjon") {
        it("should not throw when user has access to org") {
            val fnr = "12345678901"
            val orgnummer = "987654321"
            val brukerPrincipal = BrukerPrincipal(fnr, "token")
            shouldThrow<ApiErrorException.ForbiddenException> {
                altinnTilgangerService.validateTilgangToOrganisasjon(brukerPrincipal, orgnummer)
            }
        }

        it("should not throw when user has access to org") {
            val accessPair = altinnTilgangerClient.usersWithAccess.first()
            val brukerPrincipal = BrukerPrincipal(accessPair.first, "token")
            shouldNotThrow<Exception> {
                altinnTilgangerService.validateTilgangToOrganisasjon(brukerPrincipal, accessPair.second)
            }
        }

        it("should throw =Internal Server Error when client failes to make request") {
            val mockAltinnTilgangerClient = mockk<FakeAltinnTilgangerClient>()
            coEvery { mockAltinnTilgangerClient.hentTilganger(any())} throws UpstreamRequestException("Forced failure")
            val altinnTilgangerServiceWithMock = AltinnTilgangerService(mockAltinnTilgangerClient)
            val accessPair = altinnTilgangerClient.usersWithAccess.first()
            val brukerPrincipal = BrukerPrincipal(accessPair.first, "token")
            shouldThrow<ApiErrorException.InternalServerErrorException> {
                altinnTilgangerServiceWithMock.validateTilgangToOrganisasjon(brukerPrincipal, accessPair.second)
            }
        }
    }
})
