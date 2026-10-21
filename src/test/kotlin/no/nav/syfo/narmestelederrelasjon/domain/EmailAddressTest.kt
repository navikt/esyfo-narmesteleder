package no.nav.syfo.narmestelederrelasjon.domain

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe

class EmailAddressTest :
    FunSpec({
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

        listOf(
            "an empty value" to "",
            "only separators" to " ,; ;",
            "trailing separators" to "first@example.com,;",
        ).forEach { (case, value) ->
            test("parseSeparatedList ignores empty entries in $case without counting them") {
                EmailAddress.parseSeparatedList(value).discardedEmailAddressCount shouldBe 0
            }
        }

        test("parseSeparatedList returns no addresses for only separators") {
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
            test("accepts ${describe(address)} that both arbeidsgiver-notifikasjon and Altinn 3 accept") {
                withClue(address) { EmailAddress.validationReason(address) shouldBe null }
            }
        }

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
            test("rejects ${describe(address)} that arbeidsgiver-notifikasjon or Altinn 3 rejects") {
                withClue(address) {
                    EmailAddress.validationReason(address) shouldBe ManagerContactValidationReason.EMAIL_ADDRESS_MUST_BE_VALID
                }
            }
        }

        test("reports whitespace before length for an over-long address") {
            EmailAddress.validationReason("${"a".repeat(300)} @firma.no") shouldBe
                ManagerContactValidationReason.EMAIL_ADDRESS_MUST_NOT_CONTAIN_WHITESPACE
        }

        test("parseSeparatedList discards over-long addresses") {
            val result = EmailAddress.parseSeparatedList(
                listOf(
                    "first@firma.no",
                    "${"a".repeat(255 - "@firma.no".length)}@firma.no",
                    "${"a.".repeat(5000)}a@firma.no",
                ).joinToString(","),
            )
            result.validEmailAddresses shouldBe listOf(EmailAddress("first@firma.no"))
            result.discardedEmailAddressCount shouldBe 2
        }
    })

private fun describe(address: String): String = if (address.length > 64) "${address.take(16)}… (${address.length} characters)" else "'$address'"
