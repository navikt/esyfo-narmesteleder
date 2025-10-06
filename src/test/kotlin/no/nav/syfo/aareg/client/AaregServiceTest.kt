package no.nav.syfo.aareg.client

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import no.nav.syfo.aareg.AaregService
import no.nav.syfo.application.exception.ApiErrorException

class AaregServiceTest : DescribeSpec({
    describe("findOrgNumbersByPersonIdent") {
        it("should extract map of orgnumbers from AaregArbeidsforholdOversikt") {
            // Arrange
            val fakeAaregClient = FakeAaregClient()
            val fnr = "12345678901"
            val virksomhet = "987654321"
            val juridiskOrgnummer = "123456789"
            fakeAaregClient.arbeidsForholdForIdent.clear()
            fakeAaregClient.arbeidsForholdForIdent.put(fnr, listOf(virksomhet to juridiskOrgnummer))
            val service = AaregService(fakeAaregClient)
            val expected = fakeAaregClient.arbeidsForholdForIdent.entries.first()
            // Act
            val result = service.findOrgNumbersByPersonIdent(expected.key)

            // Assert

            result.size shouldBe 1
            result.entries.first().key shouldBe virksomhet
            result.entries.first().value shouldBe juridiskOrgnummer
        }
    }
    it("Should convert AaregClientException to ApiErrorException") {
        // Arrange
        val fnr = "12345678901"
        val fakeAaregClient = FakeAaregClient()
        fakeAaregClient.setFailure(AaregClientException("Forced failure", Exception()))
        val service = AaregService(fakeAaregClient)

        // Act
        // Assert
        shouldThrow<ApiErrorException.InternalServerErrorException> {
            service.findOrgNumbersByPersonIdent(fnr)
        }
    }
})
