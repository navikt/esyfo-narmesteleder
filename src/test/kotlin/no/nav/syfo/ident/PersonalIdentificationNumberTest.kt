package no.nav.syfo.ident

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class PersonalIdentificationNumberTest :
    FunSpec({
        test("accepts exactly 11 digits") {
            PersonalIdentificationNumber.isValid("12345678901") shouldBe true
            PersonalIdentificationNumber("12345678901").value shouldBe "12345678901"
        }

        test("accepts a D-number") {
            PersonalIdentificationNumber("52345678901").value shouldBe "52345678901"
        }

        mapOf(
            "empty" to "",
            "too short" to "1234567890",
            "too long" to "123456789012",
            "letters" to "1234567890a",
            "arabic-indic digit" to "1234567890٩",
            "fullwidth digits" to "１２３４５６７８９０１",
            "whitespace" to "12345 78901",
            "surrounding whitespace" to " 12345678901",
        ).forEach { (case, value) ->
            test("rejects $case") {
                PersonalIdentificationNumber.isValid(value) shouldBe false
                shouldThrow<IllegalArgumentException> { PersonalIdentificationNumber(value) }.message shouldBe
                    "PersonalIdentificationNumber must be exactly 11 digits"
            }
        }
    })
