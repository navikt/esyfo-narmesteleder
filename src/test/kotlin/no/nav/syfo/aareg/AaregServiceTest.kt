package no.nav.syfo.aareg

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import no.nav.syfo.aareg.client.AaregClientException
import no.nav.syfo.aareg.client.ArbeidsstedType
import no.nav.syfo.aareg.client.FakeAaregClient
import no.nav.syfo.aareg.client.OpplysningspliktigType
import no.nav.syfo.application.exception.ApiErrorException

class AaregServiceTest :
    DescribeSpec({
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

        describe("findArbeidsforholdByPersonIdent") {
            it("should create list of Arbeidsforhold from response from AaregArbeidsforholdOversikt") {
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
                val result = service.findArbeidsforholdByPersonIdent(expected.key)

                // Assert
                result.size shouldBe 1
                val arbeidsforhold = result.first()
                arbeidsforhold.orgnummer shouldBe virksomhet
                arbeidsforhold.opplysningspliktigOrgnummer shouldBe juridiskOrgnummer
                arbeidsforhold.arbeidsstedType shouldBe ArbeidsstedType.Underenhet
                arbeidsforhold.opplysningspliktigType shouldBe OpplysningspliktigType.Hovedenhet
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
                    service.findArbeidsforholdByPersonIdent(fnr)
                }
            }
        }

        describe("toOrgNumberList") {
            it("should convert List<Arbeidsforholdoversikt> to list of the orgnumers") {
                // Arrange
                val arbeidsfoldholdList = listOf(
                    Arbeidsforhold(
                        orgnummer = "111111111",
                        arbeidsstedType = ArbeidsstedType.Underenhet,
                        opplysningspliktigOrgnummer = "222222222",
                        opplysningspliktigType = OpplysningspliktigType.Hovedenhet
                    ),
                    Arbeidsforhold(
                        orgnummer = "333333333",
                        arbeidsstedType = ArbeidsstedType.Underenhet,
                        opplysningspliktigOrgnummer = null,
                        opplysningspliktigType = OpplysningspliktigType.Hovedenhet
                    )
                )

                // Act
                val result = arbeidsfoldholdList.toOrgNumberList().sorted()

                // Assert
                result.size shouldBe 3
                result shouldBe listOf("111111111", "222222222", "333333333")
            }
        }
    })
