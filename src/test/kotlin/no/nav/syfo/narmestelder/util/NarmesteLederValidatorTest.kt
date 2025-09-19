package no.nav.syfo.narmestelder.util

import createRandomValidOrgNumbers
import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import no.nav.syfo.application.exception.ApiErrorException
import no.nav.syfo.narmesteleder.NarmesteLederValidator.nlvalidate


class NarmesteLederValidatorTest : DescribeSpec({
    val randomOrgNumbers = createRandomValidOrgNumbers()

    describe("checkArbeidsforhold matches") {
        val nlOrgNumber = randomOrgNumbers.first()
        val innsenderOrgNumber = nlOrgNumber
        val sykemeldtOrgNumber = listOf(nlOrgNumber)

        it("It should accept when sykemeldt is only org number is within org") {
            shouldNotThrowAny {
                nlvalidate {
                    checkArbeidsforhold(
                        validOrgNumbers = sykemeldtOrgNumber,
                        innsenderEmployerOrgnr = innsenderOrgNumber,
                        nlOrgNumber = nlOrgNumber
                    )
                }
            }
        }
        it("Should accept when sykemeldt has at least one matching org number with the other parties") {
            shouldNotThrowAny {
                nlvalidate {
                    checkArbeidsforhold(
                        validOrgNumbers = sykemeldtOrgNumber + randomOrgNumbers.last(),
                        innsenderEmployerOrgnr = innsenderOrgNumber,
                        nlOrgNumber = nlOrgNumber
                    )
                }
            }
        }
    }

    describe("checkArbeidsforhold mismatch") {
        it("Should throw Forbidden if NL is not within sykemeldt orgs") {
            shouldThrow<ApiErrorException.ForbiddenException> {
                nlvalidate {
                    checkArbeidsforhold(
                        validOrgNumbers = listOf(randomOrgNumbers.first(), randomOrgNumbers[1]),
                        innsenderEmployerOrgnr = randomOrgNumbers[2],
                        nlOrgNumber = randomOrgNumbers[2]
                    )
                }
            }
        }

        it("Should throw Forbidden if arbeidsgiver is not within sykemeldt org") {
            shouldThrow<ApiErrorException.ForbiddenException> {
                nlvalidate {
                    checkArbeidsforhold(
                        validOrgNumbers = listOf(randomOrgNumbers.first(), randomOrgNumbers[1]),
                        innsenderEmployerOrgnr = randomOrgNumbers[2],
                        nlOrgNumber = randomOrgNumbers[2]
                    )
                }
            }
        }
    }

    describe("matchOne") {
        it("Should not throw when number is in list") {
            shouldNotThrowAny {
                nlvalidate {
                    matchOne(
                        validOrgNumbers = listOf(randomOrgNumbers.first(), randomOrgNumbers.last()),
                        checkOrgNumber = randomOrgNumbers.first()
                    )
                }
            }
        }

        it("Should BadRequest throw when number is not in list") {
            shouldThrow<ApiErrorException.BadRequestException> {
                nlvalidate {
                    matchOne(
                        validOrgNumbers = listOf(randomOrgNumbers.first(), randomOrgNumbers[1]),
                        checkOrgNumber = randomOrgNumbers.last()
                    )
                }
            }

        }
    }

})