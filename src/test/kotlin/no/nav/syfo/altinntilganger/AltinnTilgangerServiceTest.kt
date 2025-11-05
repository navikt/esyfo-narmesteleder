package no.nav.syfo.altinntilganger

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.mockk
import no.nav.syfo.altinntilganger.client.FakeAltinnTilgangerClient
import no.nav.syfo.application.auth.UserPrincipal
import no.nav.syfo.application.exception.ApiErrorException
import no.nav.syfo.application.exception.UpstreamRequestException

class AltinnTilgangerServiceTest : DescribeSpec({
    val altinnTilgangerClient = FakeAltinnTilgangerClient()
    val altinnTilgangerService = AltinnTilgangerService(altinnTilgangerClient)

    beforeTest {
        clearAllMocks()
        altinnTilgangerClient.reset()
    }

    describe("validateTilgangToOrganization") {
        it("should not throw when user has access to org") {
            val fnr = "12345678901"
            val orgnummer = "987654321"
            val userPrincipal = UserPrincipal(fnr, "token")
            shouldThrow<ApiErrorException.ForbiddenException> {
                altinnTilgangerService.validateTilgangToOrganization(userPrincipal, orgnummer)
            }
        }

        it("should throw Forbidden when user lacks access to org") {
            val accessPair = altinnTilgangerClient.usersWithAccess.first()
            val userPrincipal = UserPrincipal(accessPair.first, "token")
            altinnTilgangerClient.usersWithAccess.clear()
            shouldThrow<ApiErrorException.ForbiddenException> {
                altinnTilgangerService.validateTilgangToOrganization(userPrincipal, accessPair.second)
            }
        }

        it("should throw Internal Server Error when client fails to make request") {
            val mockAltinnTilgangerClient = mockk<FakeAltinnTilgangerClient>()
            coEvery { mockAltinnTilgangerClient.fetchAltinnTilganger(any())} throws UpstreamRequestException("Forced failure")
            val altinnTilgangerServiceWithMock = AltinnTilgangerService(mockAltinnTilgangerClient)
            val accessPair = altinnTilgangerClient.usersWithAccess.first()
            val userPrincipal = UserPrincipal(accessPair.first, "token")
            shouldThrow<ApiErrorException.InternalServerErrorException> {
                altinnTilgangerServiceWithMock.validateTilgangToOrganization(userPrincipal, accessPair.second)
            }
        }
    }
})
