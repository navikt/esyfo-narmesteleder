package no.nav.syfo.ereg.client

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.clearAllMocks
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import io.mockk.verify
import no.nav.syfo.application.exception.ApiErrorException
import no.nav.syfo.application.exception.UpstreamRequestException
import no.nav.syfo.application.valkey.EregCache
import no.nav.syfo.ereg.EregService
import organisasjon
import java.util.UUID

class EregServiceTest :
    DescribeSpec({
        val eregCache = mockk<EregCache>(relaxed = true)
        beforeTest {
            clearAllMocks(currentThreadOnly = true)
            every { eregCache.getOrganisasjon(any()) } returns null
        }
        describe("getOrganization") {
            it("Should return organization from cache without calling ereg client") {
                // Arrange
                val fakeEregClient = spyk(FakeEregClient())
                val organization = organisasjon()
                every { eregCache.getOrganisasjon(organization.organisasjonsnummer) } returns organization
                val service = EregService(fakeEregClient, eregCache)

                // Act
                val result = service.getOrganization(organization.organisasjonsnummer)

                // Assert
                result shouldBe organization
                verify { eregCache.getOrganisasjon(organization.organisasjonsnummer) }
                coVerify(exactly = 0) { fakeEregClient.getOrganisasjon(any()) }
                verify(exactly = 0) { eregCache.putOrganisasjon(any(), any()) }
            }

            it("Should return organization when found") {
                // Arrange
                val fakeEregClient = FakeEregClient()
                val organization = organisasjon()

                fakeEregClient.organisasjoner.clear()
                fakeEregClient.organisasjoner.put(organization.organisasjonsnummer, organization)
                val service = EregService(fakeEregClient, eregCache)
                // Act
                val result = service.getOrganization(organization.organisasjonsnummer)

                // Assert
                result shouldBe organization
                verify { eregCache.getOrganisasjon(organization.organisasjonsnummer) }
                verify { eregCache.putOrganisasjon(organization.organisasjonsnummer, organization) }
            }
        }
        it("Should convert UpstreamRequestException to InternalServerErrorException") {
            // Arrange
            val organization = organisasjon()
            val fakeEregClient = FakeEregClient()
            val expected = UpstreamRequestException("Forced failure: ${UUID.randomUUID()}")
            fakeEregClient.setFailure(expected)
            val service = EregService(fakeEregClient, eregCache)

            // Act
            // Assert
            val exception = shouldThrow<ApiErrorException.InternalServerErrorException> {
                service.getOrganization(organization.organisasjonsnummer)
            }
            exception.cause shouldBe expected
        }

        it("Should throw BadRequest if organization was not found") {
            // Arrange
            val organization = organisasjon()
            val fakeEregClient = FakeEregClient()
            fakeEregClient.organisasjoner.clear()
            val service = EregService(fakeEregClient, eregCache)

            // Act
            // Assert
            shouldThrow<ApiErrorException.BadRequestException> {
                service.getOrganization(organization.organisasjonsnummer)
            }
        }
    })
