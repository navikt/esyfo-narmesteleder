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

        test("parseSeparatedList splits and trims while discarding invalid addresses") {
            val result = EmailAddress.parseSeparatedList(" first@example.com, invalid ;second@example.com; ;third@example.com ")
            result.validEmailAddresses.map { it.value } shouldBe listOf("first@example.com", "second@example.com", "third@example.com")
            result.discardedEmailAddressCount shouldBe 1
        }

        test("parseSeparatedList keeps a single address and duplicates in order") {
            EmailAddress.parseSeparatedList("first@example.com").validEmailAddresses shouldBe listOf(EmailAddress("first@example.com"))
            EmailAddress.parseSeparatedList("first@example.com;first@example.com").validEmailAddresses shouldBe
                listOf(EmailAddress("first@example.com"), EmailAddress("first@example.com"))
        }

        test("parseSeparatedList ignores empty entries without counting them") {
            listOf("", " ,; ;", "first@example.com,;").forEach { value ->
                EmailAddress.parseSeparatedList(value).discardedEmailAddressCount shouldBe 0
            }
            EmailAddress.parseSeparatedList(" ,; ;").validEmailAddresses.shouldBeEmpty()
        }

        test("parseSeparatedList preserves legacy email validation rules") {
            val valid = listOf("øystein+tag@example.com", "a@sub.example-domain.no", "first.last@example.com")
            val invalid = listOf("no-at", "a@localhost", "a@-example.com", "a@example-.com", "a b@example.com", "a@example..com")
            val result = EmailAddress.parseSeparatedList((valid + invalid).joinToString(";"))
            result.validEmailAddresses.map { it.value } shouldBe valid
            result.discardedEmailAddressCount shouldBe invalid.size
        }
    })
