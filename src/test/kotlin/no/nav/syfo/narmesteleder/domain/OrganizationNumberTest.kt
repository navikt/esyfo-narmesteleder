package no.nav.syfo.narmesteleder.domain

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

class OrganizationNumberTest :
    DescribeSpec({
        it("should accept valid 9-digit number") {
            val orgNr = OrganizationNumber("123456789")
            orgNr.value shouldBe "123456789"
        }

        it("should reject number with fewer than 9 digits") {
            shouldThrow<IllegalArgumentException> {
                OrganizationNumber("12345678")
            }
        }

        it("should reject number with more than 9 digits") {
            shouldThrow<IllegalArgumentException> {
                OrganizationNumber("1234567890")
            }
        }

        it("should reject non-digit characters") {
            shouldThrow<IllegalArgumentException> {
                OrganizationNumber("12345678a")
            }
        }

        it("should reject empty string") {
            shouldThrow<IllegalArgumentException> {
                OrganizationNumber("")
            }
        }

        it("should reject string with spaces") {
            shouldThrow<IllegalArgumentException> {
                OrganizationNumber("123 56789")
            }
        }

        describe("OrganizationNumber.parse") {
            it("should return success for valid input") {
                val result = OrganizationNumber.parse("123456789")
                result.isSuccess shouldBe true
                result.getOrThrow().value shouldBe "123456789"
            }

            it("should return failure for invalid input") {
                val result = OrganizationNumber.parse("12345")
                result.isFailure shouldBe true
                result.exceptionOrNull().shouldBeInstanceOf<IllegalArgumentException>()
            }
        }
    })
