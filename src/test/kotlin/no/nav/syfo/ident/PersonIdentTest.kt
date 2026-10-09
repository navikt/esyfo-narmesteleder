package no.nav.syfo.ident

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class PersonIdentTest :
    FunSpec({
        test("accepts exactly 11 digits") {
            PersonIdent.isValid("12345678901") shouldBe true
            PersonIdent("12345678901").value shouldBe "12345678901"
        }

        mapOf(
            "empty" to "",
            "too short" to "1234567890",
            "too long" to "123456789012",
            "letters" to "1234567890a",
            "whitespace" to "12345 78901",
            "surrounding whitespace" to " 12345678901",
        ).forEach { (case, value) ->
            test("rejects $case") {
                PersonIdent.isValid(value) shouldBe false
                shouldThrow<IllegalArgumentException> { PersonIdent(value) }.message shouldBe
                    "PersonIdent must be exactly 11 digits"
            }
        }
    })
