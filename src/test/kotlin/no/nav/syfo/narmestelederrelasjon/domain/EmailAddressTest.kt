package no.nav.syfo.narmestelederrelasjon.domain

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe

class EmailAddressTest :
    FunSpec({
        test("fromSeparatedList splits on comma and semicolon and trims entries") {
            EmailAddress.fromSeparatedList(" first@example.com, second@example.com ;third@example.com")
                .map { it.value } shouldBe listOf("first@example.com", "second@example.com", "third@example.com")
        }

        test("fromSeparatedList skips empty entries") {
            EmailAddress.fromSeparatedList("first@example.com;; ,").map { it.value } shouldBe listOf("first@example.com")
            EmailAddress.fromSeparatedList("").shouldBeEmpty()
        }

        test("fromSeparatedList rejects invalid addresses") {
            shouldThrow<IllegalArgumentException> { EmailAddress.fromSeparatedList("first@example.com,not-an-email") }
        }
    })
