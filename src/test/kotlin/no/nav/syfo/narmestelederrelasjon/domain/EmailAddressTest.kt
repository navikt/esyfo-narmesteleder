package no.nav.syfo.narmestelederrelasjon.domain

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
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

        test("parseSeparatedList keeps deliverable addresses and discards the rest") {
            val valid = listOf("øystein+tag@example.com", "a@sub.example-domain.no", "first.last@example.com")
            val invalid = listOf(
                "no-at",
                "a@localhost",
                "a@-example.com",
                "a@example-.com",
                "a b@example.com",
                "a@example..com",
                "ola..nordmann@example.com",
                "o'neill@example.com",
                "björn@example.com",
            )
            val result = EmailAddress.parseSeparatedList((valid + invalid).joinToString(";"))
            result.validEmailAddresses.map { it.value } shouldBe valid
            result.discardedEmailAddressCount shouldBe invalid.size
        }

        test("accepts addresses that both arbeidsgiver-notifikasjon and Altinn 3 accept") {
            listOf(
                "leder@firma.no",
                "Ola.Nordmann@Firma.NO",
                "kari_ola%drift+tag-1@firma.no",
                "ærlig.øystein.åse@blåbær-økonomi.no",
                "ÆRLIG@BLÅBÆR.NO",
                "a@b.co",
                "a@${"a".repeat(63)}.no",
                "a@b.abcdefghijklmn",
                "a@${List(9) { "d" }.joinToString(".")}.no",
                "${"a".repeat(254 - "@firma.no".length)}@firma.no",
            ).forEach { address ->
                withClue(address) { EmailAddress.validationReason(address) shouldBe null }
            }
        }

        test("rejects addresses that arbeidsgiver-notifikasjon or Altinn 3 rejects") {
            listOf(
                // arbeidsgiver-notifikasjon allows no other local-part symbols than . _ % + -
                "o'neill@firma.no",
                "kari&ola@firma.no",
                "\"ola.nordmann\"@firma.no",
                // Altinn 3 allows only Æ, Ø and Å as non-ASCII letters
                "björn@firma.se",
                "müller@firma.de",
                "ola@münchen.de",
                // Altinn 3 allows only ASCII digits, while arbeidsgiver-notifikasjon allows every Unicode digit
                "ola\u0663@firma.no",
                "ola@firma\u0663.no",
                // Altinn 3 rejects leading, trailing and consecutive dots in the local-part
                "ola..nordmann@firma.no",
                ".ola@firma.no",
                "ola.@firma.no",
                // Altinn 3 requires a top-level domain of 2-14 ASCII letters and at most ten labels
                "ærlig@blåbær.økonomi",
                "a@b.c",
                "a@firma.123",
                "a@b.abcdefghijklmno",
                "a@${List(10) { "d" }.joinToString(".")}.no",
                // arbeidsgiver-notifikasjon requires a top-level domain of letters
                "a@127.0.0.1",
                // Domain labels are at most 63 characters and do not start or end with a hyphen
                "a@${"a".repeat(64)}.no",
                "a@-firma.no",
                "a@firma-.no",
                "a@fir_ma.no",
                "a@firma..no",
                "a@localhost",
                "no-at",
                "a@b@firma.no",
                "a@firma.no,b@firma.no",
                // RFC 5321 limits an address to 254 characters
                "${"a".repeat(255 - "@firma.no".length)}@firma.no",
                "${"a.".repeat(5000)}a@firma.no",
            ).forEach { address ->
                withClue(address) {
                    EmailAddress.validationReason(address) shouldBe ManagerContactValidationReason.EMAIL_ADDRESS_MUST_BE_VALID
                }
            }
        }
    })
