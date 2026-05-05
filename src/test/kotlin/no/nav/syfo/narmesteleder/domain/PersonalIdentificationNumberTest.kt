package no.nav.syfo.narmesteleder.domain

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

class PersonalIdentificationNumberTest :
    DescribeSpec({
        describe("PersonalIdentificationNumber") {
            it("should accept valid 11-digit number") {
                val pin = PersonalIdentificationNumber("12345678901")
                pin.value shouldBe "12345678901"
            }

            it("should accept D-number (first digit + 4)") {
                val dNumber = PersonalIdentificationNumber("52345678901")
                dNumber.value shouldBe "52345678901"
            }

            it("should reject number with fewer than 11 digits") {
                shouldThrow<IllegalArgumentException> {
                    PersonalIdentificationNumber("1234567890")
                }
            }

            it("should reject number with more than 11 digits") {
                shouldThrow<IllegalArgumentException> {
                    PersonalIdentificationNumber("123456789012")
                }
            }

            it("should reject non-digit characters") {
                shouldThrow<IllegalArgumentException> {
                    PersonalIdentificationNumber("1234567890a")
                }
            }

            it("should reject empty string") {
                shouldThrow<IllegalArgumentException> {
                    PersonalIdentificationNumber("")
                }
            }

            it("should reject string with spaces") {
                shouldThrow<IllegalArgumentException> {
                    PersonalIdentificationNumber("123 5678901")
                }
            }
        }

        describe("PersonalIdentificationNumber.parse") {
            it("should return success for valid input") {
                val result = PersonalIdentificationNumber.parse("12345678901")
                result.isSuccess shouldBe true
                result.getOrThrow().value shouldBe "12345678901"
            }

            it("should return failure for invalid input") {
                val result = PersonalIdentificationNumber.parse("123")
                result.isFailure shouldBe true
                result.exceptionOrNull().shouldBeInstanceOf<IllegalArgumentException>()
            }
        }
    })
