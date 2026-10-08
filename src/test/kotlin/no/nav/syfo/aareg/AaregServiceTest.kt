package no.nav.syfo.aareg

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import no.nav.syfo.application.api.ErrorType
import no.nav.syfo.application.exception.ApiErrorException
import no.nav.syfo.application.exception.UpstreamRequestException
import no.nav.syfo.integration.aareg.AAREG
import no.nav.syfo.integration.aareg.ArbeidsstedType
import no.nav.syfo.integration.aareg.FakeAaregClient
import no.nav.syfo.integration.aareg.OpplysningspliktigType
import no.nav.syfo.platform.upstream.UpstreamFailure
import no.nav.syfo.platform.upstream.UpstreamFailureStage

class AaregServiceTest :
    DescribeSpec({
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

            it("converts a Failure through the legacy bridge to UPSTREAM_SERVICE_UNAVAILABLE") {
                // Arrange
                val fnr = "12345678901"
                val fakeAaregClient = FakeAaregClient()
                val cause = IllegalStateException("Unavailable")
                fakeAaregClient.setFailure(UpstreamFailure(AAREG, UpstreamFailureStage.RESPONSE, 503, cause))
                val service = AaregService(fakeAaregClient)

                // Act
                // Assert
                val error = shouldThrow<ApiErrorException.InternalServerErrorException> {
                    service.findArbeidsforholdByPersonIdent(fnr)
                }
                error.type shouldBe ErrorType.UPSTREAM_SERVICE_UNAVAILABLE
                error.message shouldBe "Could not fetch employment status fra Aareg"
                error.cause.shouldBeInstanceOf<UpstreamRequestException>().cause shouldBe cause
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
