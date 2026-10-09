package no.nav.syfo.ident

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class OrganizationNumberTest :
    FunSpec({
        test("accepts exactly 9 digits") {
            OrganizationNumber.isValid("123456789") shouldBe true
            OrganizationNumber("123456789").value shouldBe "123456789"
        }

        mapOf(
            "empty" to "",
            "too short" to "12345678",
            "too long" to "1234567890",
            "letters" to "12345678a",
            "whitespace" to "1234 6789",
            "surrounding whitespace" to " 123456789",
        ).forEach { (case, value) ->
            test("rejects $case") {
                OrganizationNumber.isValid(value) shouldBe false
                shouldThrow<IllegalArgumentException> { OrganizationNumber(value) }.message shouldBe
                    "OrganizationNumber must be exactly 9 digits"
            }
        }
    })
