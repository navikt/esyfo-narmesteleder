package no.nav.syfo.ereg.client

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import java.util.UUID
import no.nav.syfo.ereg.EregService
import no.nav.syfo.application.exception.ApiErrorException
import no.nav.syfo.application.exception.UpstreamRequestException
import organisasjon

class EregServiceTest : DescribeSpec({
    describe("getOrganization") {
        it("Should return organization when found") {
            // Arrange
            val fakeEregClient = FakeEregClient()
            val organization = organisasjon()

            fakeEregClient.organisasjoner.clear()
            fakeEregClient.organisasjoner.put(organization.organisasjonsnummer, organization)
            val service = EregService(fakeEregClient)
            // Act
            val result = service.getOrganization(organization.organisasjonsnummer)

            // Assert
            result shouldBe organization
        }
    }
    it("Should convert UpstreamRequestException to InternalServerErrorException") {
        // Arrange
        val organization = organisasjon()
        val fakeEregClient = FakeEregClient()
        val expected = UpstreamRequestException("Forced failure: ${UUID.randomUUID()}")
        fakeEregClient.setFailure(expected)
        val service = EregService(fakeEregClient)

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
        val service = EregService(fakeEregClient)

        // Act
        // Assert
         shouldThrow<ApiErrorException.BadRequestException> {
            service.getOrganization(organization.organisasjonsnummer)
        }
    }
})
