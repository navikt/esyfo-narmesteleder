package no.nav.syfo.narmesteleder.service.validators

import createRandomValidOrgNumbers
import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import no.nav.syfo.aareg.Arbeidsforhold
import no.nav.syfo.aareg.client.ArbeidsstedType
import no.nav.syfo.aareg.client.OpplysningspliktigType
import no.nav.syfo.application.exception.ApiErrorException

class ArbeidsforholdValidatorTest :
    DescribeSpec({
        fun arbeidsforhold(
            orgnummer: String,
            opplysningspliktigOrgnummer: String,
        ) = Arbeidsforhold(
            orgnummer = orgnummer,
            arbeidsstedType = ArbeidsstedType.Underenhet,
            opplysningspliktigOrgnummer = opplysningspliktigOrgnummer,
            opplysningspliktigType = OpplysningspliktigType.Hovedenhet,
        )

        lateinit var randomOrgNumbers: List<String>
        lateinit var sykmeldtArbeidsforhold: Arbeidsforhold

        beforeTest {
            randomOrgNumbers = createRandomValidOrgNumbers(prefix = "")
            sykmeldtArbeidsforhold = arbeidsforhold(
                orgnummer = randomOrgNumbers.first(),
                opplysningspliktigOrgnummer = randomOrgNumbers.last(),
            )
        }

        describe("validateSmAndNlArbeidsforhold") {
            it("should not throw when sykmeldt and nearest leader have overlapping organization numbers") {
                shouldNotThrowAny {
                    ArbeidsforholdValidator.validateSmArbeidsforhold(
                        sykmeldtArbeidsforhold = listOf(sykmeldtArbeidsforhold),
                        orgNumberInRequest = sykmeldtArbeidsforhold.orgnummer,
                    )
                }
            }

            it("should throw BadRequestException when sykmeldt has no arbeidsforhold") {
                shouldThrow<ApiErrorException.BadRequestException> {
                    ArbeidsforholdValidator.validateSmArbeidsforhold(
                        sykmeldtArbeidsforhold = emptyList(),
                        orgNumberInRequest = sykmeldtArbeidsforhold.orgnummer,
                    )
                }
            }

            it("should throw BadRequestException when sykmeldt is missing arbeidsforhold for request org") {
                shouldThrow<ApiErrorException.BadRequestException> {
                    ArbeidsforholdValidator.validateSmArbeidsforhold(
                        sykmeldtArbeidsforhold = listOf(sykmeldtArbeidsforhold),
                        orgNumberInRequest = randomOrgNumbers[2],
                    )
                }
            }
        }
    })
