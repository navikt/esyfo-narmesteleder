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
                    ArbeidsforholdValidator.validateSmAndNlArbeidsforhold(
                        sykmeldtArbeidsforhold = listOf(sykmeldtArbeidsforhold),
                        narmesteLederArbeidsforhold = listOf(
                            arbeidsforhold(
                                orgnummer = sykmeldtArbeidsforhold.orgnummer,
                                opplysningspliktigOrgnummer = requireNotNull(sykmeldtArbeidsforhold.opplysningspliktigOrgnummer),
                            ),
                        ),
                        orgNumberInRequest = sykmeldtArbeidsforhold.orgnummer,
                    )
                }
            }

            it("should throw BadRequestException when sykmeldt has no arbeidsforhold") {
                shouldThrow<ApiErrorException.BadRequestException> {
                    ArbeidsforholdValidator.validateSmAndNlArbeidsforhold(
                        sykmeldtArbeidsforhold = emptyList(),
                        narmesteLederArbeidsforhold = listOf(sykmeldtArbeidsforhold),
                        orgNumberInRequest = sykmeldtArbeidsforhold.orgnummer,
                    )
                }
            }

            it("should throw BadRequestException when sykmeldt is missing arbeidsforhold for request org") {
                shouldThrow<ApiErrorException.BadRequestException> {
                    ArbeidsforholdValidator.validateSmAndNlArbeidsforhold(
                        sykmeldtArbeidsforhold = listOf(sykmeldtArbeidsforhold),
                        narmesteLederArbeidsforhold = listOf(sykmeldtArbeidsforhold),
                        orgNumberInRequest = randomOrgNumbers[2],
                    )
                }
            }

            it("should throw BadRequestException when nearest leader has no matching organization with sykmeldt") {
                shouldThrow<ApiErrorException.BadRequestException> {
                    ArbeidsforholdValidator.validateSmAndNlArbeidsforhold(
                        sykmeldtArbeidsforhold = listOf(sykmeldtArbeidsforhold),
                        narmesteLederArbeidsforhold = listOf(
                            arbeidsforhold(
                                orgnummer = randomOrgNumbers[2],
                                opplysningspliktigOrgnummer = randomOrgNumbers[3],
                            ),
                        ),
                        orgNumberInRequest = sykmeldtArbeidsforhold.orgnummer,
                    )
                }
            }
        }

        describe("validateNarmesteLederAvkreft") {
            describe("organization number matches for sykmeldt and innsender") {
                it("It should not throw when sykmeldt is only org number used for all parties") {
                    shouldNotThrowAny {
                        ArbeidsforholdValidator.validateNarmesteLederAvkreft(
                            sykmeldtArbeidsforhold = listOf(sykmeldtArbeidsforhold),
                            orgNumberInRequest = sykmeldtArbeidsforhold.orgnummer,
                        )
                    }
                }

                it("Should not throw when sykmeldt has at least one matching org number with the other parties") {
                    shouldNotThrowAny {
                        ArbeidsforholdValidator.validateNarmesteLederAvkreft(
                            sykmeldtArbeidsforhold = listOf(
                                sykmeldtArbeidsforhold,
                                sykmeldtArbeidsforhold.copy(
                                    orgnummer = randomOrgNumbers[2],
                                    opplysningspliktigOrgnummer = randomOrgNumbers[3],
                                ),
                            ),
                            orgNumberInRequest = sykmeldtArbeidsforhold.orgnummer,
                        )
                    }
                }
            }

            describe("Mismatch in organization number between parties") {
                it("Should throw BadRequestException if payload org is not within sykmeldt orgs") {
                    shouldThrow<ApiErrorException.BadRequestException> {
                        ArbeidsforholdValidator.validateNarmesteLederAvkreft(
                            sykmeldtArbeidsforhold = listOf(sykmeldtArbeidsforhold),
                            orgNumberInRequest = randomOrgNumbers[2],
                        )
                    }
                }

                it("Should throw BadRequestException exception if no organizations are found for sykmeldt") {
                    shouldThrow<ApiErrorException.BadRequestException> {
                        ArbeidsforholdValidator.validateNarmesteLederAvkreft(
                            sykmeldtArbeidsforhold = emptyList(),
                            orgNumberInRequest = sykmeldtArbeidsforhold.orgnummer,
                        )
                    }
                }
            }
        }
    })
