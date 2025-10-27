package no.nav.syfo.narmesteleder.util

import createRandomValidOrgNumbers
import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import no.nav.syfo.application.exception.ApiErrorException
import no.nav.syfo.narmesteleder.service.ValidateNarmesteLederException
import no.nav.syfo.narmesteleder.service.validateNarmesteLeder
import no.nav.syfo.narmesteleder.service.validateNarmesteLederAvkreft

class NarmesteLederValidatorTest : DescribeSpec({
    val randomOrgNumbers = createRandomValidOrgNumbers()
    describe("validateNarmesteLeder") {
        describe("organization number matches for sykemeldt, nl and innsender") {
            val nlOrgNumbers = mapOf(randomOrgNumbers.first() to randomOrgNumbers.last())

            it("It should not throw when sykemeldt is only org number used for all parties") {
                shouldNotThrowAny {
                    validateNarmesteLeder(
                        sykemeldtOrgNumbers = nlOrgNumbers,
                        narmesteLederOrgNumbers = nlOrgNumbers,
                        innsenderOrgNumber = nlOrgNumbers.keys.first(),
                        orgNumberInRequest = nlOrgNumbers.keys.first(),
                    )
                }
            }

            it("Should not throw when sykemeldt has at least one matching org number with the other parties") {
                val nlOrgNumbers = mapOf(randomOrgNumbers.first() to randomOrgNumbers.last())
                shouldNotThrowAny {
                    validateNarmesteLeder(
                        sykemeldtOrgNumbers = nlOrgNumbers,
                        narmesteLederOrgNumbers = nlOrgNumbers,
                        innsenderOrgNumber = randomOrgNumbers.last(),
                        orgNumberInRequest = nlOrgNumbers.keys.first()
                    )
                }
            }
        }

        describe("Mismatch in organization number between parties") {
            it("Should throw ValidateNarmesteLederException if NL is not within sykemeldt orgs") {
                val nlOrgNumbers = mapOf(randomOrgNumbers.first() to randomOrgNumbers.last())
                shouldThrow<ValidateNarmesteLederException> {
                    validateNarmesteLeder(
                        sykemeldtOrgNumbers = nlOrgNumbers,
                        narmesteLederOrgNumbers = mapOf(randomOrgNumbers[2] to randomOrgNumbers[3]),
                        innsenderOrgNumber = randomOrgNumbers.first(),
                        orgNumberInRequest = randomOrgNumbers.first(),
                    )
                }
            }

            it("Should throw ValidateNarmesteLederException if payload org is not within sykemeldt orgs") {
                val nlOrgNumbers = mapOf(randomOrgNumbers.first() to randomOrgNumbers.last())
                shouldThrow<ValidateNarmesteLederException> {
                    validateNarmesteLeder(
                        sykemeldtOrgNumbers = nlOrgNumbers,
                        narmesteLederOrgNumbers = nlOrgNumbers,
                        innsenderOrgNumber = randomOrgNumbers.first(),
                        orgNumberInRequest = randomOrgNumbers[2],
                    )
                }
            }

            it("Should throw ValidateNarmesteLederException if innsender is not within NL org") {
                val nlOrgNumbers = mapOf(randomOrgNumbers.first() to randomOrgNumbers.last())
                shouldThrow<ApiErrorException.ForbiddenException> {
                    validateNarmesteLeder(
                        sykemeldtOrgNumbers = nlOrgNumbers,
                        narmesteLederOrgNumbers = nlOrgNumbers,
                        innsenderOrgNumber = randomOrgNumbers[2],
                        orgNumberInRequest = nlOrgNumbers.keys.first()
                    )
                }
            }

            it("Should throw ValidateNarmesteLederException if no one is within the same org") {
                val nlOrgNumbers = mapOf(randomOrgNumbers.first() to randomOrgNumbers.last())
                shouldThrow<ValidateNarmesteLederException> {
                    validateNarmesteLeder(
                        sykemeldtOrgNumbers = nlOrgNumbers,
                        narmesteLederOrgNumbers = mapOf(randomOrgNumbers[2] to randomOrgNumbers[3]),
                        innsenderOrgNumber = randomOrgNumbers[2],
                        orgNumberInRequest = randomOrgNumbers[3],
                    )
                }
            }

            it("Should throw ValidateNarmesteLederException exception if no organizations are found for sykemeldt") {
                val nlOrgNumbers = mapOf(randomOrgNumbers.first() to randomOrgNumbers.last())
                shouldThrow<ValidateNarmesteLederException> {
                    validateNarmesteLeder(
                        sykemeldtOrgNumbers = emptyMap(),
                        narmesteLederOrgNumbers = nlOrgNumbers,
                        innsenderOrgNumber = nlOrgNumbers.keys.first(),
                        orgNumberInRequest = nlOrgNumbers.keys.first(),
                    )
                }
            }

            it("Should throw ValidateNarmesteLederException exception if no organizations are found for nærmeste leder") {
                val nlOrgNumbers = mapOf(randomOrgNumbers.first() to randomOrgNumbers.last())
                shouldThrow<ValidateNarmesteLederException> {
                    validateNarmesteLeder(
                        sykemeldtOrgNumbers = nlOrgNumbers,
                        narmesteLederOrgNumbers = emptyMap(),
                        innsenderOrgNumber = nlOrgNumbers.keys.first(),
                        orgNumberInRequest = nlOrgNumbers.keys.first(),
                    )
                }
            }
        }
    }

    describe("validateNarmesteLederAvkreft") {
        describe("organization number matches for sykemeldt and innsender") {
            val nlOrgNumbers = mapOf(randomOrgNumbers.first() to randomOrgNumbers.last())

            it("It should not throw when sykemeldt is only org number used for all parties") {
                shouldNotThrowAny {
                    validateNarmesteLederAvkreft(
                        sykemeldtOrgNumbers = nlOrgNumbers,
                        innsenderOrgNumber = nlOrgNumbers.keys.first(),
                        orgNumberInRequest = nlOrgNumbers.keys.first(),
                    )
                }
            }

            it("Should not throw when sykemeldt has at least one matching org number with the other parties") {
                val nlOrgNumbers = mapOf(randomOrgNumbers.first() to randomOrgNumbers.last())
                shouldNotThrowAny {
                    validateNarmesteLederAvkreft(
                        sykemeldtOrgNumbers = nlOrgNumbers,
                        innsenderOrgNumber = randomOrgNumbers.last(),
                        orgNumberInRequest = nlOrgNumbers.keys.first()
                    )
                }
            }
        }

        describe("Mismatch in organization number between parties") {
            it("Should throw ValidateNarmesteLederException if payload org is not within sykemeldt orgs") {
                val nlOrgNumbers = mapOf(randomOrgNumbers.first() to randomOrgNumbers.last())
                shouldThrow<ValidateNarmesteLederException> {
                    validateNarmesteLederAvkreft(
                        sykemeldtOrgNumbers = nlOrgNumbers,
                        innsenderOrgNumber = randomOrgNumbers.first(),
                        orgNumberInRequest = randomOrgNumbers[2],
                    )
                }
            }

            it("Should throw ValidateNarmesteLederException if innsender is not within sykmeldt org") {
                val nlOrgNumbers = mapOf(randomOrgNumbers.first() to randomOrgNumbers.last())
                shouldThrow<ApiErrorException.ForbiddenException> {
                    validateNarmesteLederAvkreft(
                        sykemeldtOrgNumbers = nlOrgNumbers,
                        innsenderOrgNumber = randomOrgNumbers[2],
                        orgNumberInRequest = nlOrgNumbers.keys.first()
                    )
                }
            }

            it("Should throw ValidateNarmesteLederException exception if no organizations are found for sykemeldt") {
                val nlOrgNumbers = mapOf(randomOrgNumbers.first() to randomOrgNumbers.last())
                shouldThrow<ValidateNarmesteLederException> {
                    validateNarmesteLederAvkreft(
                        sykemeldtOrgNumbers = emptyMap(),
                        innsenderOrgNumber = nlOrgNumbers.keys.first(),
                        orgNumberInRequest = nlOrgNumbers.keys.first(),
                    )
                }
            }
        }
    }
})
