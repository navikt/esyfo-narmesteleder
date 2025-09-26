package no.nav.syfo.narmestelder.util

import createRandomValidOrgNumbers
import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import no.nav.syfo.narmesteleder.service.ValidateNarmesteLederException
import no.nav.syfo.narmesteleder.service.validateNarmesteLeder

class NarmesteLederValidatorTest : DescribeSpec({
    val randomOrgNumbers = createRandomValidOrgNumbers()

    describe("organization number matches for sykemeldt, nl and innsender") {
        val innsenderOrgNumber = randomOrgNumbers.first()
        val nlOrgNumbers = setOf(innsenderOrgNumber)

        it("It should not throw when sykemeldt is only org number used for all parties") {
            shouldNotThrowAny {
                validateNarmesteLeder(
                    sykemeldtOrgNumbers = nlOrgNumbers,
                    innsenderOrgNumber = innsenderOrgNumber,
                    narmesteLederOrgNumbers = nlOrgNumbers
                )
            }
        }

        it("Should not throw when sykemeldt has at least one matching org number with the other parties") {
            shouldNotThrowAny {
                validateNarmesteLeder(
                    sykemeldtOrgNumbers = nlOrgNumbers + randomOrgNumbers[1],
                    innsenderOrgNumber = innsenderOrgNumber,
                    narmesteLederOrgNumbers = nlOrgNumbers
                )
            }
        }
    }

    describe("Mismatch in organization number between parties") {
        it("Should throw ValidateNarmesteLederException if NL is not within sykemeldt orgs") {
            shouldThrow<ValidateNarmesteLederException> {
                validateNarmesteLeder(
                    sykemeldtOrgNumbers = setOf(randomOrgNumbers.first()),
                    narmesteLederOrgNumbers = setOf(randomOrgNumbers[1]),
                    innsenderOrgNumber = randomOrgNumbers.first(),
                )
            }
        }

        it("Should throw ValidateNarmesteLederException if innsender is not within sykemeldt org") {
            shouldThrow<ValidateNarmesteLederException> {
                validateNarmesteLeder(
                    sykemeldtOrgNumbers = setOf(randomOrgNumbers.first()),
                    narmesteLederOrgNumbers = setOf(randomOrgNumbers[1]),
                    innsenderOrgNumber = randomOrgNumbers.first(),
                )
            }
        }

        it("Should throw ValidateNarmesteLederException exception if no organizations are found for sykemeldt") {
            shouldThrow<ValidateNarmesteLederException> {
                validateNarmesteLeder(
                    sykemeldtOrgNumbers = emptySet(),
                    narmesteLederOrgNumbers = setOf(randomOrgNumbers[1]),
                    innsenderOrgNumber = randomOrgNumbers.first(),
                )
            }
        }

        it("Should throw ValidateNarmesteLederException exception if no organizations are found for nærmeste leder") {
            shouldThrow<ValidateNarmesteLederException> {
                validateNarmesteLeder(
                    sykemeldtOrgNumbers = setOf(randomOrgNumbers.first()),
                    narmesteLederOrgNumbers = emptySet(),
                    innsenderOrgNumber = randomOrgNumbers.first(),
                )
            }
        }
    }
})
